package fr.astreinte.relais.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

private val PARIS2 = ZoneId.of("Europe/Paris")
private fun inst(d: String, t: String) = LocalDateTime.of(LocalDate.parse(d), LocalTime.parse(t)).atZone(PARIS2).toInstant()
private fun day(s: String) = LocalDate.parse(s)
private fun hour(s: String) = LocalTime.parse(s)

/** Planning (date, heure) -> nom du médecin, "OFF" pour aucun renvoi : indépendant des identifiants. */
private fun cells(shifts: List<Shift>, doctors: List<Doctor>): Map<Pair<LocalDate, LocalTime>, String> =
    shifts.associate { (it.date to it.time) to (it.doctorId?.let { id -> doctors.first { d -> d.id == id }.name } ?: "OFF") }

private val RELAY_DOCTORS = listOf(
    STANDARD_DOCTOR,
    Doctor("uuid-m", "Dr Martin", "06 11 11 11 11"),
    Doctor("uuid-d", "Dr Dupont", "06 22 22 22 22"),
    Doctor("uuid-j", "Dr Jean-Paul Équipe", "06 33 33 33 33"),
)

private val RELAY_SNAP = Snapshot(
    doctors = RELAY_DOCTORS,
    shifts = listOf(
        Shift("1", day("2026-11-09"), hour("12:00"), "uuid-m"),
        Shift("2", day("2026-11-09"), hour("18:00"), "uuid-m"),
        Shift("3", day("2026-11-10"), hour("12:00"), null),
        Shift("4", day("2026-11-10"), hour("18:00"), "uuid-d"),
    ),
    settings = Settings(mode = Mode.RELAY, enabled = true),
)
private val TODAY = day("2026-11-09")

class SyncTest {

    private fun viewerAfterPull(maxChars: Int = SyncCodec.CHUNK_CHARS): Snapshot {
        var v = Snapshot(settings = Settings(mode = Mode.VIEWER, relayNumber = "0600000000", smsPin = "1234"))
        v = v.copy(sync = SyncState(pending = PendingSync("PULL", "4821", 1000L)))
        SyncCodec.encodeData(RELAY_SNAP, "4821", TODAY, maxChars).reversed().forEach { msg ->   // ordre d'arrivée inversé
            v = SyncLogic.onDataChunk(v, SyncCodec.parseDataChunk(msg)!!, 5000L)
        }
        return v
    }

    @Test fun `le planning du relais se retrouve a l'identique sur le consultant`() {
        val v = viewerAfterPull()
        assertNull(v.sync.pending)
        assertEquals(5000L, v.sync.lastPullAt)
        assertEquals(RELAY_DOCTORS.map { it.name to it.phone }, v.doctors.map { it.name to it.phone })
        assertEquals(cells(RELAY_SNAP.shifts, RELAY_DOCTORS), cells(v.shifts, v.doctors))
        assertEquals(DEFAULT_SLOTS, v.settings.slots)           // créneaux et cibles par défaut compris
        assertEquals(v.shifts, v.baseline)
        assertEquals(0, SyncLogic.unsentCount(v))
    }

    @Test fun `le decoupage en plusieurs SMS fonctionne dans n'importe quel ordre`() {
        val msgs = SyncCodec.encodeData(RELAY_SNAP, "4821", TODAY, maxChars = 110)
        assertTrue(msgs.size > 1)
        assertTrue(msgs.all { it.length <= 110 })
        val v = viewerAfterPull(maxChars = 110)
        assertEquals(cells(RELAY_SNAP.shifts, RELAY_DOCTORS), cells(v.shifts, v.doctors))
    }

    @Test fun `un planning partiel reste en attente`() {
        val msgs = SyncCodec.encodeData(RELAY_SNAP, "4821", TODAY, maxChars = 110)
        val start = Snapshot(sync = SyncState(pending = PendingSync("PULL", "4821", 0L)))
        val v = SyncLogic.onDataChunk(start, SyncCodec.parseDataChunk(msgs.first())!!, 1L)
        assertNotNull(v.sync.pending)
        assertEquals(msgs.size, v.sync.pending!!.total)
        assertEquals(1, v.sync.pending!!.chunks.size)
        assertEquals(0L, v.sync.lastPullAt)
    }

