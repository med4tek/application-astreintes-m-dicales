package fr.astreinte.relais.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

private val PARIS = ZoneId.of("Europe/Paris")
private fun at(d: String, t: String) =
    LocalDateTime.of(LocalDate.parse(d), LocalTime.parse(t)).atZone(PARIS).toInstant()
private fun d(s: String) = LocalDate.parse(s)
private fun t(s: String) = LocalTime.parse(s)

class CodesTest {
    @Test fun `normalise un numero francais avec espaces`() {
        assertEquals("0612345678", Codes.normalizePhone("06 12 34 56 78"))
        assertEquals("0612345678", Codes.normalizePhone("06.12.34.56.78"))
    }

    @Test fun `garde le plus international`() {
        assertEquals("+33612345678", Codes.normalizePhone("+33 6 12 34 56 78"))
    }

    @Test fun `retire le zero de la notation +33 (0)`() {
        assertEquals("+33612345678", Codes.normalizePhone("+33 (0)6 12 34 56 78"))
    }

    @Test fun `refuse les numeros invalides`() {
        assertNull(Codes.normalizePhone(""))
        assertNull(Codes.normalizePhone("abc"))
        assertNull(Codes.normalizePhone("12"))
        assertNull(Codes.normalizePhone("06*12345678"))
        assertNull(Codes.normalizePhone("06#12345678"))
        assertNull(Codes.normalizePhone("0612+345678"))
        assertNull(Codes.normalizePhone("1234567890123456"))
    }

    @Test fun `construit les codes de renvoi`() {
        assertEquals("**21*0612345678#", Codes.activate("0612345678"))
        assertEquals("##21#", Codes.forDoctor(null))
        assertEquals("**21*0612345678#", Codes.forDoctor(Doctor("1", "Dr Martin", "06 12 34 56 78")))
        assertNull(Codes.forDoctor(Doctor("2", "Dr Pasbon", "pas un numero")))
    }

    @Test fun `le standard n'a aucun numero par defaut et n'envoie donc aucun renvoi tant qu'il n'est pas saisi`() {
        assertEquals("", STANDARD_DOCTOR.phone)
        assertNull(Codes.forDoctor(STANDARD_DOCTOR))
        assertEquals("**21*0123456789#", Codes.forDoctor(STANDARD_DOCTOR.copy(phone = "01 23 45 67 89")))
    }

    @Test fun `compare deux numeros quel que soit le format`() {
        assertTrue(Codes.samePhone("06 12 34 56 78", "+33612345678"))
        assertTrue(Codes.samePhone("0612345678", "+33 6 12 34 56 78"))
        assertTrue(!Codes.samePhone("0612345678", "0612345679"))
        assertTrue(!Codes.samePhone("", "0612345678"))
        assertTrue(!Codes.samePhone("123", "123"))
    }
}

class PlannerTest {
    private val shifts = listOf(
        Shift("c", d("2026-11-08"), t("08:00"), "C"),
        Shift("a", d("2026-11-06"), t("08:00"), "A"),
        Shift("b", d("2026-11-07"), t("08:00"), "B"),
        Shift("b2", d("2026-11-07"), t("20:00"), null),
    )

    @Test fun `avant le premier changement il n'y a pas d'astreinte`() {
        assertNull(Planner.current(shifts, at("2026-11-06", "07:59"), PARIS))
        assertEquals("a", Planner.next(shifts, at("2026-11-06", "07:59"), PARIS)?.id)
    }

    @Test fun `le changement prend effet pile a l'heure`() {
        assertEquals("a", Planner.current(shifts, at("2026-11-06", "08:00"), PARIS)?.id)
        assertEquals("a", Planner.current(shifts, at("2026-11-07", "07:59"), PARIS)?.id)
        assertEquals("b", Planner.current(shifts, at("2026-11-07", "08:00"), PARIS)?.id)
        assertEquals("b2", Planner.current(shifts, at("2026-11-07", "23:00"), PARIS)?.id)
        assertEquals("c", Planner.current(shifts, at("2026-11-08", "08:00"), PARIS)?.id)
    }

