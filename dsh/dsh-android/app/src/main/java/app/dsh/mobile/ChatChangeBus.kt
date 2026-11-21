package app.dsh.mobile

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 「会话集合变了」的进程内广播（v1.6.28）。
 *
 * ## 为什么需要
 *
 * 删除对话现在走**热删除**（不停引擎），所以不会再有「引擎重启 → WebUI 重载」
 * 这个顺带的副作用。但 WebUI 里的会话列表必须跟着变，否则用户回到对话界面还看得见
 * 刚删掉的条目 —— 那是最典型的"界面与数据不一致"。
 *
 * 引擎自己的工作区服务是响应式的（注册表变更会推送），多数情况下 WebUI 会自己更新。
 * 但这条链路依赖引擎版本行为，不能作为唯一保证；所以这里再补一次**软刷新**：
 * MainActivity 收到事件后重新加载同一个 URL（**不是重启引擎**，也不是换进程）。
 *
 * ## 为什么用 SharedFlow(extraBufferCapacity)
 *
 * `tryEmit` 不能阻塞调用方（这里是删除流程的后台线程收尾处），也不能丢事件 ——
 * 丢一条就等于 WebUI 保持旧列表。`replay = 0` 避免 Activity 重建时把历史删除
 * 当成新事件再次触发刷新。
 */
object ChatChangeBus {

    private val _changes = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 8)

    /** 每次会话被删除后发射一次，载荷是删除条数。 */
    val changes: SharedFlow<Int> = _changes

    /**
     * 通知 WebUI 侧同步。
     *
     * @param removed 本次删除条数（仅用于日志；0 也会发射，因为"没删掉"同样需要刷新）
     */
    fun notifyRemoved(removed: Int) {
        _changes.tryEmit(removed)
    }
}
