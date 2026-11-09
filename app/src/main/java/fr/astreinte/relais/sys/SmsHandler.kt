package fr.astreinte.relais.sys

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import fr.astreinte.relais.core.Codes
import fr.astreinte.relais.core.LogEntry
import fr.astreinte.relais.core.Planner
import fr.astreinte.relais.core.STANDARD_ID
import fr.astreinte.relais.core.Settings
import fr.astreinte.relais.core.Snapshot
import fr.astreinte.relais.core.SmsAction
import fr.astreinte.relais.core.SmsCommands
import fr.astreinte.relais.core.SmsParse
import fr.astreinte.relais.data.Store
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Traite un SMS de commande « AST <PIN> … ».
 *
 * Deux verrous : l'expéditeur doit être autorisé ET connaître le PIN. Les SMS qui ne commencent pas
 * par AST sont ignorés sans rien enregistrer. Le corps du SMS (donc le PIN) n'est jamais journalisé.
 */
object SmsHandler {
    private const val TAG = "SmsHandler"
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
        val st = snap.settings
        if (!st.smsEnabled) return onDone()
        if (st.smsPin.length < MIN_PIN) {
            if (envelope != SmsParse.NotCommand) log(sender, false, "Commande reçue mais ignorée : aucun PIN (4 caractères minimum) défini dans les réglages")
            return onDone()
        }

        when (val env = envelope) {
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
                Store.update { s ->
                    s.copy(shifts = SmsCommands.apply(s.shifts, action.groups) { UUID.randomUUID().toString() })
                }
                log(sender, true, "Planning modifié : $summary")
                Notifier.info(app, "Planning modifié par SMS", "$sender : $summary")
                reply(app, snap.settings, sender, "OK $summary")
                // Si la modification touche l'astreinte en cours, le renvoi est appliqué tout de suite
                Relay.reconcile(app, Relay.Trigger.EDIT, onDone = onDone)
            }
        }
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
        if (!st.smsReply || !Perms.sendSms(app) || to.isBlank()) return
        try {
            val sms = app.getSystemService(SmsManager::class.java)
            val trimmed = text.take(600)
            sms.sendMultipartTextMessage(to, null, sms.divideMessage(trimmed), null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Réponse SMS impossible", e)
        }
    }
}
