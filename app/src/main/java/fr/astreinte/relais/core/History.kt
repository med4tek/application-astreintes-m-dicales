package fr.astreinte.relais.core

import java.time.LocalDate
import java.time.LocalTime

/**
 * Une modification du planning, conservée pour la traçabilité : QUI l'a faite, PAR QUEL CANAL,
 * QUAND et QUOI. [cells] = nombre total de cases touchées ; [lines] = le détail (tronqué).
 */
data class HistoryEntry(
    val at: Long,
    val who: String,
    val via: String,
    val cells: Int,
    val lines: List<String>,
)

/** Auteur d'une modification. [who] = null : l'utilisateur de ce téléphone (nom saisi dans les réglages). */
data class Author(val via: String, val who: String? = null, val collapse: Boolean = false) {
    companion object {
        const val VIA_APP = "Application"
        const val VIA_IMPORT = "Import photo"
        const val VIA_SMS = "SMS"
        const val VIA_SYNC = "Synchro"
    }
}

/** Historique des modifications du planning (aucune dépendance Android). */
object History {
    const val MAX_ENTRIES = 400
    const val MAX_LINES = 40
    const val DEFAULT_WHO = "Ce téléphone"

    /** Détail case par case, trié par date et heure. Vide si rien n'a changé. */
    fun describe(old: List<Shift>, new: List<Shift>, doctors: List<Doctor>): List<String> {
        val before = old.associate { (it.date to it.time) to it.doctorId }
        val after = new.associate { (it.date to it.time) to it.doctorId }
        fun name(id: String?) = if (id == null) "aucun renvoi" else doctors.firstOrNull { it.id == id }?.name ?: "médecin supprimé"
        val keys = (before.keys + after.keys).sortedWith(compareBy({ it.first }, { it.second }))
        return keys.mapNotNull { k ->
            val b = before.containsKey(k)
            val a = after.containsKey(k)
            if (b && a && before[k] == after[k]) return@mapNotNull null
            val from = if (b) name(before[k]) else "défaut"
            val to = if (a) name(after[k]) else "défaut"
            "${shortDate(k.first)} ${shortHour(k.second)} : $from → $to"
        }
    }

    /**
     * Ajoute une entrée à [next] si le planning diffère de [prev]. [collapse] : une seule ligne
     * (remplacement complet du planning par celui du relais, sans détailler chaque case).
     */
    fun record(
        prev: Snapshot,
        next: Snapshot,
        author: Author,
        now: Long,
    ): Snapshot {
        if (prev.shifts == next.shifts) return next
        val doctors = (prev.doctors + next.doctors).distinctBy { it.id }.let { merged ->
            // les noms actuels l'emportent sur les anciens
            merged.map { d -> next.doctors.firstOrNull { it.id == d.id } ?: d }
        }
        val all = describe(prev.shifts, next.shifts, doctors)
        if (all.isEmpty()) return next
        val who = author.who?.takeIf { it.isNotBlank() }
            ?: next.settings.userName.trim().ifEmpty { DEFAULT_WHO }
        val lines = when {
            author.collapse -> listOf("Planning remplacé par celui du relais (${all.size} case(s) différente(s))")
            all.size > MAX_LINES -> all.take(MAX_LINES) + "… et ${all.size - MAX_LINES} autre(s)"
            else -> all
        }
        val entry = HistoryEntry(now, who, author.via, all.size, lines)
        return next.copy(history = (listOf(entry) + next.history).take(MAX_ENTRIES))
    }

    /** Texte à partager / archiver (ordre chronologique inverse, comme à l'écran). */
    fun asText(entries: List<HistoryEntry>, fmt: (Long) -> String): String = entries.joinToString("\n\n") { e ->
        "${fmt(e.at)} — ${e.who} (${e.via}) — ${e.cells} case(s)\n" + e.lines.joinToString("\n") { "  $it" }
    }

    private fun shortDate(d: LocalDate) = "%02d/%02d".format(d.dayOfMonth, d.monthValue)

    private fun shortHour(t: LocalTime) = if (t.minute == 0) "${t.hour}h" else "%dh%02d".format(t.hour, t.minute)
}
