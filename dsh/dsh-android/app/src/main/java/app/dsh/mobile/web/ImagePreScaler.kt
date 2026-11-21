package app.dsh.mobile.web

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import app.dsh.mobile.ui.ThermalGuard
import java.io.File
import java.io.FileOutputStream

/**
 * 图片解码/缩放前移（v1.6.36）。
 *
 * ## 要解决的问题
 * 本地长图、截图在 WebView 里打开时，Chromium 会按**原始尺寸**解码再上传纹理：
 * 一张 4000×3000 的 JPEG 解码后是 ~46MB 的位图，一次纹理上传就吃掉大量内存带宽，
 * 而它在手机上最终只显示到 ~1080px 宽。这部分工作是**可以在热余量窗口提前做完的**——
 * 提前做成「显示尺寸」的小图，WebView 到时解一张小图，热受限窗口里就只剩很轻的活。
 *
 * ## 关键实现点
 * 1. **目标尺寸解码**：先 `inJustDecodeBounds` 只读尺寸，再按 [inSampleSize][BitmapFactory.Options.inSampleSize]
 *    跳采样到「接近目标」的层级 —— 绝不把原图整张解进内存。
 * 2. **inBitmap 复用**：同尺寸的输出位图从池里复用，避免反复分配大块内存
 *    （Android 8+ 对 inBitmap 的要求宽松，这里只用「池里尺寸完全匹配」的安全做法）。
 * 3. **后台 HandlerThread**：解码全程在独立线程，绝不占主线程帧预算。
 * 4. **热状态驱动**：`ThermalGuard.canPreDecode == false`（MODERATE 及以上）时
 *    **暂停**而不是丢弃 —— 任务重新排队，等降温后补做。这正是「把预热解码移出
 *    热受限窗口」的落地。
 *
 * ## 输出
 * 生成 `*.d` 变体文件（display-size），由 [WebAssetCache] 在拦截时优先供给。
 * 变体只写一次；失败则用原图，功能零影响。
 */
object ImagePreScaler {

    private const val TAG = "ImgPreScale"

    /** 变体文件后缀（与原名同目录，`name.ext.d`）。 */
    const val VARIANT_SUFFIX = ".d"

    /** 默认目标最大边：覆盖绝大多数手机竖屏物理宽度，够清晰又不浪费带宽。 */
    const val DEFAULT_MAX_EDGE = 1280

    /** 小于这个字节数、且尺寸也不超标的图不值得再生成变体。 */
    private const val MIN_SOURCE_BYTES = 120L * 1024

    /** 变体质量：WebP 有损 82 在手机上与原图肉眼几乎无差。 */
    private const val QUALITY = 82

    /** 暂停时的重试间隔（降温后再来）。 */
    private const val RETRY_MS = 20_000L

    private val lock = Any()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var started = false

    private val scaled = java.util.concurrent.atomic.AtomicInteger()
    private val skipped = java.util.concurrent.atomic.AtomicInteger()
    private val failed = java.util.concurrent.atomic.AtomicInteger()
    private val paused = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var savedBytes = 0L

    /** inBitmap 复用池：按「宽×高」索引，只放 ARGB_8888。 */
    private val pool = HashMap<Long, Bitmap>()
    private const val POOL_MAX = 4

    private fun ensureThread() {
        if (started) return
        synchronized(lock) {
            if (started) return
            val t = HandlerThread("img-prescale").apply { start() }
            thread = t
            handler = Handler(t.looper)
            started = true
        }
    }

    /**
     * 为 [src] 生成显示尺寸变体。
     *
     * 幂等：变体已存在且不旧于源文件 → 直接返回。
     * 线程：从任意线程调用，内部投递到后台线程。
     */
    /**
     * 运行时开关（v1.6.45，对应设置里的「大图预解码」）。
     *
     * 默认**开**：与改动前行为一致。关闭后 [submit] 直接返回，已生成的变体文件
     * 仍会被 [WebAssetCache] 优先供给 —— 也就是"关掉只是不再产生新变体"，
     * 不会反过来让已优化的页面变慢，也不需要清理任何东西。
     */
    @Volatile private var enabled = true

    fun setEnabled(on: Boolean) {
        enabled = on
        Log.i(TAG, "prescale ${if (on) "enabled" else "disabled"}")
    }

