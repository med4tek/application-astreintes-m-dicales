package fr.astreinte.relais.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Classeur minimal (noms fictifs) reproduisant la structure d'un planning à cases colorées. */
private object Fixture {
    // styles : 0 aucun, 1 bleu (thème 4, teinte 0,4), 2 jaune, 3 gris clair (thème 2), 4 blanc
    private val STYLES = """<styleSheet><fills count="5">
        <fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>
        <fill><patternFill patternType="solid"><fgColor theme="4" tint="0.39997"/></patternFill></fill>
        <fill><patternFill patternType="solid"><fgColor rgb="FFFFFF00"/></patternFill></fill>
        <fill><patternFill patternType="solid"><fgColor theme="2"/></patternFill></fill></fills>
        <cellXfs count="5"><xf fillId="0"/><xf fillId="2"/><xf fillId="3"/><xf fillId="4"/><xf fillId="0"/></cellXfs></styleSheet>"""
    private val THEME = """<a:theme xmlns:a="x"><a:themeElements><a:clrScheme name="t">
        <a:dk1><a:sysClr lastClr="000000"/></a:dk1><a:lt1><a:sysClr lastClr="FFFFFF"/></a:lt1>
        <a:dk2><a:srgbClr val="1F497D"/></a:dk2><a:lt2><a:srgbClr val="EEECE1"/></a:lt2>
        <a:accent1><a:srgbClr val="4F81BD"/></a:accent1><a:accent2><a:srgbClr val="C0504D"/></a:accent2>
        </a:clrScheme></a:themeElements></a:theme>"""

    /** [cells] : référence -> (texte, style). Le texte est une chaîne partagée. */
    fun xlsx(sheetTitle: String, sheetName: String, cells: Map<String, Pair<String, Int>>): ByteArray {
        val strings = ArrayList<String>()
        fun si(t: String): Int { val i = strings.indexOf(t); return if (i >= 0) i else { strings += t; strings.size - 1 } }
        fun colNo(ref: String) = ref.takeWhile { it.isLetter() }.fold(0) { a, c -> a * 26 + (c - 'A' + 1) }
        val byRow = cells.entries.groupBy { it.key.dropWhile { c -> c.isLetter() }.toInt() }.toSortedMap()
        val rows = byRow.entries.joinToString("") { (r, l) ->
            "<row r=\"$r\">" + l.sortedBy { colNo(it.key) }.joinToString("") { (ref, v) ->
                val (text, style) = v
                if (text.isEmpty()) "<c r=\"$ref\" s=\"$style\"/>" else "<c r=\"$ref\" s=\"$style\" t=\"s\"><v>${si(text)}</v></c>"
            } + "</row>"
        }
        val sheet = "<worksheet><sheetData>$rows</sheetData></worksheet>"
        val sst = "<sst>" + strings.joinToString("") { "<si><t>${it.replace("&", "&amp;")}</t></si>" } + "</sst>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
            put("xl/workbook.xml", "<workbook xmlns:r=\"r\"><sheets><sheet name=\"$sheetName\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", "<Relationships><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>")
            put("xl/worksheets/sheet1.xml", sheet)
            put("xl/sharedStrings.xml", sst)
            put("xl/styles.xml", STYLES)
            put("xl/theme/theme1.xml", THEME)
        }
        return out.toByteArray()
    }

    /**
     * Novembre 2026 : Dr GARNNIER (4 colonnes B..E), Dr PERRIN (MPR) (4 colonnes F..I), Dr MEUNIER (3 colonnes J..L, sans intitulés).
     * Colonnes : A jour, B numéro ; Garnnier C matin D midi E apm F 17-18 ; Perrin G matin H midi I apm J 17-18 ; Meunier K L M.
     */
    fun planning(): Map<String, Pair<String, Int>> {
        val c = LinkedHashMap<String, Pair<String, Int>>()
        c["C1"] = "MOIS DE NOVEMBRE 2026" to 0
        c["C2"] = "Dr GARNNIER" to 0; c["G2"] = "Dr PERRIN (MPR)" to 0; c["K2"] = "Dr MEUNIER" to 0
        c["C3"] = "MATIN" to 0; c["E3"] = "APRES MIDI" to 0; c["F3"] = "17-18h" to 2
        c["G3"] = "MATIN" to 0; c["I3"] = "APRES MIDI" to 0; c["J3"] = "17-18h" to 2
        val days = listOf("Dimanche", "Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi")
        for (d in 1..9) {
            val r = 3 + d
            c["A$r"] = days[(d - 1) % 7] to 0
            c["B$r"] = d.toString() to 0
        }
        fun set(day: Int, col: String, style: Int, text: String = "") { c["$col${3 + day}"] = text to style }
        // dim 1 (ferie, week-end) : seul le matin de Meunier est colore ; "x" non colores ailleurs
        set(1, "K", 1); set(1, "C", 0, "x")
        // lun 2 : midi Garnnier (bleu), 18h Meunier (apres-midi, bloc sans intitule), 17h personne ; grise ignoree
        set(2, "D", 1); set(2, "M", 1); set(2, "C", 0, "x"); set(2, "H", 3)
        // mar 3 : 17h Perrin en jaune, 18h Garnnier, midi Perrin
        set(3, "J", 2); set(3, "E", 1); set(3, "H", 1)
        // mer 4 : deux cases colorees en 17-18h, une seule en jaune -> jaune retenu
        set(4, "F", 1); set(4, "J", 2); set(4, "D", 1); set(4, "I", 1)
        // jeu 5 : deux cases colorees en 17-18h, aucune jaune -> rien retenu ; midi Perrin, 18h Garnnier
        set(5, "F", 1); set(5, "J", 1); set(5, "H", 1); set(5, "E", 1)
        // ven 6 : aucune case midi -> signale
        set(6, "I", 1)
        // sam 7 : matin Perrin
        set(7, "G", 1)
        return c
    }
}

class XlsxPlanningTest {
    private val nov = YearMonth.of(2026, 11)
    private fun read(month: YearMonth = nov, sheet: String = "11", title: String = "") =
        XlsxPlanning.parse(XlsxPlanning.readZip(ByteArrayInputStream(Fixture.xlsx(title, sheet, Fixture.planning()))), month)

    private fun at(p: XlsxPlanning.Parsed, d: Int) = p.items.filter { it.date == LocalDate.of(2026, 11, d) }.associate { it.time.toString() to it.name }

    @Test fun `les cases colorees sont lues selon les regles de la procedure`() {
        val p = read()
        assertEquals("11", p.sheet)
        assertEquals(listOf("Dr GARNNIER", "Dr PERRIN (MPR)", "Dr MEUNIER"), p.doctorNames)
        // dimanche 1 (week-end) : la case matin, sur 9h seulement
        assertEquals(mapOf("09:00" to "Dr MEUNIER"), at(p, 1))
        // lundi 2 : midi, 18h (bloc sans intitules : 3e colonne), pas de 17h, la case grise est ignoree
        assertEquals(mapOf("12:00" to "Dr GARNNIER", "18:00" to "Dr MEUNIER"), at(p, 2))
        assertEquals(mapOf("12:00" to "Dr PERRIN (MPR)", "17:00" to "Dr PERRIN (MPR)", "18:00" to "Dr GARNNIER"), at(p, 3))
        // mercredi 11 aurait ete ferie ; ici le 4 : le jaune l'emporte
        assertEquals("Dr PERRIN (MPR)", at(p, 4)["17:00"])
        assertTrue(p.problems.any { it.startsWith("4/11 17h") && it.contains("jaune") })
        // jeudi 5 : ambigu, rien n'est retenu pour 17h
        assertEquals(setOf("12:00", "18:00"), at(p, 5).keys)
        assertTrue(p.problems.any { it.startsWith("5/11 17h") && it.contains("rien n'est retenu") })
        // vendredi 6 : pas de midi -> signale
        assertEquals(mapOf("18:00" to "Dr PERRIN (MPR)"), at(p, 6))
        assertTrue(p.problems.any { it.startsWith("6/11 12h") })
        // samedi 7 : matin
        assertEquals(mapOf("09:00" to "Dr PERRIN (MPR)"), at(p, 7))
        // le 8 et le 9 (jours sans aucune case) : signales mais sans affectation
        assertTrue(at(p, 8).isEmpty() && at(p, 9).isEmpty())
    }

    @Test fun `un mauvais mois est refuse avec un message clair`() {
        val p = read(month = YearMonth.of(2026, 12), sheet = "Feuil1")
        assertTrue(p.items.isEmpty())
        assertTrue(p.problems.single().contains("Aucun onglet"))
        // le titre du mois suffit a choisir l'onglet, meme s'il ne porte pas le numero du mois
        assertEquals("Feuil1", read(sheet = "Feuil1").sheet)
    }

    @Test fun `le mois et l'annee du titre doivent correspondre aux jours de la semaine`() {
        // titre NOVEMBRE 2026 mais on demande novembre 2025 : le titre ne correspond plus ; l'onglet "11" est pris,
        // puis le premier jour (dimanche) ne correspond pas au 1er novembre 2025 (samedi) -> refus
        val p = read(month = YearMonth.of(2025, 11))
        assertTrue(p.items.isEmpty())
        assertTrue(p.problems.single().contains("vérifiez le mois"))
    }

    @Test fun `les noms lus se rattachent aux medecins de la liste, avec tolerance de frappe`() {
        val doctors = listOf(
            STANDARD_DOCTOR, Doctor("g", "Eve Garnier", ""), Doctor("p", "Hugo Perrin (mpr)", ""),
        )
        val r = XlsxPlanning.toResult(read(), doctors, emptyMap())
        val byName = r.items.associate { it.raw to it.target }
        assertEquals(Target.Doc("g"), byName["Dr GARNNIER"])           // faute de frappe
        assertEquals(Target.Doc("p"), byName["Dr PERRIN (MPR)"])
        assertTrue(byName["Dr MEUNIER"] is Target.Unknown)               // inconnu : a associer ou ajouter
        assertEquals(3, r.doctors.size)
    }

    @Test fun `un fichier qui n'est pas un classeur est refuse`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { it.putNextEntry(ZipEntry("a.txt")); it.write(1); it.closeEntry() }
        val ex = runCatching { XlsxPlanning.readZip(ByteArrayInputStream(out.toByteArray())) }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }
}