    @Test fun `apres le dernier changement il reste le dernier medecin et pas de suivant`() {
        assertEquals("c", Planner.current(shifts, at("2027-01-01", "12:00"), PARIS)?.id)
        assertNull(Planner.next(shifts, at("2027-01-01", "12:00"), PARIS))
    }

    @Test fun `prochain changement strictement apres maintenant`() {
        assertEquals("b", Planner.next(shifts, at("2026-11-06", "08:00"), PARIS)?.id)
    }

    @Test fun `valeur heritee juste avant un instant`() {
        val sorted = Planner.sorted(shifts)
        assertEquals("a", Planner.inForceBefore(sorted, at("2026-11-07", "08:00"), PARIS)?.id)
        assertEquals("b2", Planner.inForceBefore(sorted, at("2026-11-08", "00:00"), PARIS)?.id)
        assertNull(Planner.inForceBefore(sorted, at("2026-11-06", "08:00"), PARIS))
    }

    @Test fun `changement d'heure d'ete ne plante pas`() {
        val s = Shift("x", d("2026-03-29"), t("02:30"), "A") // 02:30 n'existe pas ce jour-là à Paris
        assertNotNull(Planner.startOf(s, PARIS))
        assertEquals("x", Planner.current(listOf(s), at("2026-03-29", "12:00"), PARIS)?.id)
    }

    @Test fun `remplacer un jour supprime les anciens et dedoublonne les heures`() {
        val day = d("2026-11-07")
        val result = Planner.replaceDay(
            shifts, day,
            listOf(Shift("n1", day, t("09:00"), "A"), Shift("n2", day, t("09:00"), "B"))
        )
        val onDay = Planner.shiftsOn(result, day)
        assertEquals(1, onDay.size)
        assertEquals("B", onDay[0].doctorId)
        assertEquals(3, result.size) // 4 au départ, 2 sur ce jour remplacés par 1
    }

    @Test fun `vider des jours`() {
        val result = Planner.clearDates(shifts, setOf(d("2026-11-07")))
        assertEquals(2, result.size)
        assertTrue(result.none { it.date == d("2026-11-07") })
    }

    // ------------------------------------------------------------- créneaux par défaut

    @Test fun `les creneaux par defaut generent le standard a 9h et 14h`() {
        val eff = Planner.effective(emptyList(), DEFAULT_SLOTS, d("2026-11-09"), d("2026-11-10"))
        assertEquals(4, eff.size)
        assertTrue(eff.all { it.doctorId == STANDARD_ID && Planner.isDefault(it) })
        assertEquals(listOf(t("09:00"), t("14:00"), t("09:00"), t("14:00")), eff.map { it.time })
    }

    @Test fun `une saisie remplace le defaut du meme creneau`() {
        val explicit = Shift("x", d("2026-11-09"), t("09:00"), "HOL") // jour férié : autre numéro
        val eff = Planner.effective(listOf(explicit), DEFAULT_SLOTS, d("2026-11-09"), d("2026-11-09"))
        assertEquals(2, eff.size)
        assertEquals("HOL", eff.first { it.time == t("09:00") }.doctorId)
        assertTrue(!Planner.isDefault(eff.first { it.time == t("09:00") }))
        assertEquals(STANDARD_ID, eff.first { it.time == t("14:00") }.doctorId)
    }

