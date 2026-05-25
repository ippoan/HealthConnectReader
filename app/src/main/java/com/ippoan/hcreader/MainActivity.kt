package com.ippoan.hcreader

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissions = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(SpeedRecord::class),
    )

    private lateinit var client: HealthConnectClient
    private lateinit var output: TextView

    private val requestPerms = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(permissions)) {
            read()
        } else {
            output.text = "権限が足りません: $granted"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val btn = Button(this).apply { text = "今日のデータを読む" }
        output = TextView(this).apply {
            setPadding(24, 24, 24, 24)
            textSize = 12f
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(btn)
            addView(output)
        }
        setContentView(ScrollView(this).apply { addView(container) })

        // Health Connect が未インストール / 未対応の端末ではクラッシュさせず案内する。
        val status = HealthConnectClient.getSdkStatus(this)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            output.text = "Health Connect が利用できません (status=$status)"
            btn.isEnabled = false
            return
        }

        client = HealthConnectClient.getOrCreate(this)
        btn.setOnClickListener {
            lifecycleScope.launch {
                val granted = client.permissionController.getGrantedPermissions()
                if (granted.containsAll(permissions)) {
                    read()
                } else {
                    requestPerms.launch(permissions)
                }
            }
        }
    }

    private fun read() = lifecycleScope.launch {
        output.text = "読取中..."
        output.text = runCatching {
            HealthReader(client).readToday()
        }.getOrElse { e ->
            "読取失敗: ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