    @Test fun `les reponses d'une autre demande ou sans demande sont ignorees`() {
        val chunk = SyncCodec.parseDataChunk(SyncCodec.encodeData(RELAY_SNAP, "4821", TODAY).first())!!
        val none = Snapshot()
        assertSame(none, SyncLogic.onDataChunk(none, chunk, 1L))
        val other = Snapshot(sync = SyncState(pending = PendingSync("PULL", "9999", 0L)))
        assertSame(other, SyncLogic.onDataChunk(other, chunk, 1L))
        val push = Snapshot(sync = SyncState(pending = PendingSync("PUSH", "4821", 0L, total = 1)))
        assertSame(push, SyncLogic.onDataChunk(push, chunk, 1L))
    }

    @Test fun `des donnees corrompues n'ecrasent rien`() {
        val start = Snapshot(
            shifts = listOf(Shift("k", day("2026-11-09"), hour("12:00"), null)),
            sync = SyncState(pending = PendingSync("PULL", "1234", 0L)),
        )
        val v = SyncLogic.onDataChunk(start, DataChunk("1234", 1, 1, "n'importe quoi"), 9L)
        assertNull(v.sync.pending)
        assertTrue(v.sync.lastResult.contains("illisible"))
        assertEquals(start.shifts, v.shifts)
    }

    @Test fun `en-tetes invalides`() {
        assertNull(SyncCodec.parseDataChunk("bonjour"))
        assertNull(SyncCodec.parseDataChunk("ASTDATA 4821 3/2\nW"))
        assertNull(SyncCodec.parseDataChunk("ASTDATA 4821 1/x\nW"))
    }

    // ------------------------------------------------------------------ envoi des modifications

    private fun relayApplies(messages: List<String>, start: List<Shift>): Pair<List<Shift>, List<Ack>> {
        var shifts = start
        val acks = ArrayList<Ack>()
        for (m in messages) {
            val env = SmsCommands.parseEnvelope(m) as SmsParse.Command
            assertEquals("1234", env.pin)
            val action = SmsCommands.interpret(env.args, RELAY_DOCTORS, DEFAULT_SLOTS, TODAY) as SmsAction.Push
            if (action.error != null) {
                acks += SyncCodec.parseAck(SyncCodec.ackText(action.id, action.part, action.total, false, action.error))!!
                continue
            }
            shifts = SmsCommands.apply(shifts, action.groups) { java.util.UUID.randomUUID().toString() }
            acks += SyncCodec.parseAck(SyncCodec.ackText(action.id, action.part, action.total, true, SyncCodec.countCells(action.groups).toString()))!!
        }
        return shifts to acks
    }

    private fun editedLocal(v: Snapshot): List<Shift> {
        fun id(name: String) = v.doctors.first { it.name == name }.id
        return v.shifts
            .filterNot { it.date == day("2026-11-09") && it.time == hour("12:00") }                  // supprimé
            .map { if (it.date == day("2026-11-10") && it.time == hour("18:00")) it.copy(doctorId = id("Dr Martin")) else it } // changé
            .map { if (it.date == day("2026-11-10") && it.time == hour("12:00")) it.copy(doctorId = id("Dr Jean-Paul Équipe")) else it } // OFF -> nom composé
            .plus(Shift("n1", day("2026-11-12"), hour("18:00"), id("Dr Dupont")))                      // ajouté
            .plus(Shift("n2", day("2026-11-12"), hour("15:30"), null))                                 // heure libre, aucun renvoi
            .plus(Shift("n3", day("2026-11-12"), hour("17:00"), STANDARD_ID))
    }

    @Test fun `le diff ne contient que les cases modifiees`() {
        val v = viewerAfterPull()
        val changes = SyncCodec.diff(editedLocal(v), v.baseline)
        assertEquals(6, changes.size)
        assertEquals(CellEdit.Clear, changes.first { it.date == day("2026-11-09") }.edit)
        assertEquals(0, SyncCodec.diff(v.shifts, v.baseline).size)
    }

