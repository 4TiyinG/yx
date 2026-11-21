package app.dsh.mobile.web

import android.content.res.AssetManager
import android.util.Log
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.dsh.mobile.PerfPrefs

/**
 * WebUI 渲染调优注入器（v1.6.46）。
 *
 * ## 它解决什么
 * v1.6.45 之前，宿主的性能改动集中在**启动**与**首帧**（资源预载、IPC 合并、
 * 收尾外移）。页面**动起来之后**的帧时间没有专门的抓手：
 * · 按压/点击反馈第一帧要现建合成层（丢 1~2 帧）；
 * · 长列表滚出视口后仍在参与样式/布局（Chromium 只在**可见**时省，见下）；
 * · 页面自己的长任务与慢事件没有任何可观测口径，改完只能"感觉快了"。
 *
 * ## 与 [ThermalRenderGuard] 的分工
 * · `ThermalRenderGuard` = **减法**：发烫或用户选了「流畅优先」时，把模糊/阴影/
 *   强制合成层**去掉**（有观感代价，所以只在降级时做）。
 * · `RenderTune` = **不改观感**：只调整「什么时候提升合成层」「离屏的列表要不
 *   要跳过」「怎么测量」，任何档位下视觉都应与改动前一致。唯一例外是「流畅优先」
 *   档下打开长列表离屏跳过（[PerfPrefs.RenderProfile.SMOOTH]），那是有明确
 *   取舍的**可选**项，默认不启用。
 *
 * ## 为什么用 document-start 而不是 onPageFinished
 * v1.6.37 的合帧脚本是在 `onPageFinished` 注入的 —— 那时页面自己的
 * `addEventListener` 早就注册完了，包装器只能覆盖「之后」注册的监听器，
 * 实际命中率很低。`WebViewCompat.addDocumentStartJavaScript`（androidx.webkit
 * 1.14，Chromium 83+ 支持）在**页面任何脚本执行之前**运行，因此：
 * · 合帧能覆盖页面自己的全部监听器（真正的收益）；
 * · 样式与提升逻辑在首帧之前就位，不会出现"先抖一下再顺"。
 * 不支持该特性的旧内核**静默回退**到 `onPageFinished` 注入（原路径），
 * 功能不缺失，只是命中率低一些。
 *
 * ## 三条安全约束
 * 1. **幂等**：JS 侧有 `window.__dshRenderTune` 守卫，两条注入路径都调也只生效一次。
 * 2. **可撤销**：`unboost()` 会把 `content-visibility` 与行内尺寸全部还原。
 * 3. **不做全局样式**：见 `assets/js/render-tune.js` 顶部注释 —— 全量
 *    `will-change` / `translateZ(0)` 是显存爆炸的经典来源，本项目明确不用。
 */
object RenderTune {

    private const val TAG = "RenderTune"

    private const val ASSET = "js/render-tune.js"

    /** 资产文本缓存（预读在后台线程写，UI 线程读）。 */
    @Volatile
    private var source: String? = null

