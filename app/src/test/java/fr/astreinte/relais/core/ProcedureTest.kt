package fr.astreinte.relais.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val STAFF = listOf(
    STANDARD_DOCTOR,
    Doctor("a", "Alice Durand", "0612345678"),
    Doctor("b", "Bruno Martin", "0698765432"),
    Doctor("c", "Claire Dubois", "0611111111"),
    Doctor("d", "Dr Meunier Paul", "0622222222"),
)

class NameMatchingTest {
    @Test fun `un prenom seul retrouve le medecin`() {
        assertEquals("a", NameMatcher.find("Alice", STAFF)?.id)
        assertEquals("b", NameMatcher.find("bruno", STAFF)?.id)
    }

    @Test fun `un nom seul retrouve le medecin, titre ou pas`() {
        assertEquals("a", NameMatcher.find("DURAND", STAFF)?.id)
        assertEquals("a", NameMatcher.find("Dr Durand", STAFF)?.id)
    }

    @Test fun `un titre colle au nom est separe`() {
        assertEquals("c", NameMatcher.find("DrDubois", STAFF)?.id)
        assertEquals("d", NameMatcher.find("DrMEUNIER", STAFF)?.id)
        assertEquals("d", NameMatcher.find("Dr Paul", STAFF)?.id)
    }

    @Test fun `un nom qui commence par Dr n'est pas mutile`() {
        assertEquals("drouet", NameMatcher.normalize("Drouet"))
        assertEquals("dubois", NameMatcher.normalize("DrDubois"))
    }

    @Test fun `le standard n'est pas confondu avec un medecin`() {
        assertNull(NameMatcher.find("Zorro", STAFF))
    }
}

class DoctorListTest {
    @Test fun `lit plusieurs formats de lignes`() {
        val text = """
            Alice Durand ; 06 12 34 56 78
            Bruno Martin 06 98 76 54 32
            Claire Dubois
            # commentaire ignoré

            David Roux ; 06 11 22 33 44 (06 55 66 77 88)
            Dr DUPONT :06 98 76 54 32 - 07.12.34.56.78
        """.trimIndent()
        val e = DoctorList.parse(text)
        assertEquals(
            listOf(
                DoctorList.Entry("Alice Durand", "06 12 34 56 78"),
                DoctorList.Entry("Bruno Martin", "06 98 76 54 32"),
                DoctorList.Entry("Claire Dubois", ""),
                DoctorList.Entry("David Roux", "06 11 22 33 44"),
                DoctorList.Entry("Dr DUPONT", "06 98 76 54 32"),
            ),
            e
        )
    }

    @Test fun `un chiffre dans un nom n'est pas pris pour un numero`() {
        assertEquals(listOf(DoctorList.Entry("Standard 8311", "")), DoctorList.parse("Standard 8311"))
    }

    @Test fun `ajoute, complete un numero manquant, ne duplique pas et n'ecrase rien`() {
        val existing = listOf(Doctor("a", "Alice Durand", ""), Doctor("b", "Bruno Martin", "0698765432"))
        var n = 0
        val r = DoctorList.merge(
            existing,
            DoctorList.parse("Dr ALICE DURAND ; 06 12 34 56 78\nBruno Martin ; 06 00 00 00 00\nClaire Dubois ; 06 11 11 11 11")
        ) { "new${n++}" }
        assertEquals(1, r.added); assertEquals(1, r.updated); assertEquals(1, r.unchanged)
        assertEquals("06 12 34 56 78", r.doctors.first { it.id == "a" }.phone)       // numéro complété
        assertEquals("0698765432", r.doctors.first { it.id == "b" }.phone)           // jamais écrasé
        assertEquals("Claire Dubois", r.doctors.last().name)
        assertEquals(3, r.doctors.size)
    }

    @Test fun `remet en casse normale un nom en majuscules`() {
        assertEquals("Dr Dupont", DoctorList.prettyName("Dr DUPONT"))
        assertEquals("Alice Durand", DoctorList.prettyName("  Alice   Durand "))
    }
}

class AliasImportTest {
    private val docs = listOf(STANDARD_DOCTOR, Doctor("a", "Alice Durand", "1"))
    private val json = """{"days":[{"date":"2026-12-01","09:00":"Annexe","18:00":"alice"}]}"""

    @Test fun `sans correspondance memorisee un lieu reste inconnu`() {
        val r = ImportParser.parse(json, docs, DEFAULT_SLOTS)
        assertEquals(Target.Unknown("Annexe"), r.items[0].target)
        assertEquals(Target.Doc("a"), r.items[1].target)   // le prenom est reconnu tout seul
    }

