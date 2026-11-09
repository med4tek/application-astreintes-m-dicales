package fr.astreinte.relais.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Une case du planning dont la valeur diffère entre le téléphone consultant et le relais. */
data class Change(val date: LocalDate, val time: LocalTime, val edit: CellEdit)

/** Planning complet reçu du relais. */
data class DecodedPlanning(val doctors: List<Doctor>, val slots: List<Slot>, val shifts: List<Shift>, val days: Int)

data class DataChunk(val id: String, val index: Int, val total: Int, val payload: String)
data class Ack(val id: String, val index: Int, val total: Int, val ok: Boolean, val message: String)

/**
 * Synchronisation par SMS entre le relais (maître) et les téléphones de consultation.
 *
 *  - Récupérer : le consultant envoie « AST PIN SYNC 4821 » ; le relais répond par des SMS
 *    « ASTDATA 4821 i/n » contenant médecins, créneaux et planning (voir [encodeData]).
 *  - Envoyer : le consultant envoie les cases modifiées depuis la dernière synchro sous forme de
 *    commandes « AST PIN PUSH 4821 i/n 12/11/26 12:00=Dr_Martin … » ; le relais répond
 *    « ASTACK 4821 i/n OK 7 » (ou ERR). Quand tout est acquitté, le consultant est « à jour ».
 */
object SyncCodec {
    private val DAY = DateTimeFormatter.BASIC_ISO_DATE            // 20261112
    private val SMS_DATE = DateTimeFormatter.ofPattern("dd/MM/yy")
    private val HHMM = DateTimeFormatter.ofPattern("HHmm")
    const val DATA_PREFIX = "ASTDATA"
    const val ACK_PREFIX = "ASTACK"

    /** Taille visée par SMS (≈ 3 segments) : assez court pour rester fiable chez tous les opérateurs. */
    const val CHUNK_CHARS = 430

    // ------------------------------------------------------------------ relais -> consultant

    /**
     * Planning du relais à transmettre : 2 jours en arrière à 40 jours en avant, uniquement les
     * saisies (les valeurs par défaut se déduisent des créneaux). Découpé en SMS autonomes.
     */
    fun encodeData(snap: Snapshot, requestId: String, today: LocalDate, maxChars: Int = CHUNK_CHARS): List<String> {
        val from = today.minusDays(2)
        val to = today.plusDays(40)
        val refs = HashMap<String, String>()                       // identifiant de médecin -> référence courte
        var next = 1
        snap.doctors.forEach { refs[it.id] = if (it.id == STANDARD_ID) "std" else (next++).toString() }

        val lines = ArrayList<String>()
        lines += "W ${from.format(DAY)} ${to.format(DAY)}"
        snap.settings.slots.sortedBy { it.time }.forEach { s ->
            lines += "S ${s.time.format(HHMM)}|${clean(s.label)}|${s.defaultTargetId?.let { refs[it] } ?: "-"}"
        }
        snap.doctors.forEach { d -> lines += "D ${refs[d.id]}|${clean(d.name)}|${clean(d.phone)}" }
        snap.shifts.filter { it.date in from..to }
            .groupBy { it.date }.toSortedMap()
            .forEach { (date, list) ->
                val cells = list.sortedBy { it.time }.joinToString(" ") { sh ->
                    "${sh.time.format(HHMM)}=${sh.doctorId?.let { refs[it] } ?: "-"}"
                }
                lines += "E ${date.format(DAY)} $cells"
            }

        // Emballage glouton : une ligne n'est jamais coupée
        val budget = maxChars - "$DATA_PREFIX $requestId 99/99\n".length
        val bodies = ArrayList<StringBuilder>().apply { add(StringBuilder()) }
        for (line in lines) {
            val cur = bodies.last()
            if (cur.isNotEmpty() && cur.length + 1 + line.length > budget) bodies += StringBuilder()
            if (bodies.last().isNotEmpty()) bodies.last().append('\n')
            bodies.last().append(line)
        }
        val n = bodies.size
        return bodies.mapIndexed { i, b -> "$DATA_PREFIX $requestId ${i + 1}/$n\n$b" }
    }

    fun parseDataChunk(body: String): DataChunk? {
        val head = body.substringBefore('\n').trim().split(Regex("\\s+"))
        if (head.size != 3 || head[0] != DATA_PREFIX) return null
        val part = Regex("^(\\d+)/(\\d+)$").matchEntire(head[2]) ?: return null
        val i = part.groupValues[1].toInt()
        val n = part.groupValues[2].toInt()
        if (i < 1 || n < 1 || i > n) return null
        return DataChunk(head[1], i, n, body.substringAfter('\n', ""))
    }

