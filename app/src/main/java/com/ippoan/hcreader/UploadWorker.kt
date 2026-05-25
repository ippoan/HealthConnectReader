package com.ippoan.hcreader

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 1 日 1 回 Health Connect を読んで worker `/api/upload` に POST する WorkManager job。
 *
 * WorkManager の periodic work は端末再起動後も自動で再 enqueue されるので、
 * BootReceiver は不要。
 */
class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        return try {
            val status = HealthConnectClient.getSdkStatus(ctx)
            if (status != HealthConnectClient.SDK_AVAILABLE) {
                Log.w(TAG, "HC unavailable status=$status, retrying later")
                return Result.retry()
            }
            val client = HealthConnectClient.getOrCreate(ctx)
            val json = HealthReader(client).readTodayJson()
            val (code, body) = postUpload(json)
            if (code in 200..299) {
                Log.i(TAG, "upload ok body=$body")
                Result.success()
            } else {
                Log.w(TAG, "upload failed code=$code body=$body")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "upload exception", e)
            Result.retry()
        }
    }

    private fun postUpload(json: String): Pair<Int, String> {
        val url = URL(BuildConfig.WORKER_URL.trimEnd('/') + "/api/upload")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.UPLOAD_TOKEN}")
        conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }
            ?: ""
        return code to body
    }

    companion object {
        const val TAG = "HCUpload"
        const val WORK_NAME = "hc-daily-upload"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UploadWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        }

        fun isScheduled(ctx: Context): Boolean {
            return try {
                val infos = WorkManager.getInstance(ctx)
                    .getWorkInfosForUniqueWork(WORK_NAME)
                    .get()
                infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            } catch (_: Exception) {
                false
            }
        }
    }
}
