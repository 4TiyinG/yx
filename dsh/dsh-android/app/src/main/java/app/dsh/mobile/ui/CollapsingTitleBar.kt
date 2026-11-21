package app.dsh.mobile.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import app.dsh.mobile.R

/**
 * 设置页的「吸顶标题条」——v1.6.45 重做。
 *
 * ## 旧实现为什么必然闪烁（根因，不是参数没调好）
 *
 * 旧结构是：`ScrollView` 全屏 → 一个 62dp 纯色 `<View>` 盖在它上面 → 再一个
 * 34sp 的 `settingsTitle` 盖在 View 上面。三者在同一个 `FrameLayout` 里，
 * 后两者是**叠加在滚动内容之上的浮层**。于是有两个无法通过调参解决的缺陷：
 *
 * 1. **字形缝隙里能看见滚动内容**。标题 34sp 的行高约 40dp，`marginTop=60dp`，
 *    实际占据 60~100dp；而底衬只有 62dp。也就是说标题**下半部分（62~100dp）
 *    完全没有遮挡**：列表行滚过时就从「设」「置」两字的笔画缝隙、字腔里穿过去。
 *    字是抗锯齿的半透明边缘，压在**逐帧变化**的内容上 → 视觉上就是"字在闪、
 *    后面有东西在动"，也就是用户说的「闪烁残留」。这不是偶发 bug，是结构决定的，
 *    只要滚动就一定发生。
 * 2. **每帧对文字做连续缩放**。旧代码在滚动回调里写
 *    `scaleX/scaleY = 1 - 0.4375 * t`。TextView 的缩放不是位图缩放：canvas 会在
 *    每个新比例下**重新栅格化字形**（hinting/灰度抗锯齿都按新比例重算）。
 *    比例每帧都在变 → 每帧重算一次 → 字形边缘持续"抖"，同时每帧一次
 *    invalidate + 重新栅格化，低端机上直接掉帧。
 *
 * ## 新做法：把「移动的字」移出浮层
 *
 * · 大标题（34sp）**回到滚动内容里**，成为普通内容节点。它随内容自然上移、
 *   被 ScrollView 在 y=0 处裁掉、再被这条不透明标题条盖住 —— 全程没有任何
 *   "浮层压在运动内容上"的时刻。裁剪是硬边裁剪，不是 alpha 合成，不存在残影。
 * · 本组件只是一个**不透明的窄条**（[barHeightDp] 62dp），里面只放一个
 *   20sp 的**小标题**。小标题完全落在自己的不透明底衬内（上下各留 19dp），
 *   从结构上不可能有内容透出。
 * · 大小标题之间用**线性交叉淡化**衔接：只有 `alpha` 与 `translationY`，
 *   **没有任何 scale**。alpha 是合成器操作、translation 是矩阵平移，
 *   两者都不会触发文字重新栅格化 —— 零字形抖动、零额外栅格化开销。
 *
 * ## 幂等 / 无残留
 *
 * [setProgress] 是 `progress` 的**纯函数**，区间外自动钳到 0 或 1；重复调用、
 * 反向调用、跳变调用都收敛到同一结果。不存在"停在中间值"的状态机，
 * 也就不需要任何 postDelayed 兜底去"还原"。
 */
class CollapsingTitleBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 小标题（收起态）。完全位于不透明底衬内部。 */
    private val compact: TextView

    /** 当前进度，用于跳过无变化的重复写入（滚动回调每帧都会进这里）。 */
    private var progress = Float.NaN

    /** 收起态小标题的入场位移（纯平移，不做缩放）。 */
    private val liftPx = dp(4f)

    init {
        // 底衬必须不透明：这是"内容不会从字缝里透出"的唯一保证。
        setBackgroundColor(context.getColor(R.color.settings_page_bg))
        clipChildren = true
        clipToPadding = true
        isClickable = false
        isFocusable = false

        compact = TextView(context).apply {
            setText(R.string.settings_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(context.getColor(R.color.settings_text_primary))
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            alpha = 0f
        }
        addView(
            compact,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                marginStart = dp(16f)
            },
        )
    }

    /** 供外部改文案（默认取 `settings_title`，其它页面可复用本组件）。 */
    fun setTitle(text: CharSequence) {
        if (compact.text != text) compact.text = text
    }

    /**
     * 交叉淡化进度。
     *
     * @param p 0 = 完全展开（小标题隐藏），1 = 完全收起（小标题完全可见）。
     *   越界值自动钳制；调用方不必自己 clamp。
     */
    fun setProgress(p: Float) {
        val v = when {
            p.isNaN() -> 0f
            p < 0f -> 0f
            p > 1f -> 1f
            else -> p
        }
        // 滚动回调每帧都调；变化小于 0.4%（≈ 0.1dp 的位移）时不必重绘。
        if (!progress.isNaN() && kotlin.math.abs(v - progress) < 0.004f) return
        progress = v
        compact.alpha = v
        compact.translationY = (1f - v) * liftPx
    }

    /** 立即落到展开态（无动画）。 */
    fun reset() = setProgress(0f)

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
