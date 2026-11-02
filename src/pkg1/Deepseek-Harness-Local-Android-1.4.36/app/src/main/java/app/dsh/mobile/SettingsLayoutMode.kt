package app.dsh.mobile

import android.content.Context

/**
 * 设置页布局样式（v1.4.23，v1.8.2 收敛为两种）。
 *
 * 早期反馈「设置里很乱」——22 个条目平铺在一条长列表里，滚动到后半段容易迷路。
 * 现在提供两种排布：
 *
 *  · [LIST]   —— 分区标题 + 单列分组卡片（默认，一列到底，间距紧凑）
 *  · [CARD]   —— 每个分区一张独立大卡片，分区之间留更大间距，卡内每项两行
 *                （标题 + 说明），视觉分块更强
 *
 * 双列网格（GRID）在 v1.8.2 移除：它把 22 项压进一屏，代价是说明文字全部隐藏，
 * 用户找不到具体开关，反而更难用。密度问题改用「吸顶分区标题 + 点击回跳」解决。
 *
 * 两种排布复用同一份条目定义（见 SettingsActivity 的 row builders），不复制逻辑。
 */
enum class SettingsLayoutMode(val id: String, val labelRes: Int, val cols: Int) {
    LIST("list", R.string.settings_layout_list, 1),
    CARD("card", R.string.settings_layout_card, 1);

    companion object {
        private const val PREFS = "dsh_ui_prefs"
        private const val KEY = "settings_layout"

        fun fromId(id: String?): SettingsLayoutMode = entries.firstOrNull { it.id == id } ?: LIST

        fun current(ctx: Context): SettingsLayoutMode = fromId(
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        )

        fun set(ctx: Context, mode: SettingsLayoutMode) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, mode.id).apply()
        }
    }
}
