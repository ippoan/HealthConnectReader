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
    suspend fun readTodayJson(): String {
        val range = todayRange()
        val out = JSONObject()
        out.put("date", LocalDate.now(ZoneId.systemDefault()).toString())
        out.put("collectedAt", Instant.now().toString())

        val sessionsArr = JSONArray()
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
        out.put("sessions", sessionsArr)

        val distArr = JSONArray()
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
        out.put("distances", distArr)

        val speedArr = JSONArray()
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
        out.put("speeds", speedArr)

        return out.toString()
    }

    /**
     * 過去 [days] 日分 (= 今日含む) を日単位でまとめて JSON にする。
     * 出力形式は worker `/api/upload-batch` の入力に直接渡せる:
     * `{ days: [ { date: "yyyy-MM-dd", payload: { sessions, distances, speeds } } ] }`
     *
     * HC への ReadRecordsRequest は 1 回だけ全レコード読み (時間範囲フィルタのみ)、
     * クライアント側で startTime の local date でバケットに振り分ける。
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

        fun bucket(map: HashMap<String, JSONArray>, date: String): JSONArray =
            map.getOrPut(date) { JSONArray() }

        fun dateOf(inst: Instant): String =
            inst.atZone(zone).toLocalDate().toString()

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
        return JSONObject().put("days", daysArr).toString()
    }
}
