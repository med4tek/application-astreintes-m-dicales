package fr.astreinte.relais.core

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Lecture DIRECTE d'un planning Excel (.xlsx), sans assistant IA : les COULEURS des cases sont lues dans
 * le fichier, ce qu'aucun export texte ni parfois aucune photo ne permet de faire de façon fiable.
 *
 * Modèle de tableau reconnu : une ligne par jour (colonne A = jour de la semaine, B = numéro), et pour
 * chaque médecin un bloc de colonnes sous son nom (« Dr … ») : MATIN, une colonne sans titre (le midi),
 * APRES MIDI et parfois 17-18h. Une case compte si elle est COLORÉE (bleu, jaune…) ; un « x » ou « ABS »
 * sans couleur ne compte pas.
 *  - jour ouvré : 12h = case colorée « midi », 17h = case colorée 17-18h, 18h = case APRES MIDI colorée ;
 *    9h et 14h = standard (rien à écrire) ;
 *  - week-end ou jour férié : une seule personne, celle dont la case MATIN est colorée, sur 9h.
 */
object XlsxPlanning {
    data class RawItem(val date: LocalDate, val time: LocalTime, val name: String)
    data class Parsed(
        val items: List<RawItem>,
        val problems: List<String>,
        val doctorNames: List<String>,
        val sheet: String,
    )

    private const val MAX_ENTRY = 8_000_000
    private const val MAX_TOTAL = 24_000_000
    private const val MIN_CHROMA = 0.10

    private val MONTHS = listOf(
        "janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet", "aout", "septembre", "octobre", "novembre", "decembre",
    )
    private val WEEKDAYS = mapOf(
        "lundi" to DayOfWeek.MONDAY, "mardi" to DayOfWeek.TUESDAY, "mercredi" to DayOfWeek.WEDNESDAY,
        "jeudi" to DayOfWeek.THURSDAY, "vendredi" to DayOfWeek.FRIDAY, "samedi" to DayOfWeek.SATURDAY,
        "dimanche" to DayOfWeek.SUNDAY,
    )

    // ------------------------------------------------------------------ lecture du fichier

