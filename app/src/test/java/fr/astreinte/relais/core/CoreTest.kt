package fr.astreinte.relais.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

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
        assertEquals(
            "**21*0612345678#",
            Codes.forDoctor(Doctor("1", "Dr Martin", "06 12 34 56 78"))
        )
        assertNull(Codes.forDoctor(Doctor("2", "Dr Pasbon", "pas un numero")))
    }
}

class PlannerTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(d: String, t: String) =
        LocalDateTime.of(LocalDate.parse(d), LocalTime.parse(t)).atZone(paris).toInstant()

    private val shifts = listOf(
        Shift("c", LocalDate.parse("2026-11-08"), LocalTime.of(8, 0), "C"),
        Shift("a", LocalDate.parse("2026-11-06"), LocalTime.of(8, 0), "A"),
        Shift("b", LocalDate.parse("2026-11-07"), LocalTime.of(8, 0), "B"),
        Shift("b2", LocalDate.parse("2026-11-07"), LocalTime.of(20, 0), null),
    )

    @Test fun `avant le premier changement il n'y a pas d'astreinte`() {
        assertNull(Planner.current(shifts, at("2026-11-06", "07:59"), paris))
        assertEquals("a", Planner.next(shifts, at("2026-11-06", "07:59"), paris)?.id)
    }

    @Test fun `le changement prend effet pile a l'heure`() {
        assertEquals("a", Planner.current(shifts, at("2026-11-06", "08:00"), paris)?.id)
        assertEquals("a", Planner.current(shifts, at("2026-11-07", "07:59"), paris)?.id)
        assertEquals("b", Planner.current(shifts, at("2026-11-07", "08:00"), paris)?.id)
        assertEquals("b2", Planner.current(shifts, at("2026-11-07", "23:00"), paris)?.id)
        assertEquals("b2", Planner.current(shifts, at("2026-11-08", "07:59"), paris)?.id)
        assertEquals("c", Planner.current(shifts, at("2026-11-08", "08:00"), paris)?.id)
    }

    @Test fun `apres le dernier changement il reste le dernier medecin et pas de suivant`() {
        assertEquals("c", Planner.current(shifts, at("2027-01-01", "12:00"), paris)?.id)
        assertNull(Planner.next(shifts, at("2027-01-01", "12:00"), paris))
    }

    @Test fun `prochain changement strictement apres maintenant`() {
        assertEquals("b", Planner.next(shifts, at("2026-11-06", "08:00"), paris)?.id)
    }

    @Test fun `astreinte heritee de la veille`() {
        assertEquals("a", Planner.carriedInto(shifts, LocalDate.parse("2026-11-07"), paris)?.id)
        assertEquals("b2", Planner.carriedInto(shifts, LocalDate.parse("2026-11-08"), paris)?.id)
        assertNull(Planner.carriedInto(shifts, LocalDate.parse("2026-11-06"), paris))
    }

    @Test fun `changement d'heure d'ete ne plante pas`() {
        // 02:30 n'existe pas le 29 mars 2026 à Paris
        val s = Shift("x", LocalDate.parse("2026-03-29"), LocalTime.of(2, 30), "A")
        assertNotNull(Planner.startOf(s, paris))
        assertEquals("x", Planner.current(listOf(s), at("2026-03-29", "12:00"), paris)?.id)
    }

    @Test fun `remplacer un jour supprime les anciens et dedoublonne les heures`() {
        val day = LocalDate.parse("2026-11-07")
        val result = Planner.replaceDay(
            shifts, day,
            listOf(
                Shift("n1", day, LocalTime.of(9, 0), "A"),
                Shift("n2", day, LocalTime.of(9, 0), "B"),
            )
        )
        val onDay = Planner.shiftsOn(result, day)
        assertEquals(1, onDay.size)
        assertEquals("B", onDay[0].doctorId)
        // 4 changements au départ, 2 sur ce jour remplacés par 1 => 3 au total
        assertEquals(3, result.size)
    }

    @Test fun `remplir une periode`() {
        var n = 0
        val result = Planner.fillRange(
            shifts,
            LocalDate.parse("2026-11-06"), LocalDate.parse("2026-11-09"),
            LocalTime.of(9, 0), "Z"
        ) { "id${n++}" }
        // l'ancien contenu des 4 jours est parti : seuls restent les 4 nouveaux
        assertEquals(4, result.size)
        assertTrue(result.all { it.doctorId == "Z" && it.time == LocalTime.of(9, 0) })
        assertEquals(4, result.map { it.date }.distinct().size)
    }

    @Test fun `remplir avec une fin avant le debut ne fait rien`() {
        val result = Planner.fillRange(
            shifts, LocalDate.parse("2026-11-09"), LocalDate.parse("2026-11-06"),
            LocalTime.of(9, 0), "Z"
        ) { "x" }
        assertEquals(shifts, result)
    }
}

class SnapshotJsonTest {
    @Test fun `aller-retour JSON`() {
        val snap = Snapshot(
            doctors = listOf(Doctor("d1", "Dr Martin", "06 12 34 56 78")),
            shifts = listOf(
                Shift("s1", LocalDate.parse("2026-11-06"), LocalTime.of(8, 0), "d1"),
                Shift("s2", LocalDate.parse("2026-11-07"), LocalTime.of(20, 30), null),
            ),
            settings = Settings(enabled = true, defaultTime = LocalTime.of(7, 30), subscriptionId = 3, useDirectUssd = true),
            state = ApplyState("**21*0612345678#", false, 1234L, "oups \"guillemets\"", 2),
            log = listOf(LogEntry(99L, "alarme", "##21#", true, "ok\nligne 2")),
        )
        assertEquals(snap, SnapshotJson.fromJson(SnapshotJson.toJson(snap)))
    }

    @Test fun `fichier minimal`() {
        assertEquals(Snapshot(), SnapshotJson.fromJson("{}"))
    }
}
