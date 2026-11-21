package app.dsh.mobile.status

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 对话工作状态总线（v1.6.35）。
 *
 * ## 为什么单独做一个总线
 * 状态来源本来散在几处：`TurnWatcher` 从会话 JSONL 里拿到 `turn/start`、
 * `step/start`、`turn/end`；`AgentBridge` 在每个动作派发时拿到具体指令；
 * `EngineSupervisor` 掌握引擎崩溃/退避。它们分属三个模块、跑在三个线程上，
 * 而展示端（系统悬浮窗服务、无障碍悬浮球）只想「拿当前状态」。
 * 这里做唯一的汇聚点：生产者只调这里的几个方法，消费者只 collect 一个
 * StateFlow —— 两端互不知道对方存在。
 *
 * ## 状态语义
 * | Phase | 含义 | 触发源 |
 * |---|---|---|
 * | IDLE | 空闲，没有进行中的轮次 | 引擎健康且无活动；完成态超时回落 |
 * | THINKING | 思考中（模型在推理，尚未派发动作） | `turn/start` |
 * | EXECUTING | 执行中（正在调用工具/操作设备） | `step/start`、动作上报 |
 * | DONE | 本轮已完成 | `turn/end` |
 * | ERROR | 出错（引擎崩溃/退避重试/桥接失败） | `EngineSupervisor` 状态、上报 |
 *
 * 线程安全：全部经 MutableStateFlow（内部同步），任何线程可调。
 */
object WorkStatus {

    enum class Phase { IDLE, THINKING, EXECUTING, DONE, ERROR }

    /**
     * 一次状态快照。
     *
     * @param phase 五态之一
     * @param detail 具体动作/错误描述（可为空）；展示端自行决定是否显示
     * @param at 状态写入时刻（elapsedRealtime，用于「完成态停留多久」这类判断）
     * @param turnActive 是否处于一轮对话中（THINKING/EXECUTING 为 true）
     */
    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val detail: String = "",
        val at: Long = 0L,
        val turnActive: Boolean = false,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** 当前值（非响应式读取，供不需要订阅的场景） */
    val current: Snapshot get() = _state.value

    /** 思考中：模型开始一轮推理。 */
    fun thinking(detail: String = "") = set(Phase.THINKING, detail, turnActive = true)

    /** 执行中：正在使用工具/操作设备；[detail] 是具体指令。 */
    fun executing(detail: String = "") = set(Phase.EXECUTING, detail, turnActive = true)

    /** 本轮完成。 */
    fun done(detail: String = "") = set(Phase.DONE, detail, turnActive = false)

    /** 出错：引擎崩溃、退避重试、桥接失败等。 */
    fun error(detail: String = "") = set(Phase.ERROR, detail, turnActive = false)

    /** 回到空闲。 */
    fun idle() = set(Phase.IDLE, "", turnActive = false)

    /**
     * 若当前仍是「完成/错误」这类终态，并且已停留超过 [afterMs]，则回落为空闲。
     *
     * 由展示端在渲染时顺手调用（不额外起定时器）：终态只是提示，不该永久占着。
     * 进行中（THINKING/EXECUTING）不受影响。
     */
    fun idleIfSettledAfter(afterMs: Long) {
        val s = _state.value

        if (s.phase != Phase.DONE && s.phase != Phase.ERROR) return
        if (s.at <= 0L) return
        if (android.os.SystemClock.elapsedRealtime() - s.at < afterMs) return
        idle()
    }

    private fun set(phase: Phase, detail: String, turnActive: Boolean) {
        _state.value = Snapshot(
            phase = phase,
            detail = detail,
            at = android.os.SystemClock.elapsedRealtime(),
            turnActive = turnActive,
        )
    }
}
