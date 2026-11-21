package app.dsh.mobile.ui

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.util.Log
import kotlin.math.ceil

/**
 * 帧预算调度器（v1.6.30）—— 「资源隔离与预算控制」的进程级实现。
 *
 * ## 为什么需要它
 *
 * 这个进程里有**多条互不相识的帧驱动**同时在跑：宠物漫游（32ms）、宠物甩抛
 * （16ms）、宠物窗口拖拽（每个 MotionEvent 一次）、状态悬浮窗吸附（16ms）、
 * 启动遮罩……它们各自 `postDelayed` 自排下一拍，彼此不知道对方存在，于是：
 *
 * 1. **没有上限**：任何一帧里都可能同时撞上 4~5 条链的回调，主线程被一段一段
 *    切走。谁跑了多久、这一帧还剩多少预算，没人管。
 * 2. **不对齐 vsync**：`postDelayed(16)` 的 16ms 与实帧间隔（16.67@60 /
 *    11.1@90 / 8.3@120Hz）本来就不同步，会周期性「丢一帧 / 补两帧」。
 *    v1.6.21 在宠物 move 驱动上踩过这个坑（表现为「动一下、卡一下」），
 *    但其它链还在用老写法。
 * 3. **不受控的窗口事务**：拖拽时每个 MotionEvent 都要
 *    `WindowManager.updateViewLayout` —— 一次 binder IPC 进 system_server。
 *    触摸采样率（常见 120/240Hz）远高于刷新率，同一帧内可能连发好几次窗口
 *    事务，争的正是**合成器那一侧**的资源。
 *
 * ## 做法
 *
 * 一个 Choreographer 调度源 + 一张订阅表：
 *
 * - **单源**：所有帧驱动的下一拍都由这里统一排。没订阅者时**一个 wakeup
 *   都没有**（不是空转到 60fps，而是真的停摆）。
 * - **预算**：每帧给主线程一段固定预算（[budgetMs]，默认 5ms ≈ 一帧的 30%）。
 *   一轮下来超预算就把剩下的低优先级订阅者**顺延到下一帧**——不是丢弃，
 *   是推迟，语义上等价于「这一帧先干重要的」。
 * - **优先级**：HIGH 永不受预算限制（关键帧），NORMAL/LOW 可被顺延；
 *   LOW 在卡顿时自动降频。
 * - **对齐 vsync**：下一拍由 Choreographer 的帧回调给出，帧间隔跟着屏幕刷新率走。
 * - **卡顿时自适应**：跟踪帧间隔 EMA，连续卡顿时把 LOW 订阅的间隔翻倍，
 *   连续顺畅后再还回去。抖动治理优先于「低优先级也要每帧跑」。
 * - **保活回退**：Choreographer 在息屏/doze 时不再送帧，但旧代码的
 *   `postDelayed` 链会继续跑。为不改变既有语义（例如宠物熄屏靠 tick 继续到来
 *   再自行判断），带一个 [KEEPALIVE_MS] 保护：排了帧回调却迟迟等不到，就用
 *   定时器顶一次并重排帧回调。
 * - **每帧最多一次**：[postOnce] 把同一 id 的多次请求合并成一次（后写的覆盖
 *   先写的）——窗口拖拽用它，把「每事件一次窗口事务」压成「每帧最多一次」。
 *
 * ## 线程
 *
 * 只在主线程上工作。从别的线程调用会自动转投主线程（[Handler.post]），
 * 因此调用点不必自己切线程。
 *
 * ## 统计
 *
 * [stats] 给 logcat / 诊断页用：帧数、每帧均摊的主线程耗时、被顺延的次数、
 * 是否处于降频态。这些数字是**运行期真实测量**（`System.nanoTime`），不是估算。
 */
object RenderBudget {

    private const val TAG = "RenderBudget"

    /** 关键帧：不受预算限制，必须在本帧跑完 */
    const val HIGH = 0

    /** 常规：可被顺延一帧；卡顿时不降频 */
    const val NORMAL = 1

    /** 低优先：可被顺延；卡顿时自动降频（间隔 ×2） */
    const val LOW = 2

    /** 每帧给主线程的默认预算（ms） */
    const val DEFAULT_BUDGET_MS = 5.0

