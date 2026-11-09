package fr.astreinte.relais.core

import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/** Enveloppe d'un SMS : "AST <PIN> <commande…>". Tout SMS qui ne commence pas par AST est ignoré. */
sealed interface SmsParse {
    data object NotCommand : SmsParse
    data class Malformed(val why: String) : SmsParse
    data class Command(val pin: String, val args: List<String>) : SmsParse
}

/** Jours visés + ce qu'il faut y changer. */
data class EditGroup(
    val dates: Set<LocalDate>,
    val clearDay: Boolean,
    val edits: Map<LocalTime, CellEdit>,
)

sealed interface SmsAction {
    data object Query : SmsAction
    data class Edit(val groups: List<EditGroup>) : SmsAction
    data class Error(val why: String) : SmsAction
}

/**
 * Langage de commande, volontairement court (SMS tapés à la main) :
 *
 *     AST 1234 12/11 18=Martin            astreinte de 18h du 12/11 : Martin
 *     AST 1234 12/11 12=Dupont 18=Martin  plusieurs créneaux le même jour
 *     AST 1234 12/11-15/11 18=Martin      une période (bornes incluses)
 *     AST 1234 12/11,14/11 18=Martin      plusieurs jours
 *     AST 1234 12/11 18=Martin 13/11 18=Durand   plusieurs groupes
 *     AST 1234 demain 9=STD 18=OFF        mots : auj, dem/demain ; STD = standard ; OFF = aucun renvoi
 *     AST 1234 12/11 RAZ                  efface la saisie du jour (retour aux valeurs par défaut)
 *     AST 1234 12/11 12=DEF               efface seulement ce créneau
 *     AST 1234 ?                          répond avec l'état actuel
 */
object SmsCommands {
    private val DATE = Regex("^(\\d{1,2})[/.](\\d{1,2})(?:[/.](\\d{2}|\\d{4}))?$")
    private val SLOT_REF = Regex("^(\\d{1,2})(?:[h:](\\d{2})?)?$", RegexOption.IGNORE_CASE)
    private const val MAX_RANGE_DAYS = 62

    fun parseEnvelope(body: String): SmsParse {
        val tokens = body.trim().split(Regex("\\s+"))
        if (tokens.isEmpty() || !tokens[0].equals("AST", ignoreCase = true)) return SmsParse.NotCommand
        if (tokens.size < 3) return SmsParse.Malformed("Format : AST <PIN> <commande>")
        return SmsParse.Command(tokens[1], tokens.drop(2))
    }

    fun interpret(args: List<String>, doctors: List<Doctor>, slots: List<Slot>, today: LocalDate): SmsAction {
        if (args.size == 1 && args[0] == "?") return SmsAction.Query

        val groups = ArrayList<EditGroup>()
        var dates = LinkedHashSet<LocalDate>()
        var clear = false
        var edits = LinkedHashMap<LocalTime, CellEdit>()
        var hasContent = false

        fun flush(): String? {
            if (dates.isEmpty()) return null
            if (!hasContent) return "Rien à modifier pour le ${fmt(dates.first())} : ajoutez 18=Nom ou RAZ."
            groups += EditGroup(dates, clear, edits)
            return null
        }

        for (token in args) {
            val parsedDates = parseDates(token, today)
            when {
                parsedDates is DateParse.Bad -> return SmsAction.Error(parsedDates.why)
                parsedDates is DateParse.Ok -> {
                    if (hasContent) {
                        flush()?.let { return SmsAction.Error(it) }
                        dates = LinkedHashSet(); clear = false; edits = LinkedHashMap(); hasContent = false
                    }
                    dates.addAll(parsedDates.dates)
                }
                token.equals("RAZ", ignoreCase = true) -> {
                    if (dates.isEmpty()) return SmsAction.Error("Indiquez d'abord la date.")
                    clear = true; hasContent = true
                }
                '=' in token -> {
                    if (dates.isEmpty()) return SmsAction.Error("Indiquez d'abord la date avant « $token ».")
                    val slotRef = token.substringBefore('=')
                    val target = token.substringAfter('=').replace('_', ' ')
                    val time = parseSlotTime(slotRef)
                        ?: return SmsAction.Error("Créneau « $slotRef » illisible.")
                    if (slots.none { it.time == time }) {
                        val known = slots.sortedBy { it.time }.joinToString(" ") { shortHour(it.time) }
                        return SmsAction.Error("Pas de créneau ${shortHour(time)}. Créneaux : $known")
                    }
                    val edit = when (ascii(target).uppercase(Locale.ROOT)) {
                        "OFF", "AUCUN" -> CellEdit.Set(null)
                        "DEF", "DEFAUT" -> CellEdit.Clear
                        "STD", "STANDARD" -> CellEdit.Set(STANDARD_ID)
                        else -> NameMatcher.find(target, doctors)?.let { CellEdit.Set(it.id) }
                            ?: return SmsAction.Error("Médecin « $target » inconnu ou ambigu.")
                    }
                    edits[time] = edit
                    hasContent = true
                }
                else -> return SmsAction.Error("Je ne comprends pas « $token ».")
            }
        }
        flush()?.let { return SmsAction.Error(it) }
        if (groups.isEmpty()) return SmsAction.Error("Aucune date indiquée.")
        return SmsAction.Edit(groups)
    }

