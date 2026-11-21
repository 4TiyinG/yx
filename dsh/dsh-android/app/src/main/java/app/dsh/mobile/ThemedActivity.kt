package app.dsh.mobile

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import app.dsh.mobile.search.SearchAnchor
import app.dsh.mobile.ui.ImagePipeline
import app.dsh.mobile.ui.Insets
import app.dsh.mobile.ui.Motion

/** 原生壳主题：深色（默认）/ 浅色 / 跟随系统。WebView 由 dsh 自己管理，不在这里处理。 */
enum class ThemeMode(val key: String, val labelRes: Int) {
    DARK("dark", R.string.theme_dark),
    LIGHT("light", R.string.theme_light),
    SYSTEM("system", R.string.theme_system);

    companion object {
        fun fromKey(key: String?): ThemeMode =
            values().firstOrNull { it.key == key } ?: DARK
    }
}

object ThemePref {
    private const val KEY = "theme_mode"

    fun get(context: Context): ThemeMode =
        ThemeMode.fromKey(
            context.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
                .getString(KEY, ThemeMode.DARK.key),
        )

    fun set(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
            .edit().putString(KEY, mode.key).apply()
    }
}

object ThemeManager {
    /** 让 Activity 的 resources 按偏好选择 values / values-night。 */
    fun wrap(base: Context): Context {
        val mode = ThemePref.get(base)
        if (mode == ThemeMode.SYSTEM) return base
        val config = Configuration(base.resources.configuration)
        val night = if (mode == ThemeMode.DARK) {
            Configuration.UI_MODE_NIGHT_YES
        } else {
            Configuration.UI_MODE_NIGHT_NO
        }
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(config)
    }

    fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun applyBars(activity: Activity) {
        val dark = isDark(activity)
        val bg = activity.getColor(R.color.bg)
        activity.window.statusBarColor = bg
        activity.window.navigationBarColor = bg
        var flags = activity.window.decorView.systemUiVisibility
        flags = if (dark) {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        } else {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        activity.window.decorView.systemUiVisibility = flags
    }
}

/** 所有原生 Activity 的基类：attachBaseContext 里应用主题，onCreate 里统一系统栏。 */
open class ThemedActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Dsh)
        super.onCreate(savedInstanceState)
        ThemeManager.applyBars(this)
        EdgeToEdge.applyWindow(this, darkSurface = alwaysDarkSurface)
    }

    /**
     * 本页背景是否恒为深色（终端 / 日志查看器）。
     *
     * 这两页的内容是"黑底等宽字"，浅色主题下也不改底色 —— 那么状态栏图标
     * 就必须恒用浅色。重构前它们没继承本类，图标明暗按系统 uiMode 判断，
     * 于是"应用内浅色主题 + 系统浅色模式"下会出现深色图标压深色终端。
     */
    protected open val alwaysDarkSurface: Boolean get() = false

    /**
     * 设置体系统一转场：新页从右侧滑入，旧页保持。
     *
     * ## 为什么 `startActivityForResult` 也要覆盖
     *
     * 重构前只覆盖了 `startActivity`。而"选备份文件""选自定义图片""选运行时包"
     * 这些流程走的是 `startActivityForResult` —— 于是同一批页面里，
     * 普通跳转有滑入动画、系统选择器回来却是硬切。系统选择器自身的动画改不了，
     * 但**我们这一侧的进出**可以，所以两者都挂同一套资源。
     */
    @Suppress("DEPRECATION")
    override fun startActivity(intent: android.content.Intent) {
        super.startActivity(intent)
        applyEnterTransition()
    }

    @Deprecated("startActivityForResult 是文件选择器的既有契约，此处只补转场")
    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: android.content.Intent, requestCode: Int) {
        super.startActivityForResult(intent, requestCode)
        applyEnterTransition()
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        applyExitTransition()
    }

    /** MainActivity 自行管理内容 Insets（WebView 需要延伸到底部栏后方）。 */
    protected open val appliesContentInsets: Boolean get() = true

    // ================= 设置页自定义背景图 =================

    /**
     * 本 Activity 是否使用「设置页背景图」（外观与显示 → 设置页背景图）。
     *
     * 默认 false：主界面、终端、文件编辑器等不属于设置体系的页面保持原样。
     * 设置体系内的页面（设置主页与各二级页）覆盖为 true，从而整条设置路径
     * 都能看到同一张背景图 —— 而不是只有点进设置的那一屏有。
     */
    protected open val usesSettingsBackground: Boolean get() = false

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        applySettingsBackground()
        if (appliesContentInsets) Insets.applyContent(this)
        // v1.6.41：被设置搜索结果拉起来的页面，落地后定位到对应子项并高亮。
        // 没有 EXTRA_ANCHOR 时是一次纯 no-op（一次 getIntExtra 的开销）。
        SearchAnchor.consume(this)
    }

    override fun setContentView(view: View) {
        super.setContentView(view)
        applySettingsBackground()
        if (appliesContentInsets) Insets.applyContent(this)
        SearchAnchor.consume(this)
    }

    /**
     * 转场资源按用户的「界面动效」档位解析（v1.6.45）。
     *
     * 之所以在**调用点**解析而不是缓存：用户在设置里改完档位，返回上一页时就应该
     * 立刻按新档位播放，不能等进程重启。读 SharedPreferences 走的是进程内缓存，
     * 每次导航一次，成本可以忽略。
     */
    private fun applyEnterTransition() {
        val a = Motion.pageAnims(PerfPrefs.motion(this))
        overridePendingTransition(a.enter, a.exit)
    }

    private fun applyExitTransition() {
        val level = PerfPrefs.motion(this)
        if (!Motion.pageTransitionEnabled(level)) {
            overridePendingTransition(0, 0)
            return
        }
        overridePendingTransition(Motion.ANIM_PAGE_HOLD, Motion.ANIM_PAGE_OUT)
    }

    override fun onResume() {
        super.onResume()
        EdgeToEdge.applyWindow(this, darkSurface = alwaysDarkSurface)
        if (appliesContentInsets) Insets.applyContent(this)
        // 从外观页选完图返回时立即生效，不需要用户手动刷新
        if (usesSettingsBackground) applySettingsBackground()
    }

    /**
     * 把用户选的背景图铺到当前页内容根上，并把分组卡片降到半透明 ——
     * 否则卡片不透明会把图挡得只剩卡片缝隙。
     *
     * 幂等：每次都按当前偏好重算。未设置（或用户刚点「移除」）时还原成页面
     * 原本的背景与卡片不透明度，不会残留旧图。
     *
     * 原始值存在 WeakHashMap 而不是 View.setTag：`setTag(Object)` 只有一个槽，
     * 同一 View 上如果再存别的标记会互相覆盖；用 Map 更安全且不影响 View 本身。
     *
     * ## v1.6.31：为什么这个方法不能再同步跑
     *
     * 旧实现每次 `onResume` 都同步走一遍「解码 + 真高斯模糊（最长边 1080）+
     * 全树压暗卡片」。三个问题：
     *
     *  1. **主线程解码与卷积**：缓存命中时也要解一张 1080 宽的 PNG（≈4 MB），
     *     未命中时还要跑一遍逐像素高斯，那是**几百毫秒级**的主线程阻塞；
     *     每个设置页都是新 Activity，所以「切一页解一次」。
     *  2. **全页重绘**：每次都 `root.background = BitmapDrawable(...)`，即使图没变，
     *     View 也会 invalidate 一次，整页重录 display list。
     *  3. **全树压暗**：`dimCards` 遍历整棵视图树并对每个 GradientDrawable
     *     `setColor`，每次 setColor 都是一次 invalidate —— 一次 resume 能把
     *     几十个卡片全部标记为脏。
     *
     * 现在：图交给 [ImagePipeline] 在后台算（主线程零解码），算完经 vsync 合帧回贴；
     * 参数没变就直接短路返回，连一个 View 都不碰。
     */
    protected fun applySettingsBackground() {
        if (!usesSettingsBackground) return
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) as? View ?: return

        val originalBg = originalBackgrounds.getOrPut(root) { root.background }

        val file = CustomVisualPref.settingsBgFile(this)
        if (file == null) {
            // 未设置背景图：只把「曾经改过」的东西改回去，没改过就不碰
            if (appliedBgApplyKey != null && root.background !== originalBg) {
                root.background = originalBg
            }
            if (cardsDimmed) {
                restoreCardOpacity(root)
                cardsDimmed = false
            }
            appliedBgBitmapKey = null
            appliedBgApplyKey = null
            ImagePipeline.cancel(bgTag)
            return
        }

        val blur = CustomVisualPref.settingsBgBlur(this)
        val alpha = CustomVisualPref.settingsBgAlpha(this)
        // 位图键与交付键分开：alpha 只是 Drawable 的属性，改它不需要重新模糊。
        val bmpKey = "${file.absolutePath}|${file.lastModified()}|${file.length()}|$blur|${ImageFx.MAIN_EDGE}"
        val applyKey = "$bmpKey|$alpha"
        if (applyKey == appliedBgApplyKey && appliedBgBitmapKey == bmpKey) return

        ImagePipeline.requestBlur(bgTag, this, file, blur, ImageFx.MAIN_EDGE) { bmp ->
            if (isFinishing || isDestroyed) return@requestBlur
            applyBgBitmap(root, bmp, bmpKey, applyKey, alpha)
        }
    }

    /** 把算好的背景位图真正套到内容根上（主线程，成本只有一次 Drawable 包装）。 */
    private fun applyBgBitmap(
        root: View,
        bmp: Bitmap,
        bmpKey: String,
        applyKey: String,
        alphaPercent: Int,
    ) {
        root.background = BitmapDrawable(resources, bmp).apply {
            // FILL：等比放大铺满、居中裁切，不拉伸变形
            gravity = android.view.Gravity.FILL
            alpha = ImageFx.alphaOf(alphaPercent)
        }
        appliedBgBitmapKey = bmpKey
        appliedBgApplyKey = applyKey
        // 压暗是一次全树 mutate+setColor，只在「从亮到暗」的这一刻做一次
        if (!cardsDimmed) {
            dimCards(root)
            cardsDimmed = true
        }
    }

    /** 本 Activity 实例的背景解码 tag：onDestroy 靠它撤销在途请求 */
    private val bgTag: String by lazy { "settings-bg#${System.identityHashCode(this)}" }

    /** 上一次真正套上的位图键（文件|mtime|长度|模糊强度|目标边长） */
    private var appliedBgBitmapKey: String? = null

    /** 上一次真正套上的交付键（位图键 + 不透明度）：一致就短路，不触发重绘 */
    private var appliedBgApplyKey: String? = null

    /** 卡片是否已压暗（压暗是全树操作，不能每次都做） */
    private var cardsDimmed = false

    /**
     * 转屏 / 分屏变化时重算安全区（v1.6.44）。
     *
     * 本项目的 Activity 都声明了 `configChanges="orientation|screenSize"`，
     * 转屏不重建；多数 ROM 会顺带重新派发 insets，但**不保证**。
     * 不重新请求一次的话，横屏侧边的挖孔让位可能停在竖屏时的 0。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (appliesContentInsets) Insets.applyContent(this)
    }

    override fun onDestroy() {
        // 后台可能还有一张图在算：撤掉，别往即将销毁的窗口里贴
        ImagePipeline.cancel(bgTag)
        super.onDestroy()
    }

    /** 卡片降透明度，让背景图透上来（86% 下深色正文仍清晰）。 */
    private fun dimCards(root: View) {
        eachView(root) { v ->
            // 必须 mutate() 后再改色：同一份 android:background 资源常被多个 View
            // 共享同一个 GradientDrawable 实例，直接 setColor 会连带压暗别的页面。
            val bg = v.background?.mutate()
            if (bg is android.graphics.drawable.GradientDrawable) {
                val argb = runCatching { bg.color?.defaultColor }.getOrNull() ?: return@eachView
                originalCardColors.putIfAbsent(v, argb)
                val a = (android.graphics.Color.alpha(argb) * CARD_ALPHA_ON_BG).toInt()
                bg.setColor(
                    android.graphics.Color.argb(
                        a,
                        android.graphics.Color.red(argb),
                        android.graphics.Color.green(argb),
                        android.graphics.Color.blue(argb),
                    ),
                )
                v.background = bg
            }
        }
    }

    /** 复原卡片原来的不透明度（移除背景图后调用）。 */
    private fun restoreCardOpacity(root: View) {
        eachView(root) { v ->
            val saved = originalCardColors.remove(v) ?: return@eachView
            val bg = v.background?.mutate()
            if (bg is android.graphics.drawable.GradientDrawable) {
                bg.setColor(saved)
                v.background = bg
            }
        }
    }

    private fun eachView(v: View, visit: (View) -> Unit) {
        visit(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) eachView(v.getChildAt(i), visit)
        }
    }

    private companion object {
        /** 背景图启用时卡片的不透明度 */
        const val CARD_ALPHA_ON_BG = 0.86f
    }

    /** 内容根原始背景（首次应用前记下） */
    private val originalBackgrounds =
        java.util.WeakHashMap<View, android.graphics.drawable.Drawable?>()

    /** 卡片原始底色（首次压暗前记下） */
    private val originalCardColors = java.util.WeakHashMap<View, Int>()
}
