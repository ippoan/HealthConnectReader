package com.ippoan.hcreader

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
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
}
