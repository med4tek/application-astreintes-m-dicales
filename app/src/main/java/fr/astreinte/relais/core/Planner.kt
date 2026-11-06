package fr.astreinte.relais.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Calculs purs sur le planning (aucune dépendance Android, donc testable en JVM). */
object Planner {

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

    fun shiftsOn(shifts: List<Shift>, date: LocalDate): List<Shift> =
        sorted(shifts).filter { it.date == date }

    /** Astreinte déjà en cours à 00:00 de [date] (celle "héritée" de la veille). */
    fun carriedInto(shifts: List<Shift>, date: LocalDate, zone: ZoneId): Shift? {
        val midnight = date.atStartOfDay(zone).toInstant()
        return sorted(shifts).lastOrNull { startOf(it, zone) < midnight }
    }

    /** Remplace tous les changements de [date] par [newShifts] (un seul par heure, le dernier gagne). */
    fun replaceDay(shifts: List<Shift>, date: LocalDate, newShifts: List<Shift>): List<Shift> {
        val deduped = newShifts.associateBy { it.time }.values
        return shifts.filter { it.date != date } + deduped.map { it.copy(date = date) }
    }

    /**
     * Pour chaque jour de [from] à [to] inclus : supprime les changements existants et pose un
     * changement à [time] vers [doctorId]. [newId] fournit les identifiants.
     */
    fun fillRange(
        shifts: List<Shift>,
        from: LocalDate,
        to: LocalDate,
        time: LocalTime,
        doctorId: String?,
        newId: () -> String,
    ): List<Shift> {
        if (to.isBefore(from)) return shifts
        val kept = shifts.filter { it.date < from || it.date > to }
        val added = generateSequence(from) { d -> d.plusDays(1).takeIf { !it.isAfter(to) } }
            .map { Shift(newId(), it, time, doctorId) }
            .toList()
        return kept + added
    }
}
