package fr.astreinte.relais.core

import java.time.LocalDate
import java.time.LocalTime

/** Un médecin enregistré. [phone] est saisi tel quel, normalisé au moment de l'envoi. */
data class Doctor(val id: String, val name: String, val phone: String)

/**
 * Un changement d'astreinte : à partir de [date] [time], les appels sont renvoyés vers
 * [doctorId] (ou le renvoi est désactivé si [doctorId] est null), jusqu'au changement suivant.
 */
data class Shift(val id: String, val date: LocalDate, val time: LocalTime, val doctorId: String?)

/** Créneau type de la journée : heure de changement, intitulé, et cible appliquée par défaut (ou null). */
data class Slot(val time: LocalTime, val label: String, val defaultTargetId: String?)

/** Le standard qui fait sonner tous les DECT : toujours présent, utilisé par défaut à 9h et 14h. */
const val STANDARD_ID = "standard"
val STANDARD_DOCTOR = Doctor(STANDARD_ID, "Standard 8311", "0100000000")

val DEFAULT_SLOTS: List<Slot> = listOf(
    Slot(LocalTime.of(9, 0), "Début de service", STANDARD_ID),
    Slot(LocalTime.of(12, 0), "Midi", null),
    Slot(LocalTime.of(14, 0), "Retour standard", STANDARD_ID),
    Slot(LocalTime.of(17, 0), "Fin d'après-midi", null),
    Slot(LocalTime.of(18, 0), "Astreinte", null),
)

/** Modification d'une case (jour × créneau) du planning. */
sealed interface CellEdit {
    /** Supprime la saisie : retour à la valeur par défaut du créneau (ou à "pas de changement"). */
    data object Clear : CellEdit

    /** Impose [doctorId] ; null = désactiver le renvoi. */
    data class Set(val doctorId: String?) : CellEdit
}

data class Settings(
    /** Interrupteur général : tant qu'il est à false, l'app n'envoie JAMAIS aucun code. */
    val enabled: Boolean = false,
    /** Heure de relève proposée par défaut pour un nouveau jour. */
    val defaultTime: LocalTime = LocalTime.of(8, 0),
    /** -1 = SIM par défaut pour les appels. */
    val subscriptionId: Int = -1,
    /**
     * false (défaut) : le téléphone "compose" le code comme le ferait le composeur (méthode fiable
     * pour les codes de renvoi, mais l'app ne lit pas la réponse de l'opérateur).
     * true : API USSD directe (lit la réponse, mais beaucoup de téléphones la refusent pour **21*).
     */
    val useDirectUssd: Boolean = false,
    /** Découpage type de la journée (colonnes du planning). */
    val slots: List<Slot> = DEFAULT_SLOTS,
    /** Commande du planning par SMS (désactivée tant que non configurée). */
    val smsEnabled: Boolean = false,
    val smsPin: String = "",
    /** Numéros autorisés en plus des médecins (séparés par virgule, espace ou retour ligne). */
    val smsExtraNumbers: String = "",
    val smsAllowDoctors: Boolean = true,
    /** Répondre par SMS à l'expéditeur (nécessite la permission d'envoi de SMS). */
    val smsReply: Boolean = false,
)

/** Dernier envoi tenté, pour savoir s'il faut renvoyer le code. */
data class ApplyState(
    val lastCommand: String? = null,
    val lastOk: Boolean = true,
    val lastAt: Long = 0L,
    val lastMessage: String = "",
    /** Nombre d'échecs consécutifs dans la série d'essais en cours. */
    val attempts: Int = 0,
    /** Dernier SMS vu par l'app (date seulement, jamais le contenu) : sert à diagnostiquer la réception. */
    val lastSmsAt: Long = 0L,
    val lastSmsWasCommand: Boolean = false,
)

data class LogEntry(
    val at: Long,
    val trigger: String,
    val command: String,
    val ok: Boolean,
    val message: String,
)

data class Snapshot(
    val doctors: List<Doctor> = listOf(STANDARD_DOCTOR),
    val shifts: List<Shift> = emptyList(),
    val settings: Settings = Settings(),
    val state: ApplyState = ApplyState(),
    val log: List<LogEntry> = emptyList(),
) {
    fun doctor(id: String?): Doctor? = id?.let { wanted -> doctors.firstOrNull { it.id == wanted } }
}
