package app.dsh.mobile.ui

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 主线程卡顿归因器（v1.6.48）。
 *
 * ## 它回答什么问题
 * 「硬件明明富余，为什么还卡」这个问题靠猜是猜不出来的：掉帧可能来自本进程
 * 主线程被堵、渲染进程在等资源、也可能是系统合成器被别的应用抢占。前两种我们能
 * 管，而**主线程被堵这件事必须有栈证据**，否则所有优化都是盲改。
 *
 * 类名里的「归因」是关键字：它不测帧率（那是 [FrameStats] 的事），只在
 * 主线程**卡住**的那一刻把主线程**正在执行的调用栈**抓下来，写进 engine.log。
 *
 * ## 原理（为什么不用 Systrace / Perfetto）
 * 那些需要连电脑、需要 debuggable 之外的权限，用户拿不到。这里用最朴素的办法：
 *
 * ```
 * 后台线程：post 一个「回执」到主线程 → sleep(ping) → 看回执有没有按时回来
 *          没按时回来 = 主线程此刻被占住 → 直接读 mainThread.stackTrace
 * ```
 *
 * `Thread.stackTrace` 是 JVM 自带的快照，不需要任何权限；主线程忙时它照样能读
 * （读的是 safepoint 时的栈）。代价只在**卡住的时候**发生，正常时是 0。
 *
 * ## 三条防「观测反噬」的约束
 * 1. **采样有闸**：[MIN_SAMPLE_GAP_MS] 内不重复采样，避免卡顿期间不停抓栈
 *    （抓栈本身也要 safepoint，抓太勤会把 200ms 的卡顿放大成 400ms）。
 * 2. **报告有上限**：同一个栈签名最多报 [MAX_REPORT_PER_SIGNATURE] 次，
 *    防止一个常驻慢点刷满日志（日志写入也会占 IO）。
 * 3. **只在 debug 包启用**：release 包零线程、零采样。
 *
 * ## 怎么读输出
 * ```
 * [main-blocked] 412ms at BRIDGE#1
 *   app.dsh.mobile.web.WebAssetCache.shouldInterceptRequest(WebAssetCache.kt:381)
 *   ...
 * ```
 * `at BRIDGE#n` 会把帧归到几个已知热区（见 [classify]），方便一眼看出
 * 是「桥」「IO」「JSON」「渲染」还是「锁等待」。
 */
object MainThreadWatchdog {

    private const val TAG = "MainThreadWatchdog"

    /** 回执超时：超过这个时长没回来就算「主线程被占住」。60Hz 下约 12 帧。 */
    private const val BLOCK_THRESHOLD_MS = 200L

    /** 轮询间隔 */
    private const val PING_INTERVAL_MS = 400L

    /** 两次采样之间的最小间隔（防观测反噬） */
    private const val MIN_SAMPLE_GAP_MS = 900L

    /** 同一签名最多报告次数 */
    private const val MAX_REPORT_PER_SIGNATURE = 3

    /** 每次报告保留的栈帧数 */
    private const val STACK_FRAMES = 8

    @Volatile private var started = false

    private val reported = HashMap<String, Int>()

    private var lastSampleAt = 0L

    /**
     * 启动。
     *
     * @param log 落日志的回调（调用方接 `EngineLog.appendAsync`）。
     *            传 null 则只走 Logcat。
     */
    fun start(log: ((String) -> Unit)? = null) {
        if (started) return
        started = true
        val main = Looper.getMainLooper()
        val mainHandler = Handler(main)
        val mainThread = main.thread

        Thread({
            var lastPong = System.currentTimeMillis()
            while (true) {
                val sentAt = System.currentTimeMillis()
                var ponged = false
                mainHandler.post {
                    ponged = true
                    lastPong = System.currentTimeMillis()
                }
                // sleep 一段后检查：回执没回来就说明主线程被占住了
                try {
                    Thread.sleep(PING_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val now = System.currentTimeMillis()
                // 回执没来 → 主线程还堵在上一个任务上
                if (!ponged && now - sentAt >= BLOCK_THRESHOLD_MS) {
                    if (now - lastSampleAt < MIN_SAMPLE_GAP_MS) continue
                    lastSampleAt = now
                    runCatching { report(mainThread, now - sentAt, log) }
                } else if (ponged && now - lastPong > 5_000) {
                    // 长时间没有任何帧驱动（屏幕熄灭/后台）就安静待着，不报
                }
            }
        }, "dsh-main-watchdog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
        Log.i(TAG, "started (threshold=${BLOCK_THRESHOLD_MS}ms)")
    }

    private fun report(mainThread: Thread, blockedMs: Long, log: ((String) -> Unit)?) {
        val frames = mainThread.stackTrace
        if (frames.isEmpty()) return
        val top = frames.take(STACK_FRAMES)
        val signature = top.take(3).joinToString("|") { "${it.className}.${it.methodName}" }
        val seen = reported[signature] ?: 0
        if (seen >= MAX_REPORT_PER_SIGNATURE) return
        reported[signature] = seen + 1

        val hot = classify(top)
        val text = buildString {
            append("[main-blocked] ").append(blockedMs).append("ms at ").append(hot)
            if (seen > 0) append(" (repeat #").append(seen + 1).append(')')
            append('\n')
            top.forEach { f ->
                append("  ").append(f.className.substringAfterLast('.'))
                    .append('.').append(f.methodName)
                    .append(" (").append(f.fileName ?: "?").append(':')
                    .append(f.lineNumber).append(")\n")
            }
        }
        Log.w(TAG, text.trimEnd())
        runCatching { log?.invoke(text.trimEnd()) }
    }

    /**
     * 把栈归到已知热区。
     *
     * 这些名字来自本工程的真实结构：桥（JavascriptInterface/WebView 回调）、
     * 资源拦截（shouldInterceptRequest 链）、JSON、渲染预算、锁。看到哪一类，
     * 就知道下一步该看哪个文件 —— 这比「有个栈」有用得多。
     */
    private fun classify(frames: List<StackTraceElement>): String {
        for (f in frames) {
            val c = f.className
            val m = f.methodName
            when {
                c.contains("JavascriptInterface") || c.contains("Bridge") ||
                    c.contains("BridgeInjection") -> return "BRIDGE(JS→原生)"
                c.contains("WebAssetCache") || c.contains("MappedAsset") ||
                    c.contains("FullBleed") || c.contains("RenderTune") -> return "WEBASSET/注入"
                c.contains("JSONObject") || c.contains("JSONArray") ||
                    c.contains("org.json") -> return "JSON解析"
                c.contains("RenderBudget") || c.contains("FrameStats") ||
                    c.contains("Choreographer") || c.contains("ViewRootImpl") -> return "渲染/布局"
                c.contains("SharedPreferencesImpl") || c.contains("EditorImpl") -> return "SP写入"
                c.contains("FileInputStream") || c.contains("RandomAccessFile") ||
                    c.contains("FileOutputStream") || c.contains("Os.read") -> return "磁盘IO"
                m == "wait" || m == "park" || m == "sleep" -> return "锁等待/挂起"
                c.contains("WebView") && (m == "loadUrl" || m == "evaluateJavascript") ->
                    return "WebView同步等待"
            }
        }
        val f = frames.first()
        return "${f.className.substringAfterLast('.')}.${f.methodName}"
    }

    /** 供设置页/日志查看器查看已归因的种类数（只读快照）。 */
    fun summary(): String {
        val n = reported.size
        val total = reported.values.sum()
        return "watchdog samples=$total signatures=$n"
    }
}