    /** 60Hz 的名义帧间隔（ms），用于识别卡顿 */
    private const val NOMINAL_MS = 16.67

    /** 帧间隔 EMA 超过名义值的这么多倍，算卡顿 */
    private const val JANK_FACTOR = 1.6

    /** 连续这么多次卡顿 → 进入降频态 */
    private const val JANK_STREAK_TO_DEGRADE = 3

    /** 连续这么多次顺畅 → 退出降频态 */
    private const val SMOOTH_STREAK_TO_RECOVER = 20

    /** 排了帧回调却等不到时，用定时器顶上的超时（ms） */
    private const val KEEPALIVE_MS = 200L

    private val handler = Handler(Looper.getMainLooper())

    private class Sub(
        val id: String,
        var priority: Int,
        var intervalMs: Long,
        var tick: (Long) -> Unit,
        val seq: Long,
    ) {
        var lastRunNs: Long = 0L
        var runs: Long = 0L
    }

    private val subs = LinkedHashMap<String, Sub>()
    private val onceActions = LinkedHashMap<String, () -> Unit>()
    private var seqCounter = 0L

    private var framePosted = false
    private var keepAliveArmed = false
    private var pendingWake: Runnable? = null

    /** 防止 tick 内部再 request 造成嵌套分发 */
    private var dispatching = false

    var budgetMs: Double = DEFAULT_BUDGET_MS
        set(value) {
            field = value.coerceIn(0.5, 16.0)
        }

    // ---------------------------------------------------------------- 统计
    private var frameCount = 0L
    private var deferredCount = 0L
    private var dispatchNanos = 0L
    private var maxDispatchNanos = 0L
    private var lastFrameNs = 0L
    private var intervalEmaMs = NOMINAL_MS
    private var jankStreak = 0
    private var smoothStreak = 0
    private var degraded = false

    data class Stats(
        /** 已分发帧数 */
        val frames: Long,
        /** 注册中的周期订阅者数量 */
        val subscribers: Int,
        /** 待执行的 postOnce 动作数 */
        val pendingOnce: Int,
        /** 每帧均摊的主线程分发耗时（ms） */
        val avgDispatchMs: Double,
        /** 观测到的最大单帧分发耗时（ms） */
        val maxDispatchMs: Double,
        /** 因超预算被顺延的订阅次数（累计） */
        val deferred: Long,
        /** 帧间隔 EMA（ms） */
        val intervalEmaMs: Double,
        /** 是否处于卡顿降频态 */
        val degraded: Boolean,
    )

    fun stats(): Stats = Stats(
        frames = frameCount,
        subscribers = subs.size,
        pendingOnce = onceActions.size,
        avgDispatchMs = if (frameCount == 0L) 0.0 else dispatchNanos / 1_000_000.0 / frameCount,
        maxDispatchMs = maxDispatchNanos / 1_000_000.0,
        deferred = deferredCount,
        intervalEmaMs = intervalEmaMs,
        degraded = degraded,
    )

    /** 测试/诊断用：清空所有订阅与统计 */
    fun reset() {
        if (!onMain()) { handler.post { reset() }; return }
        cancelPendingWake()
        subs.clear()
        onceActions.clear()
        framePosted = false
        keepAliveArmed = false
        frameCount = 0L
        deferredCount = 0L
        dispatchNanos = 0L
        maxDispatchNanos = 0L
        lastFrameNs = 0L
        intervalEmaMs = NOMINAL_MS
        jankStreak = 0
        smoothStreak = 0
        degraded = false
    }

    fun isScheduled(id: String): Boolean = subs.containsKey(id)

    fun hasPendingOnce(id: String): Boolean = onceActions.containsKey(id)

    // ---------------------------------------------------------------- 订阅

