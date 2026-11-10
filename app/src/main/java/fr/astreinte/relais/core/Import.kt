package fr.astreinte.relais.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Ce que la réponse du LLM demande pour une case (jour × heure). */
sealed interface Target {
    data object Off : Target
    data object Clear : Target
    data class Doc(val id: String) : Target

    /** Nom lu par le LLM mais absent de la liste des médecins : à faire associer par l'utilisateur. */
    data class Unknown(val name: String) : Target
}

data class ImportItem(val date: LocalDate, val time: LocalTime, val raw: String, val target: Target)

data class ImportResult(
    val items: List<ImportItem>,
    val problems: List<String>,
    /** Médecins (nom + numéro) lus sur la photo par l'assistant, à ajouter ou compléter dans la liste. */
    val doctors: List<DoctorList.Entry> = emptyList(),
) {
    val unknownNames: List<String>
        get() = items.mapNotNull { (it.target as? Target.Unknown)?.name }.distinct()
    val dayCount: Int get() = items.map { it.date }.distinct().size
}

/** Lit la réponse JSON d'un LLM (tolérante : blocs ```json, texte autour, formats de date/heure variés). */
object ImportParser {
    private val SKIPPED_VALUES = setOf("", "?", "-", "—", "–", "null", "n/a", "na", "none")
    private val IGNORED_KEYS = setOf("date", "jour", "day", "weekday", "note", "notes", "comment", "commentaire")
    private val TIME_KEY = Regex("^(\\d{1,2})\\s*[h:]\\s*(\\d{2})?$", RegexOption.IGNORE_CASE)

    fun parse(
        text: String,
        doctors: List<Doctor>,
        slots: List<Slot>,
        aliases: Map<String, String> = emptyMap(),
    ): ImportResult {
        val problems = ArrayList<String>()
        val json = extractJson(sanitize(text))
            ?: return ImportResult(emptyList(), listOf("Aucun bloc JSON trouvé dans le texte collé."))

        val root = try {
            JSONTokener(json).nextValue()
        } catch (e: JSONException) {
            return ImportResult(
                emptyList(),
                listOf(
                    "JSON illisible : ${e.message}",
                    "Astuce : demandez à l'assistant de répondre dans un bloc de code, puis utilisez le bouton « Copier » de ce bloc.",
                )
            )
        }

        var defaultYear = LocalDate.now().year
        var photoDoctors: List<DoctorList.Entry> = emptyList()
        val days: JSONArray = when (root) {
            is JSONArray -> root
            is JSONObject -> {
                root.optString("month").takeIf { it.isNotBlank() }?.let { m ->
                    runCatching { YearMonth.parse(m.trim()) }.getOrNull()?.let { defaultYear = it.year }
                }
                photoDoctors = parseDoctors(root)
                root.optJSONArray("days")
                    ?: return if (photoDoctors.isNotEmpty()) ImportResult(emptyList(), emptyList(), photoDoctors)
                    else ImportResult(emptyList(), listOf("Le JSON ne contient pas de liste « days »."))
            }
            else -> return ImportResult(emptyList(), listOf("Format JSON inattendu."))
        }

        val items = ArrayList<ImportItem>()
        val slotTimes = slots.map { it.time }.toSet()
        val warnedTimes = HashSet<LocalTime>()
        for (i in 0 until days.length()) {
            val o = days.optJSONObject(i)
            if (o == null) {
                problems += "Élément n°${i + 1} ignoré (ce n'est pas un jour)."
                continue
            }
            val dateText = o.optString("date")
            val date = parseDate(dateText, defaultYear)
            if (date == null) {
                problems += "Élément n°${i + 1} : date illisible « $dateText »."
                continue
            }
            val keys = o.keys().asSequence().toList()
            for (key in keys) {
                if (key.trim().lowercase(Locale.ROOT) in IGNORED_KEYS) continue
                val time = parseTime(key) ?: continue
                if (o.isNull(key)) continue
                val raw = o.opt(key)?.toString()?.trim().orEmpty()
                if (raw.lowercase(Locale.ROOT) in SKIPPED_VALUES) continue
                if (time !in slotTimes && warnedTimes.add(time)) {
                    problems += "L'heure ${time} ne correspond à aucun créneau : ajoutée comme changement libre."
                }
                items += ImportItem(date, time, raw, classify(raw, doctors, aliases))
            }
        }
        val firstSlot = slots.minOfOrNull { it.time }
        val normalized = if (firstSlot == null) items else wholeDayOnOffDays(items, firstSlot)
        return ImportResult(normalized.sortedWith(compareBy({ it.date }, { it.time })), problems, photoDoctors)
    }