    @Test fun `une journee type se deroule comme prevu`() {
        val snap = Snapshot(
            doctors = listOf(STANDARD_DOCTOR, Doctor("m", "Dr Midi", "0611111111"), Doctor("s", "Dr Soir", "0622222222"), Doctor("g", "Dr Garde", "0633333333")),
            shifts = listOf(
                Shift("1", d("2026-11-09"), t("12:00"), "m"),
                Shift("2", d("2026-11-09"), t("17:00"), "s"),
                Shift("3", d("2026-11-09"), t("18:00"), "g"),
            ),
        )
        fun who(day: String, time: String) = Planner.currentShift(snap, at(day, time), PARIS)?.doctorId
        assertEquals(STANDARD_ID, who("2026-11-09", "09:00"))
        assertEquals(STANDARD_ID, who("2026-11-09", "11:59"))
        assertEquals("m", who("2026-11-09", "12:00"))
        assertEquals("m", who("2026-11-09", "13:59"))
        assertEquals(STANDARD_ID, who("2026-11-09", "14:00"))
        assertEquals(STANDARD_ID, who("2026-11-09", "16:59"))
        assertEquals("s", who("2026-11-09", "17:00"))
        assertEquals("g", who("2026-11-09", "18:00"))
        assertEquals("g", who("2026-11-10", "08:59")) // astreinte jusqu'au lendemain 9h
        assertEquals(STANDARD_ID, who("2026-11-10", "09:00")) // retour au standard, même sans saisie
    }

    @Test fun `le prochain changement est toujours trouve grace aux defauts`() {
        val next = Planner.nextShift(Snapshot(), at("2026-11-09", "10:00"), PARIS)
        assertEquals(t("14:00"), next?.time)
        assertEquals(d("2026-11-09"), next?.date)
    }

    @Test fun `modifier des cases sur plusieurs jours sans toucher aux autres creneaux`() {
        var n = 0
        val base = listOf(Shift("keep", d("2026-11-10"), t("12:00"), "M"))
        val dates = setOf(d("2026-11-10"), d("2026-11-12"))
        val result = Planner.editCells(
            base, dates,
            mapOf(t("18:00") to CellEdit.Set("G"), t("12:00") to CellEdit.Clear)
        ) { "id${n++}" }
        // le 12h du 10/11 est effacé (Clear), les 18h posés sur les deux jours
        assertEquals(2, result.size)
        assertTrue(result.all { it.time == t("18:00") && it.doctorId == "G" })
    }

    @Test fun `une case peut etre mise sur aucun renvoi`() {
        val result = Planner.editCells(emptyList(), setOf(d("2026-11-10")), mapOf(t("12:00") to CellEdit.Set(null))) { "x" }
        assertEquals(1, result.size)
        assertNull(result[0].doctorId)
    }
}

class SnapshotJsonTest {
    @Test fun `aller-retour JSON`() {
        val snap = Snapshot(
            doctors = listOf(STANDARD_DOCTOR, Doctor("d1", "Dr Martin", "06 12 34 56 78")),
            shifts = listOf(
                Shift("s1", d("2026-11-06"), t("08:00"), "d1"),
                Shift("s2", d("2026-11-07"), t("20:30"), null),
            ),
            settings = Settings(
                enabled = true, defaultTime = t("07:30"), subscriptionId = 3, useDirectUssd = true,
                slots = listOf(Slot(t("09:00"), "Service", STANDARD_ID), Slot(t("18:00"), "Garde", null)),
                smsEnabled = true, smsPin = "4321", smsExtraNumbers = "06 11 22 33 44",
                smsAllowDoctors = false, smsReply = true,
            ),
            state = ApplyState("**21*0612345678#", false, 1234L, "oups \"guillemets\"", 2, lastSmsAt = 55L, lastSmsWasCommand = true),
            log = listOf(LogEntry(99L, "alarme", "##21#", true, "ok\nligne 2")),
        )
        assertEquals(snap, SnapshotJson.fromJson(SnapshotJson.toJson(snap)))
    }

    @Test fun `fichier minimal`() {
        assertEquals(Snapshot(), SnapshotJson.fromJson("{}"))
    }

    @Test fun `migration d'une ancienne version`() {
        val old = """{"doctors":[{"id":"d1","name":"Dr X","phone":"0611"}],"shifts":[],"settings":{"enabled":true}}"""
        val snap = SnapshotJson.fromJson(old)
        assertEquals(listOf(STANDARD_ID, "d1"), snap.doctors.map { it.id })
        assertEquals(DEFAULT_SLOTS, snap.settings.slots)
        assertTrue(snap.settings.enabled)
        assertTrue(!snap.settings.smsEnabled)
        assertEquals(Mode.RELAY, snap.settings.mode) // un fichier d'avant les modes était un relais
    }
}

class NameMatcherTest {
    private val docs = listOf(
        STANDARD_DOCTOR,
        Doctor("m", "Dr Martin", "1"),
        Doctor("d", "Dr Dupont", "2"),
        Doctor("h", "Dr Hélène Roux", "3"),
    )

    @Test fun `ignore casse, titre et accents`() {
        assertEquals("m", NameMatcher.find("DOCTEUR martin", docs)?.id)
        assertEquals("h", NameMatcher.find("Dr Helene ROUX", docs)?.id)
    }

    @Test fun `un nom de famille suffit`() {
        assertEquals("h", NameMatcher.find("Roux", docs)?.id)
        assertEquals("d", NameMatcher.find("dupo", docs)?.id)
    }

    @Test fun `tolere une faute de frappe`() {
        assertEquals("d", NameMatcher.find("Dupond", docs)?.id)
    }

    @Test fun `le standard se retrouve`() {
        assertEquals(STANDARD_ID, NameMatcher.find("standard", docs)?.id)
        assertEquals(STANDARD_ID, NameMatcher.find("Standard 8311", docs)?.id)
    }

    @Test fun `ambigu ou inconnu donne null`() {
        val two = listOf(Doctor("1", "Dr Martin Paul", "1"), Doctor("2", "Dr Martin Luc", "2"))
        assertNull(NameMatcher.find("Martin", two))
        assertNull(NameMatcher.find("Zorro", docs))
        assertNull(NameMatcher.find("Dr", docs))
        assertEquals("1", NameMatcher.find("Martin Paul", two)?.id)
    }
}

class ImportTest {
    private val docs = listOf(STANDARD_DOCTOR, Doctor("m", "Dr Martin", "1"), Doctor("d", "Dr Dupont", "2"))

    private val answer = """
        Voici le résultat demandé :
        ```json
        {"month": "2026-12", "days": [
          {"date": "2026-12-01", "12:00": "Dr Martin", "17:00": "dupont", "18:00": "Zorro"},
          {"date": "02/12/2026", "12h": "AUCUN", "18:00": null, "note": "férié"}
        ]}
        ```
        N'hésite pas si tu veux autre chose.
    """.trimIndent()

    @Test fun `lit une reponse entouree de texte et de balises`() {
        val r = ImportParser.parse(answer, docs, DEFAULT_SLOTS)
        assertTrue(r.problems.toString(), r.problems.isEmpty())
        assertEquals(4, r.items.size)
        assertEquals(2, r.dayCount)
        assertEquals(Target.Doc("m"), r.items[0].target)
        assertEquals(Target.Doc("d"), r.items[1].target)
        assertEquals(Target.Unknown("Zorro"), r.items[2].target)
        assertEquals(Target.Off, r.items[3].target)
        assertEquals(listOf("Zorro"), r.unknownNames)
    }

    @Test fun `les noms inconnus peuvent etre associes ou ignores`() {
        val r = ImportParser.parse(answer, docs, DEFAULT_SLOTS)
        val ignored = ImportParser.toEdits(r.items, emptyMap())
        assertEquals(3, ignored.size) // Zorro ignoré
        val mapped = ImportParser.toEdits(r.items, mapOf("Zorro" to "m"))
        assertEquals(4, mapped.size)
        assertEquals(CellEdit.Set("m"), mapped[d("2026-12-01") to t("18:00")])
        assertEquals(CellEdit.Set(null), mapped[d("2026-12-02") to t("12:00")])
    }

    @Test fun `accepte aussi une liste nue`() {
        val r = ImportParser.parse("""[{"date":"2026-12-05","18:00":"Martin"}]""", docs, DEFAULT_SLOTS)
        assertEquals(1, r.items.size)
        assertEquals(Target.Doc("m"), r.items[0].target)
    }

    @Test fun `signale les problemes sans planter`() {
        assertTrue(ImportParser.parse("pas de json ici", docs, DEFAULT_SLOTS).problems.isNotEmpty())
        assertTrue(ImportParser.parse("{ pas du json }", docs, DEFAULT_SLOTS).problems.isNotEmpty())
        val r = ImportParser.parse("""{"days":[{"date":"n'importe quoi","18:00":"Martin"},{"date":"2026-12-05","10:30":"Martin"}]}""", docs, DEFAULT_SLOTS)
        assertEquals(1, r.items.size) // le jour sans date valide est écarté
        assertEquals(2, r.problems.size) // date illisible + heure hors créneaux
    }

    @Test fun `repare les crochets echappes d'une copie depuis une page de chat`() {
        // Cas réel : l'assistant (ou la copie) a écrit \[ et \] ; tout sur une seule ligne
        val pasted = """{ "month": "2026-11", "days": \[ {"date": "2026-11-01"}, {"date": "2026-11-02", "09:00": "Standard", "14:00": "Standard"}, {"date": "2026-11-10", "17:00": "Dr Dupont"} \] }"""
        val r = ImportParser.parse(pasted, docs, DEFAULT_SLOTS)
        assertTrue(r.problems.toString(), r.problems.isEmpty())
        assertEquals(3, r.items.size)
        assertEquals(Target.Doc("d"), r.items.last().target)
    }

    @Test fun `repare aussi les guillemets typographiques et les espaces insecables`() {
        val pasted = "{\u00A0\u201Cdays\u201D: [{\u201Cdate\u201D: \u201C2026-12-05\u201D, \u201C18:00\u201D: \u201CMartin\u201D}]}"
        val r = ImportParser.parse(pasted, docs, DEFAULT_SLOTS)
        assertTrue(r.problems.toString(), r.problems.isEmpty())
        assertEquals(Target.Doc("m"), r.items.single().target)
    }

    @Test fun `les vrais echappements JSON sont conserves`() {
        val pasted = """{"days":[{"date":"2026-12-05","18:00":"Dr \"Martin\" Paul","note":"ligne1\nligne2"}]}"""
        val r = ImportParser.parse(pasted, docs, DEFAULT_SLOTS)
        assertEquals(1, r.items.size)
        assertEquals("Dr \"Martin\" Paul", r.items.single().raw)
    }

    @Test fun `un JSON vraiment illisible donne une astuce`() {
        val r = ImportParser.parse("{ \"days\": [ {date: } ", docs, DEFAULT_SLOTS)
        assertTrue(r.problems.any { it.contains("bloc de code") })
    }

    @Test fun `la consigne contient les elements utiles`() {
        val p = PromptBuilder.build(YearMonth.of(2026, 12), docs, DEFAULT_SLOTS, "La colonne Garde = 18:00")
        assertTrue(p.contains("décembre 2026"))
        assertTrue(p.contains("Dr Martin") && p.contains("Dr Dupont"))
        assertTrue(p.contains("\"09:00\"") && p.contains("\"18:00\""))
        assertTrue(p.contains("Standard 8311"))
        assertTrue(p.contains("La colonne Garde = 18:00"))
        assertTrue(p.contains("\"2026-12-01\""))
        assertTrue(p.contains("bloc de code JSON"))
    }
}

