package com.autorun.antutu

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.*

class AntutuAccessibilityService : AccessibilityService() {

    companion object {
        const val ANTUTU_PKG = "com.antutu.ABenchMark"
        const val ACTION_RUN = "com.antutu.ABenchMark.RUN"

        var instance: AntutuAccessibilityService? = null
            private set

        var isRunning = false
            private set

        var roundCount = 0
            private set

        var cooldownSeconds = 5
        var maxRounds = 0  // 0 = 无限循环
        var userName = ""  // 飞书上传用的名字
        var statusListener: ((String) -> Unit)? = null
        var logListener: ((String) -> Unit)? = null
        var scoreListener: ((ScoreRecord) -> Unit)? = null
        val scoreHistory = mutableListOf<ScoreRecord>()

        private var currentState = State.IDLE
    }

    data class ScoreRecord(
        val round: Int,
        val total: String,
        val gpu: String,
        val mem: String,
        val cpu: String,
        val ux: String,
        val time: String,
        var uploaded: Boolean = false  // 飞书上传状态
    )

    enum class State {
        IDLE,           // 未启动
        LAUNCHING,      // 正在启动安兔兔
        TESTING,        // 跑分进行中
        READING_SCORE,  // 读取分数中
        COOLDOWN,       // 散热等待中
        WAITING_START   // 等待跑分开始
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // 防抖: 避免重复触发
    private var lastActionTime = 0L
    // 跑分开始时间，用于最短时间保护
    private var testingStartTime = 0L
    // 连续未检测到"停止测试"的次数
    private var noStopBtnCount = 0
    // 最短跑分时间（10分钟），在此之前不判定完成
    private val minTestDurationMs = 10 * 60 * 1000L
    // 需要连续多少次检测不到"停止测试"才判定完成
    private val confirmThreshold = 3

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        log("无障碍服务已连接")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        releaseWakeLock()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return
        val pkg = event.packageName?.toString()
        if (pkg != ANTUTU_PKG && pkg != "${ANTUTU_PKG}.full") return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                checkAntutuState()
            }
        }
    }

    override fun onInterrupt() {}

    fun startLoop() {
        isRunning = true
        roundCount = 0
        currentState = State.IDLE
        acquireWakeLock()
        log("开始循环跑分")
        updateStatus("启动中...")
        launchBenchmark()
    }

    fun stopLoop() {
        isRunning = false
        currentState = State.IDLE
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        log("已停止循环，共完成 $roundCount 轮")
        updateStatus("已停止")
    }

    private fun launchBenchmark() {
        if (!isRunning) return
        currentState = State.LAUNCHING
        updateStatus("启动安兔兔...")
        log("正在启动安兔兔跑分...")

        try {
            Runtime.getRuntime().exec(arrayOf("am", "force-stop", ANTUTU_PKG))
            Runtime.getRuntime().exec(arrayOf("am", "force-stop", "${ANTUTU_PKG}.full"))
        } catch (e: Exception) {
            log("force-stop失败: ${e.message}")
        }

        handler.postDelayed({
            if (!isRunning) return@postDelayed
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(ANTUTU_PKG, "com.android.module.app.ui.start.ABenchMarkStart")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
                log("已启动安兔兔，等待页面加载...")
            } catch (e: Exception) {
                log("启动安兔兔失败: ${e.message}")
            }
            currentState = State.WAITING_START
            handler.postDelayed({ pollForStartButton(0) }, 5000)
        }, 3000)
    }

    private fun pollForStartButton(attempt: Int) {
        if (!isRunning || currentState != State.WAITING_START) return

        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            log("无法获取窗口，第${attempt+1}次重试...")
            if (attempt < 12) {
                handler.postDelayed({ pollForStartButton(attempt + 1) }, 5000)
            } else {
                log("超时，尝试按返回键后重试...")
                performGlobalAction(GLOBAL_ACTION_BACK)
                handler.postDelayed({ launchBenchmark() }, 3000)
            }
            return
        }
        if (rootNode != null) {
            try {
                // 先处理可能的弹窗
                if (handleDialogs(rootNode)) return

                // 如果已经在跑分中了，直接进入监控
                if (findNodeByText(rootNode, "停止测试") != null) {
                    currentState = State.TESTING
                    updateStatus("跑分中...")
                    log("第 ${roundCount + 1} 轮跑分进行中")
                    startProgressMonitor()
                    return
                }

                // 查找"开始测试"/"立即测试"/"重新测试"按钮
                val startBtn = findNodeById(rootNode, "$ANTUTU_PKG:id/mainTestStart")
                    ?: findNodeByText(rootNode, "开始测试")
                    ?: findNodeByText(rootNode, "立即测试")
                    ?: findNodeByText(rootNode, "重新测试")
                if (startBtn != null) {
                    log("点击「${startBtn.text ?: "开始"}」")
                    clickNode(startBtn)
                    handler.postDelayed({ pollForStartButton(0) }, 5000)
                    return
                }

                // 如果在结果页面，点返回回到主页
                if (findNodeByText(rootNode, "安兔兔评测结果") != null) {
                    log("在结果页面，点击返回")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    handler.postDelayed({ pollForStartButton(0) }, 3000)
                    return
                }
            } finally {
                rootNode.recycle()
            }
        }

        // 还没找到，继续重试（最多60秒）
        if (attempt < 12) {
            handler.postDelayed({ pollForStartButton(attempt + 1) }, 5000)
        } else {
            log("超时未找到开始按钮，重试启动...")
            launchBenchmark()
        }
    }

    private fun checkAntutuState() {
        if (!isRunning) return

        val now = System.currentTimeMillis()
        // 防抖500ms
        if (now - lastActionTime < 500) return
        lastActionTime = now

        val rootNode = rootInActiveWindow ?: return

        try {
            when (currentState) {
                State.LAUNCHING, State.WAITING_START -> {
                    // 检查是否有弹窗需要处理
                    if (handleDialogs(rootNode)) return

                    // 检查是否已经在跑分中
                    if (findNodeByText(rootNode, "停止测试") != null) {
                        currentState = State.TESTING
                        testingStartTime = System.currentTimeMillis()
                        noStopBtnCount = 0
                        updateStatus("跑分中...")
                        log("第 ${roundCount + 1} 轮跑分进行中")
                        startProgressMonitor()
                        return
                    }

                    // 检查是否在主界面或结果页面，点击"开始测试"/"立即测试"/"重新测试"
                    val startBtn = findNodeById(rootNode, "$ANTUTU_PKG:id/mainTestStart")
                        ?: findNodeByText(rootNode, "开始测试")
                        ?: findNodeByText(rootNode, "立即测试")
                        ?: findNodeByText(rootNode, "重新测试")
                    if (startBtn != null) {
                        log("点击「${startBtn.text ?: "开始"}」")
                        clickNode(startBtn)
                        currentState = State.WAITING_START
                        handler.postDelayed({ checkAntutuState() }, 5000)
                        return
                    }

                    // 如果在结果页面，点返回回到主页
                    if (findNodeByText(rootNode, "安兔兔评测结果") != null) {
                        log("在结果页面，点击返回")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        handler.postDelayed({ checkAntutuState() }, 3000)
                        return
                    }
                }

                State.TESTING -> {
                    // 检查当前是否在3D插件中（仍在跑分）
                    val currentPkg = rootNode.packageName?.toString()
                    if (currentPkg == "${ANTUTU_PKG}.full") {
                        noStopBtnCount = 0
                        updateStatus("跑分中（3D测试）")
                        return
                    }

                    // 检查跑分是否还在进行
                    if (findNodeByText(rootNode, "停止测试") != null) {
                        noStopBtnCount = 0
                        val percentNode = findNodeById(rootNode, "$ANTUTU_PKG:id/mainTestPercent")
                        val percent = percentNode?.text?.toString() ?: "?"
                        updateStatus("跑分中 ${percent}%")
                        return
                    }

                    // "停止测试"按钮消失，可能是场景切换，不要急着判定完成
                    val elapsed = System.currentTimeMillis() - testingStartTime
                    noStopBtnCount++

                    // 保护：跑分不足10分钟，或连续确认次数不够，继续等待
                    if (elapsed < minTestDurationMs || noStopBtnCount < confirmThreshold) {
                        if (elapsed < minTestDurationMs) {
                            val remain = (minTestDurationMs - elapsed) / 1000
                            updateStatus("跑分中（${remain}s保护）")
                        }
                        return
                    }

                    // 额外确认：检查是否出现了结果页面标志
                    val hasResult = findNodeByText(rootNode, "重新测试") != null
                        || findNodeById(rootNode, "$ANTUTU_PKG:id/ViewGroupScore") != null
                        || findNodeByText(rootNode, "安兔兔评测结果") != null
                    if (!hasResult) {
                        // 没有结果页面标志，继续等待
                        updateStatus("跑分中（等待确认）")
                        return
                    }

                    // 确认跑分完成
                    noStopBtnCount = 0
                    currentState = State.READING_SCORE
                    log("跑分完成，等待读取成绩...")
                    handler.postDelayed({ readScore() }, 5000)
                }

                State.READING_SCORE -> {
                    // 正在等待读取分数，不处理
                }

                State.COOLDOWN, State.IDLE -> {
                    // 不需要处理
                }
            }
        } finally {
            rootNode.recycle()
        }
    }

    private fun startProgressMonitor() {
        // 定期主动检查进度（防止事件没触发的情况）
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isRunning || currentState != State.TESTING) return
                checkAntutuState()
                handler.postDelayed(this, 15_000) // 每15秒检查一次
            }
        }, 15_000)
    }

    private var readScoreRetry = 0

    private fun readScore() {
        if (!isRunning) return
        val rootNode = rootInActiveWindow

        if (rootNode == null) {
            if (readScoreRetry < 3) {
                readScoreRetry++
                handler.postDelayed({ readScore() }, 3000)
            } else {
                log("无法读取成绩，跳过")
                readScoreRetry = 0
                onBenchmarkComplete(null)
            }
            return
        }

        try {
            // 结果页面：ViewGroupScore 里的第一个 TextViewScoreValue 是总分
            val totalScoreNode = findNodeById(rootNode, "$ANTUTU_PKG:id/ViewGroupScore")
            val totalScore = if (totalScoreNode != null) {
                val scoreVal = findNodeById(totalScoreNode, "$ANTUTU_PKG:id/TextViewScoreValue")
                scoreVal?.text?.toString()
            } else null

            if (totalScore == null) {
                if (readScoreRetry < 3) {
                    readScoreRetry++
                    log("结果页未就绪，${readScoreRetry}/3 重试...")
                    handler.postDelayed({ readScore() }, 3000)
                    return
                } else {
                    log("无法读取成绩，跳过")
                    readScoreRetry = 0
                    onBenchmarkComplete(null)
                    return
                }
            }

            // 读取各子项分数（按顺序：GPU、内存、CPU、UX）
            val allScoreNodes = rootNode.findAccessibilityNodeInfosByViewId("$ANTUTU_PKG:id/TextViewScoreValue")
            // 第一个是总分，后面依次是各子项
            val subScores = allScoreNodes?.drop(1)?.map { it.text?.toString() ?: "-" } ?: emptyList()
            val gpu = subScores.getOrElse(0) { "-" }
            val mem = subScores.getOrElse(1) { "-" }
            val cpu = subScores.getOrElse(2) { "-" }
            val ux = subScores.getOrElse(3) { "-" }

            readScoreRetry = 0
            val record = ScoreRecord(
                round = roundCount + 1,
                total = totalScore,
                gpu = gpu, mem = mem, cpu = cpu, ux = ux,
                time = dateFormat.format(Date())
            )
            onBenchmarkComplete(record)
        } finally {
            rootNode.recycle()
        }
    }

    private fun onBenchmarkComplete(score: ScoreRecord?) {
        roundCount++
        currentState = State.COOLDOWN

        if (score != null) {
            scoreHistory.add(score)
            val roundLabel = if (maxRounds > 0) "$roundCount/$maxRounds" else "$roundCount"
            log("第 $roundLabel 轮 | 总分:${score.total} GPU:${score.gpu} 内存:${score.mem} CPU:${score.cpu} UX:${score.ux}")
            handler.post { scoreListener?.invoke(score) }

            // 上传到飞书
            if (userName.isNotBlank()) {
                log("上传成绩到飞书...")
                FeishuUploader.upload(userName, score.total, score.time) { success ->
                    score.uploaded = success
                    if (success) {
                        log("第 $roundLabel 轮成绩上传成功")
                    } else {
                        log("第 $roundLabel 轮成绩上传失败")
                    }
                    scoreListener?.invoke(score)
                }
            }
        } else {
            val roundLabel = if (maxRounds > 0) "$roundCount/$maxRounds" else "$roundCount"
            log("第 $roundLabel 轮跑分完成（未读取到成绩）")
        }

        if (maxRounds > 0 && roundCount >= maxRounds) {
            log("已达到设定的 $maxRounds 轮，自动停止")
            stopLoop()
            return
        }

        updateStatus("散热中...")

        handler.postDelayed({
            if (!isRunning) return@postDelayed
            log("散热完成，准备下一轮...")
            launchBenchmark()
        }, cooldownSeconds * 1000L)
    }

    private fun handleDialogs(rootNode: AccessibilityNodeInfo): Boolean {
        // 处理常见弹窗：允许、确定、同意、知道了
        val dialogTexts = listOf("允许", "确定", "同意", "知道了", "我知道了", "继续", "始终允许")
        for (text in dialogTexts) {
            val btn = findNodeByText(rootNode, text)
            if (btn != null && btn.isClickable) {
                log("处理弹窗: 点击'$text'")
                btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                handler.postDelayed({ checkAntutuState() }, 2000)
                return true
            }
        }
        return false
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes?.firstOrNull { it.text?.toString() == text }
    }

    private fun findClickableByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val node = findNodeByText(root, text) ?: return null
        if (node.isClickable) return node
        // 向上查找可点击的父节点
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) return parent
            parent = parent.parent
        }
        return node // 找不到可点击父节点，返回原节点
    }

    private fun findNodeById(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByViewId(id)
        return nodes?.firstOrNull()
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        // 先尝试 performAction
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        // 向上找可点击的父节点
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
            parent = parent.parent
        }
        // 都不可点击，使用手势API按坐标点击
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val x = rect.centerX().toFloat()
        val y = rect.centerY().toFloat()
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AntutuAutoRun::BenchmarkLock"
        ).apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun log(msg: String) {
        val time = dateFormat.format(Date())
        val line = "[$time] $msg"
        android.util.Log.d("AntutuAutoRun", line)
        handler.post { logListener?.invoke(line) }
    }

    private fun updateStatus(status: String) {
        handler.post { statusListener?.invoke(status) }
    }
}
