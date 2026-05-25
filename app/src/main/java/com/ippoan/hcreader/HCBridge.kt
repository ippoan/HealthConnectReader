package com.ippoan.hcreader

import android.content.Context
import android.webkit.JavascriptInterface
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * `window.HC.*` として WebView に注入される native bridge。
 *
 * WebView の JS は worker (`src/ui.ts`) が host する。Bridge の contract が
 * worker 側 HTML と乖離すると UI が動かないので、`src/ui.ts` と同期して維持する
 * (Refs ippoan/HealthConnectReader#6)。
 *
 * @JavascriptInterface メソッドは WebView の background thread から呼ばれるため
 * Activity context を直接触らない。Health Connect 読取は `runBlocking` で同期化
 * (WorkManager の `UploadWorker` 経由ではなく WebView UI から直接読む場合のため)。
 */
class HCBridge(
    private val appContext: Context,
    private val onRequestPermission: () -> Unit,
) {

    @JavascriptInterface
    fun readToday(): String {
        val status = HealthConnectClient.getSdkStatus(appContext)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            return errJson("hc_unavailable", "Health Connect status=$status")
        }
        val client = HealthConnectClient.getOrCreate(appContext)
        return runBlocking {
            runCatching { HealthReader(client).readTodayJson() }
                .getOrElse { errJson("read_failed", "${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /**
     * 過去 [days] 日分 (= 今日含む) をまとめて読み worker `/api/upload-batch` の
     * 入力形式 `{ days: [{date, payload}, ...] }` を返す。Refs #6
     */
    @JavascriptInterface
    fun readPastDays(days: Int): String {
        if (days < 1 || days > 366) {
            return errJson("invalid_days", "days must be 1..366, got $days")
        }
        val status = HealthConnectClient.getSdkStatus(appContext)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            return errJson("hc_unavailable", "Health Connect status=$status")
        }
        val client = HealthConnectClient.getOrCreate(appContext)
        return runBlocking {
            runCatching { HealthReader(client).readPastDaysJson(days) }
                .getOrElse { errJson("read_failed", "${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    @JavascriptInterface
    fun getUploadToken(): String = BuildConfig.UPLOAD_TOKEN

    @JavascriptInterface
    fun requestPermission() {
        onRequestPermission()
    }

    @JavascriptInterface
    fun scheduleDailyUpload() {
        UploadWorker.schedule(appContext)
    }

    @JavascriptInterface
    fun cancelDailyUpload() {
        UploadWorker.cancel(appContext)
    }

    @JavascriptInterface
    fun isDailyUploadScheduled(): Boolean = UploadWorker.isScheduled(appContext)

    private fun errJson(code: String, msg: String): String =
        JSONObject().put("error", code).put("message", msg).toString()
}