    /** Reconstitue le planning à partir du texte de tous les morceaux, dans l'ordre. */
    fun decode(joined: String): DecodedPlanning? {
        val doctorRefs = LinkedHashMap<String, Doctor>()
        val slotsRaw = ArrayList<Triple<LocalTime, String, String>>()
        val shiftsRaw = ArrayList<Triple<LocalDate, LocalTime, String>>()
        var sawWindow = false
        for (line in joined.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val kind = line.firstOrNull() ?: continue
            val rest = line.drop(2)
            try {
                when (kind) {
                    'W' -> sawWindow = true
                    'S' -> {
                        val (t, label, def) = rest.split('|').also { require(it.size == 3) }
                        slotsRaw += Triple(LocalTime.parse(t, HHMM), label, def)
                    }
                    'D' -> {
                        val p = rest.split('|').also { require(it.size == 3) }
                        val id = if (p[0] == "std") STANDARD_ID else "d${p[0]}"
                        doctorRefs[p[0]] = Doctor(id, p[1], p[2])
                    }
                    'E' -> {
                        val parts = rest.split(' ')
                        val date = LocalDate.parse(parts[0], DAY)
                        parts.drop(1).filter { it.isNotEmpty() }.forEach { cell ->
                            val (t, ref) = cell.split('=').also { require(it.size == 2) }
                            shiftsRaw += Triple(date, LocalTime.parse(t, HHMM), ref)
                        }
                    }
                }
            } catch (e: Exception) {
                return null   // données corrompues : on n'applique rien
            }
        }
        if (!sawWindow) return null
        fun idOf(ref: String): String? = if (ref == "-") null else doctorRefs[ref]?.id
        val slots = slotsRaw.map { (t, label, def) -> Slot(t, label, idOf(def)) }
        val shifts = shiftsRaw.map { (d, t, ref) -> Shift(UUID.randomUUID().toString(), d, t, idOf(ref)) }
        val doctors = doctorRefs.values.toList().let { list ->
            if (list.any { it.id == STANDARD_ID }) list else listOf(STANDARD_DOCTOR) + list
        }
        return DecodedPlanning(doctors, slots, shifts, shifts.map { it.date }.distinct().size)
    }

    // ------------------------------------------------------------------ consultant -> relais

    /** Cases dont la valeur locale diffère de la dernière version connue du relais. */
    fun diff(local: List<Shift>, baseline: List<Shift>): List<Change> {
        val l = local.associate { (it.date to it.time) to it.doctorId }
        val b = baseline.associate { (it.date to it.time) to it.doctorId }
        val changes = ArrayList<Change>()
        for (key in (l.keys + b.keys)) {
            val inLocal = key in l
            val inBase = key in b
            when {
                inLocal && (!inBase || l[key] != b[key]) -> changes += Change(key.first, key.second, CellEdit.Set(l[key]))
                !inLocal && inBase -> changes += Change(key.first, key.second, CellEdit.Clear)
            }
        }
        return changes.sortedWith(compareBy({ it.date }, { it.time }))
    }

    /**
     * Commandes SMS à envoyer au relais, une chaîne par SMS. Les changements portant sur un médecin
     * inconnu localement sont ignorés. [maxChars] limite la taille de chaque SMS.
     */
    fun pushMessages(
        changes: List<Change>,
        doctors: List<Doctor>,
        pin: String,
        requestId: String,
        maxChars: Int = CHUNK_CHARS,
    ): List<String> {
        fun token(id: String?): String? = when {
            id == null -> "OFF"
            id == STANDARD_ID -> "STD"
            else -> doctors.firstOrNull { it.id == id }?.name?.let { nameToken(it) }
        }
        // Une portion "jj/mm/aa HH:mm=Nom HH:mm=Nom" par jour
        val groups = changes.groupBy { it.date }.toSortedMap().mapNotNull { (date, list) ->
            val cells = list.sortedBy { it.time }.mapNotNull { c ->
                val value = when (val e = c.edit) {
                    CellEdit.Clear -> "DEF"
                    is CellEdit.Set -> token(e.doctorId)
                } ?: return@mapNotNull null
                "${c.time.format(DateTimeFormatter.ofPattern("HH:mm"))}=$value"
            }
            if (cells.isEmpty()) null else "${date.format(SMS_DATE)} ${cells.joinToString(" ")}"
        }
        if (groups.isEmpty()) return emptyList()

        val budget = maxChars - "AST $pin PUSH $requestId 99/99 ".length
        val packs = ArrayList<StringBuilder>().apply { add(StringBuilder()) }
        for (g in groups) {
            if (packs.last().isNotEmpty() && packs.last().length + 1 + g.length > budget) packs += StringBuilder()
            if (packs.last().isNotEmpty()) packs.last().append(' ')
            packs.last().append(g)
        }
        val n = packs.size
        return packs.mapIndexed { i, p -> "AST $pin PUSH $requestId ${i + 1}/$n $p" }
    }

