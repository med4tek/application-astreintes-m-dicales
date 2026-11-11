package fr.astreinte.relais.core

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Un message prêt à partir. [key] sert à ne jamais l'envoyer deux fois. */
data class DueMessage(
    val key: String,
    val doctor: Doctor,
    /** Numéro normalisé, ou null s'il est inutilisable. */
    val phone: String?,
    val text: String,
)

/**
 * Une période d'astreinte : suite ININTERROMPUE de créneaux confiés à la même cible (un médecin, le standard
 * ou « aucun renvoi »). Un vendredi 18h -> lundi 9h confié au même médecin est UNE période, même si le planning
 * contient des créneaux à 18h, 9h, 14h… : le médecin ne reçoit qu'un message.
 */
data class Period(val doctorId: String?, val start: Instant, val end: Instant?)

/** Message automatique du relais vers le médecin qui prend l'astreinte : de quand à quand (calculs purs). */
object Messages {
    /** Au-delà, un message en retard (téléphone éteint…) n'a plus de sens : on ne l'envoie pas. */
    val MAX_LATE: Duration = Duration.ofMinutes(90)

    fun hourText(t: LocalTime) = if (t.minute == 0) "${t.hour}h" else "%dh%02d".format(t.hour, t.minute)

    /** « ven 13/11 a 18h » (sans accent : un SMS à caractères spéciaux est découpé en messages plus courts). */
    fun whenText(at: Instant, zone: ZoneId): String {
        val z = at.atZone(zone)
        val day = z.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.FRENCH).trimEnd('.')
        return "$day %02d/%02d a %s".format(z.dayOfMonth, z.monthValue, hourText(z.toLocalTime()))
    }

    /** Les périodes de la chronologie [timeline] (déjà fusionnée avec les valeurs par défaut), dans l'ordre. */
    fun periods(timeline: List<Shift>, zone: ZoneId): List<Period> {
        val sorted = Planner.sorted(timeline)
        val out = ArrayList<Period>()
        var i = 0
        while (i < sorted.size) {
            val first = sorted[i]
            var j = i + 1
            while (j < sorted.size && sorted[j].doctorId == first.doctorId) j++
            out += Period(first.doctorId, Planner.startOf(first, zone), sorted.getOrNull(j)?.let { Planner.startOf(it, zone) })
            i = j
        }
        return out
    }

    /** La période en cours à l'instant [now], avec sa vraie fin. */
    fun currentPeriod(snap: Snapshot, now: Instant, zone: ZoneId): Period? =
        periods(Planner.timeline(snap, now, zone), zone).lastOrNull { it.start <= now && (it.end == null || it.end > now) }

    fun keyOf(p: Period, zone: ZoneId): String {
        val z = p.start.atZone(zone)
        return "P|${z.toLocalDate()}|${z.toLocalTime()}|${p.doctorId}"
    }

    /** Remplace {nom} {debut} {fin}. */
    fun render(template: String, doctor: Doctor, start: Instant, end: Instant?, zone: ZoneId): String =
        template
            .replace("{nom}", doctor.name)
            .replace("{debut}", whenText(start, zone))
            .replace("{fin}", end?.let { whenText(it, zone) } ?: "nouvel ordre")

    /**
     * Messages à envoyer maintenant : une période d'astreinte confiée à un médecin vient de commencer
     * (depuis moins de [MAX_LATE]), n'est pas terminée, n'a pas déjà été annoncée, et la bascule vers ce
     * médecin est réellement faite. Jamais pour le standard ni pour « aucun renvoi ».
     */
    fun due(snap: Snapshot, now: Instant, zone: ZoneId): List<DueMessage> {
        val st = snap.settings
        if (!st.isRelay || !st.enabled || !st.msgEnabled) return emptyList()
        val sent = snap.sentMessages.toSet()
        val out = ArrayList<DueMessage>()
        for (p in periods(Planner.timeline(snap, now, zone), zone)) {
            val id = p.doctorId ?: continue
            if (id == STANDARD_ID) continue
            if (p.start.isAfter(now) || Duration.between(p.start, now) > MAX_LATE) continue
            if (p.end != null && !p.end.isAfter(now)) continue
            val key = keyOf(p, zone)
            if (key in sent) continue
            val doctor = snap.doctor(id) ?: continue
            val expected = Codes.forDoctor(doctor)
            if (!snap.state.lastOk || expected == null || snap.state.lastCommand != expected) continue
            out += DueMessage(key, doctor, Codes.normalizePhone(doctor.phone), render(st.periodText, doctor, p.start, p.end, zone))
        }
        return out
    }

    /** Début de la prochaine période confiée à un médecin (pour programmer l'alarme), ou null. */
    fun nextDue(snap: Snapshot, now: Instant, zone: ZoneId): Instant? {
        val st = snap.settings
        if (!st.isRelay || !st.enabled || !st.msgEnabled) return null
        return periods(Planner.timeline(snap, now, zone), zone)
            .filter { it.doctorId != null && it.doctorId != STANDARD_ID && it.start.isAfter(now) }
            .minOfOrNull { it.start }
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

    /** [end] : fin de l'astreinte du nouveau médecin, déjà mise en forme (variable {fin}). */
    fun messages(snap: Snapshot, previous: String, new: String, hhmm: String, end: String = "nouvel ordre"): List<ChangeMessage> {
        val st = snap.settings
        if (!st.isRelay || !st.enabled || !st.msgEnabled || !st.changeNotify) return emptyList()
        if (previous == UNKNOWN || previous == new) return emptyList()
        fun doctor(t: String): Doctor? = if (t == UNKNOWN || t == OFF || t == STANDARD_ID) null else snap.doctor(t)
        fun render(template: String, d: Doctor) = template.replace("{nom}", d.name).replace("{heure}", hhmm).replace("{fin}", end)
        val out = ArrayList<ChangeMessage>()
        doctor(new)?.let { out += ChangeMessage(it, Codes.normalizePhone(it.phone), render(st.changeTextNew, it), true) }
        doctor(previous)?.let { out += ChangeMessage(it, Codes.normalizePhone(it.phone), render(st.changeTextOld, it), false) }
        return out
    }
}
