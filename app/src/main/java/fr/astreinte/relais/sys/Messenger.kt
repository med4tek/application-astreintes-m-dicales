package fr.astreinte.relais.sys

import android.content.Context
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
}