    @Test fun `les modifications envoyees par SMS donnent le meme planning chez le relais`() {
        val v = viewerAfterPull()
        val local = editedLocal(v)
        val messages = SyncCodec.pushMessages(SyncCodec.diff(local, v.baseline), v.doctors, "1234", "7777")
        assertTrue(messages.isNotEmpty())
        assertTrue(messages.all { it.startsWith("AST 1234 PUSH 7777 ") && it.length <= SyncCodec.CHUNK_CHARS })

        val (relayShifts, acks) = relayApplies(messages, RELAY_SNAP.shifts)
        assertTrue(acks.all { it.ok })
        assertEquals(cells(local, v.doctors), cells(relayShifts, RELAY_DOCTORS))
    }

    @Test fun `un gros envoi est decoupe en plusieurs SMS courts`() {
        val v = viewerAfterPull()
        val many = (1..40).flatMap { i ->
            val d = day("2026-12-01").plusDays(i.toLong())
            listOf("12:00", "17:00", "18:00").map { Shift("x$i$it", d, hour(it), v.doctors.first { dd -> dd.name == "Dr Dupont" }.id) }
        }
        val local = v.shifts + many
        val messages = SyncCodec.pushMessages(SyncCodec.diff(local, v.baseline), v.doctors, "1234", "7777")
        assertTrue(messages.size > 2)
        assertTrue(messages.all { it.length <= SyncCodec.CHUNK_CHARS })
        assertTrue(messages.mapIndexed { i, m -> m.contains(" PUSH 7777 ${i + 1}/${messages.size} ") }.all { it })

        val (relayShifts, acks) = relayApplies(messages, RELAY_SNAP.shifts)
        assertEquals(messages.size, acks.size)
        assertEquals(cells(local, v.doctors), cells(relayShifts, RELAY_DOCTORS))
    }

    @Test fun `rien a envoyer donne aucun SMS`() {
        val v = viewerAfterPull()
        assertTrue(SyncCodec.pushMessages(SyncCodec.diff(v.shifts, v.baseline), v.doctors, "1234", "1").isEmpty())
    }

    @Test fun `le relais refuse proprement un medecin inconnu et le dit`() {
        val env = SmsCommands.parseEnvelope("AST 1234 PUSH 7777 1/1 12/11/26 18:00=Dr_Inconnu") as SmsParse.Command
        val a = SmsCommands.interpret(env.args, RELAY_DOCTORS, DEFAULT_SLOTS, TODAY) as SmsAction.Push
        assertEquals("7777", a.id)
        assertEquals(1, a.part)
        assertNotNull(a.error)
        assertTrue(a.error!!.contains("Inconnu"))
    }

    @Test fun `les commandes sync sont reconnues`() {
        assertEquals(SmsAction.Sync("4821"), SmsCommands.interpret(listOf("SYNC", "4821"), RELAY_DOCTORS, DEFAULT_SLOTS, TODAY))
        assertTrue(SmsCommands.interpret(listOf("SYNC"), RELAY_DOCTORS, DEFAULT_SLOTS, TODAY) is SmsAction.Error)
        assertTrue(SmsCommands.interpret(listOf("SYNC", "abc"), RELAY_DOCTORS, DEFAULT_SLOTS, TODAY) is SmsAction.Error)
        // une heure libre n'est acceptée que dans un PUSH
        assertTrue(SmsCommands.interpret(listOf("12/11/26", "15:30=Martin"), RELAY_DOCTORS, DEFAULT_SLOTS, TODAY) is SmsAction.Error)
        val push = SmsCommands.interpret(listOf("PUSH", "4821", "1/2", "12/11/26", "15:30=Martin"), RELAY_DOCTORS, DEFAULT_SLOTS, TODAY) as SmsAction.Push
        assertNull(push.error)
        assertEquals(2, push.total)
    }

    // ------------------------------------------------------------------ accusés

