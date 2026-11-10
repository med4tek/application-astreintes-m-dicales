package fr.astreinte.relais.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/** Sérialisation JSON maison (pas de dépendance supplémentaire). */
object SnapshotJson {

    fun toJson(s: Snapshot): String = JSONObject().apply {
        put("version", 2)
        put("doctors", JSONArray().apply {
            s.doctors.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("phone", it.phone))
            }
        })
        put("shifts", shiftsToJson(s.shifts))
        put("baseline", shiftsToJson(s.baseline))
        put("settings", settingsToJson(s.settings))
        put(
            "state",
            JSONObject()
                .put("lastCommand", s.state.lastCommand ?: JSONObject.NULL)
                .put("lastOk", s.state.lastOk)
                .put("lastAt", s.state.lastAt)
                .put("lastMessage", s.state.lastMessage)
                .put("attempts", s.state.attempts)
                .put("lastSmsAt", s.state.lastSmsAt)
                .put("lastSmsWasCommand", s.state.lastSmsWasCommand)
                .put("lastTarget", s.state.lastTarget)
        )
        put("aliases", JSONObject().apply { s.aliases.forEach { (k, v) -> put(k, v) } })
        put("sync", syncToJson(s.sync))
        put("sentMessages", JSONArray(s.sentMessages))
        put("history", JSONArray().apply {
            s.history.forEach {
                put(
                    JSONObject()
                        .put("at", it.at).put("who", it.who).put("via", it.via).put("cells", it.cells)
                        .put("lines", JSONArray(it.lines))
                )
            }
        })
        put("log", JSONArray().apply {
            s.log.forEach {
                put(
                    JSONObject()
                        .put("at", it.at)
                        .put("trigger", it.trigger)
                        .put("command", it.command)
                        .put("ok", it.ok)
                        .put("message", it.message)
                )
            }
        })
    }.toString()

    fun fromJson(text: String): Snapshot {
        val root = JSONObject(text)
        val stored = root.optJSONArray("doctors").toList { o ->
            Doctor(o.getString("id"), o.getString("name"), o.getString("phone"))
        }
        // Le standard est toujours présent (migration des données d'une ancienne version comprise)
        val doctors = if (stored.any { it.id == STANDARD_ID }) stored else listOf(STANDARD_DOCTOR) + stored
        val settings = root.optJSONObject("settings")?.let { settingsFromJson(it) } ?: Settings()
        val state = root.optJSONObject("state")?.let { o ->
            ApplyState(
                lastCommand = if (o.isNull("lastCommand")) null else o.getString("lastCommand"),
                lastOk = o.optBoolean("lastOk", true),
                lastAt = o.optLong("lastAt", 0L),
                lastMessage = o.optString("lastMessage", ""),
                attempts = o.optInt("attempts", 0),
                lastSmsAt = o.optLong("lastSmsAt", 0L),
                lastSmsWasCommand = o.optBoolean("lastSmsWasCommand", false),
                lastTarget = o.optString("lastTarget", ""),
            )
        } ?: ApplyState()
        val log = root.optJSONArray("log").toList { o ->
            LogEntry(
                at = o.getLong("at"),
                trigger = o.optString("trigger", ""),
                command = o.optString("command", ""),
                ok = o.optBoolean("ok", false),
                message = o.optString("message", ""),
            )
        }
        return Snapshot(
            doctors = doctors,
            shifts = shiftsFromJson(root.optJSONArray("shifts")),
            settings = settings,
            state = state,
            log = log,
            baseline = shiftsFromJson(root.optJSONArray("baseline")),
            sync = root.optJSONObject("sync")?.let { syncFromJson(it) } ?: SyncState(),
            aliases = root.optJSONObject("aliases")?.let { a -> a.keys().asSequence().associateWith { a.getString(it) } } ?: emptyMap(),
            history = root.optJSONArray("history").toList { o ->
                HistoryEntry(
                    at = o.optLong("at", 0L),
                    who = o.optString("who", ""),
                    via = o.optString("via", ""),
                    cells = o.optInt("cells", 0),
                    lines = o.optJSONArray("lines")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                )
            },
            sentMessages = root.optJSONArray("sentMessages")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                ?: emptyList(),
        )
    }

    // ------------------------------------------------------------------ morceaux

    private fun shiftsToJson(shifts: List<Shift>) = JSONArray().apply {
        shifts.forEach {
            put(
                JSONObject()
                    .put("id", it.id)
                    .put("date", it.date.toString())
                    .put("time", it.time.toString())
                    .put("doctorId", it.doctorId ?: JSONObject.NULL)
            )
        }
    }

    private fun shiftsFromJson(a: JSONArray?): List<Shift> = a.toList { o ->
        Shift(
            id = o.getString("id"),
            date = LocalDate.parse(o.getString("date")),
            time = LocalTime.parse(o.getString("time")),
            doctorId = if (o.isNull("doctorId")) null else o.getString("doctorId"),
        )
    }

    private fun settingsToJson(st: Settings) = JSONObject()
        .put("enabled", st.enabled)
        .put("defaultTime", st.defaultTime.toString())
        .put("subscriptionId", st.subscriptionId)
        .put("useDirectUssd", st.useDirectUssd)
        .put("slots", JSONArray().apply {
            st.slots.forEach {
                put(
                    JSONObject()
                        .put("time", it.time.toString())
                        .put("label", it.label)
                        .put("default", it.defaultTargetId ?: JSONObject.NULL)
                )
            }
        })
        .put("smsEnabled", st.smsEnabled)
        .put("smsPin", st.smsPin)
        .put("smsExtraNumbers", st.smsExtraNumbers)
        .put("smsAllowDoctors", st.smsAllowDoctors)
        .put("smsReply", st.smsReply)
        .put("mode", st.mode)
        .put("relayNumber", st.relayNumber)
        .put("msgEnabled", st.msgEnabled)
        .put("changeNotify", st.changeNotify)
        .put("changeTextNew", st.changeTextNew)
        .put("changeTextOld", st.changeTextOld)
        .put("userName", st.userName)
        .put("msgRules", JSONArray().apply {
            st.msgRules.forEach {
                put(
                    JSONObject()
                        .put("id", it.id)
                        .put("enabled", it.enabled)
                        .put("at", it.at.toString())
                        .put("slotTime", it.slotTime.toString())
                        .put("confirmsSwitch", it.confirmsSwitch)
                        .put("text", it.text)
                )
            }
        })

    private fun settingsFromJson(o: JSONObject) = Settings(
        enabled = o.optBoolean("enabled", false),
        defaultTime = o.optString("defaultTime", "08:00").let { LocalTime.parse(it) },
        subscriptionId = o.optInt("subscriptionId", -1),
        useDirectUssd = o.optBoolean("useDirectUssd", false),
        slots = o.optJSONArray("slots")?.toList { so ->
            Slot(
                time = LocalTime.parse(so.getString("time")),
                label = so.optString("label", ""),
                defaultTargetId = if (so.isNull("default")) null else so.getString("default"),
            )
        } ?: DEFAULT_SLOTS,
        smsEnabled = o.optBoolean("smsEnabled", false),
        smsPin = o.optString("smsPin", ""),
        smsExtraNumbers = o.optString("smsExtraNumbers", ""),
        smsAllowDoctors = o.optBoolean("smsAllowDoctors", true),
        smsReply = o.optBoolean("smsReply", false),
        // Un fichier de réglages existant sans "mode" vient d'une version d'avant : c'était un relais
        mode = if (o.has("mode")) o.optString("mode", Mode.NONE) else Mode.RELAY,
        relayNumber = o.optString("relayNumber", ""),
        msgEnabled = o.optBoolean("msgEnabled", false),
        changeNotify = o.optBoolean("changeNotify", true),
        changeTextNew = o.optString("changeTextNew", DEFAULT_CHANGE_NEW),
        changeTextOld = o.optString("changeTextOld", DEFAULT_CHANGE_OLD),
        userName = o.optString("userName", ""),
        msgRules = o.optJSONArray("msgRules")?.toList { r ->
            MessageRule(
                id = r.getString("id"),
                enabled = r.optBoolean("enabled", true),
                at = LocalTime.parse(r.getString("at")),
                slotTime = LocalTime.parse(r.getString("slotTime")),
                confirmsSwitch = r.optBoolean("confirmsSwitch", false),
                text = r.optString("text", ""),
            )
        } ?: DEFAULT_MESSAGE_RULES,
    )

    private fun syncToJson(sy: SyncState) = JSONObject()
        .put("lastPullAt", sy.lastPullAt)
        .put("lastPushAt", sy.lastPushAt)
        .put("lastResult", sy.lastResult)
        .put("pending", sy.pending?.let { p ->
            JSONObject()
                .put("kind", p.kind)
                .put("id", p.id)
                .put("startedAt", p.startedAt)
                .put("total", p.total)
                .put("cellsSent", p.cellsSent)
                .put("chunks", JSONObject().apply { p.chunks.forEach { (k, v) -> put(k.toString(), v) } })
                .put("pushedShifts", shiftsToJson(p.pushedShifts))
        } ?: JSONObject.NULL)

    private fun syncFromJson(o: JSONObject) = SyncState(
        lastPullAt = o.optLong("lastPullAt", 0L),
        lastPushAt = o.optLong("lastPushAt", 0L),
        lastResult = o.optString("lastResult", ""),
        pending = o.optJSONObject("pending")?.let { p ->
            val chunks = p.optJSONObject("chunks")
            PendingSync(
                kind = p.getString("kind"),
                id = p.getString("id"),
                startedAt = p.optLong("startedAt", 0L),
                total = p.optInt("total", 0),
                cellsSent = p.optInt("cellsSent", 0),
                chunks = chunks?.keys()?.asSequence()?.associate { it.toInt() to chunks.getString(it) } ?: emptyMap(),
                pushedShifts = shiftsFromJson(p.optJSONArray("pushedShifts")),
            )
        },
    )

    private fun <T> JSONArray?.toList(map: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).map { map(getJSONObject(it)) }
    }
}
