// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import org.json.JSONArray
import org.json.JSONObject

internal object RainAlertStateCodec {
    fun encode(state: RainAlertState): String = JSONObject().put("version", 1)
        .put("events", JSONArray(state.events.takeLast(64).map { event ->
            JSONObject().put("location", event.locationIdentity).put("start", event.start)
                .put("end", event.end ?: JSONObject.NULL).put("likelihood", event.likelihood.name)
                .put("timestamps", JSONArray(event.timestamps.sorted().take(192)))
                .put("lastNotifiedAt", event.lastNotifiedAt).put("endingNotified", event.endingNotified)
                .put("cancelled", event.cancelled).put("lastSeenAt", event.lastSeenAt)
        })).toString()

    fun decode(raw: String?): RainAlertState {
        if (raw == null || raw.length > 256_000) return RainAlertState()
        return runCatching {
            val json = JSONObject(raw)
            if (json.optInt("version") != 1) return RainAlertState()
            val events = json.getJSONArray("events")
            RainAlertState((0 until events.length().coerceAtMost(64)).mapNotNull { index ->
                runCatching {
                    val event = events.getJSONObject(index)
                    val location = event.getString("location").takeIf { it.isNotBlank() && it.length <= 100 } ?: return@mapNotNull null
                    val start = event.getLong("start").takeIf { it > 0 } ?: return@mapNotNull null
                    val end = if (event.isNull("end")) null else event.getLong("end").takeIf { it > start }
                    val times = event.getJSONArray("timestamps")
                    RainEventMemory(locationIdentity = location, start = start, end = end,
                        likelihood = RainLikelihood.valueOf(event.getString("likelihood")),
                        timestamps = (0 until times.length().coerceAtMost(192)).mapNotNull { position ->
                            times.optLong(position, 0).takeIf { it > 0 }
                        }.toSet().ifEmpty { setOf(start) },
                        lastNotifiedAt = event.getLong("lastNotifiedAt").takeIf { it > 0 } ?: return@mapNotNull null,
                        endingNotified = event.optBoolean("endingNotified"), cancelled = event.optBoolean("cancelled"),
                        lastSeenAt = event.getLong("lastSeenAt").takeIf { it > 0 } ?: return@mapNotNull null)
                }.getOrNull()
            })
        }.getOrDefault(RainAlertState())
    }
}
