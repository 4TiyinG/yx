package app.dsh.mobile.ui

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window

/**
 * 帧时间观测（v1.6.46）。
 *
 * ## 为什么需要它
 * 前几轮的性能改动都只能靠"结构上不可能再产生该现象"来自证（本环境无真机）。
 * 这一轮要动的是**动画期间的每一帧**，如果没有客观口径，"更流畅了"就只是感觉。
 * [Window.addOnFrameMetricsAvailableListener]（API 24+）由系统在每帧结束后投递
 * 一份 [FrameMetrics]，正是不用 DevTools、不用真机连着电脑也能拿到的帧级数据。
 *
 * ## 采到的指标（以及为什么是这几项）
 * | 指标 | 对应验收标准 |
 * |---|---|
 * | `TOTAL_DURATION` 的 p50/p90/p99、jank%（>16.7ms 的占比） | 动画帧率是否平稳、有无周期性掉帧 |
 * | `LAYOUT_MEASURE_DURATION` 均值 | 单帧 Layout 是否趋近 0（有没有触发布局重排） |
 * | `DRAW_DURATION` 均值 | 单帧 Paint/Draw 是否趋近 0（有没有触发重绘） |
 * | `SYNC_DURATION` 均值 | 主线程与合成器的同步耗时（合成占比是否正常） |
 *
 * ## 开销
 * 回调在**独立 HandlerThread** 上执行，主线程只承担系统侧采集（无我们自己的代码）；
 * 每次回调只做 4 次 `getMetric` + 4 次数组写入。并且**只在 debug 包挂载**
 * （`FLAG_DEBUGGABLE`），release 包零成本、零引用。
 *
 * ## 用法
 * ```
 * FrameStats.attach(window)              // onCreate，一次
 * FrameStats.begin("crossfade")          // 动画开始
 * ...                                    // 动画结束
 * FrameStats.end("crossfade")            // 写 engine.log 一行
 * ```
 * 每次 `begin` 清空缓冲区，`end` 输出窗口内的统计 —— 只测关心的那一段，
 * 不受"用户停在设置页发呆"这类空闲帧稀释。
 */
object FrameStats {

    private const val TAG = "FrameStats"

    /** 单帧预算：60Hz = 16.67ms。超过即记为 jank。 */
    private const val FRAME_BUDGET_NS = 16_666_667L

    /** 单次采样窗口的帧数上限（约 10 秒 @60Hz），防止忘记 end 导致无限增长。 */
    private const val MAX_SAMPLES = 600

    private val lock = Any()

    /** 窗口内每帧的 [total, layout, draw, sync]（纳秒）。 */
    private val samples = ArrayList<LongArray>(256)

    @Volatile private var label: String? = null

    @Volatile private var thread: HandlerThread? = null

    @Volatile private var listener: Window.OnFrameMetricsAvailableListener? = null

    /** 挂载到窗口（幂等）。只应在 debug 包调用。 */
    fun attach(window: Window) {
        if (listener != null) return
        runCatching {
            val t = HandlerThread("dsh-frame-stats").apply { start() }
            thread = t
            val l = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                // FrameMetrics 实例会被复用：必须立刻把值抄出来
                val rec = longArrayOf(
                    metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                    metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                    metrics.getMetric(FrameMetrics.DRAW_DURATION),
                    metrics.getMetric(FrameMetrics.SYNC_DURATION),
                )
                synchronized(lock) {
                    if (label == null) return@OnFrameMetricsAvailableListener
                    if (samples.size >= MAX_SAMPLES) return@OnFrameMetricsAvailableListener
                    samples.add(rec)
                }
            }
            window.addOnFrameMetricsAvailableListener(l, Handler(t.looper))
            listener = l
            Log.i(TAG, "attached (debug build)")
        }.onFailure { Log.w(TAG, "attach failed: ${it.message}") }
    }

    /** 开始一个采样窗口。 */
    fun begin(name: String) {
        if (listener == null) return
        synchronized(lock) {
            samples.clear()
            label = name
        }
    }

    /** 结束窗口并输出一行统计。 */
    fun end(name: String) {
        if (listener == null) return
        val snapshot: List<LongArray>
        synchronized(lock) {
            if (label == null) return
            label = null
            snapshot = ArrayList(samples)
            samples.clear()
        }
        if (snapshot.isEmpty()) {
            Log.i(TAG, "[$name] no frames captured")
            return
        }
        Log.i(TAG, "[$name] ${format(snapshot)}")
    }

    /** 供需要主动取数的地方（例如 MainActivity 的 perf 日志）。 */
    fun describe(): String {
        val snapshot: List<LongArray>
        synchronized(lock) {
            snapshot = ArrayList(samples)
        }
        return if (snapshot.isEmpty()) "frames=none" else format(snapshot)
    }

    /**
     * 一行内存快照（v1.6.46）。
     *
     * 验收标准里「页面常驻内存平稳、无 GC 尖峰」需要客观数据：这里给的是
     * Java 堆已用 + native 堆已分配 —— 前者能看出频繁分配（GC 前兆），
     * 后者能看出合成层/位图这类不受 GC 管的纹理有没有失控。
     * 单次采样，不订阅任何回调。
     */
    fun memLine(): String {
        val rt = Runtime.getRuntime()
        val javaMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val nativeMb = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        return "mem java=${javaMb}MB native=${nativeMb}MB"
    }

    private fun format(s: List<LongArray>): String {
        val totals = s.map { it[0] }.sorted()
        val jank = totals.count { it > FRAME_BUDGET_NS }
        val layout = s.sumOf { it[1] } / s.size
        val draw = s.sumOf { it[2] } / s.size
        val sync = s.sumOf { it[3] } / s.size
        fun ms(ns: Long) = String.format("%.1f", ns / 1_000_000.0)
        return buildString {
            append("frames=${s.size}")
            append(" p50=${ms(pct(totals, 0.50))}ms")
            append(" p90=${ms(pct(totals, 0.90))}ms")
            append(" p99=${ms(pct(totals, 0.99))}ms")
            append(" jank=${String.format("%.1f", jank * 100.0 / s.size)}%")
            append(" layout=${ms(layout)}ms")
            append(" draw=${ms(draw)}ms")
            append(" sync=${ms(sync)}ms")
        }
    }

    private fun pct(sorted: List<Long>, p: Double): Long {
        if (sorted.isEmpty()) return 0L
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
