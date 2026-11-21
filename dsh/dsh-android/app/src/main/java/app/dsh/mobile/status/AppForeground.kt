package app.dsh.mobile.status

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用级前后台判定（v1.6.35）。
 *
 * ## 为什么不用 `CurrentActivity.get() != null`
 * 那个只记「当前 resumed 的 Activity」。Activity 之间切换时它有一瞬间为 null
 * （A paused → B resumed 之间），悬浮窗若按它判断，就会在页面跳转时**闪一下** ——
 * 正是需求里明确禁止的抖动。
 *
 * 这里用 started 计数：任一 Activity 处于 started 状态即视为前台，
 * 只有全部 stopped（真正退到后台/别的应用上层）才转为后台。跳转期间计数始终 > 0，
 * 不会误判。
 *
 * 线程：生命周期回调都在主线程，StateFlow 写入无需加锁。
 */
object AppForeground {

    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private var started = 0

    fun isForeground(): Boolean = started > 0

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

            override fun onActivityStarted(activity: Activity) {
                started++
                if (_foreground.value != true) _foreground.value = true
            }

            override fun onActivityStopped(activity: Activity) {
                // 某些 ROM 会补发 started/stopped 错配（极少），用下限兜住
                started = (started - 1).coerceAtLeast(0)
                if (started == 0 && _foreground.value != false) _foreground.value = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
