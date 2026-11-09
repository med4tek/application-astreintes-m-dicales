package fr.astreinte.relais.sys

import android.content.Context
import fr.astreinte.relais.core.Codes
import fr.astreinte.relais.core.PendingSync
import fr.astreinte.relais.core.SyncCodec
import fr.astreinte.relais.core.SyncLogic
import fr.astreinte.relais.data.Store

/** Actions du téléphone consultant vers le relais. Chaque fonction retourne un message d'erreur, ou null si tout est parti. */
object SyncClient {

    /** Demande au relais de renvoyer tout le planning. */
    fun requestPull(app: Context): String? {
        val (relay, pin) = prerequisites(app) ?: return explain(app)
        val id = SyncLogic.newRequestId()
        val now = System.currentTimeMillis()
        Store.update { s -> s.copy(sync = s.sync.copy(pending = PendingSync("PULL", id, now), lastResult = "")) }
        if (!SmsOut.send(app, relay, "AST $pin SYNC $id")) return abort("Le SMS n'a pas pu être envoyé.")
        return null
    }

    /** Envoie au relais les cases modifiées depuis la dernière synchronisation. */
    fun pushChanges(app: Context): String? {
        val (relay, pin) = prerequisites(app) ?: return explain(app)
        val snap = Store.snapshot.value
        val changes = SyncCodec.diff(snap.shifts, snap.baseline)
        if (changes.isEmpty()) return "Aucune modification à envoyer."
        val id = SyncLogic.newRequestId()
        val messages = SyncCodec.pushMessages(changes, snap.doctors, pin, id)
        if (messages.isEmpty()) return "Ces modifications ne peuvent pas être transmises (médecin inconnu)."

        val now = System.currentTimeMillis()
        Store.update { s ->
            s.copy(
                sync = s.sync.copy(
                    pending = PendingSync("PUSH", id, now, total = messages.size, cellsSent = changes.size, pushedShifts = s.shifts),
                    lastResult = "",
                )
            )
        }
        if (!messages.all { SmsOut.send(app, relay, it) }) return abort("Un SMS n'a pas pu être envoyé.")
        return null
    }

    private fun prerequisites(app: Context): Pair<String, String>? {
        val st = Store.snapshot.value.settings
        val relay = Codes.normalizePhone(st.relayNumber) ?: return null
        if (st.smsPin.length < 4) return null
        if (!Perms.sendSms(app)) return null
        return relay to st.smsPin
    }

    private fun explain(app: Context): String {
        val st = Store.snapshot.value.settings
        return when {
            Codes.normalizePhone(st.relayNumber) == null -> "Indiquez le numéro du téléphone relais."
            st.smsPin.length < 4 -> "Indiquez le PIN (4 caractères minimum), le même que sur le relais."
            else -> "Autorisez l'envoi de SMS pour cette application."
        }
    }

    private fun abort(message: String): String {
        Store.update { s -> s.copy(sync = s.sync.copy(pending = null, lastResult = message)) }
        return message
    }
}
