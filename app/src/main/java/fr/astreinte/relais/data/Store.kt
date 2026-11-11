package fr.astreinte.relais.data

import android.content.Context
import android.util.Log
import fr.astreinte.relais.core.Author
import fr.astreinte.relais.core.History
import fr.astreinte.relais.core.PeriodNotices
import fr.astreinte.relais.core.Snapshot
import fr.astreinte.relais.core.SnapshotJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Stockage unique de l'app : un fichier JSON dans le stockage privé + un StateFlow observé par l'UI.
 * Utilisé à la fois par l'interface et par les récepteurs d'alarme (même processus).
 */
object Store {
    private const val TAG = "Store"
    private var file: File? = null
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "astreinte.json")
        file = f
        if (f.exists()) {
            try {
                _snapshot.value = SnapshotJson.fromJson(f.readText())
            } catch (e: Exception) {
                // Fichier corrompu : on le met de côté plutôt que de l'écraser
                Log.e(TAG, "Lecture impossible, sauvegarde du fichier fautif", e)
                runCatching { f.copyTo(File(f.parentFile, "astreinte.corrompu.json"), overwrite = true) }
            }
        }
    }

    /**
     * [by] : qui est à l'origine de la modification. Si le planning change, une entrée est ajoutée à
     * l'historique (par défaut : l'utilisateur de ce téléphone, saisie dans l'application).
     */
    @Synchronized
    fun update(by: Author? = null, block: (Snapshot) -> Snapshot) {
        val prev = _snapshot.value
        var next = block(prev)
        if (next.shifts !== prev.shifts) {
            next = History.record(prev, next, by ?: Author(Author.VIA_APP), System.currentTimeMillis())
            // Astreintes à venir reprises à un autre ou retirées : les médecins concernés seront prévenus par le relais
            val zone = java.time.ZoneId.systemDefault()
            val hhmm = java.time.LocalTime.now(zone).let { "%02d:%02d".format(it.hour, it.minute) }
            val notices = PeriodNotices.compute(prev, next, java.time.Instant.now(), zone, hhmm)
            if (notices.isNotEmpty()) next = next.copy(notices = (next.notices + notices).takeLast(40))
        }
        _snapshot.value = next
        save(next)
    }

    private fun save(snapshot: Snapshot) {
        val f = file ?: return
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(SnapshotJson.toJson(snapshot))
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Écriture impossible", e)
        }
    }
}
