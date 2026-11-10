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

/**
 * Le standard qui fait sonner tous les DECT : toujours présent, utilisé par défaut à 9h et 14h.
 * Son numéro est volontairement ABSENT du code (dépôt public) : à saisir une fois sur le téléphone
 * relais (onglet Médecins, fiche « Standard »). Les téléphones de consultation le reçoivent par synchro.
 * Tant qu'il est vide, la fiche est signalée « numéro invalide » et aucun renvoi ne part vers le standard.
 */
const val STANDARD_ID = "standard"
val STANDARD_DOCTOR = Doctor(STANDARD_ID, "Standard 8311", "")

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

/** Rôle de ce téléphone. */
object Mode {
    /** Pas encore choisi (premier lancement) : l'app ne fait rien d'automatique. */
    const val NONE = ""

    /** Téléphone d'astreinte : applique le renvoi d'appel, reçoit les commandes SMS, prévient les médecins. */
    const val RELAY = "RELAY"

    /** Téléphone personnel : consulte et modifie le planning, synchronisé par SMS avec le relais. */
    const val VIEWER = "VIEWER"
}

/**
 * Message automatique envoyé par le téléphone relais à un médecin.
 * Envoyé à [at], au médecin d'astreinte au créneau [slotTime] du jour. Les variables du [text] :
 * {nom} {debut} {fin} {date}.
 */
data class MessageRule(
    val id: String,
    val enabled: Boolean,
    val at: LocalTime,
    val slotTime: LocalTime,
    /** Si vrai : n'envoyer que lorsque la bascule vers ce médecin est effectivement faite. */
    val confirmsSwitch: Boolean,
    val text: String,
)

val DEFAULT_MESSAGE_RULES: List<MessageRule> = listOf(
    MessageRule(
        "midi", true, LocalTime.of(12, 0), LocalTime.of(12, 0), true,
        "Bonjour {nom}, tu es d'astreinte ce midi ({debut}-{fin}). La bascule vers ton numero est faite."
    ),
    MessageRule(
        "apm", true, LocalTime.of(17, 0), LocalTime.of(17, 0), true,
        "Bonjour {nom}, tu es d'astreinte cet apres-midi ({debut}-{fin}). La bascule vers ton numero est faite."
    ),
    MessageRule(
        "rappel", true, LocalTime.of(17, 0), LocalTime.of(18, 0), false,
        "Rappel {nom} : tu es d'astreinte ce soir a partir de {debut} jusqu'a {fin}."
    ),
    MessageRule(
        "soir", true, LocalTime.of(18, 0), LocalTime.of(18, 0), true,
        "Bonjour {nom}, la bascule est faite : ton astreinte commence ({debut}-{fin})."
    ),
)

const val DEFAULT_CHANGE_NEW =
    "Bonjour {nom}, l'astreinte en cours vient de t'etre confiee (modification du planning a {heure}). La bascule vers ton numero est faite."
const val DEFAULT_CHANGE_OLD =
    "Bonjour {nom}, tu n'es plus d'astreinte pour l'instant (modification du planning a {heure}). Les appels ne te sont plus renvoyes."

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
    /** Rôle du téléphone, voir [Mode]. */
    val mode: String = Mode.NONE,
    /** Mode consultation : numéro du téléphone relais, auquel sont envoyées les demandes. */
    val relayNumber: String = "",
    /** Mode relais : messages automatiques aux médecins. */
    val msgEnabled: Boolean = false,
    val msgRules: List<MessageRule> = DEFAULT_MESSAGE_RULES,
    /** Mode relais : prévenir par SMS quand une MODIFICATION change l'astreinte en cours. */
    val changeNotify: Boolean = true,
    val changeTextNew: String = DEFAULT_CHANGE_NEW,
    val changeTextOld: String = DEFAULT_CHANGE_OLD,
) {
    val isRelay: Boolean get() = mode == Mode.RELAY
    val isViewer: Boolean get() = mode == Mode.VIEWER
}

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
    /** Cible du dernier renvoi réellement appliqué : "" = inconnue, "~off" = aucun renvoi, sinon id du médecin. */
    val lastTarget: String = "",
)

data class LogEntry(
    val at: Long,
    val trigger: String,
    val command: String,
    val ok: Boolean,
    val message: String,
)

/** Synchronisation en cours (téléphone consultant). */
data class PendingSync(
    /** "PULL" (demande du planning) ou "PUSH" (envoi des modifications). */
    val kind: String,
    val id: String,
    val startedAt: Long,
    /** PULL : nombre de morceaux attendus (connu à la réception du premier). PUSH : nombre de SMS envoyés. */
    val total: Int = 0,
    /** PULL : morceaux de données reçus. PUSH : accusés reçus (valeur "OK"). */
    val chunks: Map<Int, String> = emptyMap(),
    val cellsSent: Int = 0,
    /** PUSH : planning tel qu'envoyé, qui deviendra la référence quand tout est acquitté. */
    val pushedShifts: List<Shift> = emptyList(),
)

data class SyncState(
    val lastPullAt: Long = 0L,
    val lastPushAt: Long = 0L,
    val pending: PendingSync? = null,
    val lastResult: String = "",
)

data class Snapshot(
    val doctors: List<Doctor> = listOf(STANDARD_DOCTOR),
    val shifts: List<Shift> = emptyList(),
    val settings: Settings = Settings(),
    val state: ApplyState = ApplyState(),
    val log: List<LogEntry> = emptyList(),
    /** Mode consultation : planning tel que connu du relais à la dernière synchronisation. */
    val baseline: List<Shift> = emptyList(),
    val sync: SyncState = SyncState(),
    /** Messages automatiques déjà envoyés ("date|règle"), pour ne jamais envoyer deux fois. */
    val sentMessages: List<String> = emptyList(),
    /** Correspondances mémorisées nom du planning (normalisé) -> id du médecin, apprises à l'import. */
    val aliases: Map<String, String> = emptyMap(),
) {
    fun doctor(id: String?): Doctor? = id?.let { wanted -> doctors.firstOrNull { it.id == wanted } }
}
