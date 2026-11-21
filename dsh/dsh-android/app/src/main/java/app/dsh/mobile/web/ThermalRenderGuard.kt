package app.dsh.mobile.web

import android.util.Log
import android.webkit.WebView

/**
 * 渲染侧热降级（v1.6.36）。
 *
 * ## 思路
 * 降频时真正贵的不只是 CPU，还有 **GPU 像素量**：大面积高斯模糊、层层叠叠的
 * 阴影、几十个 `will-change` 提升出来的合成层，每一个都要独立栅格化 + 合成。
 * 这些效果在视觉上是「精致」，在热受限时是「纯浪费」。
 *
 * 所以热受限时注入一小段 CSS，把它们换成廉价等价物：
 * · `backdrop-filter` / `filter: blur()` → 去掉（保留纯色底）；
 * · 多层 `box-shadow` → 单层或无；
 * · `will-change` → `auto`（撤掉强制提升的合成层）；
 * · `transition/animation` → 保留但限制在短时（避免长动画持续占合成器）。
 *
 * ## 为什么用 CSS 而不是删 DOM
 * CSS 覆盖是**可逆**的（去掉 style 标签即恢复），不动页面结构，不会触发
 * React/Vue 的重排逻辑，也不会让前端的状态与实际 DOM 不一致。
 *
 * ## 图片懒加载
 * 给首屏之外的 `<img>` 补 `loading="lazy"`（原生支持，不引 IntersectionObserver，
 * 少一层 JS 观察器开销）；同时对超大图限制渲染尺寸，避免按原始分辨率上传纹理。
 */
object ThermalRenderGuard {

    private const val TAG = "ThermalRender"

    private const val STYLE_ID = "__dsh_thermal_throttle__"

    /**
     * 注入降级样式（幂等）。
     *
     * 保留品牌观感：只动「昂贵」属性，不动颜色/字号/布局。
     */
    private val THROTTLE_CSS = """
        #$STYLE_ID{display:none}
        html[data-dsh-throttle="1"] *{
            backdrop-filter:none !important;
            -webkit-backdrop-filter:none !important;
        }
        html[data-dsh-throttle="1"] *:not(button):not([role=button]){
            will-change:auto !important;
        }
        html[data-dsh-throttle="1"] .shadow,
        html[data-dsh-throttle="1"] .shadow-lg,
        html[data-dsh-throttle="1"] .shadow-xl,
        html[data-dsh-throttle="1"] [class*="shadow-"]{
            box-shadow:0 1px 2px rgba(0,0,0,.06) !important;
        }
        html[data-dsh-throttle="1"] [class*="blur"]{
            filter:none !important;
        }
        html[data-dsh-throttle="1"] img{
            max-width:100% !important;
            height:auto !important;
        }
    """.trimIndent()

    private val APPLY_JS: String by lazy {
        """
        (function(){
          try{
            var id='$STYLE_ID';
            var s=document.getElementById(id);
            if(!s){
              s=document.createElement('style');
              s.id=id;
              s.textContent=$CSS_LITERAL;
              (document.head||document.documentElement).appendChild(s);
            }
            document.documentElement.setAttribute('data-dsh-throttle','1');
            var imgs=document.querySelectorAll('img:not([loading])');
            for(var i=0;i<imgs.length;i++){ imgs[i].setAttribute('loading','lazy'); }
            return 'on';
          }catch(e){ return 'err:'+e.message; }
        })()
        """.trimIndent()
    }

    /** 撤销降级，恢复原观感。 */
    private val REVERT_JS = """
        (function(){
          try{
            document.documentElement.removeAttribute('data-dsh-throttle');
            var s=document.getElementById('$STYLE_ID');
            if(s){ s.remove(); }
            return 'off';
          }catch(e){ return 'err:'+e.message; }
        })()
    """.trimIndent()