    private fun pushing(total: Int, pushed: List<Shift>) = Snapshot(
        shifts = pushed,
        baseline = emptyList(),
        sync = SyncState(pending = PendingSync("PUSH", "7777", 0L, total = total, cellsSent = 5, pushedShifts = pushed)),
    )

    @Test fun `le consultant est a jour quand tous les accuses sont revenus`() {
        val pushed = listOf(Shift("a", day("2026-11-12"), hour("18:00"), null))
        var v = pushing(2, pushed)
        v = SyncLogic.onAck(v, SyncCodec.parseAck("ASTACK 7777 2/2 OK 3")!!, 100L)
        assertNotNull(v.sync.pending)                         // il manque la partie 1
        assertEquals(emptyList<Shift>(), v.baseline)
        v = SyncLogic.onAck(v, SyncCodec.parseAck("ASTACK 7777 1/2 OK 2")!!, 200L)
        assertNull(v.sync.pending)
        assertEquals(pushed, v.baseline)
        assertEquals(200L, v.sync.lastPushAt)
        assertTrue(v.sync.lastResult.contains("à jour"))
    }

    @Test fun `un refus du relais est signale et la reference ne bouge pas`() {
        var v = pushing(2, listOf(Shift("a", day("2026-11-12"), hour("18:00"), null)))
        v = SyncLogic.onAck(v, SyncCodec.parseAck("ASTACK 7777 1/2 ERR Médecin « Zorro » inconnu")!!, 1L)
        assertNull(v.sync.pending)
        assertEquals(emptyList<Shift>(), v.baseline)
        assertTrue(v.sync.lastResult.contains("refusé") && v.sync.lastResult.contains("Zorro"))
    }

    @Test fun `un accuse d'une autre demande est ignore`() {
        val v = pushing(1, emptyList())
        assertSame(v, SyncLogic.onAck(v, SyncCodec.parseAck("ASTACK 1111 1/1 OK 1")!!, 1L))
        assertNull(SyncCodec.parseAck("ASTACK nimporte quoi"))
        assertNull(SyncCodec.parseAck("Bonjour"))
    }

    @Test fun `une demande sans reponse expire apres 10 minutes`() {
        val v = Snapshot(sync = SyncState(pending = PendingSync("PULL", "1", 0L)))
        assertSame(v, SyncLogic.expire(v, SyncLogic.TIMEOUT_MS - 1))
        val e = SyncLogic.expire(v, SyncLogic.TIMEOUT_MS + 1)
        assertNull(e.sync.pending)
        assertTrue(e.sync.lastResult.startsWith("Pas de réponse"))
    }
}

class MessagesTest {
    private val g = Doctor("g", "Dr Garde", "07 44 44 44 44")
    private val doctors = listOf(STANDARD_DOCTOR, Doctor("m", "Dr Martin", "0611111111"), Doctor("s", "Dr Soir", "0622222222"), g)
    private val base = Snapshot(
        doctors = doctors,
        shifts = listOf(
            Shift("1", day("2026-11-09"), hour("12:00"), "m"),
            Shift("2", day("2026-11-09"), hour("17:00"), "s"),
            Shift("3", day("2026-11-09"), hour("18:00"), "g"),
        ),
        settings = Settings(mode = Mode.RELAY, enabled = true, msgEnabled = true),
    )

    /** Le renvoi actuellement en place est celui de [who] (la bascule est faite). */
    private fun switchedTo(who: Doctor, snap: Snapshot = base) =
        snap.copy(state = ApplyState(lastCommand = Codes.forDoctor(who), lastOk = true))

    private fun ids(s: Snapshot, d: String, t: String) = Messages.due(s, inst(d, t), PARIS2).map { it.rule.id to it.doctor.id }

    @Test fun `a 12h le medecin du midi est prevenu une fois la bascule faite`() {
        assertEquals(listOf("midi" to "m"), ids(switchedTo(doctors[1]), "2026-11-09", "12:00:30"))
        // bascule pas encore faite (le renvoi pointe encore sur le standard) : on attend
        assertEquals(emptyList<Pair<String, String>>(), ids(switchedTo(STANDARD_DOCTOR), "2026-11-09", "12:00:30"))
        // bascule en échec : on n'affirme pas qu'elle est faite
        val failed = base.copy(state = ApplyState(lastCommand = Codes.forDoctor(doctors[1]), lastOk = false))
        assertEquals(emptyList<Pair<String, String>>(), ids(failed, "2026-11-09", "12:00:30"))
    }

