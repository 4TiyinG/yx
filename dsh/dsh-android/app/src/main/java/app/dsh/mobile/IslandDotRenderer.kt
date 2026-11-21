package app.dsh.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import java.io.File

/**
 * 收起态灵动岛（状态栏收起后那个小圆点）的统一渲染器。
 *
 * ## 为什么要抽出来
 * 主界面真实的圆点、以及设置子页里的预览，必须长得**完全一样** —— 否则用户
 * 看到"预览是这样、收起来却是那样"。之前两边各写一套，结果预览漏掉了默认外观
 * 中间那个小亮核，与实际不符。现在两边都调 [apply]，天然一致。
 *
 * ## 默认外观的结构
 * 布局里是「容器（bg_dynamic_island_dot：外圈淡光环 + 内圈深色圆）+
 * 第一个子 View（bg_island_core_dot：中心 8dp 小亮核）」。设置自定义图后，
 * 容器背景换成图片、小亮核隐藏（整个圆点就是图片本身）。
 */
object IslandDotRenderer {

    /** 缓存条目上限。1 条目 ≈ 216²×4 ≈ 187 KB，4 条 ≈ 750 KB，够覆盖「圆/方 × 两个尺寸」。 */
    private const val CACHE_MAX_ENTRIES = 4

    /**
     * 按当前偏好渲染圆点。
     *
     * @param container 圆点容器；其第一个子 View 被当作"中心小亮核"处理（可为空）
     * @param sizeDpOverride 预览时可传固定尺寸；null 表示用用户设置的尺寸
     */
    fun apply(
        context: Context,
        container: View,
        sizeDpOverride: Int? = null,
        centerInParent: Boolean = false,
        applySize: Boolean = true,
    ) {
        if (applySize) {
            val sizeDp = sizeDpOverride ?: CustomVisualPref.islandDotSizeDp(context)
            val px = (sizeDp * context.resources.displayMetrics.density).toInt()
            container.layoutParams = container.layoutParams.apply {
                width = px
                height = px
                // 预览框里必须居中；真机的 handleBar 用 layout_gravity="top|center_horizontal"
                // 自己控制位置，这里不动它，否则会被拉到垂直居中、盖到状态栏上。
                if (centerInParent && this is android.widget.FrameLayout.LayoutParams) {
                    gravity = android.view.Gravity.CENTER
                }
            }
            container.requestLayout()
        }

        val core = (container as? ViewGroup)?.getChildAt(0)

        val file = CustomVisualPref.islandDotFile(context)
        if (file == null) {
            // 默认外观：外圈小光环 + 中心小亮核
            container.background = context.getDrawable(R.drawable.bg_dynamic_island_dot)
            core?.visibility = View.VISIBLE
            return
        }
        // 两种形状都先居中裁成正方形：直接铺原图会被容器拉伸变形（非方形素材尤其明显），
        // 圆形模式再额外描圆。这样"预览"与"实机"看到的构图一致。
        val shown = drawnFor(context, file)
        if (shown == null) {
            container.background = context.getDrawable(R.drawable.bg_dynamic_island_dot)
            core?.visibility = View.VISIBLE
            return
        }
        container.background = BitmapDrawable(context.resources, shown)
        // 整个圆点就是图片，中心小亮核不再需要
        core?.visibility = View.GONE
    }

    // ── 渲染结果缓存 ────────────────────────────────────────────────────────

    /**
     * 渲染缓存：一次「解码 + 裁剪 + 描圆」的产物，按需复用。
     *
     * ## 为什么必须有它
     *
     * [apply] 由 `setupHandleBar()` 和**每一次 `onResume`** 调用 ——
     * 也就是用户每次从别的应用切回来都会走一遍。旧实现每次都要：
     *
     *   1. `decodeFile` 解出一张 ARGB_8888；
     *   2. 非方形素材 `centerSquare` 再 `createBitmap` 一张；
     *   3. 圆形模式 `circular` 再新建一张位图 + Canvas 描圆。
     *
     * 三次分配、两到三份全尺寸位图，**且从不 recycle** —— 每次返回主界面都
     * 往堆里丢约 [DOT_MAX_PX]²×4×2 ≈ 370 KB 垃圾，用户感知就是「时不时顿一下」
     * （GC 尖峰落在 UI 线程）。这是典型的「同一份昂贵工作被重复做 N 次」。
     *
     * ## 键为什么是这三样
     *
     * `path + 文件 mtime/size + shape + 目标 px`：
     * · 换图 → path 或 mtime/size 变，必然失效；
     * · 改形状（圆/方）→ 两种图形完全不同，必须分开存（共用一份会显示错形状）；
     * · 改尺寸 → 缩放质量与像素数不同，分开存。
     *
     * 全部命中时后续调用是纯查表，不碰磁盘、不建位图。
     */
    private val drawnCache = LruCache<String, Bitmap>(CACHE_MAX_ENTRIES)

    /**
     * 取（或算出）圆点最终要显示的位图。失败返回 null，由调用方退回默认外观。
     *
     * @param screenPx 该位图最终会被画到的像素边长；只用于缓存键，不参与缩放
     *   （缩放在 `BitmapDrawable` 里由 View 的绘制矩阵完成，质量足够且省一次位图分配）
     */
    private fun drawnFor(context: Context, file: File): Bitmap? {
        val shape = CustomVisualPref.islandDotShape(context)
        val px = (CustomVisualPref.islandDotSizeDp(context) * context.resources.displayMetrics.density)
            .toInt()
        val key = buildString(64) {
            append(file.absolutePath)
            append('|').append(file.lastModified())
            append('|').append(file.length())
            append('|').append(shape.name)
            append('|').append(px)
        }
        drawnCache.get(key)?.let { return it }

        val bmp = runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }
            .getOrNull() ?: return null
        val square = centerSquare(bmp)
        val shown = when (shape) {
            CustomVisualPref.DotShape.CIRCLE -> circular(square) ?: square
            CustomVisualPref.DotShape.SQUARE -> square
        }
        // 中间产物用完即回收：square 与 bmp 若与产物不同就必须显式释放，
        // 否则缓存只留一份、中间份照样泄漏。
        if (square !== shown && !square.isRecycled) square.recycle()
        if (bmp !== square && bmp !== shown && !bmp.isRecycled) bmp.recycle()

        if (!shown.isRecycled) drawnCache.put(key, shown)
        return shown
    }

    /** 居中裁成正方形（不变形）；已是正方形时原样返回。 */
    fun centerSquare(src: Bitmap): Bitmap =
        if (src.width == src.height) src
        else {
            val size = minOf(src.width, src.height)
            Bitmap.createBitmap(
                src, (src.width - size) / 2, (src.height - size) / 2, size, size,
            )
        }

    /** 在正方形位图上描一个内切圆；入参已是正方形，不再二次裁切。 */
    fun circular(square: Bitmap): Bitmap? = runCatching {
        val size = square.width
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val r = size / 2f
        canvas.drawCircle(r, r, r, paint)
        out
    }.getOrNull()
}
