package app.dsh.mobile

import android.content.Context

/**
 * 轻量模式偏好（v1.7.6）。
 *
 * 开启后由 [app.dsh.mobile.MainActivity] 向 WebUI 注入特效削减样式：
 * `backdrop-filter`（毛玻璃）、`box-shadow`、`text-shadow`、`filter` 与 `will-change`
 * 全部置空。这些属性会让 Blink 为元素单独生成离屏合成层，是 WebView 内核
 * 内存与 GPU 纹理最主要的来源；去掉后功能（输入、滚动、布局）完全不变，
 * 只损失视觉层次感。默认关闭（多数设备外观优先）。
 */
object LiteMode {
    private const val KEY = "lite_mode"

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun set(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, on).apply()
    }
}