    /**
     * 注册（或更新）一个周期订阅。
     *
     * @param intervalMs 最小间隔。0 = 每帧一次。抖动不会累积：某一帧被顺延后，
     *   下一帧也只补跑一次，不会「欠账」式连发。
     */
    fun schedule(
        id: String,
        priority: Int = NORMAL,
        intervalMs: Long = 0L,
        tick: (frameTimeNanos: Long) -> Unit,
    ) {
        if (!onMain()) {
            handler.post { schedule(id, priority, intervalMs, tick) }
            return
        }
        val existing = subs[id]
        if (existing != null) {
            existing.priority = priority
            existing.intervalMs = intervalMs.coerceAtLeast(0L)
            existing.tick = tick
            return
        }
        subs[id] = Sub(
            id = id,
            priority = priority,
            intervalMs = intervalMs.coerceAtLeast(0L),
            tick = tick,
            seq = seqCounter++,
        )
        ensureFrame()
    }

    fun cancel(id: String) {
        if (!onMain()) {
            handler.post { cancel(id) }
            return
        }
        val removed = subs.remove(id) != null
        if (removed && subs.isEmpty() && onceActions.isEmpty()) cancelPendingWake()
    }

    /**
     * 「下一帧跑一次」，同一 [id] 在**同一帧内**多次请求只跑最后一次。
     *
     * 用于把高频事件（窗口拖拽的 MotionEvent）压成每帧一次的窗口事务：
     * 调用方只管把最新状态写好，实际 IPC 由这里排到下一个 vsync。
     */
    fun postOnce(id: String, action: () -> Unit) {
        if (!onMain()) {
            handler.post { postOnce(id, action) }
            return
        }
        onceActions[id] = action
        ensureFrame()
    }

    /** 撤掉一个尚未执行的 [postOnce]（例如拖拽已结束并已手动落地） */
    fun cancelOnce(id: String) {
        if (!onMain()) {
            handler.post { cancelOnce(id) }
            return
        }
        onceActions.remove(id)
        if (subs.isEmpty() && onceActions.isEmpty()) cancelPendingWake()
    }

    // ---------------------------------------------------------------- 调度

