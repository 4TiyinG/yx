package app.dsh.mobile

import android.app.Activity

/**
 * 全局 Edge-to-Edge 门面（v1.6.44 起是薄壳，v1.6.47 起成为**开关的唯一读点**）。
 *
 * 真正的实现搬到了 [app.dsh.mobile.ui.Insets]：那里补上了挖孔 inset、
 * ListView/NestedScrollView 的底部让位、以及横竖屏同一套映射。
 *
 * 保留这个对象是为了不动 20+ 处调用点（`EdgeToEdge.applyWindow(this)` /
 * `applyContentInsets(this)`），行为完全委托，不引入第二条实现路径 ——
 * 两套并存的 insets 逻辑正是 v1.6.42 那类回归的温床。
 *
 * ## v1.6.47：开关为什么必须在这里读
 *
 * 缺陷现场：「外观与显示 → 边到边显示」这个开关只写了 SharedPreferences，
 * **全工程没有任何一处读它** —— `applyWindow` 无条件
 * `setDecorFitsSystemWindows(false)`，所以关掉开关界面毫无变化（永远边到边）。
 *
 * 修法不是在每个 Activity 里各加一次判断（20+ 处，必然漂移），而是把偏好
 * 读点下沉到 [app.dsh.mobile.ui.Insets] 的**默认参数**里：任何调用点
 *（本门面、ThemedActivity 的直接调用、以后新增的页面）不传就是跟随开关。
 */
object EdgeToEdge {

    /**
     * @param darkSurface 本页背景恒为深色（终端 / 日志查看器）。图标恒用浅色，
     *                    否则浅色主题下会出现深色图标压在深色终端上。
     */
    @JvmOverloads
    fun applyWindow(activity: Activity, darkSurface: Boolean = false) {
        app.dsh.mobile.ui.Insets.applyWindow(activity, darkSurface)
    }

    fun applyContentInsets(activity: Activity) {
        app.dsh.mobile.ui.Insets.applyContent(activity)
    }
}