class SmsCommandsTest {
    private val docs = listOf(STANDARD_DOCTOR, Doctor("m", "Dr Martin", "1"), Doctor("d", "Dr Dupont", "2"))
    private val today = d("2026-11-09")
    private fun run(vararg args: String) = SmsCommands.interpret(args.toList(), docs, DEFAULT_SLOTS, today)
    private fun edit(vararg args: String) = run(*args) as SmsAction.Edit

    @Test fun `reconnait l'enveloppe`() {
        assertEquals(SmsParse.Command("1234", listOf("12/11", "18=Martin")), SmsCommands.parseEnvelope("AST 1234 12/11 18=Martin"))
        assertEquals(SmsParse.Command("1234", listOf("?")), SmsCommands.parseEnvelope("  ast   1234  ?  "))
        assertEquals(SmsParse.NotCommand, SmsCommands.parseEnvelope("Bonjour, ça va ?"))
        assertEquals(SmsParse.NotCommand, SmsCommands.parseEnvelope("ASTRO 1234 12/11 18=Martin"))
        assertTrue(SmsCommands.parseEnvelope("AST 1234") is SmsParse.Malformed)
    }

    @Test fun `un creneau un jour`() {
        val g = edit("12/11", "18=Martin").groups.single()
        assertEquals(setOf(d("2026-11-12")), g.dates)
        assertEquals(mapOf(t("18:00") to CellEdit.Set("m")), g.edits)
    }

    @Test fun `heures ecrites de plusieurs facons`() {
        val g = edit("12/11", "18h=Martin", "12:00=Dupont", "9=STD").groups.single()
        assertEquals(
            mapOf(t("18:00") to CellEdit.Set("m"), t("12:00") to CellEdit.Set("d"), t("09:00") to CellEdit.Set(STANDARD_ID)),
            g.edits
        )
    }

    @Test fun `periodes et listes de jours`() {
        assertEquals(4, edit("12/11-15/11", "18=Martin").groups.single().dates.size)
        assertEquals(setOf(d("2026-11-12"), d("2026-11-14")), edit("12/11,14/11", "18=Martin").groups.single().dates)
        assertEquals(setOf(d("2026-11-09"), d("2026-11-10")), edit("auj", "dem", "18=Martin").groups.single().dates)
        assertEquals(setOf(d("2026-11-10")), edit("demain", "18=Martin").groups.single().dates)
    }

    @Test fun `plusieurs groupes de jours`() {
        val groups = edit("12/11", "18=Martin", "13/11", "18=Dupont").groups
        assertEquals(2, groups.size)
        assertEquals(CellEdit.Set("d"), groups[1].edits[t("18:00")])
    }

    @Test fun `off def raz`() {
        val g = edit("12/11", "18=OFF", "12=DEF", "RAZ").groups.single()
        assertEquals(CellEdit.Set(null), g.edits[t("18:00")])
        assertEquals(CellEdit.Clear, g.edits[t("12:00")])
        assertTrue(g.clearDay)
    }

    @Test fun `nom compose avec souligne`() {
        val two = listOf(Doctor("p", "Dr Martin Paul", "1"))
        val a = SmsCommands.interpret(listOf("12/11", "18=Martin_Paul"), two, DEFAULT_SLOTS, today)
        assertTrue(a is SmsAction.Edit)
    }

    @Test fun `annee deduite de la date la plus proche`() {
        assertEquals(setOf(d("2027-01-05")), (SmsCommands.interpret(listOf("05/01", "18=Martin"), docs, DEFAULT_SLOTS, d("2026-12-20")) as SmsAction.Edit).groups.single().dates)
        assertEquals(setOf(d("2026-11-05")), edit("05/11", "18=Martin").groups.single().dates) // 4 jours de retard toléré
        assertEquals(setOf(d("2030-01-05")), edit("05/01/2030", "18=Martin").groups.single().dates)
    }

