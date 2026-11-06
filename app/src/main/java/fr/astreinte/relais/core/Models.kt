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

data class Settings(
    /** Interrupteur général : tant qu'il est à false, l'app n'envoie JAMAIS aucun code. */
    val enabled: Boolean = false,
    /** Heure de relève proposée par défaut pour un nouveau jour. */
    val defaultTime: LocalTime = LocalTime.of(8, 0),
    /** -1 = SIM par défaut pour les appels. */
    val subscriptionId: Int = -1,
)

/** Dernier envoi tenté, pour savoir s'il faut renvoyer le code. */
data class ApplyState(
    val lastCommand: String? = null,
    val lastOk: Boolean = true,
    val lastAt: Long = 0L,
    val lastMessage: String = "",
    /** Nombre d'échecs consécutifs dans la série d'essais en cours. */
    val attempts: Int = 0,
)

data class LogEntry(
    val at: Long,
    val trigger: String,
    val command: String,
    val ok: Boolean,
    val message: String,
)

data class Snapshot(
    val doctors: List<Doctor> = emptyList(),
    val shifts: List<Shift> = emptyList(),
    val settings: Settings = Settings(),
    val state: ApplyState = ApplyState(),
    val log: List<LogEntry> = emptyList(),
) {
    fun doctor(id: String?): Doctor? = id?.let { wanted -> doctors.firstOrNull { it.id == wanted } }
}
