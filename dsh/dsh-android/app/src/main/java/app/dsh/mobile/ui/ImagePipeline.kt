package app.dsh.mobile.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import app.dsh.mobile.ImageFx
import java.io.File
import java.util.ArrayDeque
import java.util.HashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * UI 图片解码流水线（v1.6.31）：**主线程零解码、零卷积**。
 *
 * ## 为什么需要它
 *
 * 这个工程的 UI 是原生 View + WebView，没有 Unity 的图集/Sprite Mesh 那一套，
 * 「削减填充率与资源加载尖峰」在本工程里的对应物只有三条：
 *
 *  1. **解码不许在主线程**：`BitmapFactory.decodeFile` 一次 1440×1080 就是 6.2 MB
 *     分配 + 十几到几十毫秒，落在主线程就是掉帧；
 *  2. **按显示尺寸解码**：给 64dp 的预览解 1440 宽的图，多出来的像素既占内存
 *     又要上传纹理，纯属白烧（等价于 Unity 里「把整张原图塞进 UI 纹理槽」）；
 *  3. **同一批请求要合帧**：列表/预览一次来 8 张图，如果 8 个回调在同一帧里
 *     各自 `setImageBitmap`，那一帧的 CPU 与上传时间就是尖峰。
 *
 * ## 设计
 *
 *  · **单线程串行**：一个后台 worker（`img-decode`，优先级略低于普通线程）。
 *    串行不是偷懒——并发解码会同时抢多核与内存带宽，反而把 UI 线程的
 *    缓存挤出去；串行天然限流，最多占一个核。
 *  · **按 tag 覆盖**：同一个 tag（一个控件/一个用途）只保留最新一条请求。
 *    拖滑块时连发 20 档，只有最后一档会被真正执行，中间的直接作废。
 *  · **合帧交付**：结果回到主线程后不立即 `apply`，而是交给
 *    [RenderBudget.postOnce]——同一 tag 一帧内多次到达只应用最后一次，
 *    把「N 次 setImageBitmap + N 次纹理上传」压成 1 次。
 *  · **内存 LRU**：按 `byteCount` 记账（不是按条目数），上限取堆上限的 1/8。
 *    缓存键含 `mtime + length`，换图立刻失效。
 *  · **过期丢弃**：worker 算完时如果 tag 的最新键已经变了，这份结果直接扔掉
 *    （`stale` 计数），不会拿旧图覆盖新图 —— 这是异步化最容易踩的坑。
 *  · **生命周期**：Activity `onDestroy` 必须 [cancel]，否则算完还要往一个
 *    已经销毁的窗口里贴图。
 *
 * ## 不做的事
 *
 *  · 不 `recycle()` 被 LRU 淘汰的位图：位图可能还挂在某个 View 上，
 *    回收会直接 `Canvas: trying to use a recycled bitmap` 崩掉。
 *    交给 GC 释放，代价只是晚一点归还内存。
 *  · 不做磁盘缓存：模糊结果的磁盘缓存仍归 [ImageFx.cachedBlur] 管，
 *    本类只负责「谁来算」和「算完怎么交付」。
 */
object ImagePipeline {

    private const val TAG = "ImagePipeline"

    /** 缓存上限 = 堆上限 / 8（dp 尺寸的预览图够放十几张，又不至于挤压其它内存） */
    private const val CACHE_DIVISOR = 8

    /** 缓存下限：低内存设备上堆上限很小，也要能放下几张缩略图 */
    private const val CACHE_MIN_BYTES = 4 * 1024 * 1024

    private val main = Handler(Looper.getMainLooper())

    // ── 后台队列 ────────────────────────────────────────────────────────────

    /** 一条待执行请求。`produce` 在 worker 线程执行，返回值可空（失败）。 */
    private class Job(val tag: String, val key: String, val produce: () -> Bitmap?)

    private val lock = ReentrantLock()
    private val hasWork = lock.newCondition()

    /** 等待执行的 tag（FIFO）；与 [queued] 配套使用 */
    private val order = ArrayDeque<String>()

    /** tag → 该 tag 在队列中**最新**的那条请求（覆盖式，队列里不会堆同一 tag 的多份） */
    private val queued = HashMap<String, Job>()

    private var workerStarted = false

    // ── 主线程侧的交付表 ────────────────────────────────────────────────────

