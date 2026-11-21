package app.dsh.mobile.ui

/**
 * 全局动效常量与工具（v1.6.44）。
 *
 * ## 为什么要有这个文件
 *
 * 重构前「动效」根本没有统一来源：
 *  · 页面转场是 `ThemedActivity` 里写死的 `R.anim.settings_in`，但
 *    [app.dsh.mobile.LogViewerActivity] 与 [app.dsh.mobile.TerminalActivity]
 *    **不继承** ThemedActivity，所以这两页是全系统里唯一"没有转场动画"的页面 ——
 *    从设置点进终端，画面是直接硬切过去的；
 *  · 弹窗动画只挂在 `Theme.Dsh.Dialog` 上，`LanAccessDialog`、`ReportDialog`
 *    这类代码里手工 `AlertDialog.Builder` 出来的弹窗走的是系统默认动画
 *    （缩放 + 淡入但时长不同），与 XML 弹窗的手感不一致；
 *  · 按压反馈只有 `activity_appearance.xml` 的三张预览卡用了
 *    `@animator/press_scale`，其余按钮一律系统涟漪 —— 一半缩、一半亮。
 *
 * 这里把"时长"和"曲线"收成唯一来源，XML 侧的同名值在
 * `res/values/tokens.xml`（`motion_*`），两处必须一致，探针会校验。
 *
 * ## 时长为什么是这几个数
 *
 * · 90 / 170ms：按下比回弹快。按压"立刻有反应"，回弹"从容收尾"，
 *   这是 Material 的 state-layer 时长区间（< 100ms 按下，150-200ms 复原）。
 * · 220 / 160ms：弹窗进场略慢于出场 —— 进场要"被看清"，出场只需"消失"。
 * · 300 / 260ms：页面转场。300ms 是横向滑动不显拖沓的上限，
 *   出场 260ms 略快，让返回动作比进入更利落。
 */
object Motion {

    /** 按下：收紧到 [PRESS_SCALE]。够快才像"按下去"，慢一点就变成"陷下去"。 */
    const val PRESS_MS = 90L

    /** 松开：回弹到 1。比按下慢，避免回弹比按下还急造成的"弹一下"。 */
    const val RELEASE_MS = 170L

    /** 页面进场（新页从右侧滑入）。 */
    const val PAGE_IN_MS = 300L

    /** 页面出场（返回时旧页退出）。 */
    const val PAGE_OUT_MS = 260L

    /** 弹窗进场。 */
    const val DIALOG_IN_MS = 220L

    /** 弹窗出场。 */
    const val DIALOG_OUT_MS = 160L

    /** 按压缩放比例（96%）。与 `@animator/press_scale` 一致。 */
    const val PRESS_SCALE = 0.96f

    /**
     * 页面转场动画资源：进入 = 新页滑入 + 旧页保持；退出 = 旧页保持 + 新页滑出。
     *
     * 用 `@JvmField val` 而不是 `const val`：`R.anim.*` 是运行时生成的
     * `static final int`（非编译期内联常量），不能用 `const`。
     */
    @JvmField val ANIM_PAGE_IN = app.dsh.mobile.R.anim.settings_in
    @JvmField val ANIM_PAGE_HOLD = app.dsh.mobile.R.anim.settings_hold
    @JvmField val ANIM_PAGE_OUT = app.dsh.mobile.R.anim.settings_out

    /** 精简档：纯淡入/淡出（无横向位移）。 */
    @JvmField val ANIM_FADE_IN = app.dsh.mobile.R.anim.page_fade_in
    @JvmField val ANIM_FADE_OUT = app.dsh.mobile.R.anim.page_fade_out

    /** 转场资源对（进场, 出场旁路），按用户的动效档位解析。 */
    class PageAnims(val enter: Int, val exit: Int)

    /**
     * 按动效档位解析转场资源。
     *
     * · FULL    → 滑入 + 保持（原行为）
     * · REDUCED → 纯淡入 + 保持（去掉整屏位移合成）
     * · OFF     → 0（`overridePendingTransition(0,0)` = 系统不播放任何动画，瞬切）
     */
    fun pageAnims(level: app.dsh.mobile.PerfPrefs.MotionLevel): PageAnims = when (level) {
        app.dsh.mobile.PerfPrefs.MotionLevel.FULL -> PageAnims(ANIM_PAGE_IN, ANIM_PAGE_HOLD)
        app.dsh.mobile.PerfPrefs.MotionLevel.REDUCED -> PageAnims(ANIM_FADE_IN, ANIM_PAGE_HOLD)
        app.dsh.mobile.PerfPrefs.MotionLevel.OFF -> PageAnims(0, 0)
    }

    /** 是否播放入场/返回转场。 */
    fun pageTransitionEnabled(level: app.dsh.mobile.PerfPrefs.MotionLevel): Boolean =
        level != app.dsh.mobile.PerfPrefs.MotionLevel.OFF
}