    @Test fun `une periode a cheval sur deux annees`() {
        val a = SmsCommands.interpret(listOf("28/12-02/01", "18=Martin"), docs, DEFAULT_SLOTS, d("2026-12-20")) as SmsAction.Edit
        val dates = a.groups.single().dates
        assertEquals(6, dates.size)
        assertTrue(d("2026-12-31") in dates && d("2027-01-01") in dates)
    }

    @Test fun `la demande d'etat`() {
        assertEquals(SmsAction.Query, run("?"))
    }

    @Test fun `consultation du planning`() {
        assertEquals(SmsAction.Plan(listOf(d("2026-11-10"))), run("?", "demain"))
        assertEquals(
            SmsAction.Plan(listOf(d("2026-11-12"), d("2026-11-13"), d("2026-11-14"))),
            run("?", "12/11-14/11")
        )
        assertEquals(SmsAction.Plan(listOf(d("2026-11-09"), d("2026-11-12"))), run("?", "12/11", "auj"))
        assertEquals(7, (run("?", "semaine") as SmsAction.Plan).dates.size)
        assertTrue((run("?", "01/11-30/11") as SmsAction.Error).why.contains("Maximum"))
        assertTrue((run("?", "blabla") as SmsAction.Error).why.contains("blabla"))
    }

    @Test fun `texte de reponse du planning`() {
        val snap = Snapshot(
            doctors = listOf(STANDARD_DOCTOR, Doctor("m", "Dr Alice", "1"), Doctor("s", "Dr Zoé", "2")),
            shifts = listOf(
                Shift("1", d("2026-11-09"), t("12:00"), "m"),
                Shift("2", d("2026-11-09"), t("18:00"), "m"),
                Shift("3", d("2026-11-10"), t("12:00"), null),
                Shift("4", d("2026-11-10"), t("18:00"), "s"),
            ),
            settings = Settings(enabled = true),
        )
        val text = SmsCommands.planText(snap, listOf(d("2026-11-09"), d("2026-11-10"), d("2026-11-12")), PARIS)
        assertEquals(
            "lun 09/11: 9h STD 12h Alice 14h STD 17h STD 18h Alice\n" +
                "mar 10/11: 9h STD 12h OFF 14h STD 17h STD 18h Zoé\n" +
                // le 12/11 : rien de saisi, l'astreinte du 10 au soir court jusqu'à 9h puis standard
                "jeu 12/11: 9h STD 12h STD 14h STD 17h STD 18h STD",
            text
        )
        assertTrue(SmsCommands.planText(snap.copy(settings = Settings(enabled = false)), listOf(d("2026-11-09")), PARIS).contains("désactivée"))
    }

    @Test fun `erreurs claires`() {
        fun err(vararg a: String) = (run(*a) as SmsAction.Error).why
        assertTrue(err("12/11", "13=Martin").contains("Créneaux"))
        assertTrue(err("12/11", "18=Zorro").contains("Zorro"))
        assertTrue(err("18=Martin").contains("date"))
        assertTrue(err("31/02", "18=Martin").contains("invalide"))
        assertTrue(err("12/11").contains("Rien à modifier"))
        assertTrue(err("blabla").contains("blabla"))
        assertTrue(err("15/11-12/11", "18=Martin").contains("inversée"))
        assertTrue(err("01/12-15/02", "18=Martin").contains("trop longue"))
    }

    @Test fun `application et resume`() {
        var n = 0
        val action = edit("12/11-13/11", "12=Dupont", "18=Martin")
        val result = SmsCommands.apply(emptyList(), action.groups) { "id${n++}" }
        assertEquals(4, result.size)
        assertEquals("12/11-13/11 12h→Dr Dupont 18h→Dr Martin", SmsCommands.describe(action.groups, docs))

        // RAZ efface toutes les saisies du jour puis applique la suite
        val cleared = SmsCommands.apply(result, edit("12/11", "RAZ").groups) { "z" }
        assertEquals(2, cleared.size)
        assertTrue(cleared.all { it.date == d("2026-11-13") })
    }
}
