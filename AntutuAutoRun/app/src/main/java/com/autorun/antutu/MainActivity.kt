package com.autorun.antutu

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.ComponentName
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvRoundCount: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnStartStop: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnRetryUpload: Button
    private lateinit var etCooldown: EditText
    private lateinit var etMaxRounds: EditText
    private lateinit var etUserName: EditText
    private lateinit var tvScoreHistory: TextView

    private val logLines = mutableListOf<String>()
    private val maxLogLines = 50

    private val serviceName = "com.autorun.antutu/com.autorun.antutu.AntutuAccessibilityService"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvRoundCount = findViewById(R.id.tvRoundCount)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvLog = findViewById(R.id.tvLog)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnRetryUpload = findViewById(R.id.btnRetryUpload)
        etCooldown = findViewById(R.id.etCooldown)
        etMaxRounds = findViewById(R.id.etMaxRounds)
        etUserName = findViewById(R.id.etUserName)
        tvScoreHistory = findViewById(R.id.tvScoreHistory)

        btnAccessibility.setOnClickListener {
            if (isAccessibilityEnabled()) {
                disableAccessibilityService()
            } else {
                enableAccessibilityService()
            }
        }

        btnStartStop.setOnClickListener {
            val service = AntutuAccessibilityService.instance
            if (service == null) {
                tvStatus.text = "请先开启无障碍服务"
                return@setOnClickListener
            }

            if (AntutuAccessibilityService.isRunning) {
                service.stopLoop()
                btnStartStop.text = "开始循环跑分"
            } else {
                val name = etUserName.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "请先输入上报名称", Toast.LENGTH_SHORT).show()
                    etUserName.requestFocus()
                    return@setOnClickListener
                }

                AlertDialog.Builder(this)
                    .setTitle("确认开始")
                    .setMessage("请确认设备已开启「极限性能测试模式」\n\n路径：设置 → 通用 → 关于 → 极限性能测试模式")
                    .setPositiveButton("已开启，开始跑分") { _, _ ->
                        val cooldown = etCooldown.text.toString().toIntOrNull() ?: 5
                        val maxRounds = etMaxRounds.text.toString().toIntOrNull() ?: 0
                        AntutuAccessibilityService.cooldownSeconds = cooldown
                        AntutuAccessibilityService.maxRounds = maxRounds
                        AntutuAccessibilityService.userName = name
                        service.startLoop()
                        btnStartStop.text = "停止"
                    }
                    .setNegativeButton("去开启") { _, _ ->
                        val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                        intent.setPackage("com.android.settings")
                        startActivity(intent)
                    }
                    .show()
            }
        }

        btnRetryUpload.setOnClickListener {
            val name = etUserName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "请先输入上报名称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val failedRecords = AntutuAccessibilityService.scoreHistory.filter { !it.uploaded }
            if (failedRecords.isEmpty()) {
                Toast.makeText(this, "没有需要重传的数据", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnRetryUpload.isEnabled = false
            btnRetryUpload.text = "上传中..."
            retryUploadAll(name, failedRecords, 0)
        }

        AntutuAccessibilityService.statusListener = { status ->
            runOnUiThread {
                tvStatus.text = status
                tvRoundCount.text = AntutuAccessibilityService.roundCount.toString()
            }
        }

        AntutuAccessibilityService.logListener = { line ->
            runOnUiThread {
                logLines.add(line)
                if (logLines.size > maxLogLines) {
                    logLines.removeAt(0)
                }
                tvLog.text = logLines.joinToString("\n")
            }
        }

        AntutuAccessibilityService.scoreListener = { _ ->
            runOnUiThread { refreshScoreHistory() }
        }
    }

    private fun retryUploadAll(
        name: String,
        records: List<AntutuAccessibilityService.ScoreRecord>,
        index: Int
    ) {
        if (index >= records.size) {
            runOnUiThread {
                btnRetryUpload.isEnabled = true
                btnRetryUpload.text = "重传失败项"
                refreshScoreHistory()
                val stillFailed = records.count { !it.uploaded }
                if (stillFailed == 0) {
                    Toast.makeText(this, "全部上传成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "仍有 $stillFailed 条上传失败", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        val record = records[index]
        val sn = FeishuUploader.getSerialNo()
        FeishuUploader.upload(name, record.total, record.time, sn) { success ->
            record.uploaded = success
            runOnUiThread { refreshScoreHistory() }
            retryUploadAll(name, records, index + 1)
        }
    }

    private fun refreshScoreHistory() {
        val history = AntutuAccessibilityService.scoreHistory
        if (history.isEmpty()) {
            tvScoreHistory.text = "暂无数据"
            return
        }
        val lines = history.map { s ->
            val uploadTag = when {
                s.uploaded -> "[已上传]"
                else -> "[未上传]"
            }
            "第${s.round}轮 ${s.total}  GPU:${s.gpu} 内存:${s.mem} CPU:${s.cpu} UX:${s.ux}  ${s.time}  $uploadTag"
        }
        tvScoreHistory.text = lines.joinToString("\n")
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()

        if (AntutuAccessibilityService.isRunning) {
            btnStartStop.text = "停止"
        } else {
            btnStartStop.text = "开始循环跑分"
        }
        tvRoundCount.text = AntutuAccessibilityService.roundCount.toString()
        refreshScoreHistory()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabledServices.contains(serviceName)
    }

    private fun allowRestrictedSettings() {
        // 等价于 Settings UI 里「允许受限设置」——以 UID 1000 调用才有效
        try {
            val appOps = getSystemService(AppOpsManager::class.java) ?: return
            val uid = packageManager.getApplicationInfo(packageName, 0).uid
            val method = appOps.javaClass.getMethod(
                "setMode", String::class.java, Int::class.java, String::class.java, Int::class.java
            )
            // 关键：设置 ACCESS_RESTRICTED_SETTINGS 以解除 Android 13+ 对侧载应用的无障碍限制
            method.invoke(appOps, "android:access_restricted_settings", uid, packageName, AppOpsManager.MODE_ALLOWED)
            method.invoke(appOps, "android:request_install_packages", uid, packageName, AppOpsManager.MODE_ALLOWED)
            method.invoke(appOps, "android:run_in_background", uid, packageName, AppOpsManager.MODE_ALLOWED)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "allowRestrictedSettings failed: $e")
        }
    }

    private fun enableAccessibilityService() {
        // 先执行「允许受限设置」（解除 H3C 对 accessibility settings 写入的限制）
        allowRestrictedSettings()

        // 尝试通过 AccessibilityManager API 直接启用（MANAGE_ACCESSIBILITY 权限，可绕过 DPM 策略）
        if (enableAccessibilityViaManager()) {
            Toast.makeText(this, "无障碍服务已开启", Toast.LENGTH_SHORT).show()
            tvAccessibilityStatus.postDelayed({ updateAccessibilityStatus() }, 1000)
            return
        }

        // 降级：直接写 Settings.Secure（需要 WRITE_SECURE_SETTINGS，UID 1000 下有效）
        try {
            val existing = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            val newValue = if (existing.isBlank()) {
                serviceName
            } else {
                "$existing:$serviceName"
            }

            Settings.Secure.putString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newValue
            )
            Settings.Secure.putInt(
                contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1
            )

            Toast.makeText(this, "无障碍服务已开启", Toast.LENGTH_SHORT).show()
            tvAccessibilityStatus.postDelayed({ updateAccessibilityStatus() }, 1000)
        } catch (e: SecurityException) {
            if (enableAccessibilityViaShell()) {
                Toast.makeText(this, "无障碍服务已开启(shell)", Toast.LENGTH_SHORT).show()
                tvAccessibilityStatus.postDelayed({ updateAccessibilityStatus() }, 1000)
            } else {
                Toast.makeText(this,
                    "权限不足，请通过ADB执行:\nadb shell pm grant com.autorun.antutu android.permission.WRITE_SECURE_SETTINGS",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun enableAccessibilityViaManager(): Boolean {
        return try {
            val am = getSystemService(AccessibilityManager::class.java) ?: return false
            val component = ComponentName(this, AntutuAccessibilityService::class.java)
            // setAccessibilityServiceEnabled 是 hidden API，使用反射调用
            // 需要 MANAGE_ACCESSIBILITY 权限，platform 签名的 system app 可绕过 DPM permitted list 限制
            val method = am.javaClass.getMethod("setAccessibilityServiceEnabled", ComponentName::class.java, Boolean::class.java)
            method.invoke(am, component, true)
            true
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "enableAccessibilityViaManager failed: $e")
            false
        }
    }

    private fun enableAccessibilityViaShell(): Boolean {
        return try {
            val existing = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val newValue = if (existing.isBlank()) serviceName else "$existing:$serviceName"

            val p1 = Runtime.getRuntime().exec(arrayOf(
                "settings", "put", "secure", "enabled_accessibility_services", newValue
            ))
            p1.waitFor()
            val p2 = Runtime.getRuntime().exec(arrayOf(
                "settings", "put", "secure", "accessibility_enabled", "1"
            ))
            p2.waitFor()
            p1.exitValue() == 0 && p2.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun disableAccessibilityService() {
        try {
            val existing = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            val newValue = existing.split(":")
                .filter { it != serviceName }
                .joinToString(":")

            Settings.Secure.putString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newValue
            )

            Toast.makeText(this, "无障碍服务已关闭", Toast.LENGTH_SHORT).show()
            tvAccessibilityStatus.postDelayed({ updateAccessibilityStatus() }, 1000)
        } catch (e: SecurityException) {
            Toast.makeText(this, "权限不足", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateAccessibilityStatus() {
        if (isAccessibilityEnabled() || AntutuAccessibilityService.instance != null) {
            tvAccessibilityStatus.text = "✓ 无障碍服务已开启"
            tvAccessibilityStatus.setTextColor(0xFF4CAF50.toInt())
            btnAccessibility.text = "关闭无障碍服务"
        } else {
            tvAccessibilityStatus.text = "⚠ 无障碍服务未开启"
            tvAccessibilityStatus.setTextColor(0xFFF44336.toInt())
            btnAccessibility.text = "一键开启无障碍服务"
        }
    }
}
