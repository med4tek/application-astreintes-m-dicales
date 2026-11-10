package fr.astreinte.relais.sys

import android.content.Context
import fr.astreinte.relais.core.Author
import fr.astreinte.relais.core.Codes
import fr.astreinte.relais.core.LogEntry
import fr.astreinte.relais.core.Planner
import fr.astreinte.relais.core.STANDARD_ID
import fr.astreinte.relais.core.Settings
import fr.astreinte.relais.core.Snapshot
import fr.astreinte.relais.core.SmsAction
import fr.astreinte.relais.core.SmsCommands
import fr.astreinte.relais.core.SmsParse
import fr.astreinte.relais.core.SyncCodec
import fr.astreinte.relais.core.SyncLogic
import fr.astreinte.relais.data.Store
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Traite un SMS entrant selon le rôle du téléphone.
 *
 * RELAIS : commandes « AST <PIN> … » (modifier / consulter / SYNC / PUSH). Deux verrous : expéditeur
 * autorisé ET PIN. Les SMS qui ne commencent pas par AST sont ignorés sans rien enregistrer. Le corps
 * du SMS (donc le PIN) n'est jamais journalisé.
 *
 * CONSULTATION : seules les réponses du relais (ASTDATA, ASTACK) sont lues, et seulement si une
 * demande est en cours, que l'expéditeur est le numéro du relais et que le numéro de demande correspond.
 */
object SmsHandler {
    private const val MIN_PIN = 4

    fun handle(context: Context, sender: String, body: String, onDone: () -> Unit) {
        val app = context.applicationContext
        Store.init(app)
        val envelope = SmsCommands.parseEnvelope(body)
        // Témoin de réception (date seulement) : permet de vérifier que les SMS arrivent bien jusqu'à l'app
        Store.update { s ->
            s.copy(state = s.state.copy(lastSmsAt = System.currentTimeMillis(), lastSmsWasCommand = envelope != SmsParse.NotCommand))
        }
        val snap = Store.snapshot.value
        when {
            snap.settings.isViewer -> handleAsViewer(app, snap, sender, body, onDone)
            snap.settings.isRelay -> handleAsRelay(app, snap, sender, envelope, onDone)
            else -> onDone()
        }
    }

    // ================================================================== consultation

    private fun handleAsViewer(app: Context, snap: Snapshot, sender: String, body: String, onDone: () -> Unit) {
        val relay = snap.settings.relayNumber
        if (relay.isBlank() || !Codes.samePhone(relay, sender)) return onDone()
        val now = System.currentTimeMillis()
        val hadPending = snap.sync.pending != null

        val chunk = SyncCodec.parseDataChunk(body)
        val ack = if (chunk == null) SyncCodec.parseAck(body) else null
        if (chunk == null && ack == null) return onDone()

        Store.update(Author(Author.VIA_SYNC, who = "Relais", collapse = true)) { s ->
            if (chunk != null) SyncLogic.onDataChunk(s, chunk, now) else SyncLogic.onAck(s, ack!!, now)
        }
        val after = Store.snapshot.value.sync
        // La synchronisation vient de se terminer (succès ou refus) : on le dit
        if (hadPending && after.pending == null && after.lastResult.isNotBlank()) {
            Notifier.info(app, if (chunk != null) "Planning reçu" else "Synchronisation", after.lastResult)
        }
        onDone()
    }

    // ================================================================== relais

    private fun handleAsRelay(app: Context, snap: Snapshot, sender: String, env: SmsParse, onDone: () -> Unit) {
        val st = snap.settings
        if (!st.smsEnabled) return onDone()
        if (st.smsPin.length < MIN_PIN) {
            if (env != SmsParse.NotCommand) log(sender, false, "Commande reçue mais ignorée : aucun PIN (4 caractères minimum) défini dans les réglages")
            return onDone()
        }

        when (env) {
            SmsParse.NotCommand -> onDone()
            is SmsParse.Malformed -> {
                if (isAllowed(sender, snap)) {
                    log(sender, false, env.why)
                    reply(app, st, sender, env.why)
                } else {
                    log(sender, false, "Refusé : expéditeur non autorisé")
                }
                onDone()
            }
            is SmsParse.Command -> {
                if (!isAllowed(sender, snap)) {
                    log(sender, false, "Refusé : expéditeur non autorisé (aucune réponse envoyée)")
                    return onDone()
                }
                if (!pinMatches(st.smsPin, env.pin)) {
                    log(sender, false, "Refusé : PIN incorrect")
                    reply(app, st, sender, "PIN incorrect.")
                    return onDone()
                }
                execute(app, sender, snap, env.args, onDone)
            }
        }
    }

