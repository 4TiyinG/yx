package app.dsh.mobile.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import app.dsh.mobile.R

/**
 * 深度搜索的**跨页定位**（v1.6.41）。
 *
 * 搜索能命中二/三/四级页面里的设置项，但「跳过去」不能只是 `startActivity`
 * —— 用户落在一个长页面的顶部，还得自己翻。这里做三件事：
 *
 *  1. **携带锚点**：`Intent` 里带目标控件 id（以及触发它的关键词，供日志追踪）。
 *  2. **落地定位**：目标页 `setContentView` 之后自动滚到那个控件，位置取「偏上三分之一」。
 *  3. **高亮回执**：控件闪一次主题色底（1.4s 后还原），让用户一眼看到「就是这个开关」。
 *
 * 接入点只有一处：[app.dsh.mobile.ThemedActivity.setContentView] —— 所有设置类
 * Activity 都继承它，所以新增页面天然支持，不需要逐页改。
 *
 * ## 三条必须守住的边界
 *
 * ① **不假设「布局一次就绪」**：子页内容常在 `onCreate` 之后填充（动态行、异步
 *    读状态后才 `visibility` 变 VISIBLE）。这里轮询到控件 measurable 为止，
 *    最多 [MAX_TRIES] 次；超时静默放弃，定位失败不影响页面正常使用。
 *
 * ② **不改动页面状态**：某些设置项是**条件可见**的（例如「语音服务」里的
 *    API Key / Endpoint / Model 只在选中云端引擎时才显示）。如果把目标强行
 *    `VISIBLE`，用户会看到一个本不该出现的输入框 —— 比「没跳准」糟得多。
 *    所以这里只向上找**最近的可见祖先**作为落点，绝不写任何 `visibility`。
 *
 * ③ **一次性**：处理完立刻摘掉 extra。转屏 / 重建不会重复滚动（用户可能已经
 *    手动滑到别处，再被拽回去很烦）。
 */
object SearchAnchor {

    const val EXTRA_ANCHOR = "dsh.search.anchor"
    const val EXTRA_QUERY = "dsh.search.query"

    /**
     * 锚点的「文字精化提示」。
     *
     * 有些页面的行是**程序化生成**的（宠物设置整页由 `row(...)` 拼出来，没有
     * 逐行 id），锚点只能指到容器 `petContent`。这时把目标条目的文案一并带过来，
     * 落地后在容器里按文字找到那一行再滚 —— 于是「三级/四级页面里的具体子项」
     * 依然能精确落位，而不用为了搜索去给每行动态 View 造 id。
     */
    const val EXTRA_ANCHOR_TEXT = "dsh.search.anchor_text"

    /** 轮询次数 × 间隔 ≈ 最坏 600ms，覆盖大部分异步填充。 */
    private const val MAX_TRIES = 10
    private const val RETRY_MS = 60L
    private const val FLASH_MS = 1400L

    /** 超过这个高度就不闪（整块卡片闪一下很晃，滚动到位就够）。 */
    private const val FLASH_MAX_DP = 220f

    /** 构造跳到目标页的 Intent（带锚点与关键词）。 */
    fun intent(
        ctx: Context,
        target: Class<out Activity>,
        anchorId: Int,
        query: String,
        anchorText: String = "",
    ): Intent = Intent(ctx, target).apply {
        if (anchorId != 0) putExtra(EXTRA_ANCHOR, anchorId)
        if (query.isNotBlank()) putExtra(EXTRA_QUERY, query)
        if (anchorText.isNotBlank()) putExtra(EXTRA_ANCHOR_TEXT, anchorText)
    }

    /**
     * 若当前 Activity 是被搜索结果拉起来的，落地后定位到锚点。
     *
     * 幂等且一次性：处理完就把 extra 摘掉，转屏/重建不会重复滚。
     */
    fun consume(activity: Activity) {
        val intent = activity.intent ?: return
        val anchor = intent.getIntExtra(EXTRA_ANCHOR, 0)
        if (anchor == 0) return
        val anchorText = intent.getStringExtra(EXTRA_ANCHOR_TEXT).orEmpty()
        intent.removeExtra(EXTRA_ANCHOR)
        intent.removeExtra(EXTRA_ANCHOR_TEXT)

        val root = activity.findViewById<View>(android.R.id.content) ?: return
        attempt(activity, root, anchor, anchorText, 0)
    }