    private fun onMain(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framePosted = false
            // 帧到了：撤掉保活，否则它 200ms 后会白发一次分发
            if (keepAliveArmed) {
                keepAliveArmed = false
                handler.removeCallbacks(keepAlive)
            }
            if (subs.isEmpty() && onceActions.isEmpty()) {
                cancelPendingWake()
                return
            }
            dispatch(frameTimeNanos)
            scheduleNext()
        }
    }

    private val keepAlive = Runnable {
        keepAliveArmed = false
        if (subs.isEmpty() && onceActions.isEmpty()) return@Runnable
        // 帧回调迟迟没来（息屏/doze/被别的窗口抢占），用定时器顶一次并重排
        framePosted = false
        dispatch(System.nanoTime())
        scheduleNext()
    }

    private fun ensureFrame() {
        if (framePosted) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
        if (!keepAliveArmed) {
            keepAliveArmed = true
            handler.postDelayed(keepAlive, KEEPALIVE_MS)
        }
    }

    private fun scheduleNext() {
        cancelPendingWake()
        if (subs.isEmpty() && onceActions.isEmpty()) return

        if (onceActions.isNotEmpty()) {
            ensureFrame()
            return
        }

        val now = System.nanoTime()
        var waitMs = Long.MAX_VALUE
        for (sub in subs.values) {
            val intervalNs = effectiveIntervalNs(sub)
            if (intervalNs <= 0L) {
                waitMs = 0L
                break
            }
            val dueNs = sub.lastRunNs + intervalNs
            val leftMs = ceil((dueNs - now) / 1_000_000.0).toLong()
            if (leftMs < waitMs) waitMs = leftMs
        }

        if (waitMs <= 0L) {
            ensureFrame()
            return
        }
        val r = Runnable {
            pendingWake = null
            ensureFrame()
        }
        pendingWake = r
        handler.postDelayed(r, waitMs)
    }

    private fun cancelPendingWake() {
        pendingWake?.let { handler.removeCallbacks(it) }
        pendingWake = null
    }

    /**
     * 该订阅当前应使用的最小间隔。
     *
     * 卡顿降频只作用于 [LOW]：NORMAL 是「界面正在动的部分」，降频会被看成卡；
     * LOW 是「背景行为」（漫游、装饰），少跑几次没人看得出。
     */
    private fun effectiveIntervalNs(sub: Sub): Long {
        val base = sub.intervalMs * 1_000_000L
        if (!degraded || sub.priority != LOW) return base
        return base * 2
    }

    private fun dispatch(frameTimeNanos: Long) {
        if (dispatching) return
        dispatching = true
        val startedNs = System.nanoTime()

        updateJankState(startedNs)

        // 按 (优先级, 注册序) 排：保证同一帧内顺序稳定、HIGH 先跑
        val ordered = subs.values.sortedWith(compareBy({ it.priority }, { it.seq }))
        val budgetNs = (budgetMs * 1_000_000.0).toLong()
        var deferred = 0

        for (sub in ordered) {
            val intervalNs = effectiveIntervalNs(sub)
            val due = intervalNs <= 0L || (startedNs - sub.lastRunNs) >= intervalNs
            if (!due) continue

            // 预算检查：只有 HIGH 能越过预算
            if (System.nanoTime() - startedNs > budgetNs && sub.priority != HIGH) {
                deferred++
                continue
            }

            sub.lastRunNs = startedNs
            sub.runs++
            try {
                sub.tick(frameTimeNanos)
            } catch (t: Throwable) {
                Log.w(TAG, "subscriber '${sub.id}' threw: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        // 一次性动作：每帧最多跑一次，跑之前先取走（动作内可再 postOnce 同一 id）
        if (onceActions.isNotEmpty()) {
            val batch = onceActions.entries.toList()
            onceActions.clear()
            for ((id, action) in batch) {
                if (System.nanoTime() - startedNs > budgetNs) {
                    // 超预算：没轮到的留到下一帧，不丢
                    onceActions[id] = action
                    deferred++
                    continue
                }
                try {
                    action()
                } catch (t: Throwable) {
                    Log.w(TAG, "once '$id' threw: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }

        val elapsed = System.nanoTime() - startedNs
        frameCount++
        dispatchNanos += elapsed
        if (elapsed > maxDispatchNanos) maxDispatchNanos = elapsed
        deferredCount += deferred

        if (frameCount % 600L == 0L) {
            val s = stats()
            Log.i(
                TAG,
                "frames=${s.frames} subs=${s.subscribers} avg=%.2fms max=%.2fms defer=${s.deferred} " +
                    "ema=%.1fms degraded=${s.degraded}".format(
                        s.avgDispatchMs, s.maxDispatchMs, s.intervalEmaMs,
                    ),
            )
        }

        dispatching = false
    }

    private fun updateJankState(nowNs: Long) {
        val prev = lastFrameNs
        lastFrameNs = nowNs
        if (prev == 0L) return
        val dtMs = (nowNs - prev) / 1_000_000.0
        // 只统计相邻两次分发的间隔，跳过长时间空闲（那时不反映渲染压力）
        if (dtMs > 500.0) return
        intervalEmaMs = intervalEmaMs * 0.9 + dtMs * 0.1

        if (intervalEmaMs > NOMINAL_MS * JANK_FACTOR) {
            jankStreak++
            smoothStreak = 0
            if (jankStreak >= JANK_STREAK_TO_DEGRADE && !degraded) {
                degraded = true
                Log.i(
                    TAG,
                    "janky (ema=%.1fms) → low-priority subscribers halved".format(intervalEmaMs),
                )
            }
        } else {
            smoothStreak++
            jankStreak = 0
            if (smoothStreak >= SMOOTH_STREAK_TO_RECOVER && degraded) {
                degraded = false
                Log.i(TAG, "smooth again (ema=%.1fms) → full rate".format(intervalEmaMs))
            }
        }
    }

    /** 诊断：当前订阅表（id → 间隔/次数），给日志或诊断页用 */
    fun dump(): String {
        if (subs.isEmpty() && onceActions.isEmpty()) return "no subscribers"
        val now = System.nanoTime()
        val body = subs.values.joinToString("\n") { s ->
            val ago = if (s.lastRunNs == 0L) -1.0 else (now - s.lastRunNs) / 1_000_000.0
            "  %-22s prio=%d interval=%dms runs=%d lastRun=%.0fms ago".format(
                s.id, s.priority, s.intervalMs, s.runs, ago,
            )
        }
        val pending = if (onceActions.isEmpty()) "" else
            "\n  pending once: " + onceActions.keys.joinToString(", ")
        return body + pending
    }
}