    /** 把 CSS 安全地嵌进 JS 字符串字面量（转义反斜杠与引号/换行）。 */
    private val CSS_LITERAL: String by lazy {
        buildString {
            append('"')
            THROTTLE_CSS.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> Unit
                    else -> append(ch)
                }
            }
            append('"')
        }
    }

    @Volatile private var applied = false

    /**
     * 当前挂着的 WebUI 实例（弱引用，v1.6.45）。
     *
     * 设置页改「渲染档位」时需要立刻对**正在显示**的页生效 —— 但设置页拿不到
     * MainActivity 的 webView。这里由 MainActivity 在创建/替换实例时登记一次，
     * 设置页直接按档位触发，两条路径共用同一段注入逻辑。
     */
    @Volatile private var liveRef: java.lang.ref.WeakReference<WebView>? = null

    /** 登记当前 WebUI 实例（MainActivity 在 WebView 建立后调用；可为 null 表示已销毁）。 */
    fun bindLive(view: WebView?) {
        liveRef = if (view == null) null else java.lang.ref.WeakReference(view)
    }

    /**
     * 按用户的「渲染档位」决定此刻要不要降级，并对当前页面立即生效。
     *
     * · SMOOTH    → 强制降级（不等热状态）
     * · QUALITY   → 强制不降级
     * · BALANCED  → 交给热状态（v1.6.36 原行为）
     */
    fun applyProfileToLivePage(
        ctx: android.content.Context,
        profile: app.dsh.mobile.PerfPrefs.RenderProfile = app.dsh.mobile.PerfPrefs.render(ctx),
    ) {
        val v = liveRef?.get() ?: return
        val want = when (profile) {
            app.dsh.mobile.PerfPrefs.RenderProfile.SMOOTH -> true
            app.dsh.mobile.PerfPrefs.RenderProfile.QUALITY -> false
            app.dsh.mobile.PerfPrefs.RenderProfile.BALANCED ->
                app.dsh.mobile.ui.ThermalGuard.current.throttled
        }
        sync(v, want)
        // v1.6.46：渲染调优里的「长列表离屏跳过」跟着档位走（SMOOTH 才开）。
        // 它不属于热降级（没有观感代价以外的副作用），所以不放进 sync 的开关逻辑。
        runCatching { app.dsh.mobile.web.RenderTune.applyProfile(v, profile) }
    }

    /**
     * 把「档位」与「热状态」合成最终的降级决定。
     *
     * 热状态那条订阅在 MainActivity 里，它只知道温度；用户的档位是更强的一级
     * 意愿（QUALITY = 宁可发烫也别降级；SMOOTH = 别等发烫，现在就降）。
     * 所以这里统一收口，避免两处各自算一遍导致来回抖。
     */
    fun resolve(ctx: android.content.Context, thermalThrottled: Boolean): Boolean =
        when (app.dsh.mobile.PerfPrefs.render(ctx)) {
            app.dsh.mobile.PerfPrefs.RenderProfile.SMOOTH -> true
            app.dsh.mobile.PerfPrefs.RenderProfile.QUALITY -> false
            app.dsh.mobile.PerfPrefs.RenderProfile.BALANCED -> thermalThrottled
        }

    /** 当前是否已处于降级态（避免每次热状态回调都重复注入同一段 JS）。 */
    val isApplied: Boolean get() = applied

    /**
     * 按热状态同步降级。
     *
     * @param throttled 是否处于降级区间（建议传 `ThermalGuard.current.throttled`）
     */
    fun sync(view: WebView?, throttled: Boolean) {
        if (view == null) return
        if (throttled == applied) return
        applied = throttled
        val js = if (throttled) APPLY_JS else REVERT_JS
        runCatching {
            view.evaluateJavascript(js) { r ->
                Log.i(TAG, "thermal render ${if (throttled) "throttle" else "restore"} -> $r")
            }
        }.onFailure { Log.w(TAG, "sync failed: ${it.message}") }
    }

    /** 引擎页面重载后需要重新注入（页面文档换了，旧 style 已不存在）。 */
    fun reapplyIfNeeded(view: WebView?) {
        if (!applied) return
        // applied 标志保持 true，但旧文档里的 style 已随文档销毁，需重新注入
        runCatching { view?.evaluateJavascript(APPLY_JS, null) }
    }
}
