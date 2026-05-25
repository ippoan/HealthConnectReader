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
}
