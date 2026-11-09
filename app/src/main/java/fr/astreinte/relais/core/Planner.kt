package fr.astreinte.relais.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Calculs purs sur le planning (aucune dépendance Android, donc testable en JVM).
 *
 * Deux sortes de changements coexistent :
 *  - les changements SAISIS ([Snapshot.shifts]) ;
 *  - les changements PAR DÉFAUT, déduits des créneaux types ([Slot.defaultTargetId]) pour chaque
 *    jour où rien n'a été saisi à cette heure (ex. 9h et 14h = Standard).
 * [effective] / [timeline] fusionnent les deux : c'est ce que le moteur exécute.
 */
object Planner {

    private const val DEFAULT_PREFIX = "default-"

    /** Un changement déduit d'un créneau type (et non saisi par l'utilisateur) ? */
    fun isDefault(shift: Shift) = shift.id.startsWith(DEFAULT_PREFIX)

    fun sorted(shifts: List<Shift>): List<Shift> =
        shifts.sortedWith(compareBy<Shift>({ it.date }, { it.time }))

    fun startOf(shift: Shift, zone: ZoneId): Instant =
        ZonedDateTime.of(shift.date, shift.time, zone).toInstant()

    /** Astreinte en vigueur à l'instant [now] : le dernier changement déjà passé. */
    fun current(shifts: List<Shift>, now: Instant, zone: ZoneId): Shift? =
        sorted(shifts).lastOrNull { startOf(it, zone) <= now }

    /** Prochain changement strictement après [now]. */
    fun next(shifts: List<Shift>, now: Instant, zone: ZoneId): Shift? =
        sorted(shifts).firstOrNull { startOf(it, zone) > now }

    /** Dernier changement strictement AVANT [at] dans une liste déjà triée (valeur "héritée"). */
    fun inForceBefore(sortedShifts: List<Shift>, at: Instant, zone: ZoneId): Shift? =
        sortedShifts.lastOrNull { startOf(it, zone) < at }

    fun shiftsOn(shifts: List<Shift>, date: LocalDate): List<Shift> =
        sorted(shifts).filter { it.date == date }

    // ------------------------------------------------------------------ créneaux par défaut

    /**
     * Changements saisis + changements par défaut des créneaux types, de [from] à [to] inclus.
     * Un défaut n'est généré que si aucun changement saisi n'existe à la même date et heure.
     * Les changements saisis hors de la fenêtre sont conservés tels quels. Résultat trié.
     */
    fun effective(shifts: List<Shift>, slots: List<Slot>, from: LocalDate, to: LocalDate): List<Shift> {
        val taken = shifts.mapTo(HashSet()) { it.date to it.time }
        val defaults = ArrayList<Shift>()
        var d = from
        while (!d.isAfter(to)) {
            for (slot in slots) {
                val target = slot.defaultTargetId ?: continue
                if ((d to slot.time) !in taken) {
                    defaults += Shift("$DEFAULT_PREFIX$d-${slot.time}", d, slot.time, target)
                }
            }
            d = d.plusDays(1)
        }
        return sorted(shifts + defaults)
    }

    /** Ce que le moteur doit exécuter autour de [now] : 7 jours en arrière, 60 jours en avant. */
    fun timeline(snap: Snapshot, now: Instant, zone: ZoneId): List<Shift> {
        val today = now.atZone(zone).toLocalDate()
        val lastExplicit = snap.shifts.maxOfOrNull { it.date }
        val to = listOfNotNull(today.plusDays(60), lastExplicit).max()
        return effective(snap.shifts, snap.settings.slots, today.minusDays(7), to)
    }

    fun currentShift(snap: Snapshot, now: Instant, zone: ZoneId): Shift? =
        current(timeline(snap, now, zone), now, zone)

    fun nextShift(snap: Snapshot, now: Instant, zone: ZoneId): Shift? =
        next(timeline(snap, now, zone), now, zone)

    // ------------------------------------------------------------------ modifications

    /** Remplace tous les changements saisis de [date] par [newShifts] (un seul par heure, le dernier gagne). */
    fun replaceDay(shifts: List<Shift>, date: LocalDate, newShifts: List<Shift>): List<Shift> {
        val deduped = newShifts.associateBy { it.time }.values
        return shifts.filter { it.date != date } + deduped.map { it.copy(date = date) }
    }

    /** Supprime toutes les saisies des [dates] (les valeurs par défaut reprennent alors). */
    fun clearDates(shifts: List<Shift>, dates: Set<LocalDate>): List<Shift> =
        shifts.filter { it.date !in dates }

    /**
     * Applique des modifications case par case. Chaque clé (date, heure) est d'abord vidée, puis
     * reposée si l'édition est un [CellEdit.Set].
     */
    fun applyEdits(
        shifts: List<Shift>,
        edits: Map<Pair<LocalDate, java.time.LocalTime>, CellEdit>,
        newId: () -> String,
    ): List<Shift> {
        val kept = shifts.filter { (it.date to it.time) !in edits }
        val added = edits.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .mapNotNull { (key, edit) ->
                (edit as? CellEdit.Set)?.let { Shift(newId(), key.first, key.second, it.doctorId) }
            }
        return kept + added
    }

    /** Même modification [edits] (par heure de créneau) sur chacune des [dates]. */
    fun editCells(
        shifts: List<Shift>,
        dates: Set<LocalDate>,
        edits: Map<java.time.LocalTime, CellEdit>,
        newId: () -> String,
    ): List<Shift> = applyEdits(
        shifts,
        dates.flatMap { d -> edits.map { (t, e) -> (d to t) to e } }.toMap(),
        newId,
    )
}
