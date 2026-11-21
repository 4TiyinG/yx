package app.dsh.mobile

import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.ui.ImagePipeline

/**
 * 展开态状态栏设置（v1.5.x）：背景图 + 贴纸。
 *
 * ## 预览为什么按比例
 * 真机状态栏是「撑满宽度、44dp 高」的胶囊。预览区里按接近的比例缩一条出来，
 * 贴纸位置用**相对比例**（0~1）记录，这样预览和真机、横竖屏、不同机型之间都
 * 对得上 —— 拖到哪儿就贴在哪儿。
 *
 * ## 贴纸交互
 *  · 添加：选图后默认落在状态栏中央；
 *  · 拖动：按住拖动改变位置（松手写回偏好）；
 *  · 删除：按住不动 600ms 即移除该贴纸（批量清空用下方「清空贴纸」按钮）。
 */
class ExpandedStatusBarActivity : ThemedActivity() {

    /** 属于设置体系：启用「设置页背景图」。 */
    override val usesSettingsBackground: Boolean get() = true

    private lateinit var preview: FrameLayout
    private lateinit var txtHint: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_status_expanded)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        preview = findViewById(R.id.previewStatusBar)
        txtHint = findViewById(R.id.txtPreviewHint)

        findViewById<View>(R.id.btnPickBg).setOnClickListener {
            pickImage(REQ_BG)
        }
        findViewById<View>(R.id.btnRemoveBg).setOnClickListener {
            CustomVisualPref.clearExpandedBg(this)
            render()
        }
        findViewById<View>(R.id.btnAddSticker).setOnClickListener {
            pickImage(REQ_STICKER)
        }
        findViewById<View>(R.id.btnClearStickers).setOnClickListener {
            StickerPref.clearAll(this)
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        OrientationPrefs.applyToSettingsPage(this)
        render()
    }

    /** 重画预览：背景 + 全部贴纸。
     *
     * ## v1.6.31：三个开销从这里接走
     *
     *  1. **背景同步解码**：旧写法在本方法里直接 `decodeFile` 整张（导入上限 1440 宽），
     *     而预览区只有一条 44dp 高的胶囊——解码量比真正要画的像素多一个数量级。
     *     现在按预览区实际尺寸采样解码，且把解码挪到后台。
     *  2. **贴纸逐张解码**：同样改为后台 + 按显示边长解码。
     *  3. **无修正重建**：旧写法每次 `onResume` 都 `removeAllViews()` 把贴纸全部
     *     删了重建（每次都触发一轮 measure/layout）。现在先把当前状态算成一个
     *     签名字符串，没变就整段跳过。
     */
    private fun render() {
        val bgFile = CustomVisualPref.expandedBgFile(this)
        val items = StickerPref.list(this)

        // 签名：图/贴纸/容器尺寸任一变化才重建；否则一个 View 都不碰
        val signature = buildString {
            append(bgFile?.let { "${it.absolutePath}|${it.lastModified()}|${it.length()}" } ?: "-")
            append('|').append(preview.width).append('x').append(preview.height)
            items.forEach {
                append('|').append(it.file).append('@').append(it.xRatio)
                    .append(',').append(it.yRatio).append(',').append(it.sizeDp)
            }
        }
        if (signature == lastRenderKey) return
        lastRenderKey = signature

        // 目标尺寸：容器尺寸还没量出来时（首次 onResume）用屏宽估算
        val targetW = preview.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels * 3 / 4)
        val targetH = preview.height.takeIf { it > 0 }
            ?: (44 * resources.displayMetrics.density).toInt()

        // 背景：有自定义图就铺图，否则用默认胶囊
        if (bgFile != null) {
            ImagePipeline.requestThumb(BG_TAG, bgFile, targetW, targetH) { bmp ->
                if (isFinishing || isDestroyed) return@requestThumb
                preview.background = BitmapDrawable(resources, bmp).apply {
                    gravity = android.view.Gravity.FILL
                }
            }
        } else {
            ImagePipeline.cancel(BG_TAG)
            preview.setBackgroundResource(R.drawable.bg_dynamic_island)
        }

        // 贴纸：清掉旧 View 再按当前列表重建（签名保证只有内容真变了才走到这里）
        preview.removeAllViews()
        txtHint.text = if (items.isEmpty()) {
            getString(R.string.setting_sticker_hint)
        } else {
            getString(R.string.setting_sticker_count, items.size)
        }

        items.forEachIndexed { index, sticker ->
            val iv = ImageView(this)
            val sizePx = (sticker.sizeDp * resources.displayMetrics.density).toInt()
            iv.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            // 位置要在布局完成后再算（那时才知道容器宽高）
            iv.post { placeSticker(iv, sticker, sizePx) }
            attachDrag(iv, sticker.file, sizePx)
            preview.addView(iv)

            val file = StickerPref.fileOf(this, sticker) ?: return@forEachIndexed
            // 按**显示边长**解码：贴纸落盘时是 256px，最大显示 64dp（≈192px），
            // 多出来的像素只有在上传纹理时白烧。
            ImagePipeline.requestThumb("$STICKER_TAG_PREFIX$index", file, sizePx, sizePx) { bmp ->
                if (isFinishing || isDestroyed) return@requestThumb
                // 数字变大时索引可能已越界：越界就不贴，避免贴到别人的控件上
                if (preview.indexOfChild(iv) < 0) return@requestThumb
                iv.setImageBitmap(bmp)
            }
        }
        // 贴纸数量变少时，多出来的 tag 也要撤
        for (i in items.size until stickerTagHighWater) {
            ImagePipeline.cancel("$STICKER_TAG_PREFIX$i")
        }
        stickerTagHighWater = items.size
    }

    override fun onDestroy() {
        ImagePipeline.cancel(BG_TAG)
        for (i in 0 until stickerTagHighWater) ImagePipeline.cancel("$STICKER_TAG_PREFIX$i")
        super.onDestroy()
    }

    /** 上一次渲染的签名；相同就整段跳过（避免每次 onResume 重建贴纸 View） */
    private var lastRenderKey: String? = null

    /** 曾经用过的最大贴纸 tag 序号：用于回收已不存在的贴纸对应请求 */
    private var stickerTagHighWater = 0


    /** 按相对比例把贴纸摆到预览容器里（以贴纸中心为锚点，并夹在容器内）。 */
    private fun placeSticker(v: View, sticker: StickerPref.Sticker, sizePx: Int) {
        val w = preview.width
        val h = preview.height
        if (w <= 0 || h <= 0) return
        // 上限夹到 >= 0：贴纸可以比预览容器还高，负的上界会让 coerceIn 抛异常
        v.x = (sticker.xRatio * w - sizePx / 2f)
            .coerceIn(0f, maxOf(0f, (w - sizePx).toFloat()))
        v.y = (sticker.yRatio * h - sizePx / 2f)
            .coerceIn(0f, maxOf(0f, (h - sizePx).toFloat()))
    }

    /**
     * 拖动贴纸：跟随手指，松手后把新位置写回偏好；按住不动 600ms 删除。
     *
     * ## 长按为什么得自己实现
     * `setOnTouchListener` 一旦返回 true 就吃掉了整个手势，`setOnLongClickListener`
     * 再也收不到派发 —— 长按删除会静默失效，而这个贴纸没有别的删除入口，等于删不掉。
     * 所以长按在这里手动实现：ACTION_DOWN 挂一个延时任务，手指抬起或位移超过触摸
     * 阈值（说明在拖动）就撤掉它。
     *
     * @param fileName 用文件名而不是列表下标定位 —— render() 会重建整个列表，
     *   闭包里存的 index 可能已经指向别的贴纸，那样长按会误删。
     */
    private fun attachDrag(v: View, fileName: String, sizePx: Int) {
        var startX = 0f
        var startY = 0f
        var originX = 0f
        var originY = 0f
        val longPress = Runnable {
            if (StickerPref.removeByFile(this, fileName)) {
                render()
                Toast.makeText(this, R.string.action_remove_sticker, Toast.LENGTH_SHORT).show()
            }
        }
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX
                    startY = ev.rawY
                    originX = view.x
                    originY = view.y
                    view.bringToFront()
                    view.postDelayed(longPress, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startX
                    val dy = ev.rawY - startY
                    // 真开始拖了就别再算长按，否则松手前的停顿会误删
                    if (dx * dx + dy * dy > touchSlopSq) view.removeCallbacks(longPress)
                    view.x = (originX + dx)
                        .coerceIn(0f, (preview.width - sizePx).coerceAtLeast(0).toFloat())
                    view.y = (originY + dy)
                        .coerceIn(0f, (preview.height - sizePx).coerceAtLeast(0).toFloat())
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPress)
                    // 中心点 → 相对比例（与 placeSticker 的换算互逆）
                    val cx = (view.x + sizePx / 2f) / preview.width.coerceAtLeast(1)
                    val cy = (view.y + sizePx / 2f) / preview.height.coerceAtLeast(1)
                    StickerPref.moveByFile(this, fileName, cx, cy)
                    true
                }
                else -> false
            }
        }
    }

    private fun pickImage(req: Int) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        runCatching { startActivityForResult(intent, req) }.onFailure {
            Toast.makeText(this, R.string.setting_image_none, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_BG -> {
                if (!CustomVisualPref.importExpandedBg(this, uri)) {
                    Toast.makeText(this, R.string.setting_image_none, Toast.LENGTH_SHORT).show()
                    return
                }
                render()
            }
            REQ_STICKER -> {
                if (StickerPref.add(this, uri) == null) {
                    Toast.makeText(this, R.string.setting_image_none, Toast.LENGTH_SHORT).show()
                    return
                }
                render()
            }
        }
    }

    private companion object {
        const val REQ_BG = 4313
        const val REQ_STICKER = 4314

        /** 背景预览的解码 tag */
        const val BG_TAG = "expanded-bg"

        /** 贴纸解码 tag 前缀（后接索引，一张贴纸一个 tag） */
        const val STICKER_TAG_PREFIX = "expanded-sticker-"

        /** 按住多久算长按（删除贴纸）。 */
        const val LONG_PRESS_MS = 600L
    }

    /** 系统触摸阈值（平方），超过它就算在拖动，撤掉长按任务。 */
    private val touchSlopSq: Int by lazy {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        slop * slop
    }
}
