package com.ippoan.hcreader

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SpeedRecord

/**
 * WebView ホスト Activity。worker `BuildConfig.WORKER_URL` を load し、
 * `window.HC` JsInterface 経由で native (Health Connect / WorkManager) を呼ばせる。
 * Refs ippoan/HealthConnectReader#6
 */
class MainActivity : ComponentActivity() {

    private val permissions = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(SpeedRecord::class),
    )

    private val requestPerms = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { /* granted result is observed by WebView の次回 readToday() で確認する */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = HealthConnectClient.getSdkStatus(this)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            val tv = TextView(this).apply {
                text = "Health Connect が利用できません (status=$status)"
                setPadding(48, 96, 48, 48)
            }
            setContentView(tv)
            return
        }

        val webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            addJavascriptInterface(
                HCBridge(applicationContext) {
                    runOnUiThread { requestPerms.launch(permissions) }
                },
                "HC",
            )
            loadUrl(BuildConfig.WORKER_URL)
        }
        setContentView(webView)
    }
}
