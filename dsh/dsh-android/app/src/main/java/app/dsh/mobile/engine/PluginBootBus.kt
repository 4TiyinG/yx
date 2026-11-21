package app.dsh.mobile.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 插件后台加载状态（与引擎状态解耦，供 UI 展示）。 */
object PluginBootBus {
    enum class State { IDLE, HOLDING, LOADING, DONE, PARTIAL, FAILED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    fun set(s: State) {
        _state.value = s
    }

    /**
     * 本地插件预读进度（v1.6.24）：`done / total` 个插件已进 page cache。
     *
     * ## 为什么不加进 [State]
     * `MainActivity` 里有一条「状态一旦离开 HOLDING/LOADING 就重载 WebUI」的判定
     * （用来在插件放回 profile 后拉一次首屏）。往枚举里插一个 `PREFETCH` 会让
     * 那条判定把「正在预热」当成「已经就绪」，于是预热刚开始就重载 WebUI ——
     * 正是要避免的那类抖动。进度是**并行的附加信息**，不是状态机的一环，
     * 所以单独用一个流承载，[State] 的语义一个字都不动。
     *
     * 值为 null 表示当前没有预热任务（未启动 / 已结束 / 已取消）。
     */
    data class Prefetch(val done: Int, val total: Int) {
        val percent: Int get() = if (total <= 0) 0 else (done * 100 / total)
    }

    private val _prefetch = MutableStateFlow<Prefetch?>(null)
    val prefetch: StateFlow<Prefetch?> = _prefetch

    fun setPrefetch(done: Int, total: Int) {
        _prefetch.value = Prefetch(done, total)
    }

    fun clearPrefetch() {
        _prefetch.value = null
    }
}
