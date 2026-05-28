package com.ippoan.hcreader

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.launch
import java.io.File

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
        // 過去 30 日 (grant 時から) 以前のデータ取得に必要。Manifest にも対応する
        // android.permission.health.READ_HEALTH_DATA_HISTORY を宣言済。Refs #6
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
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

        // SwipeRefreshLayout で WebView を包んで「スワイプ↓で再読み込み」を提供する。
        // pull-to-refresh の発火は webView.reload() を叩くだけ。WebView が再 load
        // 時に Bearer ヘッダが必要なので reload() ではなく loadUrl(..., headers)
        // を再実行する。
        // また WebView 自身がスクロール可能な領域だと swipe が誤発火するので、
        // scrollY === 0 (= 最上部) の時だけ SwipeRefresh を enable する。
        val swipe = SwipeRefreshLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    swipe.isRefreshing = true
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    swipe.isRefreshing = false
                }
            }
            addJavascriptInterface(
                HCBridge(applicationContext) {
                    runOnUiThread { requestPerms.launch(permissions) }
                },
                "HC",
            )
            // スクロール位置を監視: 最上部以外では SwipeRefresh を無効化する
            // (= ページ内スクロールが pull-to-refresh に誤検知されないようにする)
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                swipe.isEnabled = scrollY == 0
            }
            // 初回 GET / に Bearer ヘッダを注入する。auth-worker JWT cookie 認証
            // (hcreader-worker PR #16) 導入後、ヘッダ無しの WebView 起動だと
            // /oauth/google/redirect に飛ばされ Google が embedded WebView を
            // `disallowed_useragent` で恒久 block する事象を回避するため。
            // 注入は initial load のみで効くが、worker の `GET /` は 200 直接
            // 返し (= 後続 navigation なし) なので十分。XHR 経由の API call は
            // 引き続き `HC.getUploadToken()` 経由で Bearer を送る。
            // Refs ippoan/HealthConnectReader#14
            loadUrl(
                BuildConfig.WORKER_URL,
                mapOf("Authorization" to "Bearer ${BuildConfig.UPLOAD_TOKEN}"),
            )
        }
        // pull-to-refresh: Bearer ヘッダ付きで GET / を再 load
        swipe.setOnRefreshListener {
            webView.loadUrl(
                BuildConfig.WORKER_URL,
                mapOf("Authorization" to "Bearer ${BuildConfig.UPLOAD_TOKEN}"),
            )
        }
        swipe.addView(webView)
        setContentView(swipe)

        // 起動時に 4 種類 (EXERCISE / DISTANCE / SPEED / HISTORY) のうち 1 つでも
        // 未 grant なら自動で HC permission dialog を出す。uninstall → install
        // 後は全 permission が reset されるため、user が WebView 上のボタンを押す
        // 前に prompt を出して操作を 1 ステップ減らす (Refs #6 UX 改善)。
        ensurePermissionsGranted()

        // 起動時に GitHub Release を polling して新版あれば AlertDialog 表示。
        // 失敗 (network / rate limit 60/hr / parse) は silent skip (Refs #18)。
        checkForUpdate()
    }

    private fun ensurePermissionsGranted() {
        lifecycleScope.launch {
            val client = HealthConnectClient.getOrCreate(this@MainActivity)
            val granted = runCatching { client.permissionController.getGrantedPermissions() }
                .getOrDefault(emptySet())
            if (!granted.containsAll(permissions)) {
                requestPerms.launch(permissions)
            }
        }
    }

    private fun checkForUpdate() {
        lifecycleScope.launch {
            val info = UpdateChecker.check() ?: return@launch
            if (isFinishing || isDestroyed) return@launch
            val apkUrl = info.apkUrl
            val hasApk = apkUrl != null
            AlertDialog.Builder(this@MainActivity)
                .setTitle("更新があります: ${info.tagName}")
                .setMessage(
                    "現在 v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n" +
                        "最新 ${info.tagName} (build ${info.versionCode})\n\n" +
                        if (hasApk) "ダウンロードしてインストールします。"
                        else "リリースページを開きます。"
                )
                .setPositiveButton(if (hasApk) "更新" else "リリースページ") { _, _ ->
                    if (apkUrl != null) {
                        startUpdateDownload(apkUrl, info.tagName)
                    } else {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)))
                        } catch (_: Exception) {
                            // ハンドラ無しは黙殺 (= ブラウザ未インストール等)
                        }
                    }
                }
                .setNegativeButton("後で", null)
                .show()
        }
    }

    /**
     * APK を DownloadManager で DL し、完了後にパッケージインストーラを起動する。
     *
     * 旧実装は `ACTION_VIEW` で URL をブラウザに渡すだけで、DL 後にインストールへ
     * 移行しなかった (Refs #30)。アプリ内 DL → FileProvider content:// URI →
     * `ACTION_VIEW` (package-archive) でインストーラを自動起動する。
     *
     * Android 8+ は「このアプリからのインストール」許可が必要。未許可なら設定へ
     * 誘導して return する (ユーザーが許可後にもう一度「更新」を押す想定)。
     */
    private fun startUpdateDownload(apkUrl: String, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(
                this,
                "「このアプリからのインストール」を許可してから、もう一度「更新」を押してください",
                Toast.LENGTH_LONG,
            ).show()
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"),
                    )
                )
            } catch (_: Exception) {
                // 該当設定画面が無い端末は黙殺
            }
            return
        }

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        // 同名で上書き DL するため、古い APK が残っていると FileProvider が古い file を
        // 掴む。事前に消す。
        val dest = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_APK_NAME)
        dest.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("HealthConnectReader $tag")
            setMimeType(MIME_APK)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(
                this@MainActivity, Environment.DIRECTORY_DOWNLOADS, DOWNLOAD_APK_NAME,
            )
        }
        val downloadId = dm.enqueue(request)
        Toast.makeText(this, "ダウンロード中…", Toast.LENGTH_SHORT).show()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return
                try {
                    ctx.unregisterReceiver(this)
                } catch (_: Exception) {
                    // 既に解除済みは無視
                }
                val ok = dm.query(DownloadManager.Query().setFilterById(downloadId)).use { c ->
                    c.moveToFirst() &&
                        c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                        DownloadManager.STATUS_SUCCESSFUL
                }
                if (!ok) {
                    Toast.makeText(ctx, "ダウンロードに失敗しました", Toast.LENGTH_LONG).show()
                    return
                }
                installApk(dest)
            }
        }
        // ACTION_DOWNLOAD_COMPLETE は system (別プロセス) からの broadcast なので、
        // Android 14+ (API 34+) では RECEIVER_EXPORTED 指定が必須。ContextCompat が
        // API レベルを吸収する。
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /** DL 済み APK を FileProvider 経由でインストーラに渡す。 */
    private fun installApk(apk: File) {
        if (!apk.exists()) {
            Toast.makeText(this, "APK が見つかりません", Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_APK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "インストーラを起動できませんでした", Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val DOWNLOAD_APK_NAME = "hcreader-update.apk"
        const val MIME_APK = "application/vnd.android.package-archive"
    }
}
