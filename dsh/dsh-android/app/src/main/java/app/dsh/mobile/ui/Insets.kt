package app.dsh.mobile.ui

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ListView
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import app.dsh.mobile.ThemeManager

/**
 * 全局安全区装饰器（v1.6.44）。
 *
 * ## 这个文件解决什么
 *
 * 重构前所有页面的安全区逻辑都挤在 103 行的 `EdgeToEdge` 里，有三处结构性缺陷：
 *
 * 1. **只认 `systemBars()`**。刘海/挖孔屏的 cutout 是**独立**的 inset 类型，
 *    在某些 ROM 上 `systemBars()` 不含挖孔区（尤其是横屏时挖孔跑到侧边）。
 *    结果：横屏下左侧或右侧的控件被挖孔压住。
 * 2. **滚动容器的底部内边距只处理 `ScrollView`**。列表页用的是 `ListView`
 *    （日志、权限、扩展列表），最后一行的底部会被手势条盖住 ——
 *    这是"无遮挡"验收里最容易漏的一类。
 * 3. **没有 `onConfigurationChanged` 重算**。这些 Activity 都声明了
 *    `configChanges="orientation|screenSize"`，转屏不重建，如果系统没重新派发
 *    insets（部分 ROM 确实不派发），左右让位就会停在竖屏时的值。
 *
 * ## 分层
 *
 *  · [applyWindow]  管窗口：透明系统栏、edge-to-edge、状态栏显隐、图标明暗。
 *  · [applyContent] 管内容：把 insets 转成 padding。幂等，可重复调用。
 *
 * ## 为什么竖屏/横屏用同一套代码
 *
 * 横屏和竖屏的差别只是"inset 落在哪条边"。写两套分支必然漂移
 * （v1.6.42 的横屏让位就是这么出错的）。这里只做一次通用映射：
 * 四边 insets → 四边 padding，谁不为 0 就让谁。
 *
 * ## v1.6.50：为什么两种模式都 `decorFitsSystemWindows = false`
 *
 * 关闭边到边时，旧实现让系统接管内容框（`decorFits = true`）。代价是
 * **底部导航栏高度变成一条与页面割裂的主题色空条**，而且顶部还多让了一次
 *（系统让 + 我们按开关再让）—— 用户报的"上下都有大片空白"就是这个。
 *
 * 现在窗口级 inset **一律由我们分发**（不传就是跟随开关读偏好，见下面的参数）：
 *  · 顶部：紧凑模式仍保留状态栏高度（那是"合理间距"，页面头部不会被状态栏压住），
 *    但边到边模式下页面上沿一直铺到 y=0；
 *  · 底部：两种模式都不再留整条导航栏的空条 —— 内容画到屏幕底边，
 *    可滚动容器靠内容底部 padding 保证最后一项仍能滚出来。
 */
object Insets {

    /**
     * 窗口级设置：系统栏透明 + 内容画到栏后 + 图标明暗跟随主题。
     *
     * @param darkSurface 本页背景恒为深色（控制台页）。此时图标必须恒为浅色，
     *                    不能按主题判断 —— 否则浅色主题下会得到"深色图标压在
     *                    深色终端上"。
     * @param edgeToEdge  是否边到边（沉浸式）。默认值直接读用户偏好 ——
     *                    这样**所有调用点**（含直接调 Insets 的历史代码）都自动跟随开关，
     *                    不需要在 20+ 个 Activity 里各判一次。
     *                    · true：系统栏透明、内容画到栏后；状态栏按 [hideStatusBar] 显隐；
     *                    · false（紧凑）：状态栏/导航栏都透明（底色交给页面自己铺），
     *                      内容由 [applyContent] 让到状态栏下沿，底部不再让整条导航栏。
     * @param hideStatusBar 是否隐藏状态栏。**只在本页处于边到边模式时生效** ——
     *                    紧凑模式必须保留状态栏（那是用户选的"带状态栏"形态）。
     *                    默认值同样直接读偏好，调用点不必再判一次。
     */
    fun applyWindow(
        activity: Activity,
        darkSurface: Boolean = false,
        edgeToEdge: Boolean = app.dsh.mobile.MainActivity.edgeToEdgeEnabled(activity),
        hideStatusBar: Boolean = app.dsh.mobile.MainActivity.statusBarHidden(activity),
    ) {
        val w = activity.window ?: return

        // 两种模式都要透明：
        //  · 边到边：内容本来就画到栏后，不透明栏会把内容盖掉；
        //  · 紧凑：状态栏那一条**交给页面/页面根布局的底色**（v1.6.50）。
        //    旧实现把栏涂成主题底色，于是顶部永远是一条与页面割裂的空条 ——
        //    用户报的"顶部留白过大"正是它。透明之后，同一段像素由页面自己的
        //    背景填满，视觉上顶部就"短"了一截（状态栏本身仍是"合理间距"）。
        @Suppress("DEPRECATION")
        w.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        w.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= 29) {
            // 对比度 scrim 会在透明栏上再补一层半透明底 —— 那就是"空条"的另一种
            // 形态（浅色页面上尤其明显）。两模式都关掉。
            @Suppress("DEPRECATION")
            w.isStatusBarContrastEnforced = false
            @Suppress("DEPRECATION")
            w.isNavigationBarContrastEnforced = false
        }
        // 关键：两种模式都由我们自己做 inset 分发。`true` 会让系统把内容框
        // 缩到系统栏之间 —— 底部那条空条就是它造成的，且顶部会与我们的让位叠加。
        WindowCompat.setDecorFitsSystemWindows(w, false)

