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

    @Test fun `la liste medecins du JSON est lue avec ses numeros`() {
        val json = """```json
{"month":"2026-11","medecins":[{"nom":"DUPONT Alice","telephone":"06 00 00 00 01"},{"nom":"Bruno Martin","telephone":""},
{"nom":"Standard","telephone":"02 00 00 00 00"},"Claire ; 07.00.00.00.02"],
"days":[{"date":"2026-11-02","18:00":"Alice"}]}
```"""
        val r = ImportParser.parse(json, STAFF, slots)
        assertEquals(
            listOf(
                DoctorList.Entry("Dupont Alice", "06 00 00 00 01"),
                DoctorList.Entry("Bruno Martin", ""),
                DoctorList.Entry(STANDARD_DOCTOR.name, "02 00 00 00 00"),
                DoctorList.Entry("Claire", "07.00.00.00.02"),
            ),
            r.doctors,
        )
        assertEquals(1, r.items.size)
    }

    @Test fun `un numero illisible est ignore, pas le medecin`() {
        val r = ImportParser.parse("""{"medecins":[{"nom":"Zoe Test","telephone":"voir feuille"}],"days":[]}""", STAFF, slots)
        assertEquals(listOf(DoctorList.Entry("Zoe Test", "")), r.doctors)
    }

    @Test fun `des medecins sans jours sont acceptes, sans medecins ni jours c'est une erreur`() {
        assertEquals(1, ImportParser.parse("""{"medecins":[{"nom":"Zoe Test","telephone":"0600000009"}]}""", STAFF, slots).doctors.size)
        assertTrue(ImportParser.parse("""{"month":"2026-11"}""", STAFF, slots).problems.isNotEmpty())
    }

    @Test fun `fusion avec la liste existante - le standard est complete, rien n est ecrase`() {
        val r = ImportParser.parse(
            """{"medecins":[{"nom":"Standard","telephone":"02 00 00 00 00"},{"nom":"Alice Durand","telephone":"06 99 99 99 99"},{"nom":"Zoe Test","telephone":"0600000009"}],"days":[]}""",
            STAFF, slots,
        )
        val m = DoctorList.merge(STAFF, r.doctors) { "new" }
        assertEquals(1, m.added); assertEquals(1, m.updated); assertEquals(1, m.unchanged)
        assertEquals("02 00 00 00 00", m.doctors.first { it.id == STANDARD_ID }.phone)
        assertEquals("0612345678", m.doctors.first { it.id == "a" }.phone)
    }

    @Test fun `un nouveau medecin lu sur la photo est reconnu dans le planning`() {
        val json = """{"medecins":[{"nom":"Zoe Test","telephone":"0600000009"}],"days":[{"date":"2026-11-02","18:00":"Zoe"}]}"""
        val first = ImportParser.parse(json, STAFF, slots)
        val withNew = DoctorList.merge(STAFF, first.doctors) { "zz" }.doctors
        assertTrue(ImportParser.parse(json, STAFF, slots).items.single().target is Target.Unknown)
        assertEquals(Target.Doc("zz"), ImportParser.parse(json, withNew, slots).items.single().target)
    }

    @Test fun `la consigne demande les noms et numeros lus sur la photo, sans les numeros de la liste`() {
        val p = PromptBuilder.build(java.time.YearMonth.of(2026, 11), STAFF, slots, "")
        assertTrue(p.contains("\"medecins\"")); assertTrue(p.contains("\"telephone\""))
        assertTrue(p.contains("JAMAIS un numéro"))
        // les numéros déjà enregistrés dans l'app ne sont jamais envoyés à l'assistant
        STAFF.forEach { if (it.phone.isNotBlank()) assertTrue(!p.contains(it.phone)) }
    }
}