    @Test fun `une correspondance memorisee est appliquee automatiquement`() {
        val r = ImportParser.parse(json, docs, DEFAULT_SLOTS, mapOf(NameMatcher.normalize("Annexe") to STANDARD_ID))
        assertEquals(Target.Doc(STANDARD_ID), r.items[0].target)
        assertTrue(r.unknownNames.isEmpty())
    }

    @Test fun `une correspondance vers un medecin supprime est ignoree`() {
        val r = ImportParser.parse(json, docs, DEFAULT_SLOTS, mapOf("annexe" to "disparu"))
        assertEquals(Target.Unknown("Annexe"), r.items[0].target)
    }

    @Test fun `la consigne explique les prenoms et les lieux`() {
        val p = PromptBuilder.build(java.time.YearMonth.of(2026, 12), docs, DEFAULT_SLOTS, "")
        assertTrue(p.contains("prénom seul"))
        assertTrue(p.contains("lieu ou d'un service"))
    }
}

class ChangeNoticeTest {
    private val snap = Snapshot(
        doctors = listOf(STANDARD_DOCTOR, Doctor("a", "Alice Durand", "0612345678"), Doctor("b", "Bruno Martin", "0698765432")),
        settings = Settings(mode = Mode.RELAY, enabled = true, msgEnabled = true),
    )

    @Test fun `le nouveau medecin est prevenu et l'ancien aussi`() {
        val m = ChangeNotice.messages(snap, "a", "b", "18:30")
        assertEquals(listOf("Bruno Martin", "Alice Durand"), m.map { it.doctor.name })
        assertEquals(listOf(true, false), m.map { it.becomesOnCall })
        assertTrue(m[0].text.contains("Bruno Martin") && m[0].text.contains("18:30"))
        assertTrue(m[1].text.contains("plus d'astreinte"))
        assertEquals("0698765432", m[0].phone)
    }

    @Test fun `vers ou depuis le standard un seul medecin est prevenu`() {
        assertEquals(listOf("Alice Durand"), ChangeNotice.messages(snap, STANDARD_ID, "a", "10:00").map { it.doctor.name })
        assertEquals(listOf("Alice Durand"), ChangeNotice.messages(snap, "a", STANDARD_ID, "10:00").map { it.doctor.name })
        assertEquals(listOf("Alice Durand"), ChangeNotice.messages(snap, ChangeNotice.OFF, "a", "10:00").map { it.doctor.name })
        assertEquals(listOf("Alice Durand"), ChangeNotice.messages(snap, "a", ChangeNotice.OFF, "10:00").map { it.doctor.name })
    }

    @Test fun `aucun message sans changement reel ou sans etat precedent connu`() {
        assertTrue(ChangeNotice.messages(snap, "a", "a", "10:00").isEmpty())
        assertTrue(ChangeNotice.messages(snap, ChangeNotice.UNKNOWN, "a", "10:00").isEmpty())
    }

    @Test fun `rien si la fonction est coupee ou si ce n'est pas un relais`() {
        fun s(f: (Settings) -> Settings) = snap.copy(settings = f(snap.settings))
        assertTrue(ChangeNotice.messages(s { it.copy(changeNotify = false) }, "a", "b", "10:00").isEmpty())
        assertTrue(ChangeNotice.messages(s { it.copy(msgEnabled = false) }, "a", "b", "10:00").isEmpty())
        assertTrue(ChangeNotice.messages(s { it.copy(enabled = false) }, "a", "b", "10:00").isEmpty())
        assertTrue(ChangeNotice.messages(s { it.copy(mode = Mode.VIEWER) }, "a", "b", "10:00").isEmpty())
    }

    @Test fun `un numero invalide est signale par phone null`() {
        val bad = snap.copy(doctors = snap.doctors.map { if (it.id == "b") it.copy(phone = "") else it })
        assertNull(ChangeNotice.messages(bad, "a", "b", "10:00").first().phone)
    }

    @Test fun `les nouveaux champs survivent a la sauvegarde`() {
        val s = snap.copy(
            aliases = mapOf("annexe" to STANDARD_ID),
            state = ApplyState(lastTarget = "a"),
            settings = snap.settings.copy(changeNotify = false, changeTextNew = "N {nom}", changeTextOld = "O {nom}"),
        )
        assertEquals(s, SnapshotJson.fromJson(SnapshotJson.toJson(s)))
        // un fichier d'avant : valeurs par défaut
        val old = SnapshotJson.fromJson("""{"settings":{"mode":"RELAY"}}""")
        assertTrue(old.settings.changeNotify)
        assertEquals(DEFAULT_CHANGE_NEW, old.settings.changeTextNew)
        assertEquals("", old.state.lastTarget)
        assertTrue(old.aliases.isEmpty())
    }
}