    fun countCells(groups: List<EditGroup>): Int =
        groups.sumOf { it.dates.size * it.edits.size + if (it.clearDay) it.dates.size else 0 }

    fun ackText(id: String, part: Int, total: Int, ok: Boolean, detail: String) =
        "$ACK_PREFIX $id $part/$total ${if (ok) "OK" else "ERR"} ${detail.take(120)}".trim()

    fun parseAck(body: String): Ack? {
        val m = Regex("^$ACK_PREFIX\\s+(\\d+)\\s+(\\d+)/(\\d+)\\s+(OK|ERR)\\b\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)
            .matchEntire(body.trim()) ?: return null
        return Ack(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4] == "OK", m.groupValues[5].trim())
    }

    /** Nom de médecin utilisable comme un seul mot dans une commande SMS. */
    private fun nameToken(name: String) =
        name.trim().replace(Regex("[=,;|\\r\\n]"), "").replace(Regex("\\s+"), "_")

    private fun clean(s: String) = s.replace(Regex("[|\\r\\n]"), " ").trim()
}

/** Transitions d'état d'une synchronisation, côté téléphone consultant (fonctions pures). */
object SyncLogic {
    /** Délai au-delà duquel une synchronisation sans réponse est considérée comme perdue. */
    const val TIMEOUT_MS = 10 * 60 * 1000L

    fun newRequestId(): String = (1000..9999).random().toString()

    /** Un morceau du planning est arrivé. */
    fun onDataChunk(s: Snapshot, c: DataChunk, now: Long): Snapshot {
        val p = s.sync.pending ?: return s
        if (p.kind != "PULL" || p.id != c.id) return s
        val chunks = p.chunks + (c.index to c.payload)
        val pending = p.copy(total = c.total, chunks = chunks)
        if ((1..c.total).any { it !in chunks }) return s.copy(sync = s.sync.copy(pending = pending))

        val joined = (1..c.total).joinToString("\n") { chunks.getValue(it) }
        val decoded = SyncCodec.decode(joined)
            ?: return s.copy(sync = s.sync.copy(pending = null, lastResult = "Réponse du relais illisible : réessayez."))
        return s.copy(
            doctors = decoded.doctors,
            shifts = decoded.shifts,
            baseline = decoded.shifts,
            settings = s.settings.copy(slots = decoded.slots),
            sync = s.sync.copy(
                lastPullAt = now,
                pending = null,
                lastResult = "Planning reçu du relais (${decoded.days} jour(s) saisi(s)).",
            ),
        )
    }

    /** Un accusé de réception du relais est arrivé. */
    fun onAck(s: Snapshot, a: Ack, now: Long): Snapshot {
        val p = s.sync.pending ?: return s
        if (p.kind != "PUSH" || p.id != a.id) return s
        if (!a.ok) {
            return s.copy(
                sync = s.sync.copy(
                    pending = null,
                    lastResult = "Le relais a refusé la partie ${a.index}/${a.total} : ${a.message.ifBlank { "erreur" }}",
                )
            )
        }
        val acks = p.chunks + (a.index to "OK")
        if (acks.size < p.total) return s.copy(sync = s.sync.copy(pending = p.copy(chunks = acks)))
        return s.copy(
            baseline = p.pushedShifts,
            sync = s.sync.copy(
                lastPushAt = now,
                pending = null,
                lastResult = "Planning à jour sur le relais (${p.cellsSent} modification(s) confirmée(s)).",
            ),
        )
    }

    /** Une synchronisation restée sans réponse trop longtemps : on le signale et on libère les boutons. */
    fun expire(s: Snapshot, now: Long): Snapshot {
        val p = s.sync.pending ?: return s
        if (now - p.startedAt < TIMEOUT_MS) return s
        val what = if (p.kind == "PULL") "demande du planning" else "envoi des modifications"
        return s.copy(sync = s.sync.copy(pending = null, lastResult = "Pas de réponse du relais à la $what. Vérifiez le numéro, le PIN et que le relais est allumé."))
    }

    fun unsentCount(s: Snapshot): Int = SyncCodec.diff(s.shifts, s.baseline).size

}