    /** tag → 最新一次请求的缓存键（判定「算完的这份是不是过期了」） */
    private val latestKey = HashMap<String, String>()

    /** tag → 最新一次请求的交付回调 */
    private val latestApply = HashMap<String, (Bitmap) -> Unit>()

    // ── 缓存 ────────────────────────────────────────────────────────────────

    private val cache = object : LruCache<String, Bitmap>(capacityBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount

        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: Bitmap,
            newValue: Bitmap?,
        ) {
            // 故意不 recycle：位图可能仍被 View 的 Drawable 持有，回收会崩。
        }
    }

    private fun capacityBytes(): Int {
        val cap = Runtime.getRuntime().maxMemory() / CACHE_DIVISOR
        return cap.coerceIn(CACHE_MIN_BYTES.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    // ── 统计（诊断用，全部为实测值） ─────────────────────────────────────────

    private var hits = 0
    private var requests = 0
    private var stale = 0
    private var failed = 0
    private var coalesced = 0
    private var decodeCount = 0
    private var decodeTotalMs = 0.0
    private var decodeMaxMs = 0.0

    data class Stats(
        val requests: Int,
        val hits: Int,
        val stale: Int,
        val failed: Int,
        val coalesced: Int,
        val decodes: Int,
        val avgDecodeMs: Double,
        val maxDecodeMs: Double,
        val cacheBytes: Int,
    )

    fun stats(): Stats = Stats(
        requests = requests,
        hits = hits,
        stale = stale,
        failed = failed,
        coalesced = coalesced,
        decodes = decodeCount,
        avgDecodeMs = if (decodeCount == 0) 0.0 else decodeTotalMs / decodeCount,
        maxDecodeMs = decodeMaxMs,
        cacheBytes = cache.size(),
    )

    fun resetStats() {
        requests = 0; hits = 0; stale = 0; failed = 0; coalesced = 0
        decodeCount = 0; decodeTotalMs = 0.0; decodeMaxMs = 0.0
    }

    // ── 公开 API ────────────────────────────────────────────────────────────

    /**
     * 按**显示尺寸**取一张缩略图/预览图。
     *
     * [targetW]/[targetH] 传控件实际要画多大（像素）。采样只取 2 的幂且**不放大**，
     * 所以小图调大不会被糊成马赛克，大图调小则按需降采样。
     *
     * 回调一定在主线程；**同一 tag 连续调用时只有最后一次的回调会被执行**。
     */
    fun requestThumb(
        tag: String,
        file: File,
        targetW: Int,
        targetH: Int,
        onReady: (Bitmap) -> Unit,
    ) {
        val tw = targetW.coerceAtLeast(1)
        val th = targetH.coerceAtLeast(1)
        val key = "thumb|${file.absolutePath}|${file.lastModified()}|${file.length()}|${tw}x$th"
        request(tag, key, onReady) { decodeSampled(file, tw, th) }
    }

    /**
     * 取一张「设置页背景」这类需要模糊的图。
     *
     * 真正的卷积与磁盘缓存仍归 [ImageFx.cachedBlur]，这里只把它挪到 worker 线程。
     * 注意 `strength <= 0` 时 `cachedBlur` 原样返回解码结果（不再额外缩放），
     * 采样解码的目标边长同样取 [maxEdge]，与模糊路径保持同一口径。
     */
    fun requestBlur(
        tag: String,
        context: Context,
        file: File,
        strength: Int,
        maxEdge: Int,
        onReady: (Bitmap) -> Unit,
    ) {
        val key = "blur|${file.absolutePath}|${file.lastModified()}|${file.length()}|$strength|$maxEdge"
        request(tag, key, onReady) {
            // 上下文只用于定位 filesDir 下的模糊缓存目录，不持有 Activity 引用：
            // 调用方传 applicationContext 更稳，但传 Activity 也不会泄漏（用完即弃）。
            ImageFx.cachedBlur(context.applicationContext, file, strength, maxEdge)
        }
    }

    /** 撤销一个 tag 的所有在途/待执行请求（Activity onDestroy / View detach 时调用）。 */
    fun cancel(tag: String) {
        latestKey.remove(tag)
        latestApply.remove(tag)
        RenderBudget.cancelOnce(onceId(tag))
        lock.withLock { queued.remove(tag) }
    }

    /** 清空内存缓存（内存吃紧或用户换了一整套素材时调用）。 */
    fun clearCache() {
        cache.evictAll()
    }

    /**
     * 采样倍率：只返回 2 的幂，且保证**不放大**。
     *
     * 之所以要求「宽高都还够大」才继续降采样：单边判断会在极端长图上把另一边
     * 采到比显示尺寸还小，FILL 上去就糊了。
     */
    fun sampleSize(outW: Int, outH: Int, targetW: Int, targetH: Int): Int {
        if (outW <= 0 || outH <= 0 || targetW <= 0 || targetH <= 0) return 1
        var sample = 1
        while (outW / (sample * 2) >= targetW && outH / (sample * 2) >= targetH) {
            sample *= 2
        }
        return sample
    }

    /** 带 `inJustDecodeBounds` 的采样解码（worker 线程专用）。 */
    fun decodeSampled(file: File, targetW: Int, targetH: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    }.getOrNull()

    // ── 内部实现 ────────────────────────────────────────────────────────────

    private fun onceId(tag: String): String = "img:$tag"

    private fun request(
        tag: String,
        key: String,
        onReady: (Bitmap) -> Unit,
        produce: () -> Bitmap?,
    ) {
        requests++

        val hit = cache.get(key)
        if (hit != null) {
            hits++
            // 命中即同步交付：只是一次 Drawable 包装，没有解码成本，
            // 再排到下一帧反而会让「切页/返回」多一帧空白。
            latestKey.remove(tag)
            latestApply.remove(tag)
            RenderBudget.cancelOnce(onceId(tag))
            onReady(hit)
            return
        }

        latestKey[tag] = key
        latestApply[tag] = onReady

        val job = Job(tag, key, produce)
        var startWorker = false
        lock.withLock {
            val alreadyQueued = queued.containsKey(tag)
            if (alreadyQueued) coalesced++
            // 覆盖式：队列里同一个 tag 只保留最新一份请求
            queued[tag] = job
            if (!alreadyQueued) order.add(tag)
            if (!workerStarted) {
                workerStarted = true
                startWorker = true
            }
            hasWork.signalAll()
        }
        if (startWorker) {
            Thread(::workerLoop, "img-decode").apply {
                priority = Thread.NORM_PRIORITY - 1
                isDaemon = true
            }.start()
        }
    }

    private fun workerLoop() {
        while (true) {
            val job = takeNext() ?: continue
            val t0 = System.nanoTime()
            val bmp = runCatching { job.produce() }.getOrNull()
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            main.post { deliver(job, bmp, ms) }
        }
    }

    private fun takeNext(): Job? = lock.withLock {
        while (order.isEmpty()) hasWork.awaitUninterruptibly()
        val tag = order.poll() ?: return@withLock null
        // 请求可能已被 cancel()：返回 null，外层继续取下一条
        queued.remove(tag)
    }

    private fun deliver(job: Job, bmp: Bitmap?, decodeMs: Double) {
        decodeCount++
        decodeTotalMs += decodeMs
        if (decodeMs > decodeMaxMs) decodeMaxMs = decodeMs

        if (bmp == null) {
            failed++
            if (latestKey[job.tag] == job.key) {
                latestKey.remove(job.tag)
                latestApply.remove(job.tag)
            }
            return
        }

        cache.put(job.key, bmp)

        // 过期：tag 的最新请求已经换了一个键（换图/换尺寸/改模糊强度）
        if (latestKey[job.tag] != job.key) {
            stale++
            return
        }

        // 合帧交付：postOnce 保证同一 tag 一帧内只 apply 一次（后写覆盖先写）
        RenderBudget.postOnce(onceId(job.tag)) {
            if (latestKey[job.tag] == job.key) {
                val apply = latestApply[job.tag]
                latestKey.remove(job.tag)
                latestApply.remove(job.tag)
                apply?.invoke(bmp)
            }
        }
    }

    /** 诊断：一行实测统计，便于和 `adb logcat -s ImagePipeline` 对照。 */
    fun dump(): String {
        val s = stats()
        return "$TAG req=${s.requests} hit=${s.hits} stale=${s.stale} fail=${s.failed} " +
            "coalesce=${s.coalesced} decodes=${s.decodes} " +
            "avg=${"%.2f".format(s.avgDecodeMs)}ms max=${"%.2f".format(s.maxDecodeMs)}ms " +
            "cache=${s.cacheBytes / 1024}KB"
    }
}
