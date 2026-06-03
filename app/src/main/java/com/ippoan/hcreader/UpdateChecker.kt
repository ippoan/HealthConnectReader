package com.ippoan.hcreader

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * gh-pages の静的ファイル `latest.json` を fetch して新バージョンを検出する。
 *
 * 起動時に MainActivity から `check()` を呼び、結果がきたら AlertDialog 表示。
 * 失敗 (network / parse) は silent skip (`null` 返す)。
 *
 * **api.github.com は使わない** (Refs #21)。release.yml が gh-pages root に
 * `latest.json` を吐く (= APK と同じ Pages 配信)。github.com の web/Pages は
 * REST API の未認証レート制限 (60/hr/IP) 枠とは別なので、起動毎に叩いても
 * 制限を食わない。よって「24h 間引き cache」は不要 — 取得した tag を
 * `BuildConfig.VERSION_CODE` と比べるだけ。再 nag 抑制 (= 同じ版で何度も
 * ダイアログを出さない) は MainActivity 側で `last_seen_tag` を保存して行う。
 *
 * tag 形式は `v<versionName>+<runNumber>` (例: `v0.1.0+50`、release.yml 生成)。
 * 比較は `versionCode` (= `+` 以降の run_number) を `BuildConfig.VERSION_CODE`
 * と突き合わせる (dev ローカルビルドは 1 固定 → 常に更新通知が出る)。
 *
 * Refs #21
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val LATEST_JSON_URL =
        "https://ippoan.github.io/HealthConnectReader/latest.json"
    private const val TIMEOUT_MS = 5000

    data class UpdateInfo(
        val tagName: String,       // 例: "v0.1.0+50"
        val versionName: String,   // 例: "0.1.0"
        val versionCode: Int,      // 例: 50 (= tag の "+50" 部分 = CI の run_number)
        val htmlUrl: String,       // Release ページ URL (空可)
        val apkUrl: String?,       // app-release.apk の直 DL URL (無ければ null)
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
        val conn = (URL(LATEST_JSON_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ippoan-HealthConnectReader/${BuildConfig.VERSION_NAME}")
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "latest.json returned $code")
                return null
            }
            val body = conn.inputStream.bufferedReader().readText()
            return parseLatest(body)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Visible for testing — gh-pages `latest.json` を UpdateInfo に変換。
     *
     * 期待形:
     * ```json
     * {"tag":"v0.1.0+50","versionName":"0.1.0","versionCode":50,
     *  "apkUrl":"https://ippoan.github.io/HealthConnectReader/app-release.apk",
     *  "htmlUrl":"https://github.com/ippoan/HealthConnectReader/releases/tag/v0.1.0+50"}
     * ```
     * `versionName` / `versionCode` が欠けていても tag (`v<name>+<run>`) から
     * 復元する (= 後方互換)。
     */
    internal fun parseLatest(json: String): UpdateInfo? {
        val obj = JSONObject(json)
        val tag = obj.optString("tag").takeIf { it.isNotEmpty() } ?: return null
        // tag = "v<versionName>+<runNumber>" (例: v0.1.0+50)
        val versionFromTag = tag.removePrefix("v").substringBefore("+")
        val runFromTag = tag.substringAfter("+", "0").toIntOrNull() ?: 0
        val versionName = obj.optString("versionName").takeIf { it.isNotEmpty() } ?: versionFromTag
        val versionCode = if (obj.has("versionCode")) obj.optInt("versionCode", runFromTag) else runFromTag
        val apkUrl = obj.optString("apkUrl").takeIf { it.isNotEmpty() }
        val htmlUrl = obj.optString("htmlUrl")
        return UpdateInfo(
            tagName = tag,
            versionName = versionName,
            versionCode = versionCode,
            htmlUrl = htmlUrl,
            apkUrl = apkUrl,
        )
    }
}
