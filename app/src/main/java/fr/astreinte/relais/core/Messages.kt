package fr.astreinte.relais.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Un message prêt à partir. [key] sert à ne jamais l'envoyer deux fois. */
data class DueMessage(
    val key: String,
    val rule: MessageRule,
    val doctor: Doctor,
    /** Numéro normalisé, ou null s'il est inutilisable. */
    val phone: String?,
    val text: String,
)

/** Messages automatiques du relais vers les médecins (calculs purs). */
object Messages {
    /** Au-delà, un message en retard (téléphone éteint…) n'a plus de sens : on ne l'envoie pas. */
    val MAX_LATE: Duration = Duration.ofMinutes(90)

    fun hourText(t: LocalTime) = if (t.minute == 0) "${t.hour}h" else "%dh%02d".format(t.hour, t.minute)

    /** Fin d'un créneau : l'heure du créneau suivant, ou « demain 9h » pour le dernier. */
    fun endText(slotTime: LocalTime, slots: List<Slot>): String {
        val sorted = slots.map { it.time }.sorted()
        sorted.firstOrNull { it > slotTime }?.let { return hourText(it) }
        val first = sorted.firstOrNull() ?: return ""
        return "demain ${hourText(first)}"
    }

    /** Remplace {nom} {debut} {fin} {date}. */
    fun render(template: String, doctor: Doctor, date: LocalDate, slotTime: LocalTime, slots: List<Slot>): String {
        val day = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.FRENCH).trimEnd('.')
        return template
            .replace("{nom}", doctor.name)
            .replace("{debut}", hourText(slotTime))
            .replace("{fin}", endText(slotTime, slots))
            .replace("{date}", "$day %02d/%02d".format(date.dayOfMonth, date.monthValue))
    }

    /** Médecin d'astreinte au créneau de la règle ce jour-là ; null s'il s'agit du standard, de « aucun » ou de personne. */
    fun recipient(snap: Snapshot, timeline: List<Shift>, date: LocalDate, rule: MessageRule, zone: ZoneId): Doctor? {
        val at = ZonedDateTime.of(date, rule.slotTime, zone).toInstant()
        val shift = Planner.current(timeline, at, zone) ?: return null
        val id = shift.doctorId ?: return null
        if (id == STANDARD_ID) return null
        return snap.doctor(id)
    }

    /**
     * Messages à envoyer maintenant : règles actives dont l'heure est passée depuis moins de
     * [MAX_LATE], pas encore envoyées, dont le destinataire existe, et — pour les règles
     * « confirmant la bascule » — dont la bascule vers ce médecin est réellement faite.
     */
    fun due(snap: Snapshot, now: Instant, zone: ZoneId): List<DueMessage> {
        val st = snap.settings
        if (!st.isRelay || !st.enabled || !st.msgEnabled) return emptyList()
        val timeline = Planner.timeline(snap, now, zone)
        val today = now.atZone(zone).toLocalDate()
        val sent = snap.sentMessages.toSet()
        val out = ArrayList<DueMessage>()
        for (date in listOf(today.minusDays(1), today)) {
            val offDay = Holidays.isOffDay(date)
            // Un jour de week-end ou férié, seuls les messages « journée entière » partent (et inversement)
            for (rule in st.msgRules.filter { it.enabled && it.offDays == offDay }) {
                val dueAt = ZonedDateTime.of(date, rule.at, zone).toInstant()
                if (dueAt.isAfter(now) || Duration.between(dueAt, now) > MAX_LATE) continue
                // Un rappel annoncé à l'avance (envoyé avant le début du créneau) est périmé une fois le créneau commencé
                val slotStart = ZonedDateTime.of(date, rule.slotTime, zone).toInstant()
                if (rule.at < rule.slotTime && !now.isBefore(slotStart)) continue
                val key = "$date|${rule.id}"
                if (key in sent) continue
                val doctor = recipient(snap, timeline, date, rule, zone) ?: continue
                if (rule.confirmsSwitch) {
                    val expected = Codes.forDoctor(doctor)
                    if (!snap.state.lastOk || expected == null || snap.state.lastCommand != expected) continue
                }
                out += DueMessage(key, rule, doctor, Codes.normalizePhone(doctor.phone), render(rule.text, doctor, date, rule.slotTime, st.slots))
            }
        }
        return out
    }

    /** Prochaine heure d'envoi d'un message actif (pour programmer l'alarme), ou null. */
    fun nextDue(snap: Snapshot, now: Instant, zone: ZoneId): Instant? {
        val st = snap.settings
        if (!st.isRelay || !st.enabled || !st.msgEnabled) return null
        val today = now.atZone(zone).toLocalDate()
        // Chaque règle ne vaut que pour les jours ouvrés, ou que pour les week-ends et jours fériés
        return st.msgRules.filter { it.enabled }
            .flatMap { r ->
                listOf(today, today.plusDays(1)).filter { Holidays.isOffDay(it) == r.offDays }
                    .map { ZonedDateTime.of(it, r.at, zone).toInstant() }
            }
            .filter { it.isAfter(now) }
            .minOrNull()
    }
}

/** Un SMS à envoyer parce qu'une modification a changé l'astreinte EN COURS. */
data class ChangeMessage(val doctor: Doctor, val phone: String?, val text: String, val becomesOnCall: Boolean)

/**
 * Quand une modification du planning (application, SMS, synchro, import) change la cible du renvoi
 * en cours, le nouveau médecin est prévenu qu'il est d'astreinte et l'ancien qu'il ne l'est plus.
 * Un changement programmé à l'heure d'un créneau n'en déclenche pas (les messages horaires s'en chargent).
 */
object ChangeNotice {
    const val UNKNOWN = ""
    const val OFF = "~off"

    fun key(doctorId: String?): String = doctorId ?: OFF

    fun messages(snap: Snapshot, previous: String, new: String, hhmm: String): List<ChangeMessage> {
        val st = snap.settings
        if (!st.isRelay || !st.enabled || !st.msgEnabled || !st.changeNotify) return emptyList()
        if (previous == UNKNOWN || previous == new) return emptyList()
        fun doctor(t: String): Doctor? = if (t == UNKNOWN || t == OFF || t == STANDARD_ID) null else snap.doctor(t)
        fun render(template: String, d: Doctor) = template.replace("{nom}", d.name).replace("{heure}", hhmm)
        val out = ArrayList<ChangeMessage>()
        doctor(new)?.let { out += ChangeMessage(it, Codes.normalizePhone(it.phone), render(st.changeTextNew, it), true) }
        doctor(previous)?.let { out += ChangeMessage(it, Codes.normalizePhone(it.phone), render(st.changeTextOld, it), false) }
        return out
    }
}
