package app.dsh.mobile

import android.content.Context

/**
 * 「性能与流畅度」偏好（v1.6.45 新增）。
 *
 * ## 为什么要把它做成设置项，而不是写死
 *
 * 前几轮的性能改动都是**全局一刀切**：所有机器都同样的动效时长、同样的预热策略、
 * 同样的渲染降级阈值。但真机差异极大 —— 旗舰机上「动效全开」是质感，在四年前
 * 的中端机上是掉帧；旗舰机上「预热全部资源」是几毫秒的额外 IO，在低端机上是
 * 启动时的卡顿。同一套参数不可能同时是最优。
 *
 * 所以这里把三个真实影响帧时间/首屏时间的旋钮交给用户，并且**默认值全部取
 * 「与改动前完全一致的保守值」**——不选任何东西的用户，行为与 v1.6.44 相同。
 *
 * | 旋钮 | 影响 | 默认 |
 * |---|---|---|
 * | [MotionLevel] | 页面转场/弹窗/吸附动画的时长与是否播放 | FULL |
 * | [RenderProfile] | 内核侧渲染降级的触发条件（模糊/阴影/合成层） | BALANCED |
 * | [preloadEnabled] | 首屏静态资源是否在启动时预载入内存 | 开 |
 * | [preScaleEnabled] | 大图是否按显示尺寸预解码 | 开 |
 *
 * 全部是**读多写少**的布尔/枚举，用 SharedPreferences 同步读（首次读会在
 * 主线程读一次 XML，之后走内存缓存；读点在 onCreate，不在逐帧路径上）。
 */
object PerfPrefs {

    private const val PREFS = "dsh_perf_prefs"
    private const val KEY_MOTION = "motion_level"
    private const val KEY_RENDER = "render_profile"
    private const val KEY_PRELOAD = "asset_preload"
    private const val KEY_PRESCALE = "image_prescale"

    /**
     * 界面动效强度。
     *
     * · [FULL]    完整：页面左右滑入 + 弹窗缩放淡入 + 头部吸附动画（原行为）
     * · [REDUCED] 精简：页面只做淡入淡出（去掉横向位移，少一次全屏 surface 合成），
     *             弹窗与吸附保留 —— 观感基本不变，但少掉最贵的那层位移
     * · [OFF]     关闭：转场/弹窗/吸附全部瞬切。给"只想要快"的场景
     */
    enum class MotionLevel(val id: String, val labelRes: Int) {
        FULL("full", R.string.perf_motion_full),
        REDUCED("reduced", R.string.perf_motion_reduced),
        OFF("off", R.string.perf_motion_off);

        companion object {
            fun fromId(id: String?): MotionLevel = entries.firstOrNull { it.id == id } ?: FULL
        }
    }

    /**
     * 内核渲染档位 —— 决定"什么时候把昂贵视觉效果换成廉价等价物"。
     *
     * · [SMOOTH]   流畅优先：**始终**注入降级样式（卸模糊/阴影/强制合成层），
     *              并给首屏外图片加 `loading=lazy`。低端机滚动最稳。
     * · [BALANCED] 均衡：只在系统报告热受限时降级（v1.6.36 的行为，默认）
     * · [QUALITY]  画质优先：**永不**降级，即使发烫也保留模糊与阴影
     */
    enum class RenderProfile(val id: String, val labelRes: Int) {
        SMOOTH("smooth", R.string.perf_render_smooth),
        BALANCED("balanced", R.string.perf_render_balanced),
        QUALITY("quality", R.string.perf_render_quality);

        companion object {
            fun fromId(id: String?): RenderProfile = entries.firstOrNull { it.id == id } ?: BALANCED
        }
    }

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun motion(ctx: Context): MotionLevel =
        MotionLevel.fromId(sp(ctx).getString(KEY_MOTION, null))

    fun setMotion(ctx: Context, v: MotionLevel) =
        sp(ctx).edit().putString(KEY_MOTION, v.id).apply()

    fun render(ctx: Context): RenderProfile =
        RenderProfile.fromId(sp(ctx).getString(KEY_RENDER, null))

    fun setRender(ctx: Context, v: RenderProfile) =
        sp(ctx).edit().putString(KEY_RENDER, v.id).apply()

    /** 首屏静态资源预载（CSS/字体进内存层，见 WebAssetCache.preloadHot）。 */
    fun preload(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_PRELOAD, true)

    fun setPreload(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_PRELOAD, on).apply()

    /** 大图按显示尺寸预解码（见 ImagePreScaler）。 */
    fun preScale(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_PRESCALE, true)

    fun setPreScale(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_PRESCALE, on).apply()
}