    @Test fun `a 17h le medecin de l'apres-midi est prevenu et celui du soir rappele`() {
        assertEquals(setOf("apm" to "s", "rappel" to "g"), ids(switchedTo(doctors[2]), "2026-11-09", "17:00:10").toSet())
        // le rappel n'attend pas la bascule : il part même si elle n'est pas encore faite
        assertEquals(listOf("rappel" to "g"), ids(switchedTo(STANDARD_DOCTOR), "2026-11-09", "17:00:10"))
    }

    @Test fun `a 18h l'astreinte commence`() {
        assertEquals(listOf("soir" to "g"), ids(switchedTo(g), "2026-11-09", "18:00:05"))
    }

    @Test fun `un rappel annonce a l'avance est perime une fois le creneau commence`() {
        // téléphone éteint à 17h, rallumé à 17h55 : le rappel part encore ; à 18h05 il n'a plus de sens
        assertEquals(listOf("rappel" to "g"), ids(switchedTo(STANDARD_DOCTOR), "2026-11-09", "17:55:00"))
        assertEquals(emptyList<Pair<String, String>>(), ids(switchedTo(STANDARD_DOCTOR), "2026-11-09", "18:05:00"))
    }

    @Test fun `jamais deux fois le meme message`() {
        val sent = switchedTo(doctors[1]).copy(sentMessages = listOf("2026-11-09|midi"))
        assertEquals(emptyList<Pair<String, String>>(), ids(sent, "2026-11-09", "12:00:30"))
    }

    @Test fun `un message trop en retard n'est plus envoye`() {
        val s = switchedTo(doctors[1])
        assertEquals(1, Messages.due(s, inst("2026-11-09", "13:29"), PARIS2).size)   // 89 min de retard
        assertEquals(0, Messages.due(s, inst("2026-11-09", "13:31"), PARIS2).size)   // 91 min
        assertEquals(0, Messages.due(s, inst("2026-11-09", "11:59"), PARIS2).size)   // pas encore l'heure
    }

    @Test fun `personne n'est prevenu quand c'est le standard ou qu'il n'y a personne`() {
        // le 10/11 : rien de saisi, le standard tient tout
        assertEquals(emptyList<Pair<String, String>>(), ids(switchedTo(STANDARD_DOCTOR), "2026-11-10", "12:00:10"))
        val off = base.copy(shifts = listOf(Shift("o", day("2026-11-09"), hour("12:00"), null)))
        assertEquals(emptyList<Pair<String, String>>(), ids(off, "2026-11-09", "12:00:10"))
    }

    @Test fun `rien n'est envoye si la fonction est coupee ou si ce n'est pas un relais`() {
        val s = switchedTo(doctors[1])
        assertTrue(Messages.due(s.copy(settings = s.settings.copy(msgEnabled = false)), inst("2026-11-09", "12:00:30"), PARIS2).isEmpty())
        assertTrue(Messages.due(s.copy(settings = s.settings.copy(enabled = false)), inst("2026-11-09", "12:00:30"), PARIS2).isEmpty())
        assertTrue(Messages.due(s.copy(settings = s.settings.copy(mode = Mode.VIEWER)), inst("2026-11-09", "12:00:30"), PARIS2).isEmpty())
        val rules = s.settings.msgRules.map { it.copy(enabled = false) }
        assertTrue(Messages.due(s.copy(settings = s.settings.copy(msgRules = rules)), inst("2026-11-09", "12:00:30"), PARIS2).isEmpty())
    }

