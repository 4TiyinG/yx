package app.dsh.mobile.status

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import app.dsh.mobile.Accent
import app.dsh.mobile.DshApp
import app.dsh.mobile.MainActivity
import app.dsh.mobile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 系统悬浮窗状态条（v1.6.35）：基于「显示在其他应用上层」权限的常驻状态显示。
 *
 * ## 与既有 [app.dsh.mobile.StatusOverlay] 的区别
 * | | StatusOverlay（v1.2.57） | 本服务（v1.6.35） |
 * |---|---|---|
 * | 权限 | 无障碍服务（TYPE_ACCESSIBILITY_OVERLAY） | 系统悬浮窗（TYPE_APPLICATION_OVERLAY） |
 * | 前置 | 用户必须去无障碍设置里开启服务 | 用户只需授予「显示在其他应用上层」 |
 * | 常驻 | 一直在（含 App 前台） | **仅在 App 退到后台/别家应用上层时出现** |
 *
 * 两者互不影响：无障碍球是「随时可用的操作入口」，本悬浮窗是「退到后台时的状态灯」。
 *
 * ## 显示规则（需求 1）
 * `want = 总开关打开 && 有悬浮窗权限 && App 不在前台`
 * —— 主界面活跃时绝不出现；切到后台/别的应用时淡入；回前台时淡出。
 * 前后台判定用 [AppForeground]（started 计数），Activity 跳转期间不抖动。
 *
 * ## 状态同步（需求 2）
 * collect [WorkStatus.state]（思考中 / 执行中 / 已完成 / 出错），
 * 颜色用 ArgbEvaluator 平滑过渡，文字原地替换 —— 不重建视图、不闪烁。
 *
 * ## 交互（需求 3）
 * · 点一下：在「小圆点（最小化）」与「状态条」之间切换；
 * · 拖动：移动位置，松手吸附到屏幕内并持久化；
 * · 窗口 FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL：不抢焦点，范围外的触摸直接
 *   落给下面的应用 —— 最小化后不遮挡、不阻塞其它应用操作。
 *
 * ## 异常处理（需求 4）
 * 权限被关闭时：立即移除窗口（不崩、不残留），通知栏给出引导文案；
 * 本服务保持存活并每 2.5s 复查一次，权限恢复后**自动**重新挂出窗口。
 * App 内（设置页/主界面 onResume）另有明确的引导提示。
 */
