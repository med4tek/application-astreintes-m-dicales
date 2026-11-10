package fr.astreinte.relais.core

/** Ajout de plusieurs médecins d'un coup à partir d'un texte collé (une ligne par médecin). */
object DoctorList {
    data class Entry(val name: String, val phone: String)
    data class MergeResult(val doctors: List<Doctor>, val added: Int, val updated: Int, val unchanged: Int)

    // Un numéro = 8 à 14 chiffres, séparés au plus par un espace ou un point : un " - " ou des
    // parenthèses le terminent, ce qui permet de lire « 06 12 34 56 78 - 07.98.76.54.32 » comme deux numéros.
    private val PHONE = Regex("\\+?\\d(?:[ .]?\\d){7,13}")

    /**
     * Formats acceptés : « Nom ; 06 12 34 56 78 », « Nom 06 12 34 56 78 », « Nom : 06 12… - 07 …» ou « Nom » seul.
     * Quand une ligne contient plusieurs numéros, le PREMIER est retenu. Les lignes vides ou commençant
     * par # sont ignorées.
     */
    fun parse(text: String): List<Entry> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split(';', '\t')
            val rawName: String
            val phone: String
            if (parts.size >= 2) {
                rawName = parts[0]
                phone = PHONE.find(parts.drop(1).joinToString(" "))?.value?.trim() ?: ""
            } else {
                val m = PHONE.find(line)
                rawName = if (m != null) line.substring(0, m.range.first) else line
                phone = m?.value?.trim() ?: ""
            }
            val name = rawName.replace(Regex("\\s+"), " ").trim().trimEnd(':', '-', '–', ',', ' ')
            if (name.isEmpty()) null else Entry(name, phone)
        }

    /**
     * Ajoute les médecins absents ; complète le numéro d'un médecin déjà présent SANS numéro ;
     * ne modifie jamais un numéro existant. Deux noms sont les mêmes s'ils se normalisent pareil
     * (casse, accents et titre « Dr » ignorés).
     */
    fun merge(existing: List<Doctor>, entries: List<Entry>, newId: () -> String): MergeResult {
        val doctors = existing.toMutableList()
        var added = 0; var updated = 0; var unchanged = 0
        for (e in entries) {
            val key = NameMatcher.normalize(e.name)
            val idx = if (key.isEmpty()) -1 else doctors.indexOfFirst { NameMatcher.normalize(it.name) == key }
            if (idx >= 0) {
                val cur = doctors[idx]
                if (e.phone.isNotBlank() && cur.phone.isBlank()) { doctors[idx] = cur.copy(phone = e.phone); updated++ } else unchanged++
            } else {
                doctors += Doctor(newId(), e.name, e.phone); added++
            }
        }
        return MergeResult(doctors, added, updated, unchanged)
    }

    /** « Dr DUPONT » -> « Dr Dupont » : un nom tout en majuscules est remis en casse normale. */
    fun prettyName(raw: String): String = raw.trim().split(Regex("\\s+")).joinToString(" ") { w ->
        if (w.length > 2 && w == w.uppercase()) w.lowercase().replaceFirstChar { it.uppercase() } else w
    }
}