    private fun attempt(
        activity: Activity,
        root: View,
        anchor: Int,
        anchorText: String,
        tries: Int,
    ) {
        if (activity.isFinishing || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) return
        val hit = activity.findViewById<View>(anchor)
        // 先按文字在同一容器里精化（动态生成的行没有 id，但文案是稳定的）
        val refined = if (hit != null && anchorText.isNotBlank()) {
            findByText(hit, anchorText) ?: hit
        } else hit
        val target = refined?.let { nearestLaidOut(it, root) }
        if (target != null) {
            reveal(target, root)
            return
        }
        if (tries >= MAX_TRIES) return
        root.postDelayed({ attempt(activity, root, anchor, anchorText, tries + 1) }, RETRY_MS)
    }

    /** 在容器子树里找「文案等于 anchorText」的最深层 TextView（找不到返回 null）。 */
    private fun findByText(root: View, text: String): View? {
        val want = text.trim()
        if (want.isEmpty()) return null
        var found: View? = null
        fun walk(v: View) {
            if (v is android.widget.TextView && v.text?.toString()?.trim() == want) {
                found = v          // 继续往下找更深的（通常是行内标题）
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        // 命中的文本框本身不闪（太窄），交给它的父容器（整行）来高亮
        return found?.parent as? View ?: found
    }

    /**
     * 从命中控件向上找第一个「真的占位」的可见祖先。
     *
     * 命中控件自己 `GONE`、或父级还是折叠态时，直接滚它会拿到 0 坐标；
     * 退回最近的可见祖先，至少能落到正确的卡片上。永远不会改 visibility。
     */
    private fun nearestLaidOut(v: View, root: View): View? {
        var node: View? = v
        var guard = 0
        while (node != null && guard++ < 12) {
            if (node.visibility == View.VISIBLE && node.width > 0 && node.height > 0) return node
            if (node === root) return null
            node = node.parent as? View
        }
        return null
    }

    /** 把控件滚到视野内（纵向偏上 1/3），并按尺寸决定要不要闪一下。 */
    private fun reveal(target: View, root: View) {
        val scroller = verticalScroller(target, root)
        if (scroller != null) {
            val loc = IntArray(2)
            target.getLocationInWindow(loc)
            val sloc = IntArray(2)
            scroller.getLocationInWindow(sloc)
            // 目标在滚动内容里的偏移；减去 1/3 视口高度让它在偏上位置出现，
            // 而不是贴着顶栏（那样会被标题区挡住一半）。
            val y = scroller.scrollY + (loc[1] - sloc[1]) - scroller.height / 3
            val max = ((scroller.getChildAt(0)?.height ?: 0) - scroller.height).coerceAtLeast(0)
            scroller.smoothScrollTo(0, y.coerceIn(0, max))
        }
        val dp = target.resources.displayMetrics.density
        if (target.height in 1..(FLASH_MAX_DP * dp).toInt()) flash(target)
    }

    /** 最近的纵向可滚动祖先；找不到就退回根树下的第一个 ScrollView。 */
    private fun verticalScroller(v: View, root: View): ScrollView? {
        var p: android.view.ViewParent? = v.parent
        var guard = 0
        while (guard++ < 16) {
            val node = p as? View ?: break
            if (node is ScrollView) return node
            if (node is HorizontalScrollView) break
            p = node.parent
        }
        return firstScrollView(root)
    }

    private fun firstScrollView(v: View): ScrollView? {
        if (v is ScrollView) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                firstScrollView(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /**
     * 高亮：临时换成主题强调色底，1.4s 后还原。
     * 保存/还原原 drawable 引用，不会污染主题背景，也不影响页面自身状态。
     */
    private fun flash(view: View) {
        val ctx = view.context
        val original = view.background
        val radius = (ctx.resources.displayMetrics.density * 12f).toInt()
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(runCatching { ctx.getColor(R.color.accent_soft) }.getOrDefault(0xFFE0F2FE.toInt()))
        }
        view.postDelayed({ view.background = original }, FLASH_MS)
    }
}
