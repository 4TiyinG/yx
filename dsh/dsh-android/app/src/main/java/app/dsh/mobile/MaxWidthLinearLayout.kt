package app.dsh.mobile

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import app.dsh.mobile.ui.Breakpoints

/**
 * 限宽 LinearLayout（v1.4.18 引入，v1.6.44 改为按窗口尺寸自适应）。
 *
 * ## 作用
 *
 * 让设置类页面的**单列内容**在超宽窗口下不要被拉成一条超宽行；宽度不够时
 * 则完全铺满。宽度上限由 [Breakpoints.contentCap] 决定：
 *
 * | 窗口宽（dp） | 上限（dp） | 效果 |
 * |---|---|---|
 * | < 600（手机竖屏） | 760 | 内容收窄、居中，利于扫读 |
 * | 600–840 | 760 | 平板竖屏 / 分屏，仍按阅读宽度 |
 * | ≥ 840（手机横屏） | 0（不限） | **铺满**，这是 v1.6.44 的关键变化 |
 * | > 1080（平板横屏） | 1080 | 铺满但收一下，避免行宽过长 |
 *
 * ## v1.6.44 为什么要改
 *
 * 旧实现固定 760dp：手机横屏可用宽 851dp 时左右各留 45dp、平板横屏 1280dp 时
 * 左右各留 260dp —— 横屏本该"手游式铺满"，结果中间一条窄栏、两侧空带。
 *
 * ## 为什么用自定义 View 而不是 `layout-land`
 *
 * 这些 Activity 都声明了 `configChanges="orientation|screenSize"`，转屏
 * **不会重建、不会重新 inflate** —— `layout-land` 只有在以横屏启动时才生效。
 * 自定义 onMeasure 每次布局都按当前窗口重新算，因此转屏、分屏、折叠屏展开/合起
 * 都能立即生效，也不会丢滚动位置或打断正在进行的导出/安装任务。
 */
class MaxWidthLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /**
     * 页面自己声明的最大内容宽度（dp）；<= 0 表示"按全局断点策略走"。
     *
     * 设为正数时它只是**上限的上限**：竖屏仍可被全局策略收到 760，
     * 横屏不会被强制收窄（横屏的诉求是铺满）。
     */
    var maxWidthDp: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = Breakpoints.contentCap(context, maxWidthDp)
        if (cap <= 0) {
            // 不限：直接铺满（横屏 / 中等以上窗口）
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val capPx = (cap * resources.displayMetrics.density).toInt()
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val available = MeasureSpec.getSize(widthMeasureSpec)
        /**
         * 只在"可用宽 > 上限"时收窄。
         *
         * `UNSPECIFIED` 表示父容器不限宽（例如放在 ScrollView 里且未给
         * `fillViewport`）—— 此时必须给一个具体宽，否则 wrap_content 会退化成
         * "按内容宽"，列表页会缩成一条竖线。
         */
        val width = when (mode) {
            MeasureSpec.UNSPECIFIED -> capPx
            else -> minOf(available, capPx)
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, mode), heightMeasureSpec)
    }

    companion object {
        /** 旧默认值，保留给仍是 `maxWidthDp = 760` 的调用点做兼容。 */
        const val DEFAULT_MAX_WIDTH_DP = Breakpoints.CONTENT_CAP_PORTRAIT_DP
    }
}
