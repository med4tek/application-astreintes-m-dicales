package fr.astreinte.relais.sys

import android.content.Context
import fr.astreinte.relais.core.ChangeNotice
import fr.astreinte.relais.core.LogEntry
import fr.astreinte.relais.core.Messages
import fr.astreinte.relais.data.Store
import java.time.Instant
import java.time.ZoneId

/** Envoie les messages automatiques dus (voir [Messages.due]) et les consigne dans le journal. */
object Messenger {
    fun dispatchDue(app: Context) {
        Store.init(app)
        val now = Instant.now()
        val due = Messages.due(Store.snapshot.value, now, ZoneId.systemDefault())
        if (due.isEmpty()) return

        val keys = ArrayList<String>()
        val logs = ArrayList<LogEntry>()
        for (m in due) {
            val sent = m.phone != null && SmsOut.send(app, m.phone, m.text)
            keys += m.key   // une seule tentative : pas de répétition en boucle en cas d'échec
            val message = when {
                sent -> m.text
                m.phone == null -> "Non envoyé : numéro de ${m.doctor.name} invalide"
                else -> "Non envoyé : permission d'envoi de SMS refusée ?"
            }
            logs += LogEntry(System.currentTimeMillis(), "Message auto", m.doctor.name, sent, message)
        }
        Store.update { s ->
            s.copy(
                sentMessages = (s.sentMessages + keys).takeLast(60),
                log = (logs.reversed() + s.log).take(100),
            )
        }
    }

    /** Prévient les médecins concernés quand une modification change l'astreinte en cours. */
    fun notifyChange(app: Context, previous: String, new: String) {
        Store.init(app)
        val zone = ZoneId.systemDefault()
        val hhmm = java.time.LocalTime.now(zone).let { "%02d:%02d".format(it.hour, it.minute) }
        val msgs = ChangeNotice.messages(Store.snapshot.value, previous, new, hhmm)
        if (msgs.isEmpty()) return
        val logs = msgs.map { m ->
            val sent = m.phone != null && SmsOut.send(app, m.phone, m.text)
            val message = when {
                sent -> m.text
                m.phone == null -> "Non envoyé : numéro de ${m.doctor.name} invalide"
                else -> "Non envoyé : permission d'envoi de SMS refusée ?"
            }
            LogEntry(System.currentTimeMillis(), "Changement d'astreinte", m.doctor.name, sent, message)
        }
        Store.update { s -> s.copy(log = (logs.reversed() + s.log).take(100)) }
    }
}
