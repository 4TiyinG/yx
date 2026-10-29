package app.dsh.mobile

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * 应用内语言（v1.5.1）：只保留中文。
 *
 * 历史：早前支持 en / de / zh / pt-BR 并默认英语；现按需求收敛为单一中文，
 * 旧的偏好值（en/de/pt、system）一律归一化为 zh，资源里也只保留默认中文。
 */
object LocaleHelper {
    private const val PREFS = "app_locale"
    private const val KEY = "locale"

    const val DEFAULT = "zh"
    val SUPPORTED = listOf("zh")

    fun get(ctx: Context): String = DEFAULT

    fun set(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY, DEFAULT).apply()
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 包装 Context：始终使用中文 */
    fun wrap(ctx: Context): Context {
        val locale = Locale(DEFAULT)
        Locale.setDefault(locale)
        val config = android.content.res.Configuration(ctx.resources.configuration)
        config.setLocale(locale)
        return ctx.createConfigurationContext(config)
    }
}
