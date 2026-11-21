package app.dsh.mobile

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 悬浮窗「Web 界面刷新」的跨组件桥（v1.6.32，结构与 VoiceBridge 同因同型）。
 *
 * 刷新入口由**无障碍服务**的悬浮窗持有（不依赖 MainActivity 存活），但 WebView
 * 只活在 MainActivity 里 —— 两者在这里解耦：悬浮窗只发指令，执行侧由
 * MainActivity 注册（onCreate 注册 / onDestroy 注销）。
 */
object WebRefreshBridge {

    private const val TAG = "WebRefresh"

    /** 刷新回调（由 MainActivity 注册；null = 主界面不在，WebView 已随其销毁） */
    @Volatile private var hook: (() -> Unit)? = null

    /** MainActivity 注册刷新能力（onCreate 调用；onDestroy 传 null 注销） */
    fun register(block: (() -> Unit)?) {
        hook = block
    }

    /**
     * 悬浮窗点击「刷新」。
     *
     * ## 为什么要拉起主界面
     * 悬浮窗浮在**任意应用**上层。若只后台刷新、不把界面带回前台，刷新结果
     * 用户根本看不到 —— 表现就是「点了没反应」（本项目实测踩过多次的观感坑）。
     * 所以无论主界面是否存活，都先把它带到前台：
     *  · 存活（singleTask + REORDER_TO_FRONT）→ 原地置前，不重建、不闪启动遮罩；
     *  · 不存活 → 正常冷启：WebView 已随旧实例销毁，「刷新」无从谈起，
     *    此时打开主界面、WebUI 引擎 Healthy 后自动加载，就是最合理的响应。
     *
     * 刷新本体走 hook（MainActivity 内 reload 当前页，见 refreshCurrentWebView）。
     */
    fun refresh(ctx: Context) {
        val h = hook
        bringMainToFront(ctx)
        if (h == null) {
            Log.w(TAG, "refresh skipped: MainActivity not alive — bringing app to front instead")
            return
        }
        runCatching { h() }
            .onFailure { Log.w(TAG, "refresh failed: ${it.message}") }
    }

    /** 把主界面带到前台（存活的 singleTask 实例原地置前，不重建）。 */
    private fun bringMainToFront(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }.onFailure { Log.w(TAG, "bring-to-front failed: ${it.message}") }
    }
}
