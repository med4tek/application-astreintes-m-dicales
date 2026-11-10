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