    /** 已注册 document-start 的实例（弱引用集合：实例随 Activity 走，不强持有）。 */
    private val armed = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<WebView, Boolean>(),
    )

    /** 当前档位（用于日志与 describe()）。 */
    @Volatile
    private var profile: PerfPrefs.RenderProfile = PerfPrefs.RenderProfile.BALANCED

    @Volatile
    private var documentStartSupported: Boolean? = null

    /**
     * 后台线程预读脚本（复用 WebView 预热窗口，避开导航时的 UI 线程 inflate）。
     */
    fun preload(assets: AssetManager) {
        if (source != null) return
        Thread({
            runCatching { read(assets) }
                .onFailure { Log.w(TAG, "preload failed: ${it.message}") }
        }, "dsh-rendertune-preload").apply { isDaemon = true }.start()
    }

    private fun read(assets: AssetManager): String {
        source?.let { return it }
        synchronized(this) {
            source?.let { return it }
            val text = assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            source = text
            return text
        }
    }

    /** 取脚本：优先缓存，未就绪则同步读（注入一定发生，功能优先）。 */
    private fun script(assets: AssetManager): String = source ?: read(assets)

    /**
     * 在 WebView 建立后、**任何 loadUrl 之前**调用：注册 document-start 脚本。
     *
     * 返回是否注册成功（false = 内核不支持或异常，调用方在 onPageFinished
     * 走 [install] 的兼容路径）。
     */
    fun attach(view: WebView, assets: AssetManager): Boolean {
        if (armed.contains(view)) return true
        val js = runCatching { script(assets) }.getOrNull() ?: return false
        val supported = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }.getOrDefault(false)
        documentStartSupported = supported
        if (!supported) {
            Log.i(TAG, "document-start unsupported — will inject on page finished")
            return false
        }
        return runCatching {
            // "*" = 所有来源。脚本自身对 iframe 与跨端口预览页做了自我限制。
            val handler: ScriptHandler = WebViewCompat.addDocumentStartJavaScript(
                view, js, setOf("*"),
            )
            armed.add(view)
            Log.i(TAG, "document-start armed (handler=${handler.hashCode()})")
            true
        }.onFailure {
            Log.w(TAG, "document-start arm failed: ${it.message} — falling back")
        }.getOrDefault(false)
    }

    /** 实例退役：解除登记（脚本本身随 WebView 销毁）。 */
    fun detach(view: WebView?) {
        if (view == null) return
        armed.remove(view)
    }

    /**
     * `onPageFinished` 的兼容路径：只有在 document-start 没挂上时才真正注入。
     *
     * 无论走哪条路径，都会把当前档位同步到页面（boost 开关）。
     */
    fun install(view: WebView, assets: AssetManager, profile: PerfPrefs.RenderProfile) {
        this.profile = profile
        if (armed.contains(view)) {
            // 已经 document-start 生效：只需同步档位，不多花一次 IPC 重跑脚本
            applyProfile(view, profile)
            return
        }
        val js = runCatching { script(assets) }.getOrNull() ?: return
        runCatching {
            view.evaluateJavascript(js) { raw ->
                Log.i(TAG, "fallback install -> $raw")
                applyProfile(view, profile)
            }
        }.onFailure { Log.w(TAG, "install failed: ${it.message}") }
    }

    /**
     * 按渲染档位决定长列表离屏跳过是否开启。
     *
     * · SMOOTH   → 开（用户明确选了「流畅优先」，愿意换掉一部分离屏检索能力）
     * · BALANCED / QUALITY → 关（保持与改动前逐像素一致）
     */
    fun applyProfile(view: WebView?, profile: PerfPrefs.RenderProfile) {
        if (view == null) return
        this.profile = profile
        val js = if (profile == PerfPrefs.RenderProfile.SMOOTH) {
            "window.__dshRenderTune?window.__dshRenderTune.boost():'no-tune'"
        } else {
            "window.__dshRenderTune?window.__dshRenderTune.unboost():'no-tune'"
        }
        runCatching { view.evaluateJavascript(js) { r -> Log.i(TAG, "profile=$profile -> $r") } }
            .onFailure { Log.w(TAG, "applyProfile failed: ${it.message}") }
    }

    /** 设置页改档位时对**正在显示**的页面立即生效（与 ThermalRenderGuard 同口径）。 */
    fun applyProfileToLivePage(
        view: WebView?,
        profile: PerfPrefs.RenderProfile,
    ) = applyProfile(view, profile)

    /**
     * 打开观测（仅 debug 包调用）。进入页面 6 秒后由原生侧取一次，
     * 结果写进 engine.log：`longTasks / longMaxMs / cls / slowEvents`。
     *
     * 观测本身是 `PerformanceObserver`，事件驱动、无轮询 —— 长任务不发生就零开销。
     */
    fun startStats(view: WebView?) {
        if (view == null) return
        runCatching {
            view.evaluateJavascript("window.__dshRenderTune?window.__dshRenderTune.startStats():'no-tune'", null)
        }
    }

    /** 取一份观测快照写日志。 */
    fun logStats(view: WebView?, tag: String) {
        if (view == null) return
        runCatching {
            view.evaluateJavascript(
                "window.__dshRenderTune?window.__dshRenderTune.stats():'no-tune'",
            ) { raw ->
                Log.i(TAG, "stats[$tag] ${raw?.trim('"')}")
            }
        }.onFailure { Log.w(TAG, "logStats failed: ${it.message}") }
    }

    /** 一句话状态，供 `MainActivity` 的 perf 日志使用。 */
    fun describe(): String {
        val ds = when (documentStartSupported) {
            true -> "document-start"
            false -> "page-finished"
            null -> "not-attached"
        }
        return "rendertune=$ds/$profile"
    }
}
