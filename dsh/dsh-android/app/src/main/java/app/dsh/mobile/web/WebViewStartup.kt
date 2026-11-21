package app.dsh.mobile.web

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import java.util.concurrent.Executors

/**
 * WebView 异步启动（v1.6.37，基于 androidx.webkit 1.14.0）。
 *
 * ## 问题
 * 冷启动时第一次 `new WebView(ctx)` 会同步做一大坨事：加载 WebView provider
 * （ColorOS 上是 Chromium 相关 APK 或内置内核）、初始化 V8、建 GPU/合成器通道、
 * 读 profile。实测 600~900ms，全部压在**主线程**上，且发生在
 * `MainActivity.onCreate → setContentView` 的关键路径里 —— 这就是冷启动最长的那段空洞。
 *
 * ## 做法
 * `WebViewCompat.startUpWebView(...)` 把 provider 初始化挪到后台线程。
 * 主线程可以继续把首帧布局画出来；等真正需要 `new WebView()` 时若已就绪则瞬间返回，
 * 若还没好则构造函数等它 —— **总耗时不变，但等待发生在页面已可见之后**。
 *
 * ## 三条硬约束（否则适得其反）
 * 1. 调用后主线程**不得立刻触碰其它 WebView API**：任何 `new WebView()`、
 *    `setWebContentsDebuggingEnabled()`、`getCurrentWebViewPackage()` 都会把初始化
 *    反向拉回主线程同步等，异步就白做了。所以只在 `Application.onCreate()` 末尾调用。
 * 2. **失败必须静默降级**：provider 不支持时 `startUpWebView` 会抛
 *    `UnsupportedOperationException`，这里吞掉并记日志，后续 `new WebView()`
 *    走原来的同步初始化，功能零影响。
 * 3. 幂等：重复调用直接返回。
 *
 * ## UI 线程启动任务
 * `setShouldRunUiThreadStartUpTasks(true)` 是**保守**选择：这些任务里有现代理器必需的
 * 初始化（如 multiprocess 模式）。跳过能再省几毫秒，但可能让个别 ROM 上功能异常。
 * 宁可少省，不引入不确定性。
 */
object WebViewStartup {

    private const val TAG = "WebViewStartup"

    /** 后台初始化线程：单线程足够（WebView 内部自己会并行），也别和 App 抢核心。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "webview-startup").apply { isDaemon = true }
    }

    @Volatile private var started = false
    @Volatile private var done = false
    @Volatile private var totalMs = -1L
    @Volatile private var uiThreadMs = -1L

    /** 启动是否已发起（无论成功失败）。 */
    val isStarted: Boolean get() = started

    /** 启动是否已完成（成功回调或已降级）。 */
    val isDone: Boolean get() = done

    /**
     * 异步预启动 WebView。幂等；任何失败都静默降级。
     *
     * **调用方随后不要再碰任何 WebView API**，理由见类注释。
     */
    fun prewarm(ctx: Context): Boolean {
        if (started) return done
        started = true
        val appCtx = ctx.applicationContext
        return runCatching {
            val config = WebViewStartUpConfig.Builder(executor)
                .setShouldRunUiThreadStartUpTasks(true)
                .build()
            val begin = SystemClock.elapsedRealtime()
            WebViewCompat.startUpWebView(
                appCtx,
                config,
                WebViewCompat.WebViewStartUpCallback { result: WebViewStartUpResult ->
                    done = true
                    totalMs = SystemClock.elapsedRealtime() - begin
                    uiThreadMs = result.totalTimeInUiThreadMillis ?: -1L
                    Log.i(
                        TAG,
                        "async startup ok in ${totalMs}ms (uiThread=${uiThreadMs}ms, " +
                            "maxPerTask=${result.maxTimePerTaskInUiThreadMillis}ms, " +
                            "blockingLocations=${result.blockingStartUpLocations?.size ?: 0})",
                    )
                },
            )
            Log.i(TAG, "async startup requested (background provider init)")
            true
        }.onFailure {
            // provider 不支持 / 旧内核 / 其它异常：静默降级为同步初始化
            done = true
            Log.w(TAG, "async startup unavailable (${it.message}) — sync init will be used")
        }.getOrDefault(false)
    }

    /** 一句话状态，供 WebView 诊断日志使用。 */
    fun describe(): String = when {
        !started -> "not-started"
        !done -> "in-flight"
        totalMs >= 0 -> "done in ${totalMs}ms"
        else -> "done (unsupported)"
    }
}
