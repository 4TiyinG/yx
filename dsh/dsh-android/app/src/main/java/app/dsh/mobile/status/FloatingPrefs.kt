package app.dsh.mobile.status

import android.content.Context
import app.dsh.mobile.DshApp

/**
 * 系统悬浮窗（显示在其他应用上层）的持久化偏好（v1.6.35）。
 *
 * 与项目既有约定一致：全部落在 `dsh_ui` SharedPreferences，不新建文件。
 */
object FloatingPrefs {

    private const val KEY_ENABLED = "float_status_enabled"
    private const val KEY_X = "float_status_x"
    private const val KEY_Y = "float_status_y"
    private const val KEY_COLLAPSED = "float_status_collapsed"
    private const val KEY_GUIDE_SHOWN = "float_status_guide_shown"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)

    /** 总开关（用户意图）。权限缺失时仍保持 true —— 授权后即可自动恢复。 */
    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun x(ctx: Context): Int = prefs(ctx).getInt(KEY_X, -1)

    fun y(ctx: Context): Int = prefs(ctx).getInt(KEY_Y, -1)

    fun savePosition(ctx: Context, x: Int, y: Int) {
        prefs(ctx).edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    /** 最小化形态（只留一个小圆点）。 */
    fun collapsed(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_COLLAPSED, true)

    fun setCollapsed(ctx: Context, collapsed: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_COLLAPSED, collapsed).apply()
    }

    /** 权限引导弹窗是否已经展示过（避免每次回前台都弹）。 */
    fun guideShown(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_GUIDE_SHOWN, false)

    fun setGuideShown(ctx: Context, shown: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_GUIDE_SHOWN, shown).apply()
    }
}