    fun apply(shifts: List<Shift>, groups: List<EditGroup>, newId: () -> String): List<Shift> {
        var result = shifts
        for (g in groups) {
            if (g.clearDay) result = Planner.clearDates(result, g.dates)
            if (g.edits.isNotEmpty()) result = Planner.editCells(result, g.dates, g.edits, newId)
        }
        return result
    }

    /** Résumé court pour la réponse SMS / le journal. */
    fun describe(groups: List<EditGroup>, doctors: List<Doctor>): String =
        groups.joinToString(" | ") { g ->
            val days = describeDates(g.dates)
            val parts = buildList {
                if (g.clearDay) add("RAZ")
                g.edits.entries.sortedBy { it.key }.forEach { (t, e) ->
                    val who = when (e) {
                        CellEdit.Clear -> "défaut"
                        is CellEdit.Set -> if (e.doctorId == null) "aucun" else doctors.firstOrNull { it.id == e.doctorId }?.name ?: "?"
                    }
                    add("${shortHour(t)}→$who")
                }
            }
            "$days ${parts.joinToString(" ")}"
        }

    // ------------------------------------------------------------------ dates

    private sealed interface DateParse {
        data class Ok(val dates: Set<LocalDate>) : DateParse
        data class Bad(val why: String) : DateParse
        data object NotADate : DateParse
    }

    /** Un jeton peut être : une date, un mot (auj/dem), une plage A-B, ou une liste séparée par des virgules. */
    private fun parseDates(token: String, today: LocalDate): DateParse {
        val lower = ascii(token).lowercase(Locale.ROOT)
        val parts = lower.split(',')
        val out = LinkedHashSet<LocalDate>()
        for (part in parts) {
            val single = parseSingleDate(part, today)
            if (single != null) {
                when (single) {
                    is DateParse.Ok -> out.addAll(single.dates)
                    is DateParse.Bad -> return single
                    DateParse.NotADate -> return DateParse.NotADate
                }
                continue
            }
            // plage "12/11-15/11"
            val bounds = part.split('-', '>')
            if (bounds.size == 2) {
                val a = parseSingleDate(bounds[0], today)
                val b = parseSingleDate(bounds[1], today)
                if (a is DateParse.Ok && b is DateParse.Ok) {
                    val from = a.dates.first()
                    val to = b.dates.first()   // "28/12-02/01" : l'inférence d'année met déjà la fin en janvier suivant
                    if (to.isBefore(from)) return DateParse.Bad("Plage inversée : $token")
                    if (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
                        return DateParse.Bad("Plage trop longue (max $MAX_RANGE_DAYS jours).")
                    }
                    generateSequence(from) { d -> d.plusDays(1).takeIf { !it.isAfter(to) } }.forEach { out += it }
                    continue
                }
                if (a is DateParse.Bad) return a
                if (b is DateParse.Bad) return b
            }
            return DateParse.NotADate
        }
        return DateParse.Ok(out)
    }

    private fun parseSingleDate(s: String, today: LocalDate): DateParse? {
        when (s) {
            "auj", "aujourdhui" -> return DateParse.Ok(setOf(today))
            "dem", "demain" -> return DateParse.Ok(setOf(today.plusDays(1)))
        }
        val m = DATE.matchEntire(s) ?: return null
        val day = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        val yRaw = m.groupValues[3]
        return try {
            val date = if (yRaw.isNotEmpty()) {
                LocalDate.of(if (yRaw.length == 2) 2000 + yRaw.toInt() else yRaw.toInt(), month, day)
            } else {
                // Sans année : la date la plus proche, en tolérant un mois de retard
                val thisYear = LocalDate.of(today.year, month, day)
                if (thisYear.isBefore(today.minusDays(30))) thisYear.plusYears(1) else thisYear
            }
            DateParse.Ok(setOf(date))
        } catch (e: java.time.DateTimeException) {
            DateParse.Bad("Date invalide : $s")
        }
    }

    private fun parseSlotTime(ref: String): LocalTime? {
        val m = SLOT_REF.matchEntire(ref.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
    }

    private fun shortHour(t: LocalTime) = if (t.minute == 0) "${t.hour}h" else "%dh%02d".format(t.hour, t.minute)

    private fun fmt(d: LocalDate) = "%02d/%02d".format(d.dayOfMonth, d.monthValue)

    private fun describeDates(dates: Set<LocalDate>): String {
        val sorted = dates.sorted()
        if (sorted.size == 1) return fmt(sorted[0])
        val contiguous = sorted.zipWithNext().all { (a, b) -> a.plusDays(1) == b }
        return if (contiguous) "${fmt(sorted.first())}-${fmt(sorted.last())}" else sorted.joinToString(",") { fmt(it) }
    }

    private fun ascii(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
}
