package app.dsh.mobile

import android.content.res.AssetManager
import android.webkit.WebView

/**
 * WebUI 桥接脚本注入器（v1.6.20 渲染/加载性能优化）。
 *
 * ## 为什么要有这个类
 * v1.6.19 之前，`MainActivity.onPageFinished` 里**串行**发起 6 次
 * [WebView.evaluateJavascript]（自检 / blob 下载桥 / 剪贴板降级 / 强调色 /
 * mobile-keys / back-guard）。每次调用都是「浏览器进程 → 渲染进程」的一次独立
 * IPC 往返，并在渲染进程主线程上排一个独立任务；而这些任务正好落在**首屏渲染
 * 与启动遮罩收起之间**最拥挤的时间窗。
 *
 * 代理测量（本机 Chrome 145 + CDP，8001 个 DOM 节点、12 轮中位数）：同样 6 段脚本，
 * 串行 3.93ms → 合并 1.41ms（wall −64%，渲染线程任务耗时 −42%）。
 * ⚠ 这是桌面 Chrome 上的代理数据，不是 Android 真机数值；手机侧 IPC/CPU 更慢，预期
 * 收益方呅更大，但具体数字必须在真机上用 gfxinfo / frame timeline 复测后才能下结论。
 *
 * ## 做了什么
 * 1. **合并成一次注入**：6 段脚本拼成 1 个 script，一次往返搞定。
 * 2. **保留逐段故障隔离**：每段外面包 `try/catch`，某段运行期抛错只让它自己失效，
 *    并把失败原因带回日志 —— 隔离性等价于原来的 6 次独立调用。
 *    （语法错误无法在运行期 catch；两段资产与三段常量都是 APK 内的静态文本，
 *    已由 `scripts/check-inject-js.js` 做拼接后的可解析性校验。）
 * 3. **JS 资产只在首次读取并缓存**：原先每次导航都在 UI 线程同步
 *    `assets.open(...).readBytes()`（APK 内是压缩条目，读一次就要 inflate 一次）。
 *    现在由 [preload] 在 WebView 预热阶段于后台线程读好；万一预读还没完成就来了
 *    导航，仍回退成同步读取 —— **注入不会因此被跳过**，功能优先。
 * 4. **自检结果不再被丢弃**：原代码用 `evaluateJavascript(SELF_CHECK, null)`，
 *    那段脚本 `return` 的诊断串从来没进日志（注释里写的「写进日志」并未发生）。
 *    现在合并脚本的完成值就是「注入状态 + 自检结论」，交给回调落 engine.log。
 *
 * ## 幂等性
 * 各段脚本自带 `window.__dsh*Installed` / `__dshClipboardPatched` 之类的守卫，
 * 且都是 IIFE（无顶层声明泄漏），因此拼接执行与分开执行在页面上留下的状态一致。
 */
object PageBridgeInjection {

    /** 移动端键盘行为修正（issue #53） */
    private const val ASSET_KEYS = "js/mobile-keys.js"

    /** 整页浮层返回键兜底（v1.6.15） */
    private const val ASSET_BACK = "js/back-guard.js"

    /** 资产文本缓存：(mobile-keys, back-guard)。volatile —— 预读在别的线程写 */
    @Volatile
    private var cached: Pair<String, String>? = null

    private val lock = Any()

    /**
     * 在后台线程把两个 JS 资产读出来缓存，避开导航时的 UI 线程 inflate 成本。
     *
     * 调用时机：[MainActivity] 的 WebView 预热阶段（`preheatWebView`）——那时引擎
     * 还在启动，UI 线程闲着，磁盘与 CPU 都便宜。
     */
    fun preload(assets: AssetManager) {
        if (cached != null) return
        Thread({
            runCatching { assetSources(assets) }
                .onFailure { android.util.Log.w("DshWebView", "pageBridge preload failed: ${it.message}") }
        }, "dsh-bridge-preload").apply { isDaemon = true }.start()
    }

    /** 取资产文本：优先用缓存；缓存未就绪则同步读（保证注入一定发生） */
    private fun assetSources(assets: AssetManager): Pair<String, String> {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val p = readAll(assets, ASSET_KEYS) to readAll(assets, ASSET_BACK)
            cached = p
            return p
        }
    }

    private fun readAll(assets: AssetManager, name: String): String =
        assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * 拼出一次性注入用的脚本。
     *
     * 顺序：功能桥在前，自检在最后 —— 脚本的**完成值**取最后一条表达式语句，
     * 正好把自检结论带回原生侧。
     *
     * @param blobBridgeJs    [app.dsh.mobile.engine.WebViewDownloader.bridgeJs]
     * @param clipboardJs     剪贴板降级链（issue #43）
     * @param selfCheckJs     WebView 能力自检（其 `return` 值会被回收）
     * @param accentHex       `#RRGGBB`，插件侧读 `--dsh-accent`
     */
    fun buildScript(
        assets: AssetManager,
        blobBridgeJs: String,
        clipboardJs: String,
        selfCheckJs: String,
        accentHex: String,
    ): String {
        val (keysJs, backJs) = assetSources(assets)
        val sb = StringBuilder(
            blobBridgeJs.length + clipboardJs.length + selfCheckJs.length + keysJs.length +
                backJs.length + 512,
        )
        // 外层再套一个 IIFE：失败收集用的临时变量不能以顶层 `var` 的形式泄到 window 上
        //（旧的逐个注入也不会有这个新全局）。改成函数体后，脚本完成值由 `return` 提供，
        // 对 evaluateJavascript 语义等价（它取的就是脚本的完成值）。
        sb.append("(function(){\nvar __dshInjectFail=[];\n")
        // 每段独立 try/catch：保住「一段坏不影响其余段」的原有隔离性
        appendIsolated(sb, "blob", blobBridgeJs)
        appendIsolated(sb, "clipboard", clipboardJs)
        appendIsolated(sb, "accent", "document.documentElement.style.setProperty('--dsh-accent','$accentHex');")
        appendIsolated(sb, "keys", keysJs)
        appendIsolated(sb, "backguard", backJs)
        // 自检的结论回传到原生侧落 engine.log。
        // 常量文本以 `})();` 结尾，直接再包一层括号会得到 `(();)` 这种语法错误，
        // 因此先去掉结尾分号再包成表达式的右值。
        val selfExpr = selfCheckJs.trim().removeSuffix(";")
        sb.append("window.__dshSelfcheck=(").append(selfExpr).append(");\n")
        sb.append("return 'inject='+((__dshInjectFail.length)?('FAIL['+__dshInjectFail.join(',')+']'):'ok5')")
            .append("+(' | '+String(window.__dshSelfcheck));\n")
        sb.append("})()\n")
        return sb.toString()
    }

    private fun appendIsolated(sb: StringBuilder, tag: String, src: String) {
        sb.append("try{").append(src.trim()).append("}catch(e){__dshInjectFail.push('")
            .append(tag).append(":'+(e&&e.message));};\n")
    }

    /**
     * 执行一次合并注入。
     *
     * @param onResult 完成值回调（注入状态 + 自检结论），已在原生侧去过 JSON 引号
     */
    fun install(view: WebView, script: String, onResult: (String) -> Unit) {
        // 原生侧异常（WebView 已销毁 / 未 attach 等）由调用方 runCatching 兜住，
        // 与旧实现逐个注入时的 onFailure 语义一致。
        view.evaluateJavascript(script) { raw -> onResult(raw ?: "") }
    }
}
