package fr.astreinte.relais.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/** Sérialisation JSON maison (pas de dépendance supplémentaire). */
object SnapshotJson {

    fun toJson(s: Snapshot): String = JSONObject().apply {
        put("version", 1)
        put("doctors", JSONArray().apply {
            s.doctors.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("phone", it.phone))
            }
        })
        put("shifts", JSONArray().apply {
            s.shifts.forEach {
                put(
                    JSONObject()
                        .put("id", it.id)
                        .put("date", it.date.toString())
                        .put("time", it.time.toString())
                        .put("doctorId", it.doctorId ?: JSONObject.NULL)
                )
            }
        })
        put(
            "settings",
            JSONObject()
                .put("enabled", s.settings.enabled)
                .put("defaultTime", s.settings.defaultTime.toString())
                .put("subscriptionId", s.settings.subscriptionId)
        )
        put(
            "state",
            JSONObject()
                .put("lastCommand", s.state.lastCommand ?: JSONObject.NULL)
                .put("lastOk", s.state.lastOk)
                .put("lastAt", s.state.lastAt)
                .put("lastMessage", s.state.lastMessage)
                .put("attempts", s.state.attempts)
        )
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
        val doctors = root.optJSONArray("doctors").toList { o ->
            Doctor(o.getString("id"), o.getString("name"), o.getString("phone"))
        }
        val shifts = root.optJSONArray("shifts").toList { o ->
            Shift(
                id = o.getString("id"),
                date = LocalDate.parse(o.getString("date")),
                time = LocalTime.parse(o.getString("time")),
                doctorId = if (o.isNull("doctorId")) null else o.getString("doctorId"),
            )
        }
        val settings = root.optJSONObject("settings")?.let { o ->
            Settings(
                enabled = o.optBoolean("enabled", false),
                defaultTime = o.optString("defaultTime", "08:00").let { LocalTime.parse(it) },
                subscriptionId = o.optInt("subscriptionId", -1),
            )
        } ?: Settings()
        val state = root.optJSONObject("state")?.let { o ->
            ApplyState(
                lastCommand = if (o.isNull("lastCommand")) null else o.getString("lastCommand"),
                lastOk = o.optBoolean("lastOk", true),
                lastAt = o.optLong("lastAt", 0L),
                lastMessage = o.optString("lastMessage", ""),
                attempts = o.optInt("attempts", 0),
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
        return Snapshot(doctors, shifts, settings, state, log)
    }

    private fun <T> JSONArray?.toList(map: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).map { map(getJSONObject(it)) }
    }
}
