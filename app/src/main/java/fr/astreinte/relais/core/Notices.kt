package fr.astreinte.relais.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Un SMS à envoyer à cause d'une modification du planning : texte déjà mis en forme, plus la période annoncée. */
data class PendingNotice(
    val doctorId: String,
    val text: String,
    /** Clé de la période annoncée au médecin (pour ne pas lui renvoyer le message de début d'astreinte), ou "". */
    val announcedKey: String = "",
)

/**
 * Qui prévenir quand une modification du planning change des astreintes À VENIR (sous [HORIZON_DAYS] jours) :
 *  - le médecin qui REPREND une astreinte à un autre (« tu es d'astreinte du … au … ») ;
 *  - le médecin qui la PERD, même en partie (« tu n'es plus d'astreinte du … au … »).
 * Remplir un planning vide ne prévient personne (le message de début d'astreinte s'en charge), pas plus
 * que ré-importer un planning identique. Le changement de l'astreinte EN COURS est géré par [ChangeNotice]
 * (il attend que la bascule soit faite) : il n'apparaît pas ici.
 */
object PeriodNotices {
    const val HORIZON_DAYS = 7L
    private val FAR: Instant = Instant.parse("9999-01-01T00:00:00Z")

    private fun real(p: Period) = p.doctorId != null && p.doctorId != STANDARD_ID
    private fun endOf(p: Period) = p.end ?: FAR
    private fun overlaps(a: Period, b: Period) = a.start < endOf(b) && b.start < endOf(a)

    fun compute(prev: Snapshot, next: Snapshot, now: Instant, zone: ZoneId, hhmm: String): List<PendingNotice> {
        val st = next.settings
        if (!st.isRelay || !st.enabled || !st.msgEnabled || !st.changeNotify) return emptyList()
        val horizon = now.plus(Duration.ofDays(HORIZON_DAYS))
        val before = Messages.periods(Planner.timeline(prev, now, zone), zone).filter { it.start < horizon && endOf(it) > now }
        val after = Messages.periods(Planner.timeline(next, now, zone), zone).filter { it.start < horizon && endOf(it) > now }
        val out = ArrayList<PendingNotice>()

        fun fill(template: String, doctor: Doctor, start: Instant, end: Instant?) = template
            .replace("{nom}", doctor.name).replace("{heure}", hhmm)
            .replace("{debut}", Messages.whenText(start, zone))
            .replace("{fin}", end?.let { Messages.whenText(it, zone) } ?: "nouvel ordre")

        // Reprise : une période à venir en remplace une qui était confiée à un autre médecin
        for (pn in after.filter { real(it) && it.start > now }) {
            if (before.none { real(it) && it.doctorId != pn.doctorId && overlaps(it, pn) }) continue
            val d = next.doctor(pn.doctorId) ?: continue
            out += PendingNotice(d.id, fill(st.changeTextNext, d, pn.start, pn.end), Messages.keyOf(pn, zone))
        }

        // Perte : une partie à venir d'une période d'un médecin ne lui est plus confiée
        for (pp in before.filter { real(it) }) {
            var pieces = listOf(pp.start to endOf(pp))
            for (n in after.filter { it.doctorId == pp.doctorId && overlaps(it, pp) }) {
                pieces = pieces.flatMap { (s, e) ->
                    val cs = n.start; val ce = endOf(n)
                    when {
                        cs <= s && ce >= e -> emptyList()
                        else -> buildList {
                            if (cs > s) add(s to minOf(e, cs))
                            if (ce < e) add(maxOf(s, ce) to e)
                        }
                    }
                }
            }
            val d = prev.doctor(pp.doctorId) ?: continue
            for ((ls, le) in pieces) {
                if (ls <= now || ls >= horizon) continue      // l'astreinte en cours qui change : voir ChangeNotice
                out += PendingNotice(d.id, fill(st.changeTextLost, d, ls, if (le == FAR) null else le))
            }
        }
        return out
    }
}