    val isEnabled: Boolean get() = enabled

    fun submit(src: File, maxEdge: Int = DEFAULT_MAX_EDGE) {
        if (!enabled) return
        if (!src.isFile) return
        val dst = File(src.parentFile, src.name + VARIANT_SUFFIX)
        if (dst.isFile && dst.lastModified() >= src.lastModified()) return
        if (src.length() < MIN_SOURCE_BYTES) {
            // 小图当场判断尺寸，可能确实不用变体
            val bounds = boundsOf(src) ?: return
            if (maxOf(bounds.first, bounds.second) <= maxEdge) {
                skipped.incrementAndGet()
                return
            }
        }
        ensureThread()
        handler?.post { work(src, dst, maxEdge) }
    }

    private fun work(src: File, dst: File, maxEdge: Int) {
        // 热受限：不丢任务，改期 —— 降温后自动补做
        if (!ThermalGuard.canPreDecode) {
            paused.incrementAndGet()
            handler?.postDelayed({ work(src, dst, maxEdge) }, RETRY_MS)
            return
        }
        runCatching {
            val bounds = boundsOf(src) ?: return@runCatching
            val (ow, oh) = bounds
            val longest = maxOf(ow, oh)
            if (longest <= maxEdge) {
                skipped.incrementAndGet()
                return@runCatching
            }

            // 1) 跳采样解码到「接近目标」的层级（不读整张原图）
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                inSampleSize = sampleSize(ow, oh, maxEdge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
                // 池里若有同尺寸位图，直接解进它（省一次分配）
                poolRemove((ow / inSampleSize).toLong() shl 32 or (oh / inSampleSize).toLong())
                    ?.let { inBitmap = it }
            }
            val sampled = BitmapFactory.decodeFile(src.absolutePath, opts)
                ?: run {
                    failed.incrementAndGet()
                    return@runCatching
                }

            // 2) 缩到目标尺寸（采样后通常只差一点，缩放很便宜）
            val scale = maxEdge.toFloat() / maxOf(sampled.width, sampled.height)
            val out = if (scale < 1f) {
                val tw = (sampled.width * scale).toInt().coerceAtLeast(1)
                val th = (sampled.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(sampled, tw, th, true).also { b ->
                    if (b !== sampled) {
                        poolPut(sampled.width.toLong() shl 32 or sampled.height.toLong(), sampled)
                    }
                }
            } else {
                sampled
            }

            // 3) 写变体（先临时文件再改名，避免半截文件被拦截读到）
            val tmp = File(dst.parentFile, dst.name + ".tmp")
            FileOutputStream(tmp).use { fos ->
                val fmt = if (android.os.Build.VERSION.SDK_INT >= 30) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                out.compress(fmt, QUALITY, fos)
                fos.flush()
            }
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
            if (out !== sampled) out.recycle()
            scaled.incrementAndGet()
            savedBytes += (src.length() - dst.length()).coerceAtLeast(0L)
            Log.i(
                TAG,
                "variant ${src.name}: ${ow}x$oh -> ${dst.length() / 1024}KB " +
                    "(from ${src.length() / 1024}KB), ${ThermalGuard.current.label}",
            )
        }.onFailure { failed.incrementAndGet(); Log.w(TAG, "scale ${src.name} failed: ${it.message}") }
    }

    /** 只读尺寸，不解像素。 */
    private fun boundsOf(f: File): Pair<Int, Int>? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) null else o.outWidth to o.outHeight
    }.getOrNull()

    /** 2 的幂跳采样：解出的尺寸不小于目标（再由 createScaledBitmap 精修）。 */
    private fun sampleSize(w: Int, h: Int, target: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= target) s *= 2
        return s.coerceAtLeast(1)
    }

    private fun poolKey(w: Long, h: Long) = (w shl 32) or h

    private fun poolRemove(key: Long): Bitmap? = synchronized(pool) { pool.remove(key) }

    private fun poolPut(key: Long, bmp: Bitmap) {
        synchronized(pool) {
            if (pool.size >= POOL_MAX) return
            if (!bmp.isMutable) return
            pool[key] = bmp
        }
    }

    fun stats(): String =
        "prescale: ok=${scaled.get()} skip=${skipped.get()} fail=${failed.get()} " +
            "paused=${paused.get()} saved=${savedBytes / 1024}KB"
}
