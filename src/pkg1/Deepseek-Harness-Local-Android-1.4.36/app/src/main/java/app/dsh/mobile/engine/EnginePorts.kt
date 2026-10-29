package app.dsh.mobile.engine

import android.content.Context

/**
 * 引擎监听端口偏好（v1.7.4）。
 *
 * 引擎、WebUI、局域网代理与 Shizuku 桥共享同一个监听端口，所以端口只能在
 * 「引擎未运行」时改，保存后需要重启引擎才生效（设置页会给明确提示）。
 *
 * 取值范围 1024..65535：低于 1024 是系统保留端口，非 root 模式下 bind 会失败。
 */
object EnginePorts {
    private const val KEY_PORT = "engine_port"

    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    fun isValid(port: Int): Boolean = port in MIN_PORT..MAX_PORT

    /** 读取用户设置的端口；未设置或历史值非法时回落 [EngineConfig.DEFAULT_PORT]。 */
    fun get(ctx: Context): Int {
        val saved = ctx.getSharedPreferences(app.dsh.mobile.DshApp.PREFS_UI, Context.MODE_PRIVATE)
            .getInt(KEY_PORT, EngineConfig.DEFAULT_PORT)
        return if (isValid(saved)) saved else EngineConfig.DEFAULT_PORT
    }

    /**
     * 写入端口，并同步到 [EngineConfig.currentPort]（引擎下次启动生效）。
     * @return false 表示端口越界，未写入。
     */
    fun set(ctx: Context, port: Int): Boolean {
        if (!isValid(port)) return false
        ctx.getSharedPreferences(app.dsh.mobile.DshApp.PREFS_UI, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PORT, port).apply()
        EngineConfig.currentPort = port
        return true
    }
}