    /**
     * Week-end ou jour férié : une seule personne pour toute la journée. Si l'assistant l'a placée
     * sur un autre créneau que le premier (ex. la colonne « Garde » à 18h) et que toutes les cases du
     * jour désignent la même personne, elle est ramenée au premier créneau (début de la journée).
     */
    internal fun wholeDayOnOffDays(items: List<ImportItem>, firstSlot: LocalTime): List<ImportItem> {
        fun same(a: Target, b: Target) = when {
            a is Target.Unknown && b is Target.Unknown -> NameMatcher.normalize(a.name) == NameMatcher.normalize(b.name)
            else -> a == b
        }
        val out = ArrayList<ImportItem>()
        for ((date, day) in items.groupBy { it.date }) {
            val single = Holidays.isOffDay(date) && day.none { it.time == firstSlot } &&
                day.all { same(it.target, day.first().target) }
            if (single) out += day.first().copy(time = firstSlot) else out += day
        }
        return out
    }

    /**
     * Liste « medecins » du JSON : [{"nom": "..."}] (ou simples chaînes). Seuls les NOMS sont retenus :
     * un numéro éventuellement donné par l'assistant est volontairement ignoré (les numéros se saisissent
     * à la main, un chiffre mal lu enverrait le renvoi d'appel à la mauvaise personne). Le standard et
     * les doublons sont écartés.
     */
    internal fun parseDoctors(root: JSONObject): List<DoctorList.Entry> {
        val arr = listOf("medecins", "médecins", "doctors", "contacts").firstNotNullOfOrNull { root.optJSONArray(it) }
            ?: return emptyList()
        val names = ArrayList<String>()
        for (i in 0 until arr.length()) {
            when (val o = arr.opt(i)) {
                is JSONObject -> listOf("nom", "name", "medecin", "médecin").firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() } }?.let { names += it }
                is String -> names += o
            }
        }
        return names.map { it.substringBefore(';').replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() && !NameMatcher.normalize(it).startsWith("standard") }
            .map { DoctorList.Entry(DoctorList.prettyName(it), "") }
            .distinctBy { NameMatcher.normalize(it.name) }
    }

    /** Résultat d'import -> modifications de cases ; [mapping] associe les noms inconnus à un médecin. */
    fun toEdits(
        items: List<ImportItem>,
        mapping: Map<String, String>,
    ): Map<Pair<LocalDate, LocalTime>, CellEdit> {
        val out = LinkedHashMap<Pair<LocalDate, LocalTime>, CellEdit>()
        for (it in items) {
            val edit: CellEdit = when (val t = it.target) {
                Target.Off -> CellEdit.Set(null)
                Target.Clear -> CellEdit.Clear
                is Target.Doc -> CellEdit.Set(t.id)
                is Target.Unknown -> mapping[t.name]?.let { id -> CellEdit.Set(id) } ?: continue
            }
            out[it.date to it.time] = edit
        }
        return out
    }

    private fun classify(raw: String, doctors: List<Doctor>, aliases: Map<String, String>): Target {
        val key = ascii(raw).uppercase(Locale.ROOT).trim()
        return when (key) {
            "AUCUN", "AUCUN RENVOI", "OFF", "RIEN" -> Target.Off
            "DEFAUT", "DEFAULT", "PAR DEFAUT" -> Target.Clear
            else -> {
                // 1) correspondance mémorisée à un import précédent, 2) reconnaissance par le nom
                val remembered = aliases[NameMatcher.normalize(raw)]?.takeIf { id -> doctors.any { it.id == id } }
                if (remembered != null) Target.Doc(remembered)
                else NameMatcher.find(raw, doctors)?.let { Target.Doc(it.id) } ?: Target.Unknown(raw)
            }
        }
    }

    private fun ascii(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /**
     * Répare les défauts typiques d'un texte copié depuis une page de chat : espaces insécables,
     * guillemets typographiques “ ” et surtout échappements Markdown (`\\[`, `\\]`, `\\_`, `\\*`…),
     * qui rendent le JSON invalide. Les vrais échappements JSON (`\\"`, `\\n`, `\\u00e9`…) sont conservés.
     */
    internal fun sanitize(text: String): String = text
        .replace('\u00A0', ' ').replace('\u202F', ' ')
        .replace('\u201C', '"').replace('\u201D', '"').replace('\u201E', '"')
        .replace(Regex("\\\\([\\[\\]{}_*#`()!+<>|~.\\-])"), "$1")

    internal fun extractJson(text: String): String? {
        val start = text.indexOfFirst { it == '{' || it == '[' }
        val end = text.indexOfLast { it == '}' || it == ']' }
        return if (start in 0 until end) text.substring(start, end + 1) else null
    }

    internal fun parseDate(text: String, defaultYear: Int): LocalDate? {
        val t = text.trim()
        runCatching { return LocalDate.parse(t) }
        Regex("^(\\d{1,2})[/.\\-](\\d{1,2})(?:[/.\\-](\\d{2,4}))?$").matchEntire(t)?.let { m ->
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val yRaw = m.groupValues[3]
            val y = when {
                yRaw.isEmpty() -> defaultYear
                yRaw.length == 2 -> 2000 + yRaw.toInt()
                else -> yRaw.toInt()
            }
            return runCatching { LocalDate.of(y, mo, d) }.getOrNull()
        }
        return null
    }

    internal fun parseTime(key: String): LocalTime? {
        val m = TIME_KEY.matchEntire(key.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
    }
}

/** Génère la consigne à donner au LLM avec la photo du planning. */
object PromptBuilder {
    private val MONTH_FR = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.FRENCH)

    fun build(month: YearMonth, doctors: List<Doctor>, slots: List<Slot>, notes: String): String {
        val monthLabel = MONTH_FR.format(month)
        val names = doctors.filter { it.id != STANDARD_ID }.map { it.name }
        val standard = doctors.firstOrNull { it.id == STANDARD_ID }

        val slotLines = slots.sortedBy { it.time }.joinToString("\n") { s ->
            val def = s.defaultTargetId?.let { id -> doctors.firstOrNull { it.id == id }?.name }
            val suffix = if (def != null) " — par défaut : $def (à renseigner seulement si le planning indique autre chose ; jamais par défaut les week-ends et jours fériés)" else ""
            "- \"${s.time}\" : ${s.label}$suffix"
        }
        val example = exampleJson(month, names.take(3), slots)

        return buildString {
            appendLine("Tu reçois la photo d'un planning d'astreinte médicale pour $monthLabel. Lis-la attentivement et transcris-la en JSON.")
            appendLine()
            appendLine("CONTEXTE")
            appendLine("Chaque jour est découpé en créneaux. Voici les heures de changement (clés du JSON) :")
            appendLine(slotLines)
            appendLine()
            appendLine("Associe chaque colonne ou ligne de la photo au créneau qui correspond (d'après son intitulé ou son horaire).")
            appendLine()
            val off = Holidays.offDays(month)
            val first = slots.minOfOrNull { it.time }
            if (off.isNotEmpty() && first != null) {
                val fmt = DateTimeFormatter.ofPattern("EEE d", Locale.FRENCH)
                appendLine("WEEK-ENDS ET JOURS FÉRIÉS")
                appendLine("Ce mois-ci : " + off.joinToString(", ") { d -> fmt.format(d) + (Holidays.holidayName(d)?.let { " (férié)" } ?: "") } + ".")
                appendLine("Ces jours-là, UNE SEULE personne couvre toute la journée : pas de standard à 9h ni à 14h. S'il n'y a qu'un nom pour le jour, mets-le uniquement sur le premier créneau (\"$first\"), quelle que soit la colonne où il figure sur la photo. N'ajoute un autre créneau que si le planning désigne explicitement un autre médecin à une heure précise. Applique la même règle à tout autre jour férié que tu verrais sur le planning.")
                appendLine()
            }
            appendLine("MÉDECINS CONNUS")
            if (names.isEmpty()) appendLine("(aucun pour l'instant)") else appendLine(names.joinToString(", "))
            if (standard != null) appendLine("Le « ${standard.name} » désigne le standard d'urgence : écris exactement « ${standard.name} » s'il est indiqué.")
            appendLine("Sur un planning les médecins sont souvent désignés par leur prénom seul ou leur nom seul : associe chaque désignation au médecin correspondant de la liste et écris son nom EXACT tel qu'il figure dans la liste.")
            appendLine("Si une case contient autre chose qu'un médecin (nom d'un lieu ou d'un service par exemple), recopie-la telle qu'elle est écrite. Si personne de la liste ne correspond, recopie le nom tel qu'il est écrit.")
            if (notes.isNotBlank()) {
                appendLine()
                appendLine("PRÉCISIONS SUR CE PLANNING")
                appendLine(notes.trim())
            }
            appendLine()
            appendLine("MÉDECINS LUS SUR LA PHOTO")
            appendLine("Donne aussi la liste des médecins dont tu lis le nom sur la photo (planning, légende, liste…), dans une liste \"medecins\" : un objet par médecin avec son \"nom\".")
            appendLine("- Inclus TOUS les médecins dont tu lis le nom, même ceux de la liste ci-dessus (l'application s'en sert pour faire le lien entre les noms du planning et sa liste).")
            appendLine("- \"nom\" : le nom EXACT de la liste ci-dessus si c'est le même médecin, sinon le nom tel qu'il est écrit, avec prénom ET nom quand les deux figurent sur la photo.")
            appendLine("- Ne recopie JAMAIS de numéro de téléphone : les numéros sont saisis à la main dans l'application.")
            appendLine("- Ne mets pas le standard ni un lieu dans cette liste.")
            appendLine()
            appendLine("FORMAT DE RÉPONSE")
            appendLine("Réponds UNIQUEMENT par UN SEUL bloc de code JSON (balises ```json), sans aucun commentaire avant ou après :")
            appendLine(example)
            appendLine()
            appendLine("RÈGLES")
            appendLine("- Un objet par jour du mois (continue sur le même modèle que l'exemple jusqu'à la fin du mois), avec la date au format AAAA-MM-JJ.")
            appendLine("- N'inclus un créneau que si le planning le renseigne pour ce jour. Sinon omets la clé (ne devine rien).")
            appendLine("- Si une case est illisible ou douteuse, omets-la plutôt que d'inventer.")
            appendLine("- Écris \"AUCUN\" si le planning indique explicitement qu'il n'y a pas de renvoi à ce créneau.")
            appendLine("- Ne mets que des noms de personnes, jamais de numéros de téléphone.")
        }.trimEnd()
    }

    private fun exampleJson(month: YearMonth, names: List<String>, slots: List<Slot>): String {
        val a = names.getOrNull(0) ?: "Dr Martin"
        val b = names.getOrNull(1) ?: "Dr Dupont"
        val c = names.getOrNull(2) ?: a
        val noDefault = slots.sortedBy { it.time }.filter { it.defaultTargetId == null }
        fun day(day: Int, who: List<String>): String {
            val parts = noDefault.mapIndexed { i, s -> "\"${s.time}\": \"${who[i % who.size]}\"" }
            return "    {\"date\": \"${month.atDay(day.coerceAtMost(month.lengthOfMonth()))}\"" +
                (if (parts.isEmpty()) "" else ", " + parts.joinToString(", ")) + "}"
        }
        return "{\n  \"month\": \"$month\",\n" +
            "  \"medecins\": [\n    {\"nom\": \"$a\"},\n    {\"nom\": \"$b\"}\n  ],\n" +
            "  \"days\": [\n" +
            day(1, listOf(a, b, c)) + ",\n" + day(2, listOf(b, c, a)) + "\n  ]\n}"
    }
}