    /** Entrées XML utiles du .xlsx (tailles bornées : un fichier piégé ne peut pas saturer la mémoire). */
    fun readZip(input: InputStream): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        var total = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                if (e.isDirectory || !name.startsWith("xl/") || !(name.endsWith(".xml") || name.endsWith(".rels"))) continue
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val n = zip.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    total += n
                    require(buf.size() <= MAX_ENTRY && total <= MAX_TOTAL) { "Fichier trop volumineux pour un planning." }
                }
                out[name] = buf.toByteArray()
            }
        }
        require(out.containsKey("xl/workbook.xml")) { "Ce fichier n'est pas un classeur Excel (.xlsx)." }
        return out
    }

    private class Cell(val text: String, val rgb: Int?)
    private class Grid(val name: String, val cells: Map<Long, Cell>) {
        fun at(row: Int, col: Int): Cell? = cells[key(row, col)]
        fun text(row: Int, col: Int) = at(row, col)?.text.orEmpty()
        val maxRow = cells.keys.maxOfOrNull { (it shr 20).toInt() } ?: 0
        val maxCol = cells.keys.maxOfOrNull { (it and 0xFFFFF).toInt() } ?: 0
    }
    private fun key(row: Int, col: Int): Long = (row.toLong() shl 20) or col.toLong()

    /** Lit le tableau du mois [month] dans [entries] (voir [readZip]). Ne lève pas d'exception de lecture : les soucis sont des « problems ». */
    fun parse(entries: Map<String, ByteArray>, month: YearMonth): Parsed {
        val fail = { msg: String -> Parsed(emptyList(), listOf(msg), emptyList(), "") }
        val sheets = try { loadSheets(entries) } catch (e: Exception) {
            return fail("Fichier illisible : ${e.message ?: e.javaClass.simpleName}")
        }
        if (sheets.isEmpty()) return fail("Aucun onglet trouvé dans le classeur.")
        val monthName = MONTHS[month.monthValue - 1]
        val byTitle = sheets.firstOrNull { g ->
            (1..3).any { r -> (1..g.maxCol).any { c -> ascii(g.text(r, c)).let { it.contains(monthName) && it.contains(month.year.toString()) } } }
        }
        val grid = byTitle
            ?: sheets.firstOrNull { it.name.trim().toIntOrNull() == month.monthValue }
            ?: sheets.firstOrNull { g -> (1..3).any { r -> (1..g.maxCol).any { c -> ascii(g.text(r, c)).contains(monthName) } } }
            ?: return fail("Aucun onglet ne correspond à ${monthName} ${month.year} (onglets : ${sheets.joinToString { it.name }}).")
        return readGrid(grid, month)
    }

    /** Médecins lus (noms tels qu'écrits) sous forme d'entrées de liste, sans numéro. */
    fun doctorEntries(p: Parsed): List<DoctorList.Entry> =
        p.doctorNames.map { DoctorList.Entry(DoctorList.prettyName(it), "") }.distinctBy { NameMatcher.normalize(it.name) }

    /** Associe les noms lus aux médecins de la liste (même logique que l'import de photo). */
    fun toResult(p: Parsed, doctors: List<Doctor>, aliases: Map<String, String>): ImportResult =
        ImportResult(
            p.items.map { ImportItem(it.date, it.time, it.name, ImportParser.classify(it.name, doctors, aliases)) },
            p.problems,
            doctorEntries(p),
        )

    // ------------------------------------------------------------------ interprétation du tableau

    private class Block(val name: String, val matin: Int, val midi: Int, val apm: Int, val soir: Int?)

    private fun readGrid(g: Grid, month: YearMonth): Parsed {
        val problems = ArrayList<String>()
        // 1) ligne des noms de médecins : au moins deux cases « Dr … »
        val nameRow = (1..minOf(g.maxRow, 12)).firstOrNull { r ->
            (2..g.maxCol).count { c -> ascii(g.text(r, c)).let { it.startsWith("dr ") || it.startsWith("dr.") || it.startsWith("docteur") } } >= 2
        } ?: return Parsed(emptyList(), listOf("Aucune ligne de noms de médecins (« Dr … ») trouvée dans l'onglet ${g.name}."), emptyList(), g.name)
        val starts = (2..g.maxCol).filter { c -> g.text(nameRow, c).isNotBlank() && ascii(g.text(nameRow, c)).let { it.startsWith("dr") || it.startsWith("docteur") } }
        // 2) colonnes de chaque médecin d'après les intitulés de la ligne suivante, sinon d'après la largeur du bloc
        val labelRow = nameRow + 1
        val blocks = starts.mapIndexed { i, from ->
            val to = if (i + 1 < starts.size) starts[i + 1] - 1 else from + 3
            val cols = from..to
            fun colOf(pred: (String) -> Boolean) = cols.firstOrNull { c -> pred(ascii(g.text(labelRow, c)).replace(Regex("\\s+"), " ").trim()) }
            val matin = colOf { it == "matin" }
            val apm = colOf { it.startsWith("apres") }
            val soir = colOf { it.contains("17") && it.contains("18") }
            val width = if (i + 1 < starts.size) to - from + 1 else 3
            val m = matin ?: from
            val a = apm ?: (from + 2)
            val mid = (m + 1 until a).firstOrNull() ?: (m + 1)
            Block(g.text(nameRow, from).replace(Regex("\\s+"), " ").trim(), m, mid, a, soir ?: if (matin == null && apm == null && width >= 4) from + 3 else null)
        }

        // 3) lignes des jours : colonne du jour de la semaine, puis du numéro
        var dayRows = (labelRow + 1..g.maxRow).filter { r -> weekdayOf(g.text(r, 1)) != null }
        var wdCol = 1
        if (dayRows.isEmpty()) {
            wdCol = (1..3).firstOrNull { c -> (labelRow + 1..g.maxRow).any { r -> weekdayOf(g.text(r, c)) != null } } ?: 1
            dayRows = (labelRow + 1..g.maxRow).filter { r -> weekdayOf(g.text(r, wdCol)) != null }
        }
        if (dayRows.isEmpty()) return Parsed(emptyList(), listOf("Aucune ligne de jour (Lundi, Mardi…) trouvée dans l'onglet ${g.name}."), emptyList(), g.name)

        val items = ArrayList<RawItem>()
        var prev = 0
        var firstChecked = false
        for (r in dayRows) {
            val parsed = g.text(r, wdCol + 1).trim().toDoubleOrNull()?.toInt()
            val day = parsed ?: if (prev == 0) 1 else prev + 1
            if (day < prev || day > month.lengthOfMonth()) break
            prev = day
            val date = month.atDay(day)
            if (!firstChecked) {
                firstChecked = true
                if (weekdayOf(g.text(r, wdCol)) != date.dayOfWeek) {
                    return Parsed(
                        emptyList(),
                        listOf("Le tableau indique « ${g.text(r, wdCol).trim()} » pour le ${day}, mais le ${day}/${month.monthValue}/${month.year} est un ${frDay(date.dayOfWeek)} : vérifiez le mois choisi."),
                        emptyList(), g.name,
                    )
                }
            }
            fun marked(col: Int?, b: Block): Boolean = col != null && g.at(r, col)?.rgb?.let { isColored(it) } == true
            fun pick(label: String, time: LocalTime, who: List<Pair<Block, Int>>, allowNone: Boolean) {
                val picks = who.filter { (b, col) -> marked(col, b) }
                when {
                    picks.size == 1 -> items += RawItem(date, time, picks[0].first.name)
                    picks.size > 1 -> {
                        val yellow = picks.filter { (_, col) -> g.at(r, col)?.rgb?.let { isYellowish(it) } == true }
                        val names = picks.joinToString(" / ") { it.first.name }
                        if (yellow.size == 1) {
                            items += RawItem(date, time, yellow[0].first.name)
                            problems += "${date.dayOfMonth}/${date.monthValue} $label : plusieurs cases colorées ($names) : celle en jaune est retenue (${yellow[0].first.name})."
                        } else {
                            problems += "${date.dayOfMonth}/${date.monthValue} $label : plusieurs cases colorées ($names), rien n'est retenu : à saisir à la main."
                        }
                    }
                    else -> if (!allowNone) problems += "${date.dayOfMonth}/${date.monthValue} $label : aucune case colorée."
                }
            }
            if (Holidays.isOffDay(date)) {
                pick("(journée entière, case MATIN)", LocalTime.of(9, 0), blocks.map { it to it.matin }, allowNone = false)
            } else {
                pick("12h", LocalTime.of(12, 0), blocks.map { it to it.midi }, allowNone = false)
                pick("17h", LocalTime.of(17, 0), blocks.mapNotNull { b -> b.soir?.let { b to it } }, allowNone = true)
                pick("18h", LocalTime.of(18, 0), blocks.map { it to it.apm }, allowNone = false)
            }
        }
        return Parsed(
            items.sortedWith(compareBy({ it.date }, { it.time })),
            problems,
            blocks.map { it.name }.filter { it.isNotBlank() },
            g.name,
        )
    }

    // ------------------------------------------------------------------ couleurs

    private fun channels(rgb: Int) = Triple((rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255)

    /** Une case est « colorée » si sa teinte est nettement différente d'un blanc, d'un gris ou d'un noir. */
    internal fun isColored(rgb: Int): Boolean {
        val (r, g, b) = channels(rgb)
        return (maxOf(r, g, b) - minOf(r, g, b)) / 255.0 >= MIN_CHROMA
    }

    internal fun isYellowish(rgb: Int): Boolean {
        val (r, g, b) = channels(rgb)
        return r > 200 && g > 200 && b < 140
    }

    private fun tint(rgb: Int, t: Double): Int {
        if (t == 0.0) return rgb
        val (r, g, b) = channels(rgb)
        fun f(c: Int) = (if (t > 0) c * (1 - t) + 255 * t else c * (1 + t)).toInt().coerceIn(0, 255)
        return (f(r) shl 16) or (f(g) shl 8) or f(b)
    }

    // ------------------------------------------------------------------ XML

    private fun dom(bytes: ByteArray): Element {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
    }

    private fun Element.local() = tagName.substringAfter(':')
    private fun Element.kids(name: String? = null): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { name == null || it.local() == name }
    private fun Element.deep(name: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(e: Element) { e.kids().forEach { if (it.local() == name) out += it; walk(it) } }
        walk(this)
        return out
    }

    private fun loadSheets(zip: Map<String, ByteArray>): List<Grid> {
        val wb = dom(zip["xl/workbook.xml"] ?: error("classeur vide"))
        val rels = zip["xl/_rels/workbook.xml.rels"]?.let { dom(it) }?.kids("Relationship")
            ?.associate { it.getAttribute("Id") to it.getAttribute("Target") }.orEmpty()
        val shared = zip["xl/sharedStrings.xml"]?.let { dom(it) }?.kids("si")?.map { si ->
            buildString {
                si.kids().forEach { k ->
                    when (k.local()) {
                        "t" -> append(k.textContent)
                        "r" -> k.kids("t").forEach { append(it.textContent) }
                    }
                }
            }
        }.orEmpty()
        val fills = readFills(zip)
        val sheetEls = wb.deep("sheet")
        return sheetEls.mapNotNull { s ->
            val rid = s.getAttribute("r:id").ifEmpty { s.attributes.getNamedItem("id")?.nodeValue.orEmpty() }
            val target = rels[rid] ?: return@mapNotNull null
            val path = if (target.startsWith("/")) target.drop(1) else "xl/$target"
            val bytes = zip[path] ?: return@mapNotNull null
            Grid(s.getAttribute("name"), readCells(dom(bytes), shared, fills))
        }
    }

    private fun readCells(root: Element, shared: List<String>, xfFill: List<Int?>): Map<Long, Cell> {
        val out = HashMap<Long, Cell>()
        for (row in root.deep("row")) for (c in row.kids("c")) {
            val ref = c.getAttribute("r")
            val m = Regex("^([A-Za-z]+)(\\d+)$").matchEntire(ref) ?: continue
            val col = m.groupValues[1].uppercase().fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) }
            val rowNo = m.groupValues[2].toInt()
            val v = c.kids("v").firstOrNull()?.textContent.orEmpty()
            val text = when (c.getAttribute("t")) {
                "s" -> shared.getOrElse(v.trim().toIntOrNull() ?: -1) { "" }
                "inlineStr" -> c.kids("is").firstOrNull()?.deep("t")?.joinToString("") { it.textContent }.orEmpty()
                else -> v
            }
            val rgb = c.getAttribute("s").toIntOrNull()?.let { xfFill.getOrNull(it) }
            if (text.isNotEmpty() || rgb != null) out[key(rowNo, col)] = Cell(text, rgb)
        }
        return out
    }

    /** Couleur de remplissage (RGB) de chaque style de cellule, ou null s'il n'y en a pas. */
    private fun readFills(zip: Map<String, ByteArray>): List<Int?> {
        val styles = zip["xl/styles.xml"]?.let { dom(it) } ?: return emptyList()
        val theme = readTheme(zip)
        val fillColors: List<Int?> = styles.kids("fills").firstOrNull()?.kids("fill")?.map { f ->
            val p = f.kids("patternFill").firstOrNull() ?: return@map null
            if (p.getAttribute("patternType") != "solid") return@map null
            val fg = p.kids("fgColor").firstOrNull() ?: return@map null
            val base: Int? = when {
                fg.hasAttribute("rgb") -> fg.getAttribute("rgb").takeLast(6).toIntOrNull(16)
                fg.hasAttribute("theme") -> theme.getOrNull(fg.getAttribute("theme").toIntOrNull() ?: -1)
                fg.hasAttribute("indexed") -> INDEXED.getOrNull(fg.getAttribute("indexed").toIntOrNull() ?: -1)
                else -> null
            }
            base?.let { tint(it, fg.getAttribute("tint").toDoubleOrNull() ?: 0.0) }
        }.orEmpty()
        return styles.kids("cellXfs").firstOrNull()?.kids("xf")?.map { xf ->
            fillColors.getOrNull(xf.getAttribute("fillId").toIntOrNull() ?: 0)
        }.orEmpty()
    }

    /** Couleurs du thème dans l'ordre d'Excel : 0 clair1, 1 sombre1, 2 clair2, 3 sombre2, 4-9 accents. */
    private fun readTheme(zip: Map<String, ByteArray>): List<Int?> {
        val root = zip.entries.firstOrNull { it.key.startsWith("xl/theme/") && it.key.endsWith(".xml") }?.let { dom(it.value) }
            ?: return emptyList()
        val scheme = root.deep("clrScheme").firstOrNull() ?: return emptyList()
        val colors = scheme.kids().map { e ->
            val c = e.kids().firstOrNull()
            (c?.getAttribute("val")?.takeIf { it.length == 6 } ?: c?.getAttribute("lastClr"))?.toIntOrNull(16)
        }
        // ordre du fichier : dk1, lt1, dk2, lt2, accent1..6, hlink, folHlink
        fun at(i: Int) = colors.getOrNull(i)
        return listOf(at(1), at(0), at(3), at(2)) + (4..11).map { at(it) }
    }

    private val INDEXED = listOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
        0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
    )

    // ------------------------------------------------------------------ utilitaires

    private fun ascii(s: String) =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

    private fun weekdayOf(s: String): DayOfWeek? = WEEKDAYS[ascii(s)]

    private fun frDay(d: DayOfWeek) = WEEKDAYS.entries.first { it.value == d }.key
}