class HistoryTest {
    private val d = java.time.LocalDate.of(2026, 11, 12)
    private fun shift(id: String, h: Int, who: String?) = Shift(id, d, java.time.LocalTime.of(h, 0), who)
    private val base = Snapshot(doctors = STAFF, shifts = listOf(shift("1", 18, "a")))

    @Test fun `un changement de case est decrit avec avant et apres`() {
        val lines = History.describe(base.shifts, listOf(shift("1", 18, "b"), shift("2", 12, "c")), STAFF)
        assertEquals(listOf("12/11 12h : défaut → Claire Dubois", "12/11 18h : Alice Durand → Bruno Martin"), lines)
    }

    @Test fun `suppression et aucun renvoi sont distingues`() {
        assertEquals(listOf("12/11 18h : Alice Durand → défaut"), History.describe(base.shifts, emptyList(), STAFF))
        assertEquals(listOf("12/11 18h : Alice Durand → aucun renvoi"), History.describe(base.shifts, listOf(shift("1", 18, null)), STAFF))
    }

    @Test fun `rien n'est enregistre si le planning est identique`() {
        val same = base.copy(shifts = listOf(shift("autre-id", 18, "a")))
        assertTrue(History.record(base, same, Author(Author.VIA_APP), 1L).history.isEmpty())
    }

    @Test fun `l'auteur par defaut est le nom saisi dans les reglages`() {
        val next = base.copy(shifts = listOf(shift("1", 18, "b")), settings = base.settings.copy(userName = "Dr Test"))
        val e = History.record(base, next, Author(Author.VIA_IMPORT), 42L).history.single()
        assertEquals("Dr Test", e.who); assertEquals("Import photo", e.via); assertEquals(1, e.cells); assertEquals(42L, e.at)
        val anon = History.record(base, base.copy(shifts = emptyList()), Author(Author.VIA_APP), 1L).history.single()
        assertEquals(History.DEFAULT_WHO, anon.who)
    }

    @Test fun `un auteur explicite l'emporte, les plus recents d'abord, liste bornee`() {
        var s = base
        repeat(History.MAX_ENTRIES + 5) { i ->
            val next = s.copy(shifts = listOf(shift("1", 18, if (i % 2 == 0) "b" else "a")))
            s = History.record(s, next, Author(Author.VIA_SMS, who = "Dr X (0600000000)"), i.toLong())
        }
        assertEquals(History.MAX_ENTRIES, s.history.size)
        assertEquals((History.MAX_ENTRIES + 4).toLong(), s.history.first().at)
        assertEquals("Dr X (0600000000)", s.history.first().who)
    }

    @Test fun `un gros remplacement est resume et le detail tronque`() {
        val many = (0 until 60).map { Shift("s$it", d.plusDays(it.toLong()), java.time.LocalTime.of(18, 0), "a") }
        val e = History.record(base.copy(shifts = emptyList()), base.copy(shifts = many), Author("Test"), 1L).history.single()
        assertEquals(60, e.cells); assertEquals(History.MAX_LINES + 1, e.lines.size); assertTrue(e.lines.last().contains("20 autre"))
        val c = History.record(base.copy(shifts = emptyList()), base.copy(shifts = many), Author("Synchro", collapse = true), 1L).history.single()
        assertEquals(1, c.lines.size)
    }

    @Test fun `l'historique et le nom survivent a la sauvegarde`() {
        val s = base.copy(
            history = listOf(HistoryEntry(5L, "Dr Test", "SMS", 2, listOf("12/11 18h : A → B", "12/11 12h : défaut → C"))),
            settings = base.settings.copy(userName = "Dr Test"),
        )
        assertEquals(s, SnapshotJson.fromJson(SnapshotJson.toJson(s)))
        assertTrue(SnapshotJson.fromJson("""{"settings":{"mode":"RELAY"}}""").history.isEmpty())
    }
}

class PhotoDoctorsTest {
    private val slots = DEFAULT_SLOTS

