package app.dsh.mobile.ui

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 热状态守卫（v1.6.36）。
 *
 * ## 为什么需要它
 * 发热降频时，**同样的工作要花更长时间**。此时再并发做批量小文件读取、大批图片
 * 解码，只会把热窗口拉长、让降频更深，用户看到的是「越用越卡」的自激循环。
 * 正确做法是**把可推迟的工作挪到热余量窗口**，而不是在热受限窗口里硬扛。
 *
 * ## 数据源
 * `PowerManager.getCurrentThermalStatus()`（API 29+，即本项目 compileSdk 覆盖、
 * minSdk 26 上做版本判断）。它比 `Intent.ACTION_BATTERY_CHANGED` 的温度值更贴近
 * 「系统是否已经在降频」这个我们真正关心的问题。
 *
 * · API < 29：固定返回 [Level.NONE]（无信息即不降级，绝不因「测不到」而误伤功能）。
 * · 监听 `OnThermalStatusChangedListener`：系统状态变化时立刻回调，不做轮询。
 *
 * ## 分级语义（对应系统的 THERMAL_STATUS_*）
 * | Level | 系统值 | 我们的策略 |
 * |---|---|---|
 * | NONE | 0 | 全速：可并发预热、可预解码 |
 * | LIGHT | 1 | 正常：预热降为 1 个并发 |
 * | MODERATE | 2 | 收紧：暂停新解码，只做必须的命中服务 |
 * | SEVERE | 3 | 限制：串行 IO，暂停预热与预解码 |
 * | CRITICAL | 4+ | 只服务，不产生任何新工作 |
 *
 * 线程：状态回调在系统线程，StateFlow 内部同步，任何线程可读。
 */
object ThermalGuard {

    private const val TAG = "ThermalGuard"

    enum class Level {
        NONE, LIGHT, MODERATE, SEVERE, CRITICAL;

        /** 是否已经进入会影响性能的降频区间。 */
        val throttled: Boolean get() = this >= MODERATE

        /** 是否连「命中服务」之外的工作都应停下。 */
        val severe: Boolean get() = this >= SEVERE

        val label: String
            get() = when (this) {
                NONE -> "none"
                LIGHT -> "light"
                MODERATE -> "moderate"
                SEVERE -> "severe"
                CRITICAL -> "critical"
            }
    }

    private val _level = MutableStateFlow(Level.NONE)
    val level: StateFlow<Level> = _level.asStateFlow()

    val current: Level get() = _level.value

    /** 前台/预热/解码三处都读这个：热受限时不要新增后台工作量。 */
    val canDoBackgroundWork: Boolean get() = !current.throttled

    /** 允许预解码（把解码前移到热余量窗口的核心开关）。 */
    val canPreDecode: Boolean get() = !current.throttled

    /** 允许批量（并发）文件预热。 */
    val canBatchIo: Boolean get() = !current.throttled

    private var installed = false
    private var listener: Any? = null

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        if (Build.VERSION.SDK_INT < 29) {
            Log.i(TAG, "thermal status API unavailable (<29) — staying NONE")
            return
        }
        runCatching {
            val pm = ctx.applicationContext.getSystemService(PowerManager::class.java) ?: return@runCatching
            _level.value = map(pm.currentThermalStatus)
            val l = PowerManager.OnThermalStatusChangedListener { status ->
                val next = map(status)
                val prev = _level.value
                if (next != prev) {
                    _level.value = next
                    Log.i(TAG, "thermal ${prev.label} -> ${next.label}")
                }
            }
            pm.addThermalStatusListener(pmHandler(), l)
            listener = l
            Log.i(TAG, "installed, status=${_level.value.label}")
        }.onFailure { Log.w(TAG, "install failed: ${it.message}") }
    }

    /** 供外部（如引擎日志页）读取当前状态的一句话描述。 */
    fun describe(): String = "thermal=${current.label} preDecode=$canPreDecode batchIo=$canBatchIo"

    private fun pmHandler(): java.util.concurrent.Executor =
        java.util.concurrent.Executor { r -> Handler(Looper.getMainLooper()).post(r) }

    private fun map(status: Int): Level = when {
        status >= PowerManager.THERMAL_STATUS_CRITICAL -> Level.CRITICAL
        status == PowerManager.THERMAL_STATUS_SEVERE -> Level.SEVERE
        status == PowerManager.THERMAL_STATUS_MODERATE -> Level.MODERATE
        status == PowerManager.THERMAL_STATUS_LIGHT -> Level.LIGHT
        else -> Level.NONE
    }
}
