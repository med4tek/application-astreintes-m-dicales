package fr.astreinte.relais.core

/** Codes MMI/USSD standard (GSM 02.30) de renvoi d'appel inconditionnel. */
object Codes {
    /** Désactive le renvoi inconditionnel. */
    const val DEACTIVATE = "##21#"

    /** Interroge l'état du renvoi inconditionnel. */
    const val QUERY = "*#21#"

    /** Enregistre ET active le renvoi inconditionnel vers [phone]. */
    fun activate(phone: String) = "**21*$phone#"

    /**
     * Nettoie un numéro saisi à la main ("06 12 34 56 78", "+33 (0)6 12.34.56.78"…).
     * Retourne null si le numéro n'est pas plausible.
     */
    fun normalizePhone(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.any { !(it.isDigit() || it in " .-()+") }) return null
        if (t.indexOf('+') > 0) return null          // un seul "+", et seulement en tête
        if (t.count { it == '+' } > 1) return null
        val plus = t.startsWith("+")
        var digits = t.filter { it.isDigit() }
        // Notation "+33 (0)6…" : le 0 de la forme nationale ne doit pas être envoyé
        if (plus && digits.startsWith("330")) digits = "33" + digits.drop(3)
        if (digits.length !in 6..15) return null
        return (if (plus) "+" else "") + digits
    }

    /**
     * Code à composer pour que les appels aillent chez [doctor] ; [DEACTIVATE] si [doctor] est null.
     * Retourne null si le numéro du médecin est inutilisable.
     */
    fun forDoctor(doctor: Doctor?): String? {
        if (doctor == null) return DEACTIVATE
        return normalizePhone(doctor.phone)?.let { activate(it) }
    }
}