    @Test fun `la liste medecins du JSON est lue, noms seulement`() {
        val json = """```json
{"month":"2026-11","medecins":[{"nom":"DUPONT Alice","telephone":"06 00 00 00 01"},{"nom":"Bruno Martin"},
{"nom":"Standard"},"Claire","bruno  martin"],
"days":[{"date":"2026-11-02","18:00":"Alice"}]}
```"""
        val r = ImportParser.parse(json, STAFF, slots)
        // le numero eventuellement donne par l'assistant est ignore ; standard et doublons ecartes
        assertEquals(
            listOf(
                DoctorList.Entry("Dupont Alice", ""),
                DoctorList.Entry("Bruno Martin", ""),
                DoctorList.Entry("Claire", ""),
            ),
            r.doctors,
        )
        assertEquals(1, r.items.size)
    }

    @Test fun `des medecins sans jours sont acceptes, sans medecins ni jours c'est une erreur`() {
        assertEquals(1, ImportParser.parse("""{"medecins":[{"nom":"Zoe Test"}]}""", STAFF, slots).doctors.size)
        assertTrue(ImportParser.parse("""{"month":"2026-11"}""", STAFF, slots).problems.isNotEmpty())
    }

    @Test fun `un medecin deja present n'est pas duplique et un nouveau est ajoute sans numero`() {
        val r = ImportParser.parse("""{"medecins":[{"nom":"Alice Durand"},{"nom":"Zoe Test"}],"days":[]}""", STAFF, slots)
        val m = DoctorList.merge(STAFF, r.doctors) { "new" }
        assertEquals(1, m.added); assertEquals(0, m.updated); assertEquals(1, m.unchanged)
        assertEquals("", m.doctors.first { it.id == "new" }.phone)
        assertEquals("0612345678", m.doctors.first { it.id == "a" }.phone)
    }

    @Test fun `un nouveau medecin lu sur la photo est reconnu dans le planning`() {
        val json = """{"medecins":[{"nom":"Zoe Test"}],"days":[{"date":"2026-11-02","18:00":"Zoe"}]}"""
        val first = ImportParser.parse(json, STAFF, slots)
        val withNew = DoctorList.merge(STAFF, first.doctors) { "zz" }.doctors
        assertTrue(ImportParser.parse(json, STAFF, slots).items.single().target is Target.Unknown)
        assertEquals(Target.Doc("zz"), ImportParser.parse(json, withNew, slots).items.single().target)
    }

    @Test fun `un prenom complete par le nom lu sur la photo permet de le retrouver`() {
        assertEquals("Eve Garnier", DoctorList.completeName("Eve", "Dr Garnier"))
        assertEquals("Eve Garnier", DoctorList.completeName("Eve Garnier", "Dr GARNIER"))
        assertEquals("Dr Hugo Perrin", DoctorList.completeName("Dr Hugo", "Perrin"))
        val prenoms = listOf(STANDARD_DOCTOR, Doctor("p", "Eve", ""), Doctor("q", "Hugo", ""))
        // sans lien, le nom de famille n'est pas reconnu...
        assertNull(NameMatcher.find("Dr Garnier", prenoms))
        // ... avec le nom complete, il l'est (et le prenom seul aussi)
        val complets = prenoms.map { if (it.id == "p") it.copy(name = DoctorList.completeName(it.name, "Dr Garnier")) else it }
        assertEquals("p", NameMatcher.find("Dr Garnier", complets)?.id)
        assertEquals("p", NameMatcher.find("Eve", complets)?.id)
        // un nom deja reconnu n'est pas propose a l'ajout
        assertEquals("p", NameMatcher.find("Eve Garnier", prenoms)?.id)
    }

    @Test fun `la consigne demande les noms lus sur la photo, jamais de numeros`() {
        val p = PromptBuilder.build(java.time.YearMonth.of(2026, 11), STAFF, slots, "")
        assertTrue(p.contains("\"medecins\"")); assertTrue(!p.contains("\"telephone\""))
        assertTrue(p.contains("Ne recopie JAMAIS de numéro"))
        STAFF.forEach { if (it.phone.isNotBlank()) assertTrue(!p.contains(it.phone)) }
        // les noms deja connus sont donnes a l'assistant
        assertTrue(p.contains("Alice Durand") && p.contains("Bruno Martin"))
    }
}

class OffDaysTest {
    private val staff = STAFF
    private fun d(y: Int, m: Int, day: Int) = java.time.LocalDate.of(y, m, day)