class FloatingStatusService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private var wm: WindowManager? = null
    private var root: View? = null
    private var dot: View? = null
    private var text: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    /** 窗口当前是否已挂在 WindowManager 上 */
    private var attached = false

    /** 当前形态：true = 只有小圆点 */
    private var collapsed = true

    /** 权限缺失告警是否已发过（避免每 2.5s 刷一次） */
    private var permissionWarned = false

    /** 上一次渲染的状态（用于判断是否需要更新通知/文字） */
    private var lastPhase: WorkStatus.Phase? = null

    private var textAnim: Job? = null

    /** 周期检查：权限 + 终态回落。 */
    private val ticker = object : Runnable {
        override fun run() {
            checkPermission()
            WorkStatus.idleIfSettledAfter(TERMINAL_HOLD_MS)
            syncVisibility()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
        startAsForeground()
        collapsed = FloatingPrefs.collapsed(this)

        scope.launch {
            WorkStatus.state.collectLatest { snapshot -> render(snapshot) }
        }
        scope.launch {
            AppForeground.foreground.collectLatest { syncVisibility() }
        }
        // v1.6.35：引擎异常也归入悬浮窗状态（需求 2 的「发生错误」）。
        // 只认 Failed/Backoff：Starting/Installing 是正常启动过程，不算错误。
        scope.launch {
            (application as DshApp).supervisor.state.collectLatest { st ->
                when (st) {
                    is app.dsh.mobile.engine.EngineSupervisor.State.Failed ->
                        WorkStatus.error(st.reason.take(40))
                    is app.dsh.mobile.engine.EngineSupervisor.State.Backoff ->
                        WorkStatus.error(getString(R.string.float_status_engine_restarting))
                    is app.dsh.mobile.engine.EngineSupervisor.State.Healthy,
                    is app.dsh.mobile.engine.EngineSupervisor.State.SafeMode,
                    -> if (WorkStatus.current.phase == WorkStatus.Phase.ERROR) WorkStatus.idle()
                    else -> Unit
                }
            }
        }
        handler.post(ticker)
        Log.i(TAG, "floating status service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                FloatingPrefs.setEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (!FloatingPrefs.enabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        checkPermission()
        syncVisibility()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        detachWindow(animate = false)
        root = null
        dot = null
        text = null
        params = null
        if (instance === this) instance = null
        Log.i(TAG, "floating status service destroyed")
    }

    // ---------------------------------------------------------------- 显示/隐藏

    /** 需求 1：只有「开关开 + 有权限 + App 不在前台」才显示。 */
    private fun syncVisibility() {
        val want = FloatingPrefs.enabled(this) &&
            Settings.canDrawOverlays(this) &&
            !AppForeground.isForeground()
        when {
            want && !attached -> attachWindow()
            !want && attached -> detachWindow(animate = true)
        }
    }

    private fun attachWindow() {
        val w = wm ?: return
        if (attached) return
        runCatching {
            val view = root ?: LayoutInflater.from(this)
                .inflate(R.layout.overlay_work_status, null).also { v ->
                    root = v
                    dot = v.findViewById(R.id.floatDot)
                    text = v.findViewById(R.id.floatText)
                    bindTouch(v)
                }

            val p = params ?: WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= 26) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                // 不抢焦点：下面应用的输入法/按键照常工作
                // + HARDWARE_ACCELERATED：addView 挂出的窗口不自动硬件加速（v1.6.48）
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            // 首次挂出/重新挂出时，按当前状态决定初始形态：
            // 正在干活 → 直接条状（用户退到后台就该看到状态，而不是先看到一颗点）；
            // 空闲 → 小圆点。会覆盖「上次保存的展开态」，但那只在本轮生效（不写偏好）。
            collapsed = WorkStatus.current.phase == WorkStatus.Phase.IDLE
            applyForm(view, collapsed, animate = false)

            // 首次挂出：位置取上次保存值，否则默认右上角
            if (p.x == 0 && p.y == 0) {
                val dm = resources.displayMetrics
                p.x = FloatingPrefs.x(this).takeIf { it >= 0 } ?: (dm.widthPixels - dp(96))
                p.y = FloatingPrefs.y(this).takeIf { it >= 0 } ?: (dm.heightPixels * 3 / 10)
            }
            clamp(p)
            params = p

            view.alpha = 0f
            view.scaleX = 0.92f
            view.scaleY = 0.92f
            w.addView(view, p)
            attached = true
            render(WorkStatus.current)
            view.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(ANIM_IN_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }.onFailure { Log.w(TAG, "attach failed: ${it.message}") }
    }

    private fun detachWindow(animate: Boolean) {
        val w = wm ?: return
        val view = root ?: return
        if (!attached) return
        attached = false
        if (!animate) {
            runCatching { w.removeView(view) }
            return
        }
        view.animate()
            .alpha(0f).scaleX(0.92f).scaleY(0.92f)
            .setDuration(ANIM_OUT_MS)
            .withEndAction {
                // 动画期间可能又被重新挂上（快速前后台切换）——只移除本轮的视图
                if (!attached) runCatching { w.removeView(view) }
            }
            .start()
    }

    // ---------------------------------------------------------------- 状态渲染

    private fun render(s: WorkStatus.Snapshot) {
        val view = root ?: return
        val label = labelFor(s)
        val color = colorFor(s.phase)

        text?.let { tv ->
            if (tv.text.toString() != label) tv.text = label
        }
        // 颜色平滑过渡（不做逐帧重绘，ArgbEvaluator 直接改写 tint）
        animateDotColor(color)

        // 只在**相位变化**时调整形态与通知：同一个相位内的动作细节刷新不动形态，
        // 否则用户手动收起后会被每一次「执行中 · xxx」更新又弹回来。
        if (lastPhase != s.phase) {
            lastPhase = s.phase
            updateNotification(label)
            // 需求 2/3：有活干 → 展开显示状态；空闲 → 收回小圆点不遮挡。
            // 不写入偏好，用户手动切换的习惯不被自动行为覆盖。
            if (s.phase == WorkStatus.Phase.IDLE) {
                if (!collapsed) setCollapsed(true, animate = true, persist = false)
            } else {
                if (collapsed) setCollapsed(false, animate = true, persist = false)
            }
        }
        if (view.alpha < 1f && attached) {
            view.animate().alpha(1f).setDuration(ANIM_IN_MS).start()
        }
    }

    private fun labelFor(s: WorkStatus.Snapshot): String = when (s.phase) {
        WorkStatus.Phase.IDLE -> getString(R.string.float_status_idle)
        WorkStatus.Phase.THINKING -> getString(R.string.float_status_thinking)
        WorkStatus.Phase.EXECUTING ->
            if (s.detail.isBlank()) getString(R.string.float_status_executing)
            else getString(R.string.float_status_executing_detail, s.detail.take(24))
        WorkStatus.Phase.DONE -> getString(R.string.float_status_done)
        WorkStatus.Phase.ERROR ->
            if (s.detail.isBlank()) getString(R.string.float_status_error)
            else getString(R.string.float_status_error_detail, s.detail.take(24))
    }

    private fun colorFor(phase: WorkStatus.Phase): Int = when (phase) {
        WorkStatus.Phase.IDLE -> COLOR_IDLE
        WorkStatus.Phase.THINKING -> Accent.current(this).argb
        WorkStatus.Phase.EXECUTING -> Accent.current(this).argb
        WorkStatus.Phase.DONE -> COLOR_DONE
        WorkStatus.Phase.ERROR -> COLOR_ERROR
    }

    private fun animateDotColor(target: Int) {
        val d = dot ?: return
        val current = lastDotColor ?: target
        if (current == target) {
            d.background?.setTint(target)
            return
        }
        lastDotColor = target
        ValueAnimator.ofObject(ArgbEvaluator(), current, target).apply {
            duration = COLOR_ANIM_MS
            addUpdateListener { d.background?.setTint(it.animatedValue as Int) }
            start()
        }
    }

    private var lastDotColor: Int? = null

    // ---------------------------------------------------------------- 形态切换

    private fun setCollapsed(next: Boolean, animate: Boolean, persist: Boolean = true) {
        collapsed = next
        if (persist) FloatingPrefs.setCollapsed(this, next)
        val view = root ?: return
        applyForm(view, next, animate)
    }

    /**
     * 应用形态。
     *
     * 无闪烁的关键：**先让尺寸变化发生在不可见瞬间** —— 收起时先把文字淡出再 GONE，
     * 展开时先让文字（alpha=0）占位再淡入。窗口尺寸随之变化时画面始终是连续的，
     * 不会出现「内容跳一下」。
     */
    private fun applyForm(view: View, shouldCollapse: Boolean, animate: Boolean) {
        val tv = text ?: return
        val padC = dp(6)
        val padH = dp(10)
        val padV = dp(7)

        if (shouldCollapse) {
            if (animate) {
                tv.animate().alpha(0f).setDuration(ANIM_FORM_MS).withEndAction {
                    tv.visibility = View.GONE
                    tv.alpha = 1f
                    view.setPadding(padC, padC, padC, padC)
                    relayout()
                }.start()
            } else {
                tv.visibility = View.GONE
                view.setPadding(padC, padC, padC, padC)
            }
        } else {
            tv.visibility = View.VISIBLE
            view.setPadding(padH, padV, dp(12), padV)
            if (animate) {
                tv.alpha = 0f
                relayout()
                tv.animate().alpha(1f).setDuration(ANIM_FORM_MS).start()
            } else {
                tv.alpha = 1f
            }
        }
    }

    private fun relayout() {
        val w = wm ?: return
        val v = root ?: return
        val p = params ?: return
        runCatching { w.updateViewLayout(v, p) }
    }

    // ---------------------------------------------------------------- 触摸

    private fun bindTouch(view: View) {
        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        var dragging = false

        view.setOnTouchListener { _, ev ->
            val p = params ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    originX = p.x
                    originY = p.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!dragging && (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6))) {
                        dragging = true
                    }
                    if (dragging) {
                        p.x = originX + dx.toInt()
                        p.y = originY + dy.toInt()
                        clamp(p)
                        // 拖动期不加动画：直接跟手才跟得住
                        runCatching { wm?.updateViewLayout(view, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        FloatingPrefs.savePosition(this, p.x, p.y)
                    } else {
                        // 点按：在最小化与状态条之间切换
                        setCollapsed(!collapsed, animate = true)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    /** 把窗口夹在屏幕可视区内（留边距，避免拖出屏幕再也点不到）。 */
    private fun clamp(p: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val vw = root?.width?.takeIf { it > 0 } ?: dp(96)
        val vh = root?.height?.takeIf { it > 0 } ?: dp(36)
        val margin = dp(4)
        p.x = p.x.coerceIn(margin, (dm.widthPixels - vw - margin).coerceAtLeast(margin))
        p.y = p.y.coerceIn(margin, (dm.heightPixels - vh - margin).coerceAtLeast(margin))
    }

    // ---------------------------------------------------------------- 权限

    /**
     * 需求 4：权限被关闭 → 立即撤窗（不留残影、不崩），并给出一次引导。
     * 恢复后由 ticker / App 内 onResume 自动重新挂出。
     */
    private fun checkPermission() {
        if (!FloatingPrefs.enabled(this)) return
        val ok = Settings.canDrawOverlays(this)
        if (!ok) {
            if (attached) detachWindow(animate = false)
            if (!permissionWarned) {
                permissionWarned = true
                // 重置「已引导」标记：用户是**中途**撤掉的权限，回到 App 时
                // 应该再给一次明确引导（而不是因为开过一次就再不提醒）。
                FloatingPrefs.setGuideShown(this, false)
                Log.w(TAG, "overlay permission revoked — window removed, waiting for restore")
                updateNotification(getString(R.string.float_status_need_permission))
            }
        } else if (permissionWarned) {
            permissionWarned = false
            Log.i(TAG, "overlay permission restored — re-attaching")
            syncVisibility()
            updateNotification()
        }
    }

    // ---------------------------------------------------------------- 通知

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.float_status_channel_name),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = getString(R.string.float_status_channel_desc)
                    setShowBadge(false)
                },
            )
        }
    }

    private fun startAsForeground() {
        runCatching { startForeground(NOTIF_ID, buildNotification()) }
            .onFailure { Log.w(TAG, "startForeground failed: ${it.message}") }
    }

    private fun buildNotification(subtitle: String? = null): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.float_status_notif_title))
            .setContentText(subtitle ?: getString(R.string.float_status_notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun updateNotification(subtitle: String? = null) {
        if (!FloatingPrefs.enabled(this)) return
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(subtitle))
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "DshFloatStatus"
        private const val CHANNEL_ID = "float_status"
        private const val NOTIF_ID = 0x5F17

        /** 周期检查（权限恢复 / 终态回落）。2.5s：够快又几乎零开销。 */
        private const val TICK_MS = 2_500L

        /** 完成/错误态在悬浮窗停留多久后回落为空闲 */
        private const val TERMINAL_HOLD_MS = 12_000L

        private const val ANIM_IN_MS = 180L
        private const val ANIM_OUT_MS = 140L
        private const val ANIM_FORM_MS = 110L
        private const val COLOR_ANIM_MS = 220L

        private const val COLOR_IDLE = 0xFF9AA0A6.toInt()
        private const val COLOR_DONE = 0xFF81C995.toInt()
        private const val COLOR_ERROR = 0xFFF28B82.toInt()

        const val ACTION_STOP = "app.dsh.mobile.status.action.STOP"

        @Volatile private var instance: FloatingStatusService? = null

        fun isRunning(): Boolean = instance != null

        fun start(ctx: Context) {
            val i = Intent(ctx, FloatingStatusService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }.onFailure { Log.w(TAG, "start failed: ${it.message}") }
        }

        /** 关闭总开关并停服务（设置页关掉时调用）。 */
        fun stop(ctx: Context) {
            FloatingPrefs.setEnabled(ctx, false)
            runCatching { ctx.stopService(Intent(ctx, FloatingStatusService::class.java)) }
        }

        /**
         * 需求 4 的「自动恢复」入口：MainActivity.onResume / 设置页 onResume 调用。
         * 只要用户开着开关且有权限，就把服务拉起来（幂等）。
         */
        fun restoreIfEnabled(ctx: Context) {
            if (!FloatingPrefs.enabled(ctx)) return
            if (!Settings.canDrawOverlays(ctx)) return
            if (isRunning()) return
            start(ctx)
        }
    }
}