    @Test fun `les textes sont personnalises`() {
        val s = switchedTo(g)
        val soir = Messages.due(s, inst("2026-11-09", "18:00:05"), PARIS2).single()
        assertEquals("Bonjour Dr Garde, la bascule est faite : ton astreinte commence (18h-demain 9h).", soir.text)
        assertEquals("0744444444", soir.phone)
        val rappel = Messages.due(s, inst("2026-11-09", "17:00:05"), PARIS2).single { it.rule.id == "rappel" }
        assertEquals("Rappel Dr Garde : tu es d'astreinte ce soir a partir de 18h jusqu'a demain 9h.", rappel.text)
        assertEquals("lun 09/11", Messages.render("{date}", g, day("2026-11-09"), hour("12:00"), DEFAULT_SLOTS))
    }

    @Test fun `fin de creneau`() {
        assertEquals("14h", Messages.endText(hour("12:00"), DEFAULT_SLOTS))
        assertEquals("18h", Messages.endText(hour("17:00"), DEFAULT_SLOTS))
        assertEquals("demain 9h", Messages.endText(hour("18:00"), DEFAULT_SLOTS))
        assertEquals("", Messages.endText(hour("18:00"), emptyList()))
        assertEquals("9h30", Messages.hourText(hour("09:30")))
    }

    @Test fun `un numero invalide est signale`() {
        val bad = base.copy(doctors = doctors.map { if (it.id == "g") it.copy(phone = "pas un numero") else it })
        // le rappel (sans attente de bascule) est dû, mais le numéro est inutilisable : signalé par phone == null
        val rappel = Messages.due(switchedTo(STANDARD_DOCTOR, bad), inst("2026-11-09", "17:00:05"), PARIS2).single { it.rule.id == "rappel" }
        assertNull(rappel.phone)
        // quant au message « bascule faite », il ne part pas : la bascule vers ce numéro est impossible
        assertTrue(Messages.due(switchedTo(g, bad), inst("2026-11-09", "18:00:05"), PARIS2).isEmpty())
    }

    @Test fun `prochain envoi pour programmer l'alarme`() {
        assertEquals(inst("2026-11-09", "12:00"), Messages.nextDue(base, inst("2026-11-09", "11:00"), PARIS2))
        assertEquals(inst("2026-11-09", "17:00"), Messages.nextDue(base, inst("2026-11-09", "12:30"), PARIS2))
        assertEquals(inst("2026-11-10", "12:00"), Messages.nextDue(base, inst("2026-11-09", "18:30"), PARIS2))
        assertNull(Messages.nextDue(base.copy(settings = base.settings.copy(msgEnabled = false)), inst("2026-11-09", "11:00"), PARIS2))
    }
}

class StateJsonTest {
    @Test fun `aller-retour complet avec mode, synchro et messages`() {
        val snap = Snapshot(
            doctors = listOf(STANDARD_DOCTOR, Doctor("d1", "Dr Martin", "0611111111")),
            shifts = listOf(Shift("s1", day("2026-11-06"), hour("12:00"), "d1")),
            settings = Settings(
                mode = Mode.VIEWER, relayNumber = "06 00 00 00 00", smsPin = "1234",
                msgEnabled = true,
                msgRules = listOf(MessageRule("r1", false, hour("11:30"), hour("12:00"), true, "Salut {nom}")),
            ),
            baseline = listOf(Shift("b1", day("2026-11-06"), hour("18:00"), null)),
            sync = SyncState(
                lastPullAt = 11L, lastPushAt = 22L, lastResult = "ok",
                pending = PendingSync(
                    "PUSH", "7777", 33L, total = 3, chunks = mapOf(1 to "OK", 3 to "OK"), cellsSent = 9,
                    pushedShifts = listOf(Shift("p1", day("2026-11-07"), hour("17:00"), "d1")),
                ),
            ),
            sentMessages = listOf("2026-11-06|midi", "2026-11-06|soir"),
        )
        assertEquals(snap, SnapshotJson.fromJson(SnapshotJson.toJson(snap)))
    }

    @Test fun `premier lancement sans fichier le role est a choisir`() {
        assertEquals(Mode.NONE, Snapshot().settings.mode)
        assertTrue(!Snapshot().settings.isRelay && !Snapshot().settings.isViewer)
    }
}
