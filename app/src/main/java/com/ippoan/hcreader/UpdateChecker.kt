package com.ippoan.hcreader

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Release API を polling して新バージョンを検出する。
 *
 * 起動時に MainActivity から `check()` を呼び、結果がきたら AlertDialog 表示。
 * 失敗 (network / parse / rate limit) は silent skip (`null` 返す)。
 *
 * tag 形式は `v<versionName>+<runNumber>` (例: `v0.1.0+42`、release.yml で生成)。
 * 比較は **runNumber 部分** を抜いて BuildConfig.VERSION_CODE と比較する
 * (= CI の build.gradle.kts sed で versionCode に run_number が焼かれている前提)。
 *
 * Refs #18
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val RELEASES_LATEST_URL =
        "https://api.github.com/repos/ippoan/HealthConnectReader/releases/latest"
    private const val TIMEOUT_MS = 5000

    data class UpdateInfo(
        val tagName: String,       // 例: "v0.1.0+42"
        val versionName: String,   // 例: "0.1.0"
        val versionCode: Int,      // 例: 42 (= tag の "+42" 部分 = CI の run_number)
        val htmlUrl: String,       // Release ページ URL
        val apkUrl: String?,       // app-release.apk asset の直 DL URL (無ければ null)
    )

    /**
     * 最新 release を取得し、現在の VERSION_CODE より大きければ UpdateInfo を返す。
     * 同等 or 取得失敗時は null。
     */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val info = fetchLatest() ?: return@withContext null
            if (info.versionCode > BuildConfig.VERSION_CODE) {
                Log.i(TAG, "update available: ${info.tagName} (current=${BuildConfig.VERSION_CODE})")
                info
            } else {
                Log.d(TAG, "up to date: tag=${info.tagName} current=${BuildConfig.VERSION_CODE}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "check failed (silent)", e)
            null
        }
    }

    private fun fetchLatest(): UpdateInfo? {
        val conn = (URL(RELEASES_LATEST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ippoan-HealthConnectReader/${BuildConfig.VERSION_NAME}")
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "github releases/latest returned $code")
                return null
            }
            val body = conn.inputStream.bufferedReader().readText()
            return parseRelease(body)
        } finally {
            conn.disconnect()
        }
    }

    /** Visible for testing — release API JSON を UpdateInfo に変換。 */
    internal fun parseRelease(json: String): UpdateInfo? {
        val obj = JSONObject(json)
        val tag = obj.optString("tag_name").takeIf { it.isNotEmpty() } ?: return null
        // tag = "v<versionName>+<runNumber>" (例: v0.1.0+42)
        val versionPart = tag.removePrefix("v").substringBefore("+")
        val runPart = tag.substringAfter("+", "0").toIntOrNull() ?: 0
        val htmlUrl = obj.optString("html_url")
        // assets[] から `*.apk` を探す
        var apkUrl: String? = null
        val assets = obj.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (name.endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url").takeIf { it.isNotEmpty() }
                    break
                }
            }
        }
        return UpdateInfo(
            tagName = tag,
            versionName = versionPart,
            versionCode = runPart,
            htmlUrl = htmlUrl,
            apkUrl = apkUrl,
        )
    }
}