        @Suppress("DEPRECATION")
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val controller = WindowCompat.getInsetsController(w, w.decorView)
        // 系统栏行为：隐藏时允许"从边缘下滑临时唤出"，否则用户在没有状态栏的
        // 全屏态里连通知都看不到（且无法用下滑手势确认手势条还在）。
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (edgeToEdge && hideStatusBar) {
            // 只隐藏状态栏；导航栏保持可见（手势条/三键键位是主要返回途径）。
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }

        val lightIcons = if (darkSurface) false else !ThemeManager.isDark(activity)
        controller.isAppearanceLightStatusBars = lightIcons
        controller.isAppearanceLightNavigationBars = lightIcons
    }

    /**
     * 内容级安全区：把 insets 映射成根布局 padding。
     *
     * 布局约定（与 [applyContent] 的契约，改动需同步探针）：
     *  · 根布局**不留底部 padding**：背景与滚动内容延伸到手势条后方，
     *    沉浸感来自"内容画到边"，而不是"内容缩在安全区里"；
     *  · 滚动容器的**内容底部**补上 inset（`clipToPadding=false`），
     *    这样最后一项能滚出来，又不会被手势条压住；
     *  · 没有滚动容器的页（终端、编辑器）才给根留底部 padding。
     */
    fun applyContent(
        activity: Activity,
        edgeToEdge: Boolean = app.dsh.mobile.MainActivity.edgeToEdgeEnabled(activity),
    ) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) as? View ?: return
        val base = basePaddings.getOrPut(root) {
            intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        }

        // v1.6.50：**只有一条路径**。
        //
        // 旧实现有一条 `if (!edgeToEdge)` 的"还原分支"（摘监听器 + 把 padding 写回
        // 原值），因为那时紧凑模式让系统接管内容框，我们再补 padding 就是二次内缩。
        // 现在两种模式都是 decorFits=false（见 [applyWindow]），系统不再替任何页面
        // 让位 —— 也就不存在"两份 inset 打架"，还原分支自然消失。
        // 少一条分支 = 少一类"切换后残留旧 padding"的漂移（v1.6.47 修过一次）。
        //
        // [edgeToEdge] 参数保留：调用点仍按它传值，只是不再分流（避免 20+ 处
        // 调用点同时改动带来的风险；行为差异现在全部集中在 applyWindow 与
        // MainActivity 的页面内让位里）。
        content.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            // 顶部 / 左右：系统栏 ∪ 挖孔。横屏时挖孔落在侧边，靠这一行让开。
            root.updatePadding(
                left = base[0] + bars.left,
                top = base[1] + bars.top,
                right = base[2] + bars.right,
                bottom = base[3],
            )
            val consumedByScroll = applyScrollableBottomPadding(root, bars.bottom)
            if (!consumedByScroll) {
                root.updatePadding(bottom = base[3] + bars.bottom)
            }
            // 不消费：MainActivity 的 WebView 还要用这些 insets 算页内让位。
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }

    /**
     * 给滚动容器补底部内容内边距。
     *
     * 覆盖 `ScrollView` / `NestedScrollView`（运行时是 AndroidX 的实现类，继承
     * `ScrollView` 也能命中）/ `ListView` / `HorizontalScrollView` 的垂直方向内容高。
     *
     * @return 是否找到并处理了纵向滚动容器
     */
    private fun applyScrollableBottomPadding(root: View, insetBottom: Int): Boolean {
        var found = false

        fun visit(v: View) {
            when {
                // ListView / GridView / RecyclerView 这类"虚拟化列表"：
                // 补的是列表自己的底部 padding，配合 clipToPadding=false
                // 让最后一项能滚到手势条上方。
                v is ListView -> {
                    found = true
                    val original = scrollBasePaddings.getOrPut(v) { v.paddingBottom }
                    val want = original + insetBottom
                    if (v.paddingBottom != want) {
                        v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, want)
                    }
                    v.clipToPadding = false
                }
                // AndroidX 的 NestedScrollView 不是 ScrollView 子类，需单独判断
                v is androidx.core.widget.NestedScrollView -> {
                    found = true
                    val original = scrollBasePaddings.getOrPut(v) { v.paddingBottom }
                    val want = original + insetBottom
                    if (v.paddingBottom != want) {
                        v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, want)
                    }
                    v.clipToPadding = false
                }
                v is ScrollView -> {
                    found = true
                    val original = scrollBasePaddings.getOrPut(v) { v.paddingBottom }
                    val want = original + insetBottom
                    if (v.paddingBottom != want) {
                        v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, want)
                    }
                    v.clipToPadding = false
                }
                v is HorizontalScrollView -> {
                    // 横向滚动条自身不占纵向空间，但它里面的工具栏常在底部；
                    // 这里只标记"页面可滚动"，底部让给根 padding 处理。
                    found = true
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) visit(v.getChildAt(i))
            }
        }
        visit(root)
        return found
    }

    private val basePaddings = java.util.WeakHashMap<View, IntArray>()
    private val scrollBasePaddings = java.util.WeakHashMap<View, Int>()
}