    private fun execute(app: Context, sender: String, snap: Snapshot, args: List<String>, onDone: () -> Unit) {
        val zone = ZoneId.systemDefault()
        when (val action = SmsCommands.interpret(args, snap.doctors, snap.settings.slots, LocalDate.now(zone))) {
            is SmsAction.Error -> {
                log(sender, false, action.why)
                reply(app, snap.settings, sender, "Erreur : ${action.why}")
                onDone()
            }
            SmsAction.Query -> {
                answerQuery(app, snap, sender, "Demande d'état", statusText(snap, zone))
                onDone()
            }
            is SmsAction.Plan -> {
                val text = SmsCommands.planText(snap, action.dates, zone)
                answerQuery(app, snap, sender, "Consultation du planning (${action.dates.size} jour(s))", text)
                onDone()
            }
            is SmsAction.Edit -> {
                val summary = SmsCommands.describe(action.groups, snap.doctors)
                Store.update(Author(Author.VIA_SMS, who = senderName(snap, sender))) { s ->
                    s.copy(shifts = SmsCommands.apply(s.shifts, action.groups) { UUID.randomUUID().toString() })
                }
                log(sender, true, "Planning modifié : $summary")
                Notifier.info(app, "Planning modifié par SMS", "$sender : $summary")
                reply(app, snap.settings, sender, "OK $summary")
                // Si la modification touche l'astreinte en cours, le renvoi est appliqué tout de suite
                Relay.reconcile(app, Relay.Trigger.EDIT, onDone = onDone)
            }
            is SmsAction.Sync -> {
                // Un téléphone consultant demande tout le planning : réponse forcée, c'est le but de la demande
                val chunks = SyncCodec.encodeData(snap, action.id, LocalDate.now(zone))
                val ok = chunks.all { SmsOut.send(app, sender, it) }
                log(sender, ok, if (ok) "Planning envoyé pour synchronisation (${chunks.size} SMS)" else "Planning non envoyé : permission d'envoi de SMS refusée ?")
                onDone()
            }
            is SmsAction.Push -> {
                if (action.error != null) {
                    log(sender, false, "Modifications reçues (${action.part}/${action.total}) refusées : ${action.error}")
                    SmsOut.send(app, sender, SyncCodec.ackText(action.id, action.part, action.total, false, action.error))
                    return onDone()
                }
                val cells = SyncCodec.countCells(action.groups)
                val summary = SmsCommands.describe(action.groups, snap.doctors)
                Store.update(Author("Synchro SMS", who = senderName(snap, sender))) { s ->
                    s.copy(shifts = SmsCommands.apply(s.shifts, action.groups) { UUID.randomUUID().toString() })
                }
                log(sender, true, "Synchronisation reçue ${action.part}/${action.total} : $cells case(s)")
                Notifier.info(app, "Planning mis à jour depuis $sender", summary)
                SmsOut.send(app, sender, SyncCodec.ackText(action.id, action.part, action.total, true, cells.toString()))
                Relay.reconcile(app, Relay.Trigger.EDIT, onDone = onDone)
            }
        }
    }

    /** Qui est derrière ce numéro : le médecin enregistré, sinon le numéro lui-même (numéro autorisé « en plus »). */
    internal fun senderName(snap: Snapshot, sender: String): String {
        val doctor = snap.doctors.firstOrNull { it.id != STANDARD_ID && Codes.samePhone(it.phone, sender) }
        return if (doctor != null) "${doctor.name} ($sender)" else sender
    }

    /** Une consultation n'a de sens que si la réponse par SMS est possible : sinon on le dit dans le journal. */
    private fun answerQuery(app: Context, snap: Snapshot, sender: String, what: String, text: String) {
        val st = snap.settings
        if (!st.smsReply) {
            log(sender, false, "$what reçue mais « Répondre par SMS » est désactivé dans les réglages")
        } else if (!Perms.sendSms(app)) {
            log(sender, false, "$what reçue mais la permission d'envoi de SMS est refusée")
        } else {
            log(sender, true, what)
            reply(app, st, sender, text)
        }
    }

    private fun statusText(snap: Snapshot, zone: ZoneId): String {
        val now = Instant.now()
        fun name(id: String?) = if (id == null) "aucun renvoi" else snap.doctor(id)?.name ?: "?"
        fun at(s: fr.astreinte.relais.core.Shift) = "%02d/%02d %02d:%02d".format(s.date.dayOfMonth, s.date.monthValue, s.time.hour, s.time.minute)
        val cur = Planner.currentShift(snap, now, zone)
        val next = Planner.nextShift(snap, now, zone)
        return buildString {
            append("Renvoi : ")
            append(if (cur == null) "rien en cours" else "${name(cur.doctorId)} (depuis ${at(cur)})")
            if (next != null) append(". Suivant : ${at(next)} → ${name(next.doctorId)}")
            if (!snap.settings.enabled) append(". ATTENTION : bascule auto désactivée")
        }
    }

    /** Médecins enregistrés (hors standard) si autorisé, plus les numéros ajoutés à la main. */
    internal fun allowedNumbers(snap: Snapshot): List<String> {
        val st = snap.settings
        val doctors = if (st.smsAllowDoctors) snap.doctors.filter { it.id != STANDARD_ID }.map { it.phone } else emptyList()
        val extras = st.smsExtraNumbers.split(Regex("[,;\\s]+")).filter { it.isNotBlank() }
        return doctors + extras
    }

    internal fun isAllowed(sender: String, snap: Snapshot): Boolean =
        allowedNumbers(snap).any { Codes.samePhone(it, sender) }

    private fun pinMatches(expected: String, given: String): Boolean =
        MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())

    private fun log(sender: String, ok: Boolean, message: String) {
        Store.update { s ->
            s.copy(log = (listOf(LogEntry(System.currentTimeMillis(), "SMS", sender, ok, message)) + s.log).take(100))
        }
    }

    private fun reply(app: Context, st: Settings, to: String, text: String) {
        if (!st.smsReply || to.isBlank()) return
        SmsOut.send(app, to, text.take(600))
    }
}
