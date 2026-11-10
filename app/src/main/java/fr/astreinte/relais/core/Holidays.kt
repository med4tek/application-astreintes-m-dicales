package fr.astreinte.relais.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Jours fériés français (métropole, sans les spécificités d'Alsace-Moselle) et jours « chômés » pour
 * l'astreinte : les samedis, dimanches et jours fériés, où UNE SEULE personne couvre toute la journée.
 */
object Holidays {

    /** Dimanche de Pâques (calendrier grégorien, algorithme de Meeus/Jones/Butcher). */
    fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    /** Nom du jour férié, ou null. */
    fun holidayName(date: LocalDate): String? {
        val y = date.year
        val fixed = when (date.monthValue to date.dayOfMonth) {
            1 to 1 -> "Jour de l'an"
            5 to 1 -> "Fête du travail"
            5 to 8 -> "Victoire 1945"
            7 to 14 -> "Fête nationale"
            8 to 15 -> "Assomption"
            11 to 1 -> "Toussaint"
            11 to 11 -> "Armistice"
            12 to 25 -> "Noël"
            else -> null
        }
        if (fixed != null) return fixed
        val easter = easter(y)
        return when (date) {
            easter.plusDays(1) -> "Lundi de Pâques"
            easter.plusDays(39) -> "Ascension"
            easter.plusDays(50) -> "Lundi de Pentecôte"
            else -> null
        }
    }

    fun isHoliday(date: LocalDate) = holidayName(date) != null

    fun isWeekend(date: LocalDate) = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    /** Week-end ou jour férié : une seule personne pour toute la journée, pas de standard par défaut. */
    fun isOffDay(date: LocalDate) = isWeekend(date) || isHoliday(date)

    fun offDays(month: YearMonth): List<LocalDate> = (1..month.lengthOfMonth()).map { month.atDay(it) }.filter { isOffDay(it) }
}
