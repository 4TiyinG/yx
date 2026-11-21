package app.dsh.mobile.ui

import android.content.Context
import android.content.res.Configuration

/**
 * 全局布局断点（v1.6.44）。
 *
 * 取值与 `res/values/tokens.xml` 的 `bp_*` 一致，Material 3 的窗口尺寸类别：
 *
 * | 类别     | 宽（dp） | 典型设备                 | 本项目的布局策略              |
 * |---------|---------|-------------------------|----------------------------|
 * | compact | < 600   | 手机竖屏、上下折叠的折叠屏 | 单列，内容限宽 760          |
 * | medium  | ≥ 840   | 手机横屏、小平板          | 单列，限宽放宽到 1080        |
 * | expanded| ≥ 1100  | 平板横屏、折叠屏展开       | 单列但铺满，允许双栏（未启用） |
 *
 * ## 为什么横屏要单独一档，而不是沿用"宽度 > 760 就限宽 760"
 *
 * 手机横屏可用宽是 851dp。旧逻辑（`MaxWidthLinearLayout` 固定 760dp 上限）
 * 会在这个宽度上左右各留 45dp 空白 —— 横屏本该是"手游式铺满"，结果中间
 * 一条窄栏、两侧各一条空带，正是用户报的观感问题。
 *
 * 所以横屏（或任何宽 ≥ medium 的窗口）改用 1080dp 上限：手机横屏 851dp
 * 完全不被限制（铺满），平板 1280dp 才收到 1080dp（否则一行文字过长难读）。
 */
object Breakpoints {

    /** 低于此宽度算紧凑（手机竖屏）。 */
    const val COMPACT_DP = 600

    /** 中等窗口（手机横屏 / 小平板）：从这里开始放开横屏限宽。 */
    const val MEDIUM_DP = 840

    /** 展开窗口（平板横屏 / 折叠屏展开）。 */
    const val EXPANDED_DP = 1100

    /** 单列阅读型内容在竖屏下的限宽：太宽会难扫读。 */
    const val CONTENT_CAP_PORTRAIT_DP = 760

    /** 横屏 / 中等以上窗口的限宽：基本等于"不限"，只防超大平板。 */
    const val CONTENT_CAP_LANDSCAPE_DP = 1080

    /** 当前窗口可用宽（dp，已扣掉系统栏）。 */
    fun widthDp(context: Context): Int {
        val config: Configuration = context.resources.configuration
        return config.screenWidthDp
    }

    /** 是否为横屏几何（不含"已应用的渲染模式"，那属于 MainActivity 的 WebView 逻辑）。 */
    fun isLandscape(context: Context): Boolean {
        val config = context.resources.configuration
        // screenWidthDp 在小窗/分屏下也正确，比 Configuration.orientation 可靠
        return config.screenWidthDp > config.screenHeightDp
    }

    /**
     * 单列内容的限宽（dp）。返回 0 表示"不限制，铺满"。
     *
     * @param requestedCap 页面自己声明的限宽（`MaxWidthLinearLayout.maxWidthDp`）。
     *                     传 0 或负数时按本函数给的默认策略。
     */
    fun contentCap(context: Context, requestedCap: Int = 0): Int {
        val w = widthDp(context)
        if (w >= MEDIUM_DP) {
            // 中等以上：横屏/平板 —— 铺满，只在超大屏收一下防止行宽过长
            return if (requestedCap > 0 && requestedCap > CONTENT_CAP_LANDSCAPE_DP) {
                CONTENT_CAP_LANDSCAPE_DP
            } else if (requestedCap > 0) {
                0   // 页面自己声明了更小的限宽（< 1080），尊重它但不额外收紧
            } else {
                if (w > CONTENT_CAP_LANDSCAPE_DP) CONTENT_CAP_LANDSCAPE_DP else 0
            }
        }
        return if (requestedCap > 0) requestedCap else CONTENT_CAP_PORTRAIT_DP
    }
}
