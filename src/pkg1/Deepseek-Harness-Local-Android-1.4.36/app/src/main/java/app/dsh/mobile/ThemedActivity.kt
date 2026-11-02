package app.dsh.mobile

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** 原生壳主题：深色（默认）/ 浅色 / 跟随系统。WebView 由 dsh 自己管理，不在这里处理。 */
enum class ThemeMode(val key: String, val labelRes: Int) {
    DARK("dark", R.string.theme_dark),
    LIGHT("light", R.string.theme_light),
    SYSTEM("system", R.string.theme_system);

    companion object {
        fun fromKey(key: String?): ThemeMode =
            values().firstOrNull { it.key == key } ?: DARK
    }
}

object ThemePref {
    private const val KEY = "theme_mode"

    fun get(context: Context): ThemeMode =
        ThemeMode.fromKey(
            context.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
                .getString(KEY, ThemeMode.DARK.key),
        )

    fun set(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
            .edit().putString(KEY, mode.key).apply()
    }
}

object ThemeManager {
    /** 让 Activity 的 resources 按偏好选择 values / values-night。 */
    fun wrap(base: Context): Context {
        val mode = ThemePref.get(base)
        if (mode == ThemeMode.SYSTEM) return base
        val config = Configuration(base.resources.configuration)
        val night = if (mode == ThemeMode.DARK) {
            Configuration.UI_MODE_NIGHT_YES
        } else {
            Configuration.UI_MODE_NIGHT_NO
        }
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(config)
    }

    fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * 统一系统栏（v1.5）：所有原生页面默认 Edge-to-Edge ——
     * 内容绘制到状态栏/导航栏后面，但**系统栏本身保持可见**。
     *
     * 只关掉窗口「为系统栏留位」的行为（setDecorFitsSystemWindows(false) /
     * LAYOUT_FULLSCREEN 布局位），绝不下 SYSTEM_UI_FLAG_FULLSCREEN / IMMERSIVE_STICKY。
     * edgeToEdge=false 时恢复传统模式（系统栏留位 + 主题底色）。
     */
    fun applyBars(activity: Activity, edgeToEdge: Boolean = true) {
        val window = activity.window
        val dark = isDark(activity)
        if (edgeToEdge) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(true)
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility and
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN.inv()
            }
            @Suppress("DEPRECATION")
            window.statusBarColor = activity.getColor(R.color.bg)
        }
        @Suppress("DEPRECATION")
        window.navigationBarColor = activity.getColor(R.color.bg)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // 状态栏/导航栏图标明暗：浅色底 → 深色图标，深色底 → 浅色图标。
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }
}

/** 所有原生 Activity 的基类：attachBaseContext 里应用主题，onCreate 里统一系统栏。 */
open class ThemedActivity : Activity() {
    /**
     * 内容区是否自动避让系统栏。
     * 默认 true：把 setContentView 的根视图按 statusBars / navigationBars 内边距下移，
     * 页面标题栏不会被状态栏图标压住（Edge-to-Edge 下的标准做法）。
     * MainActivity 覆写为 false —— 网页内容要真正铺满，由它自己只给原生浮层做避让。
     */
    protected open val insetContentAutomatically: Boolean = true

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Dsh)
        super.onCreate(savedInstanceState)
        ThemeManager.applyBars(this)
    }

    override fun onContentChanged() {
        super.onContentChanged()
        if (!insetContentAutomatically) return
        val content = findViewById<View>(android.R.id.content) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            if (v.paddingTop != bars.top || v.paddingBottom != bars.bottom) {
                v.setPadding(0, bars.top, 0, bars.bottom)
            }
            insets
        }
        content.requestApplyInsets()
    }
}