    @Test fun `jours feries francais de 2026`() {
        assertEquals(d(2026, 4, 5), Holidays.easter(2026))
        listOf(d(2026, 1, 1), d(2026, 4, 6), d(2026, 5, 1), d(2026, 5, 8), d(2026, 5, 14), d(2026, 5, 25),
            d(2026, 7, 14), d(2026, 8, 15), d(2026, 11, 1), d(2026, 11, 11), d(2026, 12, 25))
            .forEach { assertTrue("$it", Holidays.isHoliday(it)) }
        assertTrue(!Holidays.isHoliday(d(2026, 11, 12)))
        assertEquals(d(2027, 3, 28), Holidays.easter(2027))
    }

    @Test fun `pas de standard par defaut un week-end ni un jour ferie`() {
        val eff = Planner.effective(emptyList(), DEFAULT_SLOTS, d(2026, 11, 9), d(2026, 11, 15))
        // lundi 9 .. vendredi 13 : 9h et 14h standard (le 11 est ferie) ; samedi 14 et dimanche 15 : rien
        val days = eff.map { it.date }.distinct()
        assertTrue(d(2026, 11, 14) !in days && d(2026, 11, 15) !in days && d(2026, 11, 11) !in days)
        assertEquals(listOf(d(2026, 11, 9), d(2026, 11, 10), d(2026, 11, 12), d(2026, 11, 13)), days)
    }

    @Test fun `une seule personne saisie a 9h couvre toute la journee du week-end`() {
        val sat = d(2026, 11, 14)
        val shifts = listOf(Shift("1", sat, java.time.LocalTime.of(9, 0), "a"))
        val zone = java.time.ZoneId.of("Europe/Paris")
        val snap = Snapshot(doctors = staff, shifts = shifts, settings = Settings(mode = Mode.RELAY))
        fun at(h: Int) = java.time.ZonedDateTime.of(sat, java.time.LocalTime.of(h, 0), zone).toInstant()
        listOf(9, 12, 14, 17, 18, 23).forEach { assertEquals("a a ${it}h", "a", Planner.currentShift(snap, at(it), zone)?.doctorId) }
        // lundi 9h : le standard reprend
        val monday = java.time.ZonedDateTime.of(d(2026, 11, 16), java.time.LocalTime.of(9, 30), zone).toInstant()
        assertEquals(STANDARD_ID, Planner.currentShift(snap, monday, zone)?.doctorId)
    }

    @Test fun `messages horaires seulement les jours ouvres, message de journee les jours chomes`() {
        val zone = java.time.ZoneId.of("Europe/Paris")
        val st = Settings(mode = Mode.RELAY, enabled = true, msgEnabled = true)
        val sat = d(2026, 11, 14)
        val snap = Snapshot(
            doctors = staff,
            shifts = listOf(Shift("1", sat, java.time.LocalTime.of(9, 0), "a")),
            settings = st,
            state = ApplyState(lastCommand = Codes.forDoctor(staff.first { it.id == "a" }), lastOk = true),
        )
        fun due(day: java.time.LocalDate, h: Int, min: Int = 5) =
            Messages.due(snap, java.time.ZonedDateTime.of(day, java.time.LocalTime.of(h, min), zone).toInstant(), zone)
        assertEquals(listOf("jour"), due(sat, 9).map { it.rule.id })
        // un samedi, rien a 12h ni 17h ni 18h
        listOf(12, 17, 18).forEach { assertTrue(due(sat, it).isEmpty()) }
    }

    @Test fun `l'import ramene la personne du week-end au premier creneau`() {
        val json = """{"month":"2026-11","days":[{"date":"2026-11-14","18:00":"Alice"},{"date":"2026-11-15","12:00":"Alice","18:00":"Bruno"},{"date":"2026-11-16","18:00":"Alice"}]}"""
        val items = ImportParser.parse(json, STAFF, DEFAULT_SLOTS).items
        // samedi : ramenee a 9h ; dimanche : deux personnes, laisse tel quel ; lundi (ouvre) : intact
        assertEquals(listOf("09:00"), items.filter { it.date == d(2026, 11, 14) }.map { it.time.toString() })
        assertEquals(listOf("12:00", "18:00"), items.filter { it.date == d(2026, 11, 15) }.map { it.time.toString() })
        assertEquals(listOf("18:00"), items.filter { it.date == d(2026, 11, 16) }.map { it.time.toString() })
    }

    @Test fun `la consigne liste les week-ends et feries du mois`() {
        val p = PromptBuilder.build(java.time.YearMonth.of(2026, 11), STAFF, DEFAULT_SLOTS, "")
        assertTrue(p.contains("WEEK-ENDS ET JOURS FÉRIÉS"))
        assertTrue(p.contains("mer. 11 (férié)") && p.contains("sam. 14") && p.contains("\"09:00\""))
    }

