package app.dsh.mobile.engine

import android.content.Context

/** Linux 工作区已移除：引擎固定运行在 Android 原生模式。 */
object EngineModeSelector {
    enum class Mode { NATIVE }

    @Volatile
    var lastMode: Mode = Mode.NATIVE
        private set

    @Volatile
    var lastReason: String = ""
        private set

    @Volatile
    var resolved: Boolean = true
        private set

    fun isLinuxEnabled(ctx: Context): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun setLinuxEnabled(ctx: Context, on: Boolean) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun resolve(ctx: Context): Mode {
        lastMode = Mode.NATIVE
        lastReason = ""
        resolved = true
        return Mode.NATIVE
    }

    data class StatusLine(
        val enabled: Boolean,
        val running: Mode,
        val rootfsReady: Boolean,
        val reason: String,
    )

    @Suppress("UNUSED_PARAMETER")
    fun statusLine(ctx: Context): StatusLine =
        StatusLine(enabled = false, running = Mode.NATIVE, rootfsReady = false, reason = "")
}