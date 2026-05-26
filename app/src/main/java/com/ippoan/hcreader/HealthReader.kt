package com.ippoan.hcreader

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HealthReader(private val client: HealthConnectClient) {

    private fun todayRange(): TimeRangeFilter {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().atStartOfDay(zone).toInstant()
        val end = Instant.now()
        return TimeRangeFilter.between(start, end)
    }

    // 診断モード: フィルタなしで全件読み、値と出所 (packageName) を吐く。
    // Life Fitness の packageName が判明したら dataOriginFilter を足してフィルタする。
    suspend fun readToday(): String {
        val sb = StringBuilder()
        val range = todayRange()

        val sessions = client.readRecords(
            ReadRecordsRequest(ExerciseSessionRecord::class, range)
        ).records
        sb.appendLine("=== ExerciseSession (${sessions.size}) ===")
        sessions.forEach {
            sb.appendLine(
                "  ${it.startTime}–${it.endTime} type=${it.exerciseType} " +
                    "title=${it.title} src=${it.metadata.dataOrigin.packageName}"
            )
        }

        val distances = client.readRecords(
            ReadRecordsRequest(DistanceRecord::class, range)
        ).records
        sb.appendLine("=== Distance (${distances.size}) ===")
        distances.forEach {
            sb.appendLine(
                "  ${it.startTime}–${it.endTime} " +
                    "${it.distance.inKilometers}km src=${it.metadata.dataOrigin.packageName}"
            )
        }

        val speeds = client.readRecords(
            ReadRecordsRequest(SpeedRecord::class, range)
        ).records
        sb.appendLine("=== Speed (${speeds.size}) ===")
        speeds.forEach { rec ->
            sb.appendLine(
                "  ${rec.startTime}–${rec.endTime} " +
                    "src=${rec.metadata.dataOrigin.packageName} samples=${rec.samples.size}"
            )
            rec.samples.forEach { s ->
                sb.appendLine("     ${s.time} ${s.speed.inKilometersPerHour}km/h")
            }
        }

        Log.d("HCReader", sb.toString())
        return sb.toString()
    }

    // Worker `/api/upload` に送る JSON 表現。診断モード (filter なし) のまま、
    // 端末ローカル時間ではなく ISO-8601 文字列で出す。
    //
    // 各 readRecords は **per-type try-catch** で囲み partial 読取を許容する。
    // Android 16 + HC SDK 1.1.0 で、診断モード (= dataOriginFilter なし) かつ
    // `READ_HEALTH_DATA_HISTORY` 付きの time-range query が他データ型
    // (例: SKIN_TEMPERATURE = record type 37) の権限を要求する事象を回避するため。
    // 落ちた section は top-level `readErrors` に格納し、worker は `days` だけ
    // 見るので無害 (unknown field は ignore)。Refs #16
    suspend fun readTodayJson(): String {
        val range = todayRange()
        val out = JSONObject()
        out.put("date", LocalDate.now(ZoneId.systemDefault()).toString())
        out.put("collectedAt", Instant.now().toString())
        val readErrors = JSONObject()

        val sessionsArr = JSONArray()
        try {
            client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range))
                .records
                .forEach {
                    sessionsArr.put(
                        JSONObject()
                            .put("startTime", it.startTime.toString())
                            .put("endTime", it.endTime.toString())
                            .put("exerciseType", it.exerciseType)
                            .put("title", it.title ?: JSONObject.NULL)
                            .put("source", it.metadata.dataOrigin.packageName)
                    )
                }
        } catch (e: Exception) {
            recordReadError(readErrors, "sessions", e)
        }
        out.put("sessions", sessionsArr)

        val distArr = JSONArray()
        try {
            client.readRecords(ReadRecordsRequest(DistanceRecord::class, range))
                .records
                .forEach {
                    distArr.put(
                        JSONObject()
                            .put("startTime", it.startTime.toString())
                            .put("endTime", it.endTime.toString())
                            .put("km", it.distance.inKilometers)
                            .put("source", it.metadata.dataOrigin.packageName)
                    )
                }
        } catch (e: Exception) {
            recordReadError(readErrors, "distances", e)
        }
        out.put("distances", distArr)

        val speedArr = JSONArray()
        try {
            client.readRecords(ReadRecordsRequest(SpeedRecord::class, range))
                .records
                .forEach { rec ->
                    val samples = JSONArray()
                    rec.samples.forEach { s ->
                        samples.put(
                            JSONObject()
                                .put("time", s.time.toString())
                                .put("kmh", s.speed.inKilometersPerHour)
                        )
                    }
                    speedArr.put(
                        JSONObject()
                            .put("startTime", rec.startTime.toString())
                            .put("endTime", rec.endTime.toString())
                            .put("source", rec.metadata.dataOrigin.packageName)
                            .put("samples", samples)
                    )
                }
        } catch (e: Exception) {
            recordReadError(readErrors, "speeds", e)
        }
        out.put("speeds", speedArr)

        if (readErrors.length() > 0) out.put("readErrors", readErrors)
        return out.toString()
    }

    /**
     * 過去 [days] 日分 (= 今日含む) を日単位でまとめて JSON にする。
     * 出力形式は worker `/api/upload-batch` の入力に直接渡せる:
     * `{ days: [ { date: "yyyy-MM-dd", payload: { sessions, distances, speeds } } ] }`
     *
     * HC への ReadRecordsRequest は 1 回だけ全レコード読み (時間範囲フィルタのみ)、
     * クライアント側で startTime の local date でバケットに振り分ける。
     *
     * `readTodayJson` と同じく per-type try-catch で partial 読取を許容
     * (Refs #16)。落ちた section は top-level `readErrors` に格納。
     */
    suspend fun readPastDaysJson(days: Int): String {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val rangeStart = today.minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant()
        val rangeEnd = Instant.now()
        val range = TimeRangeFilter.between(rangeStart, rangeEnd)

        // date → JSONArray
        val sessionsByDay = HashMap<String, JSONArray>()
        val distancesByDay = HashMap<String, JSONArray>()
        val speedsByDay = HashMap<String, JSONArray>()
        val readErrors = JSONObject()

        fun bucket(map: HashMap<String, JSONArray>, date: String): JSONArray =
            map.getOrPut(date) { JSONArray() }

        fun dateOf(inst: Instant): String =
            inst.atZone(zone).toLocalDate().toString()

        try {
            client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range))
                .records
                .forEach {
                    bucket(sessionsByDay, dateOf(it.startTime)).put(
                        JSONObject()
                            .put("startTime", it.startTime.toString())
                            .put("endTime", it.endTime.toString())
                            .put("exerciseType", it.exerciseType)
                            .put("title", it.title ?: JSONObject.NULL)
                            .put("source", it.metadata.dataOrigin.packageName)
                    )
                }
        } catch (e: Exception) {
            recordReadError(readErrors, "sessions", e)
        }

        try {
            client.readRecords(ReadRecordsRequest(DistanceRecord::class, range))
                .records
                .forEach {
                    bucket(distancesByDay, dateOf(it.startTime)).put(
                        JSONObject()
                            .put("startTime", it.startTime.toString())
                            .put("endTime", it.endTime.toString())
                            .put("km", it.distance.inKilometers)
                            .put("source", it.metadata.dataOrigin.packageName)
                    )
                }
        } catch (e: Exception) {
            recordReadError(readErrors, "distances", e)
        }

        try {
            client.readRecords(ReadRecordsRequest(SpeedRecord::class, range))
                .records
                .forEach { rec ->
                    val samples = JSONArray()
                    rec.samples.forEach { s ->
                        samples.put(
                            JSONObject()
                                .put("time", s.time.toString())
                                .put("kmh", s.speed.inKilometersPerHour)
                        )
                    }
                    bucket(speedsByDay, dateOf(rec.startTime)).put(
                        JSONObject()
                            .put("startTime", rec.startTime.toString())
                            .put("endTime", rec.endTime.toString())
                            .put("source", rec.metadata.dataOrigin.packageName)
                            .put("samples", samples)
                    )
                }
        } catch (e: Exception) {
            recordReadError(readErrors, "speeds", e)
        }

        // バケット union を date 昇順で並べ、データ無し日も含めて連続的に出力
        // (= worker 側 R2 で「読み取り 0 件」の証跡を残す)。
        val daysArr = JSONArray()
        for (i in 0 until days) {
            val d = today.minusDays((days - 1 - i).toLong())
            val key = d.toString()
            val payload = JSONObject()
                .put("date", key)
                .put("collectedAt", Instant.now().toString())
                .put("sessions", sessionsByDay[key] ?: JSONArray())
                .put("distances", distancesByDay[key] ?: JSONArray())
                .put("speeds", speedsByDay[key] ?: JSONArray())
            daysArr.put(JSONObject().put("date", key).put("payload", payload))
        }
        val result = JSONObject().put("days", daysArr)
        if (readErrors.length() > 0) result.put("readErrors", readErrors)
        return result.toString()
    }

    private fun recordReadError(into: JSONObject, section: String, e: Throwable) {
        val msg = "${e.javaClass.simpleName}: ${(e.message ?: "").take(240)}"
        Log.w("HCReader", "readRecords[$section] failed: $msg", e)
        into.put(section, msg)
    }
}
