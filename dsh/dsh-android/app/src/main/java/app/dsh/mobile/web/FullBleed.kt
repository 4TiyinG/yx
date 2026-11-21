package app.dsh.mobile.web

import android.content.res.AssetManager
import android.webkit.WebView

/**
 * 横屏桌面「全幅铺满」（v1.6.43）。
 *
 * ## 为什么要有这个类（v1.6.42 的回归）
 * v1.6.42 为了让横屏内容不被灵动岛遮住，把 **WebView 四边各缩了一圈**
 * （`topMargin/bottomMargin/marginStart/marginEnd`）。露出来的是 Activity 的
 * `windowBackground`，于是横屏变成「上下左右各一条空白/黑边」—— 这就是用户报的
 * 「大量空白区域」。错在**让位做在了容器外面**：容器一缩，页面再满幅也只画在
 * 那个缩小的框里。
 *
 * 本类的做法是把让位**搬进页面**：
 * ```
 * WebView   match_parent（永远满幅，页面背景自己铺满 → 不会有窗口底色露出）
 *    └─ 注入样式表 + CSS 变量
 *         ├─ 外壳容器解除 max-width / 居中 margin / aspect-ratio → 吃满 100%
 *         └─ 页面自己的顶栏/底栏让开 safe-area（滚动内容仍延伸到屏幕边缘）
 * ```
 *
 * ## 两段式接口（性能）
 * - [install]：**重**。注入样式表 + 扫描外壳容器 + 标记贴边栏。只在
 *   `onPageFinished` 与模式切换时调用一次。
 * - [updateInsets]：**轻**。只写 4 个 CSS 变量。insets 回调频率很高（键盘动画
 *   每帧都可能触发），样式表用 `var(--dsh-safe-*)` 引用，所以这里不必重建任何东西。
 *
 * ## 可撤销
 * `install(view, on = false)` 会执行脚本的 teardown 分支：移除样式表、root 属性、
 * 所有内联覆盖。竖屏必须与改动前逐像素一致（用户硬约束），靠的就是这条路径。
 */
object FullBleed {

    private const val ASSET = "js/full-bleed.js"

    /** 外壳扫描深度上限（body 起算）。3 层覆盖 `body > #root > .app > main` 这类常见结构。 */
    private const val MAX_SHELL_DEPTH = 3

    /** 计算宽低于视口的这个比例，才认为它"被窄化"了（留 4% 容忍滚动条/边框）。 */
    private const val MIN_FILL = 0.96

    @Volatile
    private var source: String? = null

    /** 后台线程预读（复用 [app.dsh.mobile.MainActivity] 的 WebView 预热窗口）。 */
    fun preload(assets: AssetManager) {
        if (source != null) return
        Thread({
            runCatching { read(assets) }
                .onFailure {
                    android.util.Log.w("DshWebView", "full-bleed preload failed: ${it.message}")
                }
        }, "dsh-bleed-preload").apply { isDaemon = true }.start()
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

    /**
     * 安装 / 卸载。
     *
     * @param on false 时执行 teardown（边到边竖屏、或用户关掉桌面渲染）
     * @param insetsCss [top, left, right, bottom] 的 CSS px 值
     * @param shell 是否**解除页面外壳的窄化**（v1.6.50）。默认 true（横屏桌面的既有行为）。
     *              紧凑模式的竖屏传 false：只要安全区让位，不动 `max-width`/居中 ——
     *              手机竖屏本来就没有被窄化，解锁外壳只会在插件改版时引入排版抖动。
     * @return 脚本完成值（进 engine.log 便于真机核对 `freed=` / `safe=` / `vw=`；
     *         v1.6.50 起还带 `top=` / `bottom=` / `left=` / `right=` 四条边各自的命中数，
     *         调用方靠它判定「页面能不能自己接管顶部安全区」）
     */
    fun install(
        assets: AssetManager,
        view: WebView,
        on: Boolean,
        insetsCss: FloatArray,
        shell: Boolean = true,
        onResult: (String) -> Unit = {},
    ) {
        val js = runCatching { read(assets) }.getOrNull() ?: return
        val cfg = "(function(){" +
            "window.__dshFullBleed={" +
            "on:${if (on) "true" else "false"}," +
            "top:${f(insetsCss[0])}," +
            "left:${f(insetsCss[1])}," +
            "right:${f(insetsCss[2])}," +
            "bottom:${f(insetsCss[3])}," +
            "shell:${if (shell) "true" else "false"}," +
            "maxShellDepth:$MAX_SHELL_DEPTH," +
            "minFill:$MIN_FILL" +
            "};})();\n"
        runCatching {
            view.evaluateJavascript(cfg + js) { raw -> onResult(unquote(raw)) }
        }
    }

    /**
     * 只更新安全区数值（轻量，insets 每帧调用也安全）。
     *
     * 样式表用 `var(--dsh-safe-*)` 引用这些值，所以这里不需要重建样式表、
     * 也不需要重扫 DOM —— 只有 4 次 setProperty。
     */
    fun updateInsets(view: WebView, insetsCss: FloatArray) {
        val js = "(function(){" +
            "var r=document.documentElement;if(!r)return;" +
            "r.style.setProperty('--dsh-safe-top','${f(insetsCss[0])}px');" +
            "r.style.setProperty('--dsh-safe-left','${f(insetsCss[1])}px');" +
            "r.style.setProperty('--dsh-safe-right','${f(insetsCss[2])}px');" +
            "r.style.setProperty('--dsh-safe-bottom','${f(insetsCss[3])}px');" +
            "})()"
        runCatching { view.evaluateJavascript(js, null) }
    }

    /** JS 侧配置串里的数字：统一 2 位小数，且用 Locale.US 避免逗号小数点。 */
    private fun f(v: Float): String =
        String.format(java.util.Locale.US, "%.2f", if (v.isNaN() || v < 0f) 0f else v)

    private fun unquote(raw: String?): String =
        raw?.trim()?.removeSurrounding("\"")?.replace("\\n", "\n").orEmpty()

    /** 诊断后缀：桌面/竖屏两条链在日志里必须能一眼区分。 */
    fun describe(on: Boolean): String = if (on) "full-bleed=on" else "full-bleed=off"
}
