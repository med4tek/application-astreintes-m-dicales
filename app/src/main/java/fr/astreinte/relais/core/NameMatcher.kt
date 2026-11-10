package fr.astreinte.relais.core

import java.text.Normalizer
import java.util.Locale

/** Retrouve un médecin à partir d'un nom écrit à la main / lu par un LLM / tapé dans un SMS. */
object NameMatcher {
    private val NOISE = setOf("dr", "docteur", "doc", "pr", "professeur", "mme", "madame", "mr", "monsieur", "m")

    /** Minuscules, sans accents ni ponctuation, sans "Dr"/"Docteur"… */
    fun normalize(s: String): String {
        // "DrDupont" / "DrMARTIN" : le titre collé au nom est séparé avant la mise en minuscules
        val spaced = s.replace(Regex("\\b(Dr|DR|Dre|Pr|PR)(?=\\p{Lu})"), "$1 ")
        val stripped = Normalizer.normalize(spaced.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return stripped.split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() && it !in NOISE }
            .joinToString(" ")
    }

    /**
     * Le médecin correspondant, ou null si aucun ou si plusieurs conviennent (ambigu).
     * Ordre : égalité exacte, puis correspondance de débuts de mots, puis petite faute de frappe.
     */
    fun find(raw: String, doctors: List<Doctor>): Doctor? {
        val wanted = normalize(raw)
        if (wanted.isEmpty()) return null
        val candidates = doctors.map { it to normalize(it.name) }.filter { it.second.isNotEmpty() }

        candidates.filter { it.second == wanted }.let { exact ->
            if (exact.size == 1) return exact[0].first
            if (exact.size > 1) return null
        }

        val wantedTokens = wanted.split(' ')
        val partial = candidates.filter { (_, name) ->
            val tokens = name.split(' ')
            wantedTokens.all { w -> tokens.any { it.startsWith(w) } } ||
                tokens.all { t -> wantedTokens.any { it.startsWith(t) } }
        }
        if (partial.size == 1) return partial[0].first
        if (partial.size > 1) return null

        // Faute de frappe dans un mot (« Garnnier » pour « Garnier ») : chaque mot cherché doit ressembler à un mot du nom
        val fuzzy = candidates.filter { (_, name) ->
            val tokens = name.split(' ')
            wantedTokens.all { w -> tokens.any { t -> distance(t, w) <= tokenTolerance(w) } }
        }
        if (fuzzy.size == 1) return fuzzy[0].first
        if (fuzzy.size > 1) return null

        val maxDistance = if (wanted.length <= 5) 1 else 2
        val close = candidates
            .map { (d, name) -> d to distance(name, wanted) }
            .filter { it.second <= maxDistance }
        val best = close.minOfOrNull { it.second } ?: return null
        return close.filter { it.second == best }.singleOrNull()?.first
    }

    /** Fautes admises dans un mot : aucune pour un mot court, 1 à partir de 7 lettres, 2 à partir de 11. */
    private fun tokenTolerance(w: String) = when { w.length >= 11 -> 2; w.length >= 7 -> 1; else -> 0 }

    /** Distance de Levenshtein. */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