    @Test fun `un ancien fichier recoit le message de journee, sans le dupliquer ensuite`() {
        val old = """{"settings":{"mode":"RELAY","msgRules":[{"id":"midi","enabled":true,"at":"12:00","slotTime":"12:00","confirmsSwitch":true,"text":"x"}]}}"""
        val s = SnapshotJson.fromJson(old)
        assertEquals(listOf("midi", "jour"), s.settings.msgRules.map { it.id })
        val again = SnapshotJson.fromJson(SnapshotJson.toJson(s))
        assertEquals(listOf("midi", "jour"), again.settings.msgRules.map { it.id })
        val deleted = SnapshotJson.fromJson(SnapshotJson.toJson(again.copy(settings = again.settings.copy(msgRules = again.settings.msgRules.filter { it.id != "jour" }))))
        assertEquals(listOf("midi"), deleted.settings.msgRules.map { it.id })
    }
}

class LayoutNotesTest {
    private val staff = listOf(STANDARD_DOCTOR, Doctor("a", "Eve Garnier", ""), Doctor("b", "Hugo Perrin (mpr)", ""), Doctor("c", "Dr Meunier", ""))

    @Test fun `une faute de frappe dans le nom de famille est toleree`() {
        assertEquals("a", NameMatcher.find("Dr GARNIERE", staff)?.id)
        assertEquals("a", NameMatcher.find("Dr GARNNIER", staff)?.id)
        assertEquals("c", NameMatcher.find("Dr MEUNNIER", staff)?.id)
        assertEquals("b", NameMatcher.find("Dr PERRIN (MPR)", staff)?.id)
        // un mot court ne change pas : pas de faux rapprochement
        assertNull(NameMatcher.find("Dr Dupin", staff))
    }

    @Test fun `les precisions sont memorisees et le modele figure dans la consigne`() {
        val s = Snapshot(settings = Settings(importNotes = PromptBuilder.LAYOUT_TEMPLATE))
        assertEquals(s, SnapshotJson.fromJson(SnapshotJson.toJson(s)))
        val p = PromptBuilder.build(java.time.YearMonth.of(2026, 11), staff, DEFAULT_SLOTS, s.settings.importNotes)
        assertTrue(p.contains("PRÉCISIONS SUR CE PLANNING") && p.contains("COLORIÉE") && p.contains("APRES MIDI"))
        assertTrue(SnapshotJson.fromJson("""{"settings":{"mode":"RELAY"}}""").settings.importNotes.isEmpty())
    }

    @Test fun `la reponse type d'un tableau a cases colorees s'importe comme voulu`() {
        // jours ouvres : 12h, 17h, 18h ; week-end : 9h seulement ; jamais 9h ni 14h un jour ouvre
        val json = """{"month":"2026-11","days":[
          {"date":"2026-11-02","12:00":"Hugo Perrin (mpr)","18:00":"Dr Meunier"},
          {"date":"2026-11-07","09:00":"Eve Garnier"},
          {"date":"2026-11-11","09:00":"Eve Garnier"}]}"""
        val r = ImportParser.parse(json, staff, DEFAULT_SLOTS)
        assertEquals(4, r.items.size)
        assertTrue(r.items.all { it.target is Target.Doc })
        val snap = Snapshot(doctors = staff, shifts = Planner.applyEdits(emptyList(), ImportParser.toEdits(r.items, emptyMap())) { java.util.UUID.randomUUID().toString() })
        val zone = java.time.ZoneId.of("Europe/Paris")
        fun at(d: String, h: Int) = java.time.ZonedDateTime.of(java.time.LocalDate.parse(d), java.time.LocalTime.of(h, 0), zone).toInstant()
        fun who(d: String, h: Int) = Planner.currentShift(snap, at(d, h), zone)?.doctorId
        assertEquals(STANDARD_ID, who("2026-11-02", 10)); assertEquals("b", who("2026-11-02", 12))
        assertEquals(STANDARD_ID, who("2026-11-02", 15)); assertEquals("c", who("2026-11-02", 19))
        // samedi et mercredi 11 (ferie) : la meme personne toute la journee
        listOf(9, 12, 14, 17, 18, 22).forEach { assertEquals("a", who("2026-11-07", it)); assertEquals("a", who("2026-11-11", it)) }
    }
}
