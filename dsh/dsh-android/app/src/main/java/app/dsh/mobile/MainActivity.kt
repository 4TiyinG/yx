package app.dsh.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.BootTrace
import app.dsh.mobile.engine.EngineUpdater
import app.dsh.mobile.engine.OpenBridge
import app.dsh.mobile.engine.RepoConfig
import app.dsh.mobile.engine.RuntimeInstaller
import app.dsh.mobile.engine.WebViewDownloader
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.Privilege
import app.dsh.mobile.engine.PluginBootBus
import app.dsh.mobile.service.EngineService
import app.dsh.mobile.web.WebAssetCache
import app.dsh.mobile.pet.PetOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * WebView 容器。
 *
 * UI 策略：不重写官方 WebUI（上游 developer preview 迭代快，追协议是无底洞），
 * 只做原生外壳 —— 引擎 Healthy 后加载 127.0.0.1 回环页面，状态条显示引擎生命周期。
 *
 * 设置入口：右上角 ⋯ 跳转独立设置页（SettingsActivity，MIUI 风格分组卡片），
 * 不再使用悬浮弹窗菜单。
 */
class MainActivity : ThemedActivity() {

    /** 主界面由 applyIslandInsets 自行处理 Insets（WebView 需延伸到底部栏后方）。 */
    override val appliesContentInsets: Boolean get() = false

    private lateinit var webView: WebView
    private lateinit var statusBar: TextView
    private var urlLoaded = false

    /**
     * WebView 预热已发起（v1.6.0）。
     *
     * 冷启动时 `loadUrl` 只在引擎进入 Healthy 后才会被调用（见 render()），
     * 于是 Chromium 进程创建 + JS 引擎初始化 + 合成器建立（实测约 700ms）
     * 全部排在引擎之后串行执行。但这段初始化**不依赖引擎** —— 只有页面内容依赖。
     *
     * 所以 onCreate 里先加载一个本地空白页，把这 700ms 提前跑掉；等引擎
     * Healthy 后 `loadLocalUrl(realUrl)` 直接导航，Chromium 已是热态。
     *
     * 注意：预热页的 `onPageFinished` **不能**触发 dismissSplash()，
     * 否则会在引擎还没起来时就把遮罩收掉，露出还没内容的 WebView。
     */
    private var webViewPreheated = false

    // ── v1.6.37：渲染进程死亡后的「静默恢复」状态机 ──────────────────
    //
    // ## 为什么必须改
    // ColorOS 会在后台回收 WebView 渲染进程（内存压力/省电策略），旧实现在
    // onRenderProcessGone 里**当场重建并 loadUrl**，于是：后台回收 → 立刻重建 +
    // 加载 → 再次被回收 → 再重建 …… 形成重载死循环，同时把遮罩/白屏反复闪出。
    //
    // ## 新规则
    // · onRenderProcessGone：只**退役**旧实例（移除 + destroy），标记 pendingRecovery，
    //   返回 true。不弹窗、不重载、不碰引擎。
    // · 回到前台（onResume）时，若 pendingRecovery 为真，才创建全新 WebView 静默加载。
    //
    /** 渲染进程已死，等待回到前台后静默恢复。 */
    @Volatile private var pendingRecovery = false
    /** 当前 webView 实例已退役（已 remove + destroy）——任何方法都不可再调。 */
    @Volatile private var webViewDead = false
    /** 正在恢复，防重入（onResume 与点击事件可能同一帧触发）。 */
    private var recovering = false
    /** WebView 的宿主容器：退役后 webView 的 parent 就没了，必须提前记住。 */
    private var webViewHost: android.view.ViewGroup? = null
    /** 热状态订阅已装（setupWebView 会在恢复时再跑一遍，不能重复 collect）。 */
    private var thermalSubscribed = false

    /**
     * 首帧后的收尾延迟（v1.6.45）。
     *
     * 取值 = WebUI 交叉溶解时长（360ms）以外的"已经进入稳定帧"的时点。
     * 400ms 让淡入动画完整走完；再短会和动画的最后一段重叠，再长会让
     * 「合帧脚本」装得太晚（用户可能已经在滚动了）。
     */
    private val FIRST_FRAME_SETTLE_MS = 400L

    /**
     * 页面加载完成后再等多久取一次渲染观测快照（v1.6.46，仅 debug）。
     *
     * 6s ≈ 首屏 + 第一轮插件加载都完成之后：太早只能看到启动噪声，
     * 太晚用户可能已经离开了这页。取完即写 engine.log，不常驻。
     */
    private val RENDER_STATS_DELAY_MS = 6000L

    /**
     * IME 让位量化步长（dp）。8dp 在视觉上低于「滑动中的键盘」可辨阈值，
     * 但足以把一次键盘动画的重排次数从 30+ 压到个位数。
     */
    private val IME_PAD_QUANTUM_DP = 8

    /** 键盘停稳判定：最后一次 insets 之后多久补精确值 */
    private val IME_SETTLE_DELAY_MS = 90L

    /**
     * 灵动岛与「上方那条边」的间距（dp，v1.6.50）。
     *
     * App 自己在顶部唯一能缩的就是这一段 —— 状态栏的高度是系统的，不属于我们。
     *   · 边到边：岛下面就是网页内容，10dp 呼吸位让它不像"压"在页面上；
     *   · 紧凑：岛上面是状态栏图标，4dp 足够不贴住，也把 6dp 还给页面。
     */
    private val ISLAND_TOP_GAP_E2E_DP = 10
    private val ISLAND_TOP_GAP_COMPACT_DP = 4

    /**
     * 紧凑模式底部「触摸地板」（dp，v1.6.50）：手势导航下页面底部只让出这么多。
     * 8dp 足以避开手势条的视觉高度，又不至于把页面压回 v1.6.49 那条 24dp 空条。
     */
    private val COMPACT_BOTTOM_FLOOR_DP = 8

    /**
     * 导航条高度超过这个值（dp）就按「实体按键导航」处理（整条让开）。
     * 手势条高度实测 16~30dp，三键/两键导航 40dp+，32dp 是两者之间的安全分界。
     */
    private val COMPACT_NAV_GESTURE_MAX_DP = 32

    /** 当前包是否 debuggable（观测类代码只在 debug 包开启，release 零成本）。 */
    private val isDebuggable: Boolean by lazy {
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /** 预热占位页的 URL 前缀；onPageFinished 靠它区分「预热页」与「真正的 WebUI」 */
    private val preheatUrl = "about:blank"

    /**
     * 页面上报的返回栈状态（手机形态布局插件经 `DshAndroidBack` 写入）。
     *
     * 只看两个字段：
     * - `mobile`：当前是不是手机竖屏形态（插件负责判定，桌面/原生恒 false）；
     * - `consumable`：WebView 历史栈顶是否真有**同文档**条目可退。
     *
     * 为什么不能只信 `canGoBack()`：WebView 历史里可能存在跨文档条目（启动
     * 重定向、runtime 跳转、外链回退），退过去就是整页重载 —— 用户看到的是
     * 「按返回它重新加载了一下」。所以只有插件明确说「有条目」时才 goBack。
     *
     * 字段由 JavaBridge 线程写入，用 @Volatile 保证 UI 线程可见。
     */
    @Volatile private var backMobile = false
    @Volatile private var backConsumable = false

    // ── v1.6.32 预览实例治理 ─────────────────────────────────────────────
    // 预览页在同一个 WebView 里跨文档打开（点击 AI 给的 127.0.0.1:PORT 链接）。
    // 此前「返回引擎页」只是 loadUrl：预览页连同上一份引擎页都留在 back/forward
    // 历史栈里，文档实例（DOM + JS 堆）被会话历史持有 —— 反复进出预览，栈越积
    // 越深；系统返回键还可能 goBack 回到预览页，把已“离开”的页面拉起来继续跑。
    // 修复分三层（互为保险，全部幂等），详见 installPreviewJanitor / purgePreviewHistory。
    /** 当前 WebView 是否正显示预览页（updatePreviewChrome 维护，JavaBridge/UI 线程都会写） */
    @Volatile private var previewActive = false
    /** 已离开预览页，待回到引擎页 onPageFinished 后清除历史栈的挂起标记 */
    @Volatile private var previewReturnPending = false

    /** 上一次「再按一次退出」的时刻（elapsedRealtime，不受用户改系统时间影响）。 */
    private var lastBackAt = 0L

    /** 桌面模式：桌面 UA + 固定 1280px 视口 + 手势缩放（手机浏览器「电脑模式」等价物） */
    private var desktopMode = false
    private var defaultUa: String = ""

    /** 屏幕朝向偏好（v1.2.120）：portrait 锁竖 / landscape 锁横+桌面布局 / auto 跟随旋转 */
    private var orientationMode: OrientationMode = OrientationMode.PORTRAIT

    /** 页面缩放百分比（竖屏时应用；等价浏览器 Ctrl+/Ctrl-）。横屏桌面模式交给布局宽 meta，不叠加 */
    private var pageScale = DEFAULT_PAGE_SCALE

    // ── 视口重算（v1.5.1：自由窗口/分屏下字号变小修复）─────────────────────────

    /** 上一次应用视口时的 WebView 宽度（物理 px）。用于判断"真的变宽了"才重算 */
    private var lastViewportWidth = -1

    /**
     * 窗口尺寸变化后的延迟重应用任务（不 reload，只重写 viewport meta）。
     * 用字段持有是为了能 removeCallbacks —— 自由窗口拖拽时尺寸变化是高频事件。
     */
    private var viewportReapply: Runnable? = null

    /** 本页当前生效的语言代码（onResume 比对，变了就重建） */
    private var appliedLocale: String = ""

    private val uiScope = CoroutineScope(Dispatchers.Main)

    /**
     * 显式退出回调（v1.3.0 审查：实例感知注销）。
     *
     * Android 允许 recreate() 时**旧实例的 onDestroy 晚于新实例的 onCreate**；
     * 若 onDestroy 无条件置 null，会把新实例刚注册的回调清掉（退出后界面不关）。
     * 注销时比对实例引用即可避免。
     */
    private val exitCallback: () -> Unit = {
        Log.i("MainActivity", "explicit exit → finishing task to release WebView")
        finishAndRemoveTask()
    }

    /** 待回传的文件选择结果（onShowFileChooser → onActivityResult 之间持有；null 表示无进行中请求） */
    private var pendingFileCallback: android.webkit.ValueCallback<Array<Uri>>? = null

    /**
     * 挂起的网页媒体权限请求（issue #72）。
     *
     * `PermissionRequest` 是一次性对象：要么 grant、要么 deny，且只能各调一次。
     * 系统权限框是异步的，所以授权前必须先把它存起来，回到
     * `onRequestPermissionsResult` 再决定 grant/deny。
     * 新请求到达时先 deny 掉旧的，避免上一个请求永远悬着（页面会一直 pending）。
     */
    private var pendingWebPermission: android.webkit.PermissionRequest? = null

    // ---------- 启动遮罩（v1.6.19：状态收进 SplashController） ----------
    /** 全屏启动遮罩；null = 已淡出/未初始化 */
    private var splash: android.view.View? = null
    /** 遮罩的渲染 / 退出 / 动画全部由它负责 */
    private var splashController: app.dsh.mobile.ui.SplashController? = null
    /** 是否已经淡出（幂等） */
    private var splashDismissed = false
    private var lastReloadToken: Long? = null
    private var webReloadPending = false
    /** 「等插件就绪再重载」的超时任务（v1.6.21：不能让插件无限期挡住 WebUI）。 */
    private var pluginWaitJob: kotlinx.coroutines.Job? = null
    /** 主文档是否正在导航（看门狗据此不把「忙」误判成「死」）。 */
    @Volatile private var pageIsLoading = false
    /** 首帧后静态资源预热是否已调度（每进程一次，v1.6.25）。 */
    private var assetWarmDone = false

    /** WebUI 首帧已完成（onPageFinished 的真实页面），可安全淡入。 */
    private var webUiReady = false
    private var webUiCrossfaded = false
    private var splashMinMs = 700L
    /** 当前 Activity 已应用的主题；从设置页返回时检测变化并 recreate。 */
    private var appliedTheme: ThemeMode? = null
    /** 缺运行时只自动拉起一次文件选择器，避免 onResume 反复弹。 */
    private var runtimeImportPrompted = false
    /** 本地导入进行中：启动遮罩按“忙”处理，避免被看门狗收掉。 */
    private var runtimeImporting = false
    /** 预热历史条目是否已清理（幂等标记；clearHistory 只该做一次） */
    private var preheatHistoryDropped = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appliedTheme = ThemePref.get(this)
        // 用户主动打开了 App → 清除「显式退出」标记，恢复正常保活行为。
        // 与 EngineService.exitCompletely() 的置位成对：退出后不复活，重开即恢复。
        runCatching { DshApp.setUserExitedExplicitly(this, false) }
        // 首启保护：若用户尚未完成引导（如直接拉起 MainActivity），先跳 Onboarding
        if (!Privilege.isOnboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        // 记录本页生效的语言（onResume 里比对，设置页改过就重建本页）
        appliedLocale = LocaleHelper.get(this)

        setupSplash()
        statusBar = findViewById(R.id.statusBar)
        // 先赋值字段再配置：setupWebView 内部读取的是 this.webView，
        // 若写在 apply{} 里会在赋值完成前执行而触发 UninitializedPropertyAccessException。
        webView = findViewById<WebView>(R.id.webView)
        setupWebView()
        // v1.6.46：帧级观测（仅 debug）。挂在 window 上，回调跑在独立线程，
        // 只在我们显式 begin/end 的窗口（首屏、交叉溶解）里累数据。
        if (isDebuggable) {
            app.dsh.mobile.ui.FrameStats.attach(window)
            // v1.6.48：主线程卡顿归因（debug only）。卡顿发生时把主线程**正在跑的栈**
            // 写进 engine.log —— 「硬件富余却卡」必须有栈证据，否则优化只能靠猜。
            app.dsh.mobile.ui.MainThreadWatchdog.start { line -> logWebView(line) }
        }
        // v1.6.37：记住宿主容器。渲染进程被回收时要先把旧实例从树里摘掉，
        // 此时 parent 关系即将失效，之后新建的实例还得挂回同一个位置。
        webViewHost = webView.parent as? android.view.ViewGroup
        logWebView("webview startup: ${app.dsh.mobile.web.WebViewStartup.describe()}")
        // v1.6.0：立刻预热 WebView（详见 webViewPreheated 注释）。
        // 必须放在 setupWebView 之后（settings/client 已就绪）、且在
        // 任何 await 引擎状态的 UI 逻辑之前 —— 这样 Chromium 的 700ms
        // 初始化与引擎启动并行，而不是串行叠加。
        preheatWebView()
        // 读回用户保存的页面缩放与屏幕朝向偏好
        readUiPrefs()
        applyOrientation(orientationMode)
        // v1.2.120：冷启动也要把桌面渲染落到偏好 —— 旧实现只在 onResume 的「偏好变化」
        // 分支里 setDesktop，导致 landscape=true 冷启动时 UA/视口/缩放手势全是竖屏那套，
        // 直到用户在设置里动过一次或旋转设备才恢复。
        setDesktop(shouldUseDesktop())

        // ── v1.5.1 自由窗口/分屏视口跟随 ─────────────────────────────────────
        // 只改窗口尺寸、不触发 onConfigurationChanged 的路径（OEM 分屏自由缩放、insets 重排）
        // 走这里兜底。只关心**宽度**：软键盘/状态栏变化只改高度，会被
        // scheduleViewportReapply 内部的宽度比对直接跳过，不会误触发重排。
        webView.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                scheduleViewportReapply("layout ${right - left}px")
            }
        }

        // 热重启：用户显式动作，完整 stop→start 链路；urlLoaded 复位让 Healthy 后重载 3080。
        // restart() 自身立即返回（内部串行 + 先置「启动中」），无需再套线程；重复点击幂等。
        // Toast 给即时反馈：引擎优雅退出最长等 10s，期间状态栏可能来不及刷新（实测观感
        // 是「点了没反应」于是连点三下 → 触发并发 stop/start 踩踏）。
        findViewById<View>(R.id.btnRestart).setOnClickListener {
            urlLoaded = false
            (application as DshApp).supervisor.restart()
            Toast.makeText(this, getString(R.string.engine_restarting), Toast.LENGTH_SHORT).show()
        }
        // 隐藏工具栏：一键收起让网页全屏（点顶部小把手唤回）
        findViewById<View>(R.id.btnHide).setOnClickListener { toggleToolbar() }
        // 状态栏（通知栏）显隐（v1.6.50）：全屏/带状态栏之间即时切换。
        // **不重建 Activity、不碰引擎**：只改窗口级 insets 可见性 + 补一次 insets 派发，
        // 页面文档、滚动位置、正在生成的回复都保持原样。
        findViewById<View>(R.id.btnStatusBar).setOnClickListener { toggleStatusBar() }
        // Web 界面刷新（v1.6.33）：灵动岛内直接刷新当前 Web 视图（引擎页/预览页都适用）。
        // 旋转回执先启动（给即时反馈），刷新指令同帧发出 —— 两条线互不阻塞。
        findViewById<View>(R.id.btnRefresh).setOnClickListener { v ->
            v.animate().rotationBy(360f)
                .setDuration(450L)
                .setInterpolator(android.view.animation.LinearInterpolator())
                .withEndAction { v.rotation = 0f }
                .start()
            refreshCurrentWebView()
        }
        // 原生终端入口：跳转独立终端页（真 PTY，自带 bash）
        findViewById<View>(R.id.btnTerminal).setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
        }
        // 齿轮按钮：跳转独立设置页
        findViewById<View>(R.id.btnMore).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // v1.4.62：岛是圆角胶囊，选项的系统方角涟漪会从圆角外戳出来（按压残影）。
        // 整行按轮廓裁切兜底，配合子项的 22dp 圆角涟漪（bg_island_btn）。
        // v1.4.63：上一轮并发改动把它删了，残影会复发 —— 补回。
        findViewById<View>(R.id.statusBarRow).clipToOutline = true
        // 预览模式返回：一键从 AI 起的服务页回引擎主界面
        // v1.6.32：返回前先触发预览页自毁（停 WS/轮询/定时器，见 installPreviewJanitor）。
        // 页面级停止（这里）+ 导航销毁（跨文档导航固有）+ 历史栈清除（purgePreviewHistory）
        // 三层保证 —— 预览实例不再后台常驻，也不再有内存越积越多。
        findViewById<View>(R.id.btnBack).setOnClickListener {
            runCatching { webView.evaluateJavascript(PREVIEW_TEARDOWN_CALL, null) }
            runCatching { (application as DshApp).supervisor.webUrl() }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let { loadLocalUrl(it) }
        }
        // 工具栏收起/唤回：点横栏文字空白区收起（网页全屏）
        statusBar.setOnClickListener { toggleToolbar() }
        // 把手：可拖到屏幕边缘任意位置（避让遮挡）；移动距离小于阈值视为点击唤回工具栏
        setupHandleBar()

        // 语音输入（v1.2.65）：麦克风入口已移交无障碍服务（不再依赖本 Activity 存活）；
        // 这里只注册「把文字送进 WebUI」的能力，供服务侧识别完成后调用。
        VoiceBridge.registerSender { text -> sendVoiceText(text) }
        // v1.6.32：注册悬浮窗「Web 界面刷新」能力 —— WebView 只在本 Activity 里，
        // 由这里登记刷新动作；onDestroy 注销（见 onDestroy 与 WebRefreshBridge）。
        WebRefreshBridge.register { refreshCurrentWebView() }

        val app = application as DshApp
        // v1.2.187/188：用户从通知栏「退出」→ 结束界面并销毁 WebView，释放 ~380MB。
        // 用回调而不是"标记位"：Activity 不在时回调为 null，不会留下残留状态
        // （标记方案曾导致"退出后重开 App 秒退"，审查 P0）。
        app.supervisor.onExplicitExit = exitCallback
        uiScope.launch {
            app.supervisor.state.collectLatest {
                val token = (application as DshApp).supervisor.webReloadToken
                if (lastReloadToken == null) {
                    lastReloadToken = token
                } else if (lastReloadToken != token) {
                    lastReloadToken = token
                    // 引擎重启后旧 WebUI 的 typert 连接已失效：必须重载页面。
                    urlLoaded = false
                    webReloadPending = true
                    // v1.6.21：插件就绪只给一个很短的宽限窗，绝不无限期挂住 WebUI。
                    // 旧行为：等 PluginBootBus 离开 HOLDING/LOADING 才重载，而那个状态
                    // 要等 awaitPluginHealth 走完 —— 它最长 **20 秒**。后果：引擎重启
                    // （崩溃自愈 / 改配置 / 断电恢复）之后，页面最长 20 秒停在死页面上，
                    // 用户看到的就是「连接异常，刷新重试」，不手动刷新回不来。
                    // 现在：最多等 [PLUGIN_WAIT_MAX_MS]，到点照常重载，插件随后由
                    // dsh-hmr 流式补上 —— 这本来就是快速启动的设计意图。
                    pluginWaitJob?.cancel()
                    pluginWaitJob = uiScope.launch {
                        kotlinx.coroutines.delay(PLUGIN_WAIT_MAX_MS)
                        if (webReloadPending) {
                            logWebView(
                                "plugin wait exceeded ${PLUGIN_WAIT_MAX_MS}ms — reloading WebUI anyway " +
                                    "(plugins continue streaming via hmr)",
                            )
                            webReloadPending = false
                            urlLoaded = false
                            render((application as DshApp).supervisor.state.value)
                        }
                    }
                }
                render(it)
            }
        }
        // 会话被删除后同步 WebUI（v1.6.28）：热删除不停引擎，所以不会再有
        // 「引擎重启 → 重载页面」这个副作用，这里补一次**软刷新**（重载同一 URL，
        // 引擎进程与对话上下文都还在）。
        // 只在一次删除完成后触发一次：删除流程是用户主动动作，界面上此时正停在
        // 设置页，重载发生在它背后，用户返回时看到的就是最新列表。
        uiScope.launch {
            ChatChangeBus.changes.collect { removed ->
                softReloadWebUi("chats-removed:$removed")
            }
        }
        // 插件后台加载状态：首屏已显示后，用非阻塞提示反馈进度/异常。
        uiScope.launch {
            PluginBootBus.prefetch.collect { p ->
                // v1.6.24：本地插件预读进度（快启场景才非空）。
                // 只在有值时改写文案：初值是 null，若无条件处理会把启动阶段
                // BootTrace 驱动的阶段文案盖掉。
                if (p != null && !splashDismissed) {
                    splashController?.setFallbackStatusText(
                        getString(R.string.plugin_boot_prefetch, p.done, p.total),
                    )
                }
            }
        }
        uiScope.launch {
            PluginBootBus.state.collect {
                when (it) {
                    PluginBootBus.State.LOADING ->
                        setSplashStatus(R.string.plugin_boot_loading)
                    PluginBootBus.State.DONE ->
                        Toast.makeText(
                            this@MainActivity, R.string.plugin_boot_done, Toast.LENGTH_SHORT,
                        ).show()
                    PluginBootBus.State.PARTIAL ->
                        Toast.makeText(
                            this@MainActivity, R.string.plugin_boot_partial, Toast.LENGTH_SHORT,
                        ).show()
                    PluginBootBus.State.FAILED ->
                        Toast.makeText(
                            this@MainActivity, R.string.plugin_boot_failed, Toast.LENGTH_LONG,
                        ).show()
                    else -> Unit
                }
            }
        }
        uiScope.launch {
            PluginBootBus.state.collectLatest { st ->
                if (webReloadPending &&
                    st != PluginBootBus.State.HOLDING &&
                    st != PluginBootBus.State.LOADING
                ) {
                    webReloadPending = false
                    pluginWaitJob?.cancel()
                    urlLoaded = false
                    render((application as DshApp).supervisor.state.value)
                }
            }
        }
        uiScope.launch {
            app.supervisor.installProgress.collectLatest { renderProgress(it) }
        }
        // v1.4.8：启动过程可视化。状态流驱动的是"阶段切换"，秒数与明细由秒表刷新
        // （200ms 一次，只更新文本，不触发重排），两者互补不重复。
        uiScope.launch {
            BootTrace.snapshot.collectLatest { snap ->
                if (!splashDismissed) renderSplashTrace(snap)
            }
        }
    }

    

    /**
     * 🔴 v1.4.56 修：点「已下载」通知只回主窗口、不弹安装 —— 根因在这里缺
     * `onNewIntent`。MainActivity 是 `singleTask`（AndroidManifest），下载完成时
     * 进程几乎必然活着，系统对已存在实例走 `onNewIntent()`（默认空实现），
     * 通知 Intent 里的 `ACTION_OPEN_INSTALL` 被直接丢弃；`onResume` 读到的
     * `intent` 还是旧实例的（action=null），判断永远不成立 → 只回到主窗口。
     * 补上 `setIntent` 后，随后必到的 `onResume` 就能吃到新 action。
     * （进程被杀后点通知冷启动原本就走 onCreate→onResume，不受影响。）
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    

    /** 同步贴纸的可见性：胶囊收起时贴纸也要跟着消失。 */
    /**
     * 贴纸当前应有的可见性。
     *
     * 必须记下来：[applyExpandedStatusBar] 会在 onResume 跑，而那时胶囊可能正
     * 处于收起态。新建的贴纸 View 若不按这个值初始化，收起态下就会有几张贴纸
     * 凭空浮在屏幕顶部。
     */
    private var stickersWantedVisible = true

    private fun setStickersVisible(visible: Boolean) {
        stickersWantedVisible = visible
        val overlay = findViewById<ViewGroup>(R.id.statusBarOverlay) ?: return
        for (i in 0 until overlay.childCount) {
            val c = overlay.getChildAt(i)
            if (c.tag == stickerTag) c.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    /** 贴纸 View 的标记：重建时据此清理旧贴纸（类内字段，避免为此新建 companion）。 */
    private val stickerTag = "dsh_sticker"

    override fun onResume() {
        super.onResume()
        // NFC 读卡与屏幕录制都需要 Activity：把自身登记给两个 relay，
        // 让「贴标签」和「录屏授权」能真正接通（桥本身拿不到 Activity）。
        app.dsh.mobile.engine.cap.NfcForegroundRelay.attach(this)
        app.dsh.mobile.engine.cap.ScreenRecordRelay.attach(this)
        // 外观 → 收起态状态栏改完返回本页时，立即按新图片/形状/尺寸重绘，
        // 不必等用户手动收起再展开才看到效果
        runCatching { findViewById<View>(R.id.handleBar)?.let { applyIslandDotImage(it) } }
        // 展开态状态栏：自定义背景图 + 贴纸，同样在返回本页时立即套用
        runCatching { applyExpandedStatusBar() }
        val currentTheme = ThemePref.get(this)
        if (appliedTheme != null && appliedTheme != currentTheme) {
            appliedTheme = currentTheme
            recreate()
            return
        }
        // 🔴 v1.2.91：恢复 WebView 的 JS 定时器。
        // 主人实测「回到后台时通过语音发送消息，消息发出去了但模型不响应，
        // 切回前台才开始响应」—— 根因就是这里缺 webView.onResume()：
        // Android 在 Activity 进入后台时会**冻结 WebView 的 JS 定时器**
        // （前端靠定时器/轮询驱动流式渲染），所以后台期间 AI 的回复渲染不出来。
        //
        // v1.6.37：渲染进程已被 ColorOS 回收时，旧实例已 destroy —— 这里先做静默恢复，
        // 再操作 WebView。不在后台重建（那就是重载死循环），只在回到前台这一时机做。
        if (pendingRecovery) {
            pendingRecovery = false
            recoverWebViewSilently("foreground after renderer reclaim")
        }
        liveWebView()?.let { wv ->
            runCatching { wv.onResume() }
            runCatching { wv.resumeTimers() }
        }
        // Web 内核版本门禁（v1.2.100，酷安用户反馈引出的需求）：
        // WebView < Chrome 85 时引擎前端必白屏（??= 等解析期语法 polyfill 补不了），
        // 与其让用户看「进程异常退出/白屏」无限循环，不如先检测、给出明确升级指引，
        // 由用户决定要不要继续。见 checkWebViewGate()。
        if (AppGate.webViewState != AppGate.State.OK &&
            AppGate.webViewState != AppGate.State.OVERRIDDEN && !checkWebViewGate()
        ) {
            return   // 门禁未过：不启动引擎（引擎起了前端也渲染不了，纯粹耗电）
        }
        // 前台进入即拉起前台服务；服务存在则幂等。
        // v1.4.20：noruntime 包首启先确认下载运行时（约 130MB），不在用户不知情时
        // 直接走蜂窝下载；确认后仍由监督器的 Installing 流程显示真实进度。
        if (RuntimeInstaller(this).needsRuntimeDownload()) {
            if (!runtimeImportPrompted) {
                runtimeImportPrompted = true
                runCatching { RuntimeImporter.pickZip(this) }.onFailure {
                    Toast.makeText(
                        this,
                        getString(R.string.runtime_import_failed, it.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        } else {
            EngineService.start(this)
            // 引擎层热更新：6 小时节流，后台静默检查，失败不打扰启动。
            EngineUpdater.autoCheckInBackground(this)
        }
        // 用户已开启桌宠时恢复；未授权/未开启时这里是零开销。
        runCatching { PetOverlayService.restoreIfEnabled(this) }
        // 系统悬浮窗状态显示（v1.6.35，需求 4）：
        //  · 开关开着且有权限 → 自动拉起服务（授权/系统回收后的自动恢复）；
        //  · 开关开着但权限被关 → 应用内给一次明确引导（不反复骚扰）。
        runCatching {
            val enabled = app.dsh.mobile.status.FloatingPrefs.enabled(this)
            val canDraw = android.provider.Settings.canDrawOverlays(this)
            if (enabled && canDraw) {
                app.dsh.mobile.status.FloatingStatusService.restoreIfEnabled(this)
            } else if (enabled && !canDraw &&
                !app.dsh.mobile.status.FloatingPrefs.guideShown(this)
            ) {
                app.dsh.mobile.status.FloatingPrefs.setGuideShown(this, true)
                showFloatPermissionGuideIfNeeded()
            }
        }
        // 页面卡死看门狗（v1.2.100）：前台时才检测（后台由系统节流，探针会误判）
        startHangWatchdog()
        // 从设置页返回：语言若被改过，原地重建本页（设置页只重建了它自己，
        // 主界面返回时也得跟上，否则会出现「设置页已是新语言、主界面还是旧的」）
        val langNow = LocaleHelper.get(this)
        if (langNow != appliedLocale) {
            appliedLocale = langNow
            recreate()
            return
        }
        // 从设置页返回：重新读取朝向/缩放偏好，若被改则同步并重载
        val oldScale = pageScale
        val oldMode = orientationMode
        readUiPrefs()
        applyEdgeToEdge()
        applyIslandInsets()
        // v1.6.50：从设置页回来时状态栏开关可能被改过 —— 按钮图标与可见性跟上。
        // applyEdgeToEdge 内部也会同步一次；这里再调用是幂等的零成本（只在值变了才碰 View），
        // 覆盖"设置页改了但本页窗口没重设"的窄边界。
        updateStatusBarToggle()
        if (oldMode != orientationMode) {
            setDesktop(shouldUseDesktop())
            applyOrientation(orientationMode)
            // v1.6.42：模式可能刚变（桌面 ↔ 移动），横屏让位边距与视口都要重算。
            // 不能等 insets 回调 —— 设置页返回时窗口尺寸没变，它不会重跑。
            applyUserAgentOverride(desktopMode)
            applyViewport(liveWebView() ?: webView)
            findViewById<View>(android.R.id.content)?.requestApplyInsets()
            installFullBleed(liveWebView() ?: webView, "settingsReturn")
        } else if (oldScale != pageScale) {
            // v1.6.42：只改了缩放 —— 重写视口 meta 就够（视口计算本来就吃 pageScale），
            // 不需要整页重载。旧实现无条件 reload，改一次缩放就白屏一次。
            applyViewport(liveWebView() ?: webView)
        }
    }

    /**
     * v1.2.91：**不**暂停 WebView。
     *
     * 默认实现（或主动调 `webView.onPause()`）会冻结 JS 定时器 —— 那正是
     * 「后台时 AI 不响应、切回前台才响应」的根因。
     * 本项目的前端需要**在后台继续驱动**（AI 回复的流式渲染、会话状态更新），
     * 而进程本身已由前台服务 + 无障碍服务保活，所以这里保持 WebView 活跃。
     *
     * ⚠️ 代价：后台时 WebView 仍会消耗少量 CPU（前端本身的轮询）。
     * 这是「后台也要能收到 AI 回复」这个需求的必要成本。
     */
    override fun onPause() {
        // 后台停看门狗：系统会节流后台 WebView，探针超时会造成误判并反复重建页面
        stopHangWatchdog()
        // 刻意不调用 super.onPause() 里的 WebView 暂停逻辑；
        // 同时显式 resumeTimers，确保切后台瞬间定时器仍在跑。
        // v1.6.37：已退役实例不可再碰。
        liveWebView()?.let { runCatching { it.resumeTimers() } }
        super.onPause()
    }

    /*
     * v1.6.20 调查记录：曾在此实现 onTrimMemory → WebView 减压，最终**不上**。
     * 编译期核实（android.jar 36 的公开符号）：
     *   · WebView.trimMemory(int) 不在公开 SDK 里（AOSP 隐藏 API，非系统应用调用会被
     *     隐藏接口名单拦截）——不可用；
     *   · WebView.freeMemory() 与 ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL /
     *     TRIM_MEMORY_COMPLETE 均已标记 deprecated（编译告警实测），新内核不保证投递；
     *   · freeMemory 契约是「宿主一段时间不用这个 View」，而本 App 刻意在后台继续跑
     *     流式渲染（见 onPause），拿它换内存就是拿功能换内存。
     * 结论：WebView 常驻的内存治理应走「退出即 destroy（已有，释放 ~380MB）」这条确定
     * 路径；如要做内核级减压，需要能在真机上抓 dumpsys meminfo + gfxinfo 验证后再定，
     * 不在无设备条件下引入 deprecated API。
     */

    /** 读取用户持久化的屏幕朝向与缩放偏好（设置页与首次引导共用同一组 prefs） */
    private fun readUiPrefs() {
        val p = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        pageScale = p.getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)
        orientationMode = OrientationPrefs.get(this)
    }

    /**
     * v1.2.120：当前是否应该走桌面渲染。
     * landscape = 恒桌面；portrait = 恒移动；auto = 看**实际**朝向
     * （物理横屏 → 桌面布局，竖屏 → 移动布局），朝向取自屏幕几何（见 [screenIsLandscape]，
     * 不用会滞后的 `Configuration.orientation`），窗口尺寸一变就重新求值。
     *
     * ⚠️ v1.5.1（小窗字号变小）：窗口被压缩且窄于桌面断点时，一律给移动渲染。
     * 原因：桌面视口固定 `width=1280`，468dp 宽的小窗里内容被整体缩到 0.366 倍，
     * 字号只剩四成。全屏形态完全不受影响（[isWindowed] 为假时走原逻辑）。
     */
    private fun shouldUseDesktop(): Boolean {
        if (isWindowed() && windowDpWidth() < DESKTOP_MIN_WIDTH_DP) return false
        return when (orientationMode) {
            OrientationMode.LANDSCAPE -> true
            OrientationMode.PORTRAIT -> false
            OrientationMode.AUTO -> screenIsLandscape()
        }
    }

    /**
     * AUTO 模式下"屏幕当前是否横屏"。
     *
     * ## 为什么不用 `resources.configuration.orientation`
     * v1.5.1 实测（MuMu 平板，`wm size` 横→竖→横）：窗口重新布局时 WebView 已经是
     * 3840px 宽，而 `resources.configuration` 还停在上一朝向（`screenWidthDp=592`），
     * 于是 AUTO 按**旧**朝向算，模式切换被跳过；等配置真正更新过来时已经没有事件
     * 再触发一次求值 —— 页面就永久停在错误的渲染模式（1137dp 横屏上显示移动单栏）。
     *
     * ## 用屏幕几何
     * 屏幕尺寸用 `Display.getRealMetrics`（**整块屏幕**，不是窗口），显示尺寸是窗口
     * resize 的**原因**，所以它一定先于布局回调更新，不存在上面那个时间差；
     * 读的是物理屏尺寸，也不受软键盘改窗口高度影响，
     * 不会出现"弹出键盘 → 窗口变扁 → 误判成横屏"的抖动。
     *
     * 小窗/分屏"窗口窄但屏幕横"的情况不靠这里兜 —— 由 [shouldUseDesktop] 里的
     * 窗口宽度断点（[isWindowed]）先行拦住。
     */
    private fun screenIsLandscape(): Boolean {
        val real = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        val ok = runCatching { windowManager.defaultDisplay.getRealMetrics(real) }.isSuccess
        if (ok && real.widthPixels > 0 && real.heightPixels > 0) {
            return real.widthPixels > real.heightPixels
        }
        // 取不到屏幕尺寸时才退回 Configuration（冷启动极早期才会走到）
        return resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
    }

    /**
     * 当前是否「横屏桌面渲染」（v1.6.42）—— 横屏沉浸让位与自适应布局的共同开关。
     *
     * 判据用 [desktopMode]（**已应用**的渲染模式）而不是再算一次 [shouldUseDesktop]：
     * 两者在模式切换的过渡帧里会不一致，用已应用值才能保证「边距与视口是同一套」。
     * 朝向仍取屏幕几何（[screenIsLandscape]），不用会滞后的 `Configuration.orientation`。
     */
    private fun isLandscapeDesktop(): Boolean = desktopMode && screenIsLandscape()

    /** 当前窗口宽度（dp）。已布局时用 WebView 实测值，否则退回 Configuration（多窗口下即窗口宽） */
    private fun windowDpWidth(): Float {
        val px = if (::webView.isInitialized && webView.width > 0) {
            webView.width
        } else {
            (resources.configuration.screenWidthDp * resources.displayMetrics.density).toInt()
        }
        return px / resources.displayMetrics.density
    }

    /**
     * 当前是否"窗口被压缩"（小窗 / 分屏 / OEM 自由窗口），即窗口明显窄于整块屏幕。
     *
     * ## 为什么不用系统现成的判据
     * vivo 自由窗口实测：`isInMultiWindowMode()` 返回 **false**，`Configuration.orientation`
     * 报 **land**（屏幕朝向），而窗口实际是 467×826dp 的竖长条 —— 两个判据都会把
     * 小窗当成"全屏横屏"，于是继续走桌面渲染。唯一可靠的是**几何**：窗口比屏幕窄。
     *
     * 屏幕尺寸必须用 [Display.getRealMetrics]（整块屏幕，不受窗口影响）；
     * `resources.displayMetrics` 在多窗口下可能已经被裁成窗口尺寸，比不出差值。
     */
    private fun isWindowed(): Boolean {
        val real = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        runCatching { windowManager.defaultDisplay.getRealMetrics(real) }
            .onFailure { return false }
        if (real.widthPixels <= 0 || real.density <= 0f) return false
        val screenDp = real.widthPixels / real.density
        return windowDpWidth() < screenDp * 0.92f
    }

    private fun setupWebView() {
        // v1.6.25：静态资源缓存。attach() 会比对 stamp（schema|versionCode|运行时版本），
        // 不一致就清空重建，并在后台做一次容量修剪 —— 全在后台线程，不占启动主线程。
        runCatching { WebAssetCache.attach(this) }
            .onFailure { logWebView("webcache attach failed: ${it.message}") }
        // v1.6.36：热状态 → 渲染降级 / 预热补做。
        // 一处订阅，两个作用：
        //  · 热受限时注入轻量样式（卸掉模糊/阴影/合成层）；降温后撤销；
        //  · 降温后把被推迟的预热接上（任务不丢，只是晚点做）。
        uiScope.launch {
            if (thermalSubscribed) return@launch
            thermalSubscribed = true
            app.dsh.mobile.ui.ThermalGuard.level.collect { lv ->
                // v1.6.45：热状态只是**一个输入**。用户选的渲染档位是更强的一级意愿
                //（SMOOTH = 别等发烫现在就降；QUALITY = 宁可发烫也别降），
                // 两者在 ThermalRenderGuard.resolve 里统一合成，避免两处各算一遍来回抖。
                val throttle = app.dsh.mobile.web.ThermalRenderGuard.resolve(this@MainActivity, lv.throttled)
                runCatching {
                    app.dsh.mobile.web.ThermalRenderGuard.sync(liveWebView(), throttle)
                }.onFailure { logWebView("thermal render sync failed: ${it.message}") }
                if (!lv.throttled) {
                    WebAssetCache.retryDeferredIfCool()
                }
                logWebView("thermal: ${app.dsh.mobile.ui.ThermalGuard.describe()}")
            }
        }
        // v1.6.45：把实例登记给 ThermalRenderGuard —— 设置页改「渲染档位」时
        // 要能立刻对**正在显示**的这页生效（设置页自己没有 WebView 引用）。
        runCatching { app.dsh.mobile.web.ThermalRenderGuard.bindLive(webView) }
        // v1.6.46：渲染调优的 document-start 挂载。
        // **必须在任何 loadUrl 之前**（setupWebView 比 preheatWebView 早）：
        // 合帧与合成层提示只有在页面自己的脚本之前就位才有意义，晚一步就只
        // 能覆盖"之后"注册的监听器（见 EventCoalesce / RenderTune 类注释）。
        // 内核不支持 document-start 时会返回 false，onPageFinished 的兼容路径接手。
        runCatching {
            app.dsh.mobile.web.RenderTune.attach(webView, assets)
            app.dsh.mobile.web.EventCoalesce.attach(webView)
        }.onFailure { logWebView("render-tune attach failed: ${it.message}") }
        // 页面首帧绘制前用主题底色填充，避免交叉溶解时透出白屏/黑屏。
        runCatching { webView.setBackgroundColor(getColor(R.color.bg)) }
        // debug 包打开 WebView 远程调试（chrome://inspect / CDP）：排障时能直接看
        // 页面控制台、读 history 状态与返回栈条数；release 包保持关闭。
        // 用 FLAG_DEBUGGABLE 而不是 BuildConfig：AGP 8 默认不生成 BuildConfig。
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) WebView.setWebContentsDebuggingEnabled(true)
        // 本地渲染优先：提高 WebView 渲染进程优先级，减少后台回收/降频导致的卡顿。
        runCatching {
            webView.setRendererPriorityPolicy(
                android.webkit.WebView.RENDERER_PRIORITY_IMPORTANT, false,
            )
        }
        defaultUa = webView.settings.userAgentString
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false       // 关闭文件域，收窄 WebView 攻击面
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            // issue #72：语音插件的 getUserMedia 在部分 WebView 版本上要等用户手势
            // 才会 resolve，而它们的录制按钮本身就是那次手势。不关掉这个限制，
            // 权限已经 grant 了仍可能卡在 NotAllowedError。只影响自动播放策略，
            // 不涉及媒体采集权限（那个由 onPermissionRequest 管）。
            mediaPlaybackRequiresUserGesture = false
            // 竖屏页面缩小靠 viewport meta 改写（同桌面模式机制），useWideViewPort 必须开。
            useWideViewPort = true
            loadWithOverviewMode = true
            applyZoomControls(desktopMode)
        }
        // v1.4.20（issue #43）：document focus 是 navigator.clipboard.writeText 的
        // 硬条件——WebView 失焦时页面复制被 NotAllowedError 拒绝。让 WebView 可聚焦
        // 并在布局后拿到焦点，配合注入的剪贴板降级链把复制失败率压到最低。
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()
        // ── WebView 诊断（v1.2.49）：用户报「用不了」时能拿到确凿信息 ────────────
        // 背景：华为 Mate40 报 `AbortSignal.any is not a function`、另有 Android 16 用户
        // 报失败，但 App 此前既不捕获 WebView 错误也不转发 console，排查只能靠猜。
        // 这里把 JS 控制台消息、资源加载错误、以及 polyfill/WebView 版本自检全部落进
        // engine.log（用户可在设置里导出），日志里带 [webview] 前缀便于定位。
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                msg ?: return false
                val text = msg.message() ?: return false
                // 只记有价值的信息：错误/警告，或我们自己的自检标记
                val important = msg.messageLevel() >= android.webkit.ConsoleMessage.MessageLevel.WARNING ||
                    text.contains("[dsh-android]")
                if (important) {
                    logWebView("console/${msg.messageLevel()} ${msg.sourceId()}:${msg.lineNumber()} $text")
                }
                return false
            }

            /**
             * 网页媒体权限（getUserMedia）—— issue #72。
             *
             * ## 为什么必须有
             * Android WebView 的**默认实现直接拒绝**所有网页媒体请求，于是
             * `navigator.mediaDevices.getUserMedia({audio:true})` 恒返回
             * `DOMException: NotAllowedError / "Permission denied"`。
             * 第三方语音插件（`@goodandready/dsh-voice`、引擎内置
             * `dsh-experimental-client-ui-voice-input`）全靠这个 API，全部失效。
             *
             * 我们自己的语音输入走原生 `AsrManager`（不经 WebView），所以一直正常 ——
             * 这也是为什么这个缺口长期没被发现。
             *
             * ## 流程
             * ① 只处理 `RESOURCE_AUDIO_CAPTURE`，其余资源（视频/摄像头）原样拒绝；
             * ② 系统已有 `RECORD_AUDIO` → 直接 grant；
             * ③ 没有 → 弹系统权限框，结果在 `onRequestPermissionsResult` 里回到
             *    `pendingWebPermission` 再 grant/deny。
             *
             * ⚠️ 必须判空并只 grant 一次：`PermissionRequest` 是一次性的，
             * 重复调用 grant/deny 会抛 IllegalStateException（WebView 133 上实测）。
             */
            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                request ?: return
                // ⚠️ v1.4.46（安全审查 M2/L9）：旧实现只看「App 有没有 RECORD_AUDIO」，
                // 不校验请求页面来源。配合预览功能可打开任意回环端口，被提示注入的
                // AI 只要让 WebView 导航到某个恶意回环页面，那个页面就能静默拿到
                // 麦克风。这里只对本机引擎/回环放行，其他来源直接拒绝。
                val host = runCatching { java.net.URI(request.origin.toString()).host }.getOrNull()
                if (host != "127.0.0.1" && host != "localhost") {
                    android.util.Log.w("WebPerm", "deny media for non-loopback origin: ${request.origin}")
                    request.deny()
                    return
                }
                val wantsAudio = request.resources?.contains(
                    android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE,
                ) == true
                if (!wantsAudio) {
                    // 摄像头/视频采集目前没有使用场景，保守拒绝（不扩大攻击面）
                    request.deny()
                    return
                }
                val granted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) {
                    grantAudio(request)
                    return
                }
                // 挂起这次请求，等系统权限框回来
                pendingWebPermission?.deny()
                pendingWebPermission = request
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_WEB_AUDIO)
            }

            /**
             * 网页 <input type="file"> / WebUI 附件按钮 → 系统文件选择器。
             *
             * ⚠️ 2026-10-05（Issue #5）：此前**未实现**此回调，WebView 收到文件选择请求时
             * 无人应答，表现为「上传什么都失败、也弹不出选择窗口」（WebUI 附件功能整体不可用）。
             * Android WebView 不会自行弹选择器，必须由宿主实现本方法并通过
             * filePathCallback.onReceiveValue() 回传结果 —— 不回传则页面永远等待。
             *
             * 权限：用 ACTION_GET_CONTENT（SAF）而非直接读路径，**无需**存储权限，
             * 也不受 allowFileAccess=false 影响（我们刻意关着它收窄攻击面）。
             */
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                params: android.webkit.WebChromeClient.FileChooserParams?,
            ): Boolean {
                filePathCallback ?: return false
                // 上一个请求未结束（用户连点/页面切换）→ 先回传空值释放，否则 WebView 卡死
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                // v1.4.27（issue #54）：页面只要图片时走系统**照片选择器**——
                // 缩略图网格、原生多选、无需存储权限，比文件管理器翻目录快得多。
                // 判据是 accept 类型（页面 <input type=file accept="image/…">）。
                val intent = if (wantsOnlyImages(params)) {
                    buildPhotoPickerIntent(params)
                } else {
                    runCatching { params?.createIntent() }.getOrNull()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                }
                return runCatching {
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQ_FILE_CHOOSER)
                    true
                }.getOrElse {
                    logWebView("file chooser failed to launch: ${it.message}")
                    pendingFileCallback = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }
        }
        // ── 下载支持（v1.4.17，issue #37）──────────────────────────────────
        // 用户报告：文件分享插件的「下载」按钮点了没反应。根因是宿主从未实现
        // DownloadListener——Android WebView 收到下载请求时若无监听器**直接静默丢弃**。
        // 且 App 只声明了 MANAGE_EXTERNAL_STORAGE（API 30 权限），Android 10 的设置里
        // 既没有「所有文件访问」也没有普通存储项，用户无从授权自救。
        //
        // 修复分两条路径（详见 WebViewDownloader）：
        //   · http(s) 直链 → 系统 DownloadManager 代写，任何 API 级别都不需要存储权限；
        //     必须把 WebView 会话的 Cookie 带上，否则下载到的是登录页 HTML。
        //   · blob:（插件的「打包下载」）→ DownloadListener 根本收不到，由注入页面的
        //     JS 桥（onPageFinished 里注入）把 blob 转 base64 交给 DshAndroidDownloader。
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:")) {
                // 理论上桥会拦掉 blob 点击；走到这里说明页面绕过了桥（如 location 赋值）
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.download_blob_unsupported),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                logWebView("download: blob URL reached listener without bridge")
                return@setDownloadListener
            }
            val err = WebViewDownloader.enqueueHttpDownload(
                this, url, userAgent, contentDisposition, mimeType,
            )
            runOnUiThread {
                Toast.makeText(
                    this,
                    getString(
                        if (err == null) R.string.download_started else R.string.download_failed,
                        err ?: "",
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        webView.addJavascriptInterface(
            object : Any() {
                /** JS 桥回调：blob 转 base64 后落盘到公共 Download/。 */
                @android.webkit.JavascriptInterface
                fun saveBase64(mimeType: String?, base64: String?, fileName: String?): String {
                    val data = base64 ?: return "no data"
                    // 落盘在后台线程做；JS 侧同步拿回执（少量阻塞可接受，blob 通常 < 数十 MB）
                    return runCatching {
                        WebViewDownloader.saveBase64(
                            applicationContext,
                            mimeType ?: "",
                            data,
                            fileName ?: "",
                        )
                    }.getOrElse { e -> e.message ?: "save failed" }
                }
            },
            WebViewDownloader.BRIDGE_NAME,
        )
        // v1.4.20（issue #43）：剪贴板兜底桥。navigator.clipboard.writeText 在
        // WebView 里常因焦点/激活限制抛 NotAllowedError（页面又吞掉异常），
        // 桥让页面侧降级链有第二条路。调用来自 JavaBridge 线程，前台限制不适用。
        webView.addJavascriptInterface(
            object : Any() {
                @android.webkit.JavascriptInterface
                fun copyText(text: String?): Boolean {
                    val t = text ?: return false
                    return runCatching {
                        val cm = getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("text", t))
                        true
                    }.getOrDefault(false)
                }
            },
            "DshAndroidClipboard",
        )
        // v1.5.1（平板适配）：系统打开/分享桥。dsh-solution-explorer 的
        // 「用系统程序打开」经此把工作区文件交给 ACTION_VIEW 默认程序或
        // ACTION_SEND 选择器；失败返回 false，插件侧自动落回下载兜底。
        webView.addJavascriptInterface(
            OpenBridge(applicationContext),
            "DshAndroidOpen",
        )
        // 返回栈桥（手机形态布局插件专用）：页面把「当前能不能安全回退」上报
        // 到这里。没有这个桥时 onBackPressed 只能猜（canGoBack），一旦 WebView
        // 历史里混入跨文档条目，返回就变成整页重载。
        //
        // 调用来自 JavaBridge 线程 → 字段声明成 @Volatile；payload 解析失败
        // 就保持上次状态（宁可多一次提示，也不能退到未知条目上）。
        webView.addJavascriptInterface(
            object : Any() {
                @android.webkit.JavascriptInterface
                fun setState(payload: String?) {
                    if (payload.isNullOrBlank()) return
                    runCatching { org.json.JSONObject(payload) }.getOrNull()?.let { json ->
                        backMobile = json.optBoolean("mobile", false)
                        backConsumable = json.optBoolean("consumable", false)
                    }
                }
            },
            "DshAndroidBack",
        )
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?,
            ) {
                // 主文档加载失败才是致命的；子资源失败常见（favicon 等），降级记录
                val url = request?.url?.toString() ?: "?"
                val desc = error?.description?.toString() ?: "?"
                val fatal = request?.isForMainFrame == true
                logWebView("${if (fatal) "LOAD-FAIL" else "subresource"} $url — $desc")
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                // 仅允许回环导航；外部链接交给系统浏览器
                if (uri.host == "127.0.0.1" || uri.host == "localhost") return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }

            /**
             * 静态资源本地缓存（v1.6.25）。
             *
             * 命中就吐本地字节（省掉 Node + proot + 回环 HTTP 一整条链），
             * 未命中返回 null → WebView 走网络，**行为与改动前完全一致**。
             *
             * 只拦 CSS / 字体 / 图片，**绝不拦 JS** —— 拦截会让资源脱离
             * Chromium 的 code cache（V8 字节码缓存），那是 WebUI 里唯一真正
             * 意义上的“预编译”缓存，丢了它反而每次都重新 parse + compile。
             * 完整理由见 [WebAssetCache] 类注释。
             *
             * 本回调在 WebView 的后台 IO 线程上执行，不阻塞 UI 线程。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? = WebAssetCache.serve(request)

            /**
             * 渲染进程崩溃自愈（v1.2.100，酷安 issue #8）。
             *
             * ## 为什么必须有
             * issue 反馈：「会话内容卡住，计时器还在计数，模型仍在运行，要重启应用」——
             * 典型症状是**前端页面停止更新但后端一切正常**。WebView 的渲染进程
             * （独立沙箱进程）可能因内存压力被系统回收或自身崩溃，此时：
             *  · 不实现本回调 → Android 默认**直接杀掉宿主 App**（整个应用消失），或
             *  · 页面残留但不再刷新（用户看到「卡住」）
             * 两种都不是我们想要的：引擎由前台服务持有，本该继续跑。
             *
             * ## 做法
             * 返回 true 表示「我自己处理」，阻止系统杀 App；随后**重建 WebView 并重载**，
             * 用户视角是页面自动恢复（引擎没重启，会话与进行中的任务都在）。
             */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?,
            ): Boolean {
                logWebView(
                    "render process gone: crashed=${detail?.didCrash()} " +
                        "priority=${detail?.rendererPriorityAtExit()} — retiring instance",
                )
                // v1.6.37（ColorOS 后台回收死循环修复）：
                // ① 就地把旧实例退役（移除 + destroy），之后**一行代码都不再碰它**；
                // ② 只置 pendingRecovery，不重载、不弹窗 —— 重载一律等回到前台；
                // ③ 返回 true 告诉系统「已处理」，阻止它杀 App。
                retireDeadWebView()
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // 主文档开始导航：看门狗在导航期间不解探（见 hangTick）。
                if (!isPreheatNavigation(url)) {
                    pageIsLoading = true
                    // v1.6.50：新文档 = 页面里的样式表与覆盖全丢，顶部接管状态一并失效。
                    // 先退回"原生让位"（保守），等 installFullBleed 的完成值再判定 ——
                    // 否则会用上一份文档的结论去排这一份（标题被状态栏压住一帧）。
                    pageOwnsTopInset = false
                    // v1.6.46：记录"导航开始 → 首帧提交"这一段（首屏是否顺畅的唯一硬口径）
                    app.dsh.mobile.ui.FrameStats.begin("first-paint")
                }
            }

            /**
             * WebUI 已提交可见首帧（v1.6.21）。
             *
             * 这是「页面真的画出来了」最早的一个信号（早于 onPageFinished，
             * 后者还要等子资源），所以拿它当插件 HMR 释放的扳机最准 —— 既不会
             * 在首屏绘制时抢 CPU/IO，也不会在快机上白等固定延迟。
             */
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                if (isPreheatNavigation(url)) return
                app.dsh.mobile.ui.FrameStats.end("first-paint")
                notifyWebUiPainted()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Edge-to-Edge 需要透明系统栏，页面加载后重新断言一次。
                applyEdgeToEdge()
                if (view == null) return
                // v1.6.0：预热占位页（about:blank）不算「页面可用」。
                // 它只是为了提前建起 Chromium 进程与渲染管线，此时引擎还没起，
                // 若继续走下去会：① dismissSplash() 提前收遮罩露出空 WebView；
                // ② 对 about:blank 注入插件桥（无意义且可能报错）；
                // ③ BootTrace 误记首屏时间。所以到这里直接返回。
                if (isPreheatNavigation(url)) {
                    logWebView("preheat page finished (engine still starting)")
                    return
                }
                // 导航结束，看门狗恢复正常节奏。
                pageIsLoading = false
                // 部分 WebView 内核不回调 onPageCommitVisible（SPA/首次导航），
                // 这里补一次同样的信号（幂等，重复调用无害）。
                notifyWebUiPainted()
                // v1.6.16：清掉预热留下的 about:blank 历史条目。
                // 必须在这里（真实页面加载完成、且已排除预热页）调用 —— 见
                // dropPreheatHistory 的注释，早调清不掉。
                dropPreheatHistory()
                // ── v1.6.45：把「不需要在首帧之前完成」的收尾挪出关键路径 ────────
                //
                // 这一段原本全部同步跑在 onPageFinished 里，而此刻正在发生的是
                // **遮罩淡出 + WebUI 淡入（360ms）** —— 一次整屏 alpha 合成。
                // 下面四项都只是维护性动作（装守护、清历史、重注样式、装合帧脚本），
                // 和"这一帧能不能画出来"无关，却正好和淡入抢同一个时间窗。
                // 挪到淡入结束后再做：首屏切换期间主线程只剩合成工作，视觉上更稳。
                //
                // 保留同步执行的只剩 [dropPreheatHistory]（历史栈必须立刻一致，
                // 否则用户在这 200ms 内按返回键会看到 about:blank）与桥接注入（功能）。
                view.postDelayed({
                    if (view !== liveWebView()) return@postDelayed
                    runCatching {
                        // v1.6.32：预览页装载「自毁守护」；回到引擎页后清历史栈释放预览实例。
                        installPreviewJanitor(view, url)
                        purgePreviewHistory(view, url)
                        // v1.6.36：文档已换，若当前处于降级态则重新注入降级样式
                        app.dsh.mobile.web.ThermalRenderGuard.reapplyIfNeeded(view)
                        // v1.6.37：高频事件合帧（scroll/resize/touchmove → rAF 一帧一次）。
                        // 前端不可改，只能在宿主侧包装 addEventListener；脚本幂等，
                        // Native 侧有 in-flight 守卫，不会堆 IPC。只对引擎页注入（预览页不动）。
                        // v1.6.46：document-start 已挂上时这里直接返回（不再多花一次 IPC）。
                        if (!isPreviewUrl(url)) app.dsh.mobile.web.EventCoalesce.install(view)
                        // v1.6.46：渲染调优（合成层提示 + 长列表离屏跳过 + 档位同步）。
                        // 与上面同理只对引擎页；预览页是用户内容，不动它的渲染策略。
                        if (!isPreviewUrl(url)) {
                            app.dsh.mobile.web.RenderTune.install(
                                view, assets, PerfPrefs.render(this@MainActivity),
                            )
                            // 观测只在 debug 包开：取一份帧窗口快照写 engine.log，
                            // 用来验证优化是否真的落地（而不是"感觉快了"）。
                            if (isDebuggable) {
                                app.dsh.mobile.web.RenderTune.startStats(view)
                                view.postDelayed({
                                    if (view !== liveWebView()) return@postDelayed
                                    app.dsh.mobile.web.RenderTune.logStats(view, "after-load")
                                    // 内存快照与帧统计同一时刻取：判断"卡"是算力还是 GC
                                    // 引起时，缺任何一半都只能猜。
                                    logWebView("perf mem: ${app.dsh.mobile.ui.FrameStats.memLine()}")
                                }, RENDER_STATS_DELAY_MS)
                            }
                        }
                    }.onFailure { logWebView("deferred page task failed: ${it.message}") }
                }, FIRST_FRAME_SETTLE_MS)
                // ── 桥接脚本注入（v1.6.20：6 次往返合并为 1 次）─────────────────────
                // 这里原本**串行**发起 6 次 evaluateJavascript：环境自检、blob 下载桥
                //（issue #37）、剪贴板降级链（issue #43）、强调色 --dsh-accent（v1.4.24）、
                // 键盘行为修正（issue #53）、整页浮层返回兜底（v1.6.15）。每次调用都是
                // 浏览器进程→渲染进程的一次 IPC 往返 + 渲染主线程上的一个独立任务，
                // 而这些任务正好落在首屏渲染与遮罩收起之间最拥挤的时间窗。
                //
                // 语义保持不变：脚本内容一字未改，幂等守卫（window.__dsh*Installed）
                // 依旧生效；每段外面包了 try/catch，某段运行期抛错仍不影响其余段。
                // 两段 JS 资产改由 [PageBridgeInjection.preload] 在 WebView 预热阶段
                // 于后台线程读好，不再每次导航都在 UI 线程同步 inflate（预读未完成时
                // 仍回退同步读 —— 注入不会被跳过）。动机与实测收益见该类注释。
                runCatching {
                    val hex = String.format("#%06X", Accent.current(this@MainActivity).argb and 0xFFFFFF)
                    val script = PageBridgeInjection.buildScript(
                        assets = assets,
                        blobBridgeJs = WebViewDownloader.bridgeJs(),
                        clipboardJs = CLIPBOARD_FALLBACK_JS,
                        selfCheckJs = WEBVIEW_SELFCHECK_JS,
                        accentHex = hex,
                    )
                    PageBridgeInjection.install(view, script) { raw ->
                        // 完成值 = 注入状态 + 自检结论。旧实现把自检的返回值丢了
                        //（callback 传的是 null），注释里说的「写进日志」从未真的发生，
                        // 这里才真正落进 engine.log。
                        val line = jsResult(raw)
                        logWebView(line.ifEmpty { "inject: empty result" })
                        if (line.contains("FAIL[")) logWebView("⚠️ 部分桥接脚本运行期异常：$line")
                    }
                }.onFailure { logWebView("bridge inject failed: ${it.message}") }
                // v1.4.6：视口缩放必须在**启动遮罩收起之前**定稿，并在遮罩之下整页
                // 重栅格化一次。旧顺序是「先 dismissSplash()、再改 meta」——用户会先看到
                // 按 initial-scale=1 画好的首屏（含登录/开始使用弹窗），紧接着整页缩放
                // 突变；WebView 会把部分合成层留在旧栅格里，弹窗被拉宽/截断、文字与按钮
                // 错位（复现截图与判定证据见本 PR 描述）。
                // v1.3.1 的约束仍然满足：收遮罩在两个渲染分支之后统一执行，不会提前 return。
                // v1.6.20：新页面一律要重栅格化一次，所以先清掉去重键（窗口尺寸变化的
                // 场景下才靠它避免重复软绘）。
                lastRerasterKey = null
                applyViewport(view)
                if (lastSafeBottom >= 0) {
                    injectWebSafeArea(lastSafeBottom, lastSafeLeft, lastSafeRight, lastSafeTop)
                }
                // v1.6.43：横屏桌面全幅铺满（解除页面外壳的 max-width/居中 + 安全区让位）。
                // **必须在这里**（onPageFinished = 每次导航后的新文档）：样式表与
                // 容器覆盖都是挂在 document 上的，导航会整个丢掉，晚一步就是满屏空白。
                installFullBleed(view, "pageFinished")
                // v1.4.8：首屏 Paint 是用户体感的一部分 —— 引擎 healthy 到页面可用之间
                // 还有加载 WebUI 与执行客户端插件的空档。补记进 boot.log 便于对比。
                BootTrace.noteWebViewLoaded(this@MainActivity)
                webUiReady = true
                dismissSplash()
                // v1.6.37：首屏与遮罩落定后再做合帧注入，不与桥接注入抢同一个窗口
                app.dsh.mobile.web.EventCoalesce.install(view)
                logWebView(
                    "perf: ${app.dsh.mobile.web.WebViewStartup.describe()} · " +
                        "${app.dsh.mobile.web.EventCoalesce.stats()} · " +
                        "${app.dsh.mobile.web.RenderTune.describe()} · " +
                        app.dsh.mobile.ui.ThermalGuard.describe(),
                )
                // v1.6.25：首帧与遮罩都已落定，现在才去摸静态资源。
                scheduleAssetWarm(view)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updatePreviewChrome(url)
            }
        }
    }

    /**
     * 首帧之后的静态资源预热（v1.6.25）。
     *
     * ## 为什么放在这里、还延后
     * 这段要做的事（读 `performance` 清单 + 后台 HTTP 拉几十个图片/字体）完全
     * 可以晚一点做，而首帧落定后紧接着就是插件 HMR 的窗口 —— 那两个阶段是用户
     * 唯一能感知到“卡”的地方，不能跟它们抢 CPU/IO。所以延后 [ASSET_WARM_DELAY_MS]。
     *
     * 每个进程只跑一次：清单在本次页面里不会变多，重复跑只是重复下载。
     *
     * 采集用 `performance.getEntriesByType('resource')` 而不是自己在 JS 里劫持
     * fetch/XHR：前者是浏览器自己记的、零侵入，不会碰到页面的任何运行时行为。
     */
    private fun scheduleAssetWarm(view: WebView) {
        if (assetWarmDone) return
        assetWarmDone = true
        uiScope.launch {
            delay(ASSET_WARM_DELAY_MS)
            runCatching {
                view.evaluateJavascript(ASSET_MANIFEST_JS) { raw ->
                    val list = jsString(raw)
                        .split('\n')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    if (list.isEmpty()) return@evaluateJavascript
                    WebAssetCache.learn(this@MainActivity, list)
                    WebAssetCache.logStats(this@MainActivity, "asset manifest (${list.size} url)")
                    Log.i("WebCache", app.dsh.mobile.web.ImagePreScaler.stats())
                }
            }.onFailure { logWebView("asset warm failed: ${it.message}") }
        }
    }

    /**
     * 预览 chrome：WebView 导航到非引擎端口的回环页面（用户点击 AI 在对话里给的
     * http://127.0.0.1:PORT 链接）时，状态栏切预览模式并亮出返回按钮；
     * 回到引擎主界面自动恢复。AI 无需任何特殊协议，输出普通链接即可。
     */
    /** 该 URL 是否是预览页（回环地址但端口不是引擎端口；v1.6.32 从 updatePreviewChrome 提取复用）。 */
    private fun isPreviewUrl(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val loopback = uri.host == "127.0.0.1" || uri.host == "localhost"
        val enginePort = (application as DshApp).supervisor.healthyPort
        return loopback && uri.port != enginePort
    }

    private fun updatePreviewChrome(url: String?) {
        val preview = isPreviewUrl(url)
        findViewById<View>(R.id.btnBack).visibility = if (preview) View.VISIBLE else View.GONE
        if (preview) {
            val port = url?.let { runCatching { Uri.parse(it).port }.getOrNull() }
            if (port != null) statusBar.text = getString(R.string.status_preview, port)
            previewActive = true
        } else if (previewActive) {
            // v1.6.32：预览 → 非预览 = 离开预览页。系统返回键（goBack）与工具栏返回按钮
            //（loadUrl）都会回调 doUpdateVisitedHistory，两种返回路径在这里统一收口。
            previewActive = false
            previewReturnPending = true
            // 兜底自毁：走到这里时页面可能已在卸载窗口里，能停一点是一点（幂等）。
            // 主路径是 btnBack 点击时的主动调用 —— 那时页面还活着，自毁一定生效。
            runCatching { webView.evaluateJavascript(PREVIEW_TEARDOWN_CALL, null) }
        }
    }

    /**
     * 预览页自毁守护（v1.6.32）。
     *
     * 预览页加载完成时装一小段守护脚本：用不可见的 MonkeyPatch 追踪页面创建的
     * WebSocket / EventSource / XHR 与 setTimeout/setInterval，并暴露
     * `__dshPreviewTeardown()`。离开预览页前调用它，页面自身的后台活动
     *（长连接、轮询、定时器）立刻停止 —— 即使导航销毁尚未完成，也不会有
     * “页面已在后台却还在连引擎/回环服务”的窗口期。
     *
     * 为什么只追踪不清数据流：补丁全程透传原实现（行为零改变），只在
     * `__dshPreviewTeardown()` 被显式调用时才 close/abort/clear ——
     * 页面功能不受任何影响；数组带长度上限，长驻预览页自身也不会膨胀。
     */
    private fun installPreviewJanitor(view: WebView, url: String?) {
        if (!isPreviewUrl(url)) return
        runCatching { view.evaluateJavascript(PREVIEW_JANITOR_JS, null) }
            .onFailure { logWebView("preview janitor install failed: ${it.message}") }
    }

    /**
     * 回到引擎页后清除历史栈，彻底释放预览实例（v1.6.32）。
     *
     * `clearHistory()` 把除当前页外的全部条目抹掉：预览页与上一份引擎页的
     * 会话条目变成不可达，渲染器随之丢弃它们持有的文档实例（DOM + JS 堆）
     * 交给 GC —— 这就是“彻底销毁并清除”的落地：没有任何 API 能再导航回
     * 那些页面，实例必然被回收。
     *
     * 时机与 dropPreheatHistory 同理：必须在返回目标页 onPageFinished（已提交）
     * 之后调用，早了清不掉还没进栈的条目。预览页之间的跳转不算返回，不清。
     */
    private fun purgePreviewHistory(view: WebView?, url: String?) {
        if (!previewReturnPending) return
        if (isPreviewUrl(url)) return
        previewReturnPending = false
        runCatching {
            view?.clearHistory()
            // SPA 内部返回标记随栈一起作废：清栈后 canGoBack()=false，返回键
            // 自然走「双击退出」，不会再 goBack 进任何旧条目（防复活）。
            backMobile = false
            backConsumable = false
            logWebView("preview history purged — preview instance released")
        }.onFailure { logWebView("preview history purge failed: ${it.message}") }
    }

    /**
     * 预热 WebView（v1.6.0）。
     *
     * ## 为什么
     * 冷启动的时序原本是「引擎全部就绪 → 才 loadUrl」，于是 Chromium 进程创建、
     * JS 引擎初始化、合成器建立（实测约 700ms）全被排在引擎之后串行叠加。
     * 但这三件事**不依赖引擎**，只有页面内容依赖。
     *
     * 实测证据（logcat，同一次冷启）：
     * ```
     * 06:37:06.491 engine healthy on :3080
     * 06:37:06.508 chromium android_setCpu already initialized   ← 晚 17ms
     * ```
     * 即 Chromium 确实是在引擎 healthy 之后才开始初始化。
     *
     * ## 做什么
     * 加载 `about:blank`：不联网、不读文件、不触发任何插件注入，
     * 只让 Chromium 把进程与渲染管线建起来。等引擎 Healthy 后
     * `loadLocalUrl(realUrl)` 时已是热态。
     *
     * ## 风险与规避
     * 预热页同样会触发 `onPageFinished`，那里会 `dismissSplash()`。
     * 若不加区分，遮罩会在引擎未就绪时就被收掉，用户直接看到空白 WebView。
     * 因此 `onPageFinished` 里用 [webViewPreheated] + URL 判定跳过预热页。
     */
    private fun preheatWebView() {
        if (webViewPreheated) return
        webViewPreheated = true
        // v1.6.20：预热阶段顺手把要注入的 JS 资产在后台线程读好（APK 内是压缩条目，
        // 读一次就要 inflate 一次）。此刻引擎还在启动，UI 线程正空着，这笔开销放在
        // 这里比放在「页面加载完成 → 收遮罩」的关键路径上便宜得多。
        PageBridgeInjection.preload(assets)
        app.dsh.mobile.web.FullBleed.preload(assets)
        // v1.6.46：渲染调优脚本同样在预热窗口预读（APK 内是压缩条目）
        app.dsh.mobile.web.RenderTune.preload(assets)
        runCatching {
            // about:blank 是唯一零依赖的目标：不需要 assets、不需要网络、
            // 不走 shouldInterceptRequest，且 onPageFinished 会正常回调。
            liveWebView()?.loadUrl(preheatUrl)
        }.onFailure { logWebView("preheat failed: ${it.message}") }
    }

    /**
     * 当前这次 onPageFinished 是否属于预热页（应跳过遮罩关闭与所有页面注入）。
     *
     * 只认 `about:blank`：真正 WebUI 的 URL 一定是 `http://127.0.0.1:3080/...`。
     */
    private fun isPreheatNavigation(url: String?): Boolean {
        if (!webViewPreheated) return false
        if (url.isNullOrEmpty()) return false
        return url == preheatUrl || url == "about:blank"
    }

    /**
     * 告诉引擎监督器：WebUI 已真正提交可见首帧（v1.6.21）。
     *
     * 监督器用它取代原来「固定 `Thread.sleep(800)`」来决定什么时候把第三方
     * 插件放回 profile 并触发 dsh-hmr 流式加载 —— 即用户要求的
     * 「插件在 WebUI 启动完成后快速异步流式加载」。
     */
    private fun notifyWebUiPainted() {
        runCatching { (application as DshApp).supervisor.notifyWebUiPainted() }
            .onFailure { logWebView("paint signal failed: ${it.message}") }
    }

    /** 统一的回环页加载入口：缩放统一由 onPageFinished 的 viewport meta 接管，这里只导航。 */
    private fun loadLocalUrl(url: String) {
        // v1.6.37：实例已退役时直接丢弃 —— 宁可少一次导航，也不能对
        // destroy 过的 WebView 调方法（那是 ColorOS 后台场景最典型的崩溃源）。
        val wv = liveWebView()
        if (wv == null) {
            logWebView("loadLocalUrl skipped (no live webview): $url")
            return
        }
        wv.loadUrl(url)
    }

    /**
     * 软刷新 WebUI：重新加载同一个地址，**不重启引擎**。
     *
     * ## 与 restart() 的区别
     *
     * `restart()` 会停掉引擎进程再拉起，几秒内 WebUI 完全不可用；
     * 这里只是让 WebView 重新请求页面 —— 引擎仍在跑、typert 连接重建、静态资源
     * 命中 v1.6.25 的本地缓存，观感接近一次普通刷新。
     *
     * 为什么要刷新而不是什么都不做：引擎的工作区服务是响应式的，多数情况会自己
     * 推送变更；但这条链路依赖引擎版本行为，不能作为唯一保证。删完对话后列表
     * 还留着已删条目，是最容易让用户以为"没删掉"的那种不一致。
     *
     * 拿不到地址（引擎未就绪）时把 `urlLoaded` 复位，等状态机下一次 Healthy 时
     * 自然加载，不会留下一个永远不刷新的标记。
     */
    private fun softReloadWebUi(reason: String) {
        val url = runCatching { (application as DshApp).supervisor.webUrl() }.getOrNull()
        if (url.isNullOrEmpty()) {
            urlLoaded = false
            logWebView("soft reload skipped ($reason): webUrl empty")
            return
        }
        logWebView("soft reload WebUI ($reason)")
        webReloadPending = false
        urlLoaded = true
        loadLocalUrl(url)
    }

    /**
     * 悬浮窗权限缺失时的应用内引导（v1.6.35，需求 4）。
     * 只在用户已开启开关但权限被关时弹，且每个安装只弹一次（guideShown）。
     */
    private fun showFloatPermissionGuideIfNeeded() {
        runCatching {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.float_status_perm_title)
                .setMessage(R.string.float_status_perm_msg)
                .setPositiveButton(R.string.float_status_perm_go) { _, _ ->
                    runCatching {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:$packageName"),
                            ),
                        )
                    }
                }
                .setNegativeButton(R.string.float_status_perm_later, null)
                .show()
        }
    }

    /**
     * 悬浮窗「Web 界面刷新」（v1.6.32）：软刷新**当前** Web 视图 —— 无论当前是
     * 引擎 WebUI 还是预览页，都不重启引擎。
     *
     * 为什么用 reload() 而不是 loadUrl(webUrl())：后者固定回引擎首页，会把预览页
     * 「刷没了」；reload() 重取当前地址，语义就是普通浏览器刷新。
     *
     * 平稳性：reload() 期间 Chromium 持续显示旧帧直到新帧可合成 —— 引擎页静态
     * 资源命中 v1.6.25 本地缓存、预览页是本机回环内容，重载百毫秒级完成，
     * 不会白屏闪烁；也绝不触碰引擎进程，没有 restart() 的整页灰屏期。
     */
    private fun refreshCurrentWebView() {
        runOnUiThread {
            runCatching {
                // v1.6.37：实例已退役时不可 reload（已 destroy）。
                // 这种情况下悬浮窗的 Bridge 会拉起主界面，由 onResume 的恢复流程接住。
                val wv = liveWebView()
                if (wv != null) {
                    wv.reload()
                    logWebView("overlay web refresh triggered")
                } else {
                    logWebView("overlay web refresh skipped: no live webview")
                }
            }.onFailure { logWebView("overlay web refresh failed: ${it.message}") }
        }
    }

    /**
     * 把预热留下的 `about:blank` 历史条目清掉。
     *
     * 不清的后果有两个，都是用户可见的：
     * 1. **右滑返回变白屏** —— 返回目标是空白页，界面整块变白（本次报障）；
     * 2. 返回键要按两次才退出应用 —— 一次退到空白页，一次才真退出。
     *
     * 调用时机很关键：必须在真实页面**加载完成后**调用。`loadUrl` 刚返回时
     * 新条目还没提交进历史，此时 `clearHistory()` 清不掉任何东西（历史里只有
     * 预热页那一条，清完反而让页面变成无历史状态）。所以放在 `onPageFinished`
     * 里、确认不是预热页之后再调。
     *
     * `clearHistory()` 只是**不可见地**抹掉历史，不触发导航，不重载页面，
     * 用户不会感知到任何闪动。
     */
    private fun dropPreheatHistory() {
        if (!webViewPreheated) return
        if (preheatHistoryDropped) return
        val list = runCatching { webView.copyBackForwardList() }.getOrNull() ?: return
        if (list.size <= 1) {
            // 历史里只有当前页 —— 预热条目已被 WebView 自己合并掉，无需处理
            preheatHistoryDropped = true
            return
        }
        val first = list.getItemAtIndex(0)
        val firstUrl = first?.url
        if (firstUrl == preheatUrl || firstUrl == "about:blank") {
            runCatching { webView.clearHistory() }
                .onSuccess {
                    preheatHistoryDropped = true
                    logWebView("dropped preheat history entry")
                }
                .onFailure { logWebView("clearHistory failed: ${it.message}") }
        } else {
            // 首条不是预热页：说明这次导航没经过 about:blank，不必清
            preheatHistoryDropped = true
        }
    }

    /** 手势缩放开关：竖屏关闭（锁死固定全屏，禁止双指捏合/拖动移动），桌面模式开启（保留双指缩放）。
     *  displayZoomControls 恒 false，只保留捏合不显示 +/- 浮层按钮。 */
    private fun applyZoomControls(enable: Boolean) {
        // v1.6.37：已退役实例不可碰 settings
        liveWebView()?.settings?.apply {
            setSupportZoom(enable)
            builtInZoomControls = enable
            displayZoomControls = false
        }
    }

    /**
     * 只改桌面渲染的 UA（供**后续导航**使用）+ 手势缩放开关，不 reload。
     *
     * v1.6.42 起 `reload` 不再发生在模式切换上（转屏整页重载 = 白闪 + 滚动位置归零，
     * 见 [switchRenderMode]）；当前文档的 UA 由 [applyUserAgentOverride] 即时覆盖，
     * 视口由 [applyViewport] 改写，三者在同一帧内保持一致。
     */
    private fun setDesktop(enable: Boolean) {
        desktopMode = enable
        // v1.6.37：实例已退役时只记状态，UA 等新实例就绪后由 setupWebView 按
        // desktopMode 重新应用 —— 不对 destroy 过的实例调方法。
        liveWebView()?.settings?.userAgentString = if (enable) DESKTOP_UA else defaultUa
        applyZoomControls(enable)
    }

    /** 工具栏收起/唤回：收起时网页全屏，仅留顶部灵动岛提示可恢复 */
    private fun toggleToolbar() {
        val row = findViewById<View>(R.id.statusBarRow)
        val handle = findViewById<View>(R.id.handleBar)
        if (row.visibility == View.VISIBLE) {
            // 合拢：岛缩小淡出 → 把手淡入（灵动岛收起的观感）
            // width 此刻有效（row 可见），但布局未完成时仍可能是 0 —— 兜底居中。
            row.pivotX = if (row.width > 0) row.width / 2f else resources.displayMetrics.widthPixels / 2f
            row.pivotY = 0f
            row.animate().scaleX(0.6f).scaleY(0.2f).alpha(0f).setDuration(160L)
                .withEndAction {
                    row.visibility = View.GONE
                    row.scaleX = 1f; row.scaleY = 1f; row.alpha = 1f
                    handle.alpha = 0f
                    // ⚠️ v1.4.54：上一次拖拽留下的 translation 会把圆推离挖孔，
                    // 收起时归零（拖拽是临时手感，不该影响对齐）
                    handle.translationX = 0f
                    handle.translationY = 0f
                    handle.visibility = View.VISIBLE
                    // GONE → VISIBLE 期间 insets 回调可能已经跑过（那时设的
                    // layoutParams 对 GONE 视图不生效），这里补一次对齐
                    handle.requestApplyInsets()
                    // 贴纸是胶囊上的装饰，胶囊收起后必须一起消失，
                    // 否则会孤零零浮在顶部
                    setStickersVisible(false)
                    handle.animate().alpha(1f).setDuration(140L).start()
                }.start()
        } else {
            // 展开：从把手位置撑开成岛（灵动岛展开的观感）
            handle.animate().scaleX(0.4f).scaleY(0.4f).alpha(0f).setDuration(120L)
                .withEndAction {
                    handle.visibility = View.GONE
                    handle.scaleX = 1f; handle.scaleY = 1f; handle.alpha = 1f
                    // 展开态不需要对齐挖孔（全宽胶囊本来就罩住顶部），
                    // 但下次收起要重新按当前挖孔算位置，故清掉临时位移
                    handle.translationX = 0f
                    handle.translationY = 0f
                    row.scaleX = 0.7f; row.scaleY = 0.4f; row.alpha = 0f
                    row.visibility = View.VISIBLE
                    // ⚠️ 必须等一次布局后再取 width：GONE → VISIBLE 的同一帧里
                    // row.width 还是 0，pivotX 会被算成 0.5px，岛就从左上角展开
                    // 而不是从中心撑开。post 到下一帧即可拿到真实宽度。
                    setStickersVisible(true)
                    row.post {
                        row.pivotX = row.width / 2f
                        row.pivotY = 0f
                        row.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(180L)
                            .setInterpolator(android.view.animation.OvershootInterpolator(0.9f))
                            .start()
                    }
                }.start()
        }
    }

    /**
     * 把手拖拽：按住可在父容器内自由移动（clamp 保住 60% 宽度在屏内，防止拖丢），
     * 松手时若位移小于 touch slop（约 12px）则视为点击 → 唤回工具栏。
     * 位置仅本次隐藏期间保持（重显工具栏后再收起回到上次位置，rotation/重建回默认）。
     */
    /**
     * 展开态状态栏的外观：自定义背景图 + 用户贴的贴纸（v1.5.x）。
     *
     * 贴纸放在 `statusBarOverlay` 这层 FrameLayout 里 —— 它与胶囊共用一个容器，
     * 位置尺寸完全一致，所以贴纸的相对坐标直接乘容器宽高即可，不必关心
     * 状态栏被 insets 下移了多少。
     *
     * 贴纸不消费触摸，不会挡住胶囊上的返回/重启/设置按钮。
     */
    private fun applyExpandedStatusBar() {
        val row = findViewById<View>(R.id.statusBarRow) ?: return
        val overlay = findViewById<ViewGroup>(R.id.statusBarOverlay) ?: return

        applyExpandedBg(row)

        // —— 贴纸：按需增量同步，不推倒重建 ——
        syncStickers(overlay)
    }

    // ── 展开态状态栏的背景图 ────────────────────────────────────────────────

    /**
     * 背景图缓存：键 = 路径 | mtime | 字节数 | 目标宽，最多 2 条（深/浅主题各一）。
     *
     * [applyExpandedStatusBar] 由 `onResume` 调用 —— 用户每次从别的应用切回来都会走。
     * 旧实现每次都 `BitmapFactory.decodeFile` 整图：1440×1080 是 5.9 MB，
     * 1440×2560 是 14.1 MB，**每切一次应用白解一次、而且解出来的位图只做背景用完即废**。
     * 用户感知就是「从微信切回来，主界面先顿一下」。
     *
     * 缓存的代价是常驻一份位图，但换掉的是「每次 onResume 一次的整图解码 + 一份
     * 同尺寸垃圾」。两者量级相同，而后者会持续触发 GC —— 常驻反而更稳。
     */
    private val expandedBgCache = android.util.LruCache<String, android.graphics.Bitmap>(2)

    /** 上一次实际套用的背景键。一致就整段跳过，连缓存都不用查。 */
    private var appliedExpandedBgKey: String? = null

    private fun applyExpandedBg(row: View) {
        val bgFile = CustomVisualPref.expandedBgFile(this)
        if (bgFile == null) {
            if (appliedExpandedBgKey != null) {
                row.setBackgroundResource(R.drawable.bg_dynamic_island)
                appliedExpandedBgKey = null
            }
            return
        }

        // 目标宽取屏幕宽：背景是 FILL 铺满，解码到比屏幕更宽纯属浪费内存
        val targetW = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val key = "${bgFile.absolutePath}|${bgFile.lastModified()}|${bgFile.length()}|$targetW"
        if (key == appliedExpandedBgKey) return

        val bmp = expandedBgCache.get(key) ?: decodeSampled(bgFile, targetW) ?: run {
            row.setBackgroundResource(R.drawable.bg_dynamic_island)
            appliedExpandedBgKey = null
            return
        }
        expandedBgCache.put(key, bmp)
        row.background = android.graphics.drawable.BitmapDrawable(resources, bmp).apply {
            gravity = android.view.Gravity.FILL
        }
        appliedExpandedBgKey = key
    }

    /**
     * 按目标宽度解码，超大图先 `inSampleSize` 粗降采样再解码。
     *
     * 导入侧已经把图片压到 1440 宽以内（`CustomVisualPref.BG_MAX_WIDTH`），所以
     * 多数情况下 `sampleSize` 仍是 1；但只要用户是通过旧版本、数据迁移或手工塞进
     * 来的大图，这里就能挡住「一张 4000×3000 直接解码成 48 MB」的 OOM 路径。
     */
    private fun decodeSampled(file: java.io.File, targetW: Int): android.graphics.Bitmap? =
        runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0) return@runCatching null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2

            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        }.getOrNull()

    // ── 贴纸 ────────────────────────────────────────────────────────────────

    /** 当前贴在状态栏上的贴纸 View，与 [StickerPref.list] 顺序一一对应。 */
    private val stickerViews = ArrayList<ImageView>()

    /**
     * 把 `statusBarOverlay` 里的贴纸同步成当前列表。
     *
     * ## 为什么不再 `removeViewAt` 全删再 `addView` 全加
     *
     * 旧实现每次 `onResume` 都把全部贴纸 View 删掉重建。每次 `removeViewAt` /
     * `addView` 都会让 overlay `requestLayout()`，连带父链重新测量 —— 一个用户
     * 贴了 5 张贴纸，就等于每次返回主界面触发 10 次布局失效，且新建的 ImageView
     * 还要重新解码位图。用户看到的是「切回来时状态栏闪一下」。
     *
     * 现在按索引**复用**已有 View：数量不变时一张 View 都不新建，只在贴纸的
     * 文件/尺寸/位置真的变了时改对应属性（`x`/`y` 是平移，不触发布局）。
     */
    private fun syncStickers(overlay: ViewGroup) {
        val items = StickerPref.list(this)

        // 绝大多数 onResume 里贴纸根本没变：签名一致 + 容器尺寸一致，就整段跳过。
        // 没有这条，每次返回主界面仍要排一次 post、跑一遍属性比较。
        val signature = items.joinToString(";") {
            "${it.file},${it.xRatio},${it.yRatio},${it.sizeDp}"
        } + "|" + overlay.width + "x" + overlay.height
        if (signature == lastStickerSignature) return
        // 先把非贴纸的子 View（胶囊按钮之类）排除掉，避免索引错位
        val live = ArrayList<ImageView>(items.size)
        for (i in 0 until overlay.childCount) {
            (overlay.getChildAt(i) as? ImageView)?.takeIf { it.tag == stickerTag }?.let(live::add)
        }

        // 多出来的回收
        while (live.size > items.size) {
            overlay.removeView(live.removeAt(live.size - 1))
        }
        // 不够的补建（只在贴纸数量增加时发生）
        while (live.size < items.size) {
            val iv = ImageView(this)
            iv.tag = stickerTag
            iv.visibility = if (stickersWantedVisible) View.VISIBLE else View.GONE
            overlay.addView(iv, android.widget.FrameLayout.LayoutParams(0, 0))
            live += iv
        }
        stickerViews.clear()
        stickerViews.addAll(live)
        if (items.isEmpty()) return

        // 位置/尺寸依赖容器实际宽高，必须等布局完成后再算
        overlay.post {
            if (isFinishing || isDestroyed) return@post
            val w = overlay.width
            val h = overlay.height
            if (w <= 0 || h <= 0) return@post
            items.forEachIndexed { index, st ->
                val iv = stickerViews.getOrNull(index) ?: return@forEachIndexed
                bindSticker(iv, st, w, h)
            }
            lastStickerSignature = items.joinToString(";") {
                "${it.file},${it.xRatio},${it.yRatio},${it.sizeDp}"
            } + "|" + w + "x" + h
        }
    }

    /** 上一次同步完成时的贴纸签名；相同则跳过整段同步。 */
    private var lastStickerSignature: String? = null

    /**
     * 把一张贴纸套到复用的 ImageView 上。
     *
     * 位图按「文件 + mtime + 字节数」判定是否换过：没换就完全不动 `drawable`，
     * 避免每次 onResume 重新解码（贴纸文件已由导入侧压到 256px 内，但重复解码
     * 一样是白花的时间与垃圾）。
     */
    private fun bindSticker(iv: ImageView, st: StickerPref.Sticker, w: Int, h: Int) {
        val file = StickerPref.fileOf(this, st)
        val key = file?.let { "${it.absolutePath}|${it.lastModified()}|${it.length()}" }
        if (key != null && iv.getTag(R.id.stickerBitmapKey) != key) {
            val bmp = runCatching {
                android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            }.getOrNull()
            if (bmp != null) {
                iv.setImageBitmap(bmp)
                iv.setTag(R.id.stickerBitmapKey, key)
            }
        }

        val sizePx = (st.sizeDp * resources.displayMetrics.density).toInt()
        val lp = iv.layoutParams
        if (lp == null || lp.width != sizePx || lp.height != sizePx) {
            iv.layoutParams = android.widget.FrameLayout.LayoutParams(sizePx, sizePx)
        }
        // 上限必须夹到 >= 0：贴纸边长可以大于容器高（32dp 贴纸 vs 44dp 高，
        // 放大到 64dp 时 h-sizePx 为负），coerceIn(0f, 负数) 会直接抛
        // IllegalArgumentException 把主界面搞崩。
        val x = (st.xRatio * w - sizePx / 2f).coerceIn(0f, maxOf(0f, (w - sizePx).toFloat()))
        val y = (st.yRatio * h - sizePx / 2f).coerceIn(0f, maxOf(0f, (h - sizePx).toFloat()))
        // x/y 是 translation，只影响绘制、不触发 requestLayout
        if (iv.x != x) iv.x = x
        if (iv.y != y) iv.y = y
    }

    /**
     * 收起态灵动岛的渲染：统一交给 [IslandDotRenderer]，与设置子页里的预览
     * 用同一套代码，保证"预览长什么样、收起就什么样"。
     */
    private fun applyIslandDotImage(handle: View) =
        // applySize = false：真机圆点的尺寸由 applyIslandInsets 统一管理
        // （那里还要考虑挖孔对齐与不溢出屏幕），两处都设会互相覆盖。
        IslandDotRenderer.apply(this, handle, applySize = false)

    private fun setupHandleBar() {
        val handle = findViewById<View>(R.id.handleBar)
        applyIslandDotImage(handle)
        val slop = 12f
        var rawX0 = 0f; var rawY0 = 0f
        var initTx = 0f; var initTy = 0f
        handle.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    rawX0 = ev.rawX; rawY0 = ev.rawY
                    initTx = v.translationX; initTy = v.translationY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val parent = v.parent as View
                    v.translationX = (initTx + (ev.rawX - rawX0))
                        .coerceIn(-v.width * 0.6f, parent.width - v.width * 0.4f)
                    v.translationY = (initTy + (ev.rawY - rawY0))
                        .coerceIn(0f, (parent.height - v.height).coerceAtLeast(0).toFloat())
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = ev.rawX - rawX0; val dy = ev.rawY - rawY0
                    if (dx * dx + dy * dy < slop * slop) toggleToolbar()
                    true
                }
                else -> false
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // ⚠️ v1.5.1：自由窗口/分屏的**尺寸变化也走这里**（configChanges 含 screenSize），
        // 而且拖动时每变一像素来一次。旧实现无条件 reload() → 拖动过程中连续整页重载
        // （卡顿、闪白、反复重走启动遮罩）。
        //
        // 这里**不能**当场判断渲染模式：此刻 WebView 还没重新布局，webView.width 仍是
        // 旧窗口（乃至全屏）的尺寸，isWindowed() 会算成"窗口够宽"，于是小窗里继续走
        // 桌面渲染。统一交给 scheduleViewportReapply，在布局完成后判断模式、
        // 必要时才 reload，单纯尺寸变化只重写 viewport meta。
        applyZoomControls(desktopMode)
        applyEdgeToEdge()
        scheduleViewportReapply("config ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp")
    }
    /**
     * 进出小窗 / 分屏 / 画中画（v1.5.1）。
     *
     * Activity 不会因此重建，但 WebView 的尺寸变了 —— 而视口宽正是按 WebView 尺寸算的，
     * 不在这里补一次，页面就会保持进小窗前的宽度被整体缩小（字号变小的直接成因）。
     */
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        scheduleViewportReapply("multiwindow=$isInMultiWindowMode")
    }

    /**
     * 当前窗口（= WebView 视口）的 CSS 宽度（v1.5.1）。
     *
     * ## 为什么必须显式算，而不是交给 `device-width`
     * `device-width` 在 Android WebView 里恒等于**整块屏幕**的 CSS 宽，且不随
     * WebView 尺寸变化。手机上手窗口==屏幕所以一直没暴露；自由窗口/分屏里窗口比
     * 屏窄，页面仍按屏宽排版，再被 `loadWithOverviewMode` 整体缩进窗口 ——
     * 字号按 `窗口宽/屏宽` 等比缩水（vivo 平板小窗实测 468/1238 ≈ 0.38，字只剩 40%）。
     *
     * ## 为什么用 view.width 而不是 WindowManager
     * - `resources.displayMetrics.widthPixels`：整块屏幕物理宽，自由窗口下与窗口无关；
     * - `WindowManager.getCurrentWindowMetrics()`：含窗口装饰，且 API 30+（本项目 minSdk=26），
     *   拿到的也比 WebView 内容宽略大；
     * - WebView 在 activity_main.xml 里是 `match_parent` 全幅铺满窗口 → `view.width`
     *   本身就是窗口内容宽（物理 px）。除以 density 即 CSS px（WebView 的
     *   devicePixelRatio 恒等于 resources.displayMetrics.density；即便系统做兼容缩放，
     *   两者同步变化，比值不变）。
     *
     * @return 窗口 CSS 宽度，下限 200（异常态兜底，避免 meta 写出非法值）
     */
    private fun windowCssWidth(view: View): Int {
        if (view.width > 0) {
            val css = view.width / resources.displayMetrics.density
            if (css >= 200f) return css.toInt()
        }
        // 布局尚未完成时兜底：多窗口下 configuration.screenWidthDp 就是**窗口**宽（不是屏宽）
        return resources.configuration.screenWidthDp.coerceAtLeast(200)
    }

    /**
     * 竖屏视口缩放 JS（v1.5.1 重写）：布局宽 = 窗口 CSS 宽 / R，显示比例 = R。
     *
     * - `width=(宽/R)` + `initial-scale=R` → `(宽/R) × R = 宽`，正好铺满视口；
     *   旧实现写 `width=device-width`，自由窗口下会把整屏宽当布局宽（见 [windowCssWidth]）。
     * - `min=max=R` → 锁死缩放（既不会被 clamp 回 100%，也不许用户手动捏合）。
     *   ⚠️ 这也正是必须把 R 同时放进 width 的原因：若布局宽已经等于视口宽、再只用
     *   `initial-scale=R`，R<1 时 WebView 会把"填满视口"优先于初始缩放，缩放直接失效。
     * - R 由用户 pageScale 推导，范围 0.5–1.5。
     */
    private fun portraitViewportJs(cssWidth: Int, ratio: Float): String {
        val r = ratio.coerceIn(0.5f, 1.5f)
        val w = (cssWidth / r).toInt().coerceIn(200, 4000)
        return "(function(){" +
            "var m=document.querySelector('meta[name=\"viewport\"]');" +
            "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
            "(document.head||document.documentElement).appendChild(m);}" +
            "m.setAttribute('content','width=$w, initial-scale=$r, minimum-scale=$r, maximum-scale=$r, user-scalable=no, viewport-fit=cover');" +
            "})()"
    }

    private fun desktopViewportJs(cssWidth: Int): String {
        // 与竖屏的差别只有三处：对齐窗口宽（device-width）、允许安全区扩展
        // （viewport-fit=cover）、以及**不**做任何缩放。
        //
        // `maximum-scale=1.0 + user-scalable=no`：横屏下禁掉双指缩放。
        // v1.6.42 曾保留 `user-scalable=yes` 让用户放大细看小目标 —— 但既然现在
        // 布局宽 = 窗口宽、字号是原生尺寸，不再需要靠放大补救；反而捏合缩放会把
        // 页面缩到小于窗口，视觉上又是一圈空白（用户报的正是这个）。锁死更稳。
        val content = "width=device-width, initial-scale=1.0, maximum-scale=1.0, " +
            "user-scalable=no, viewport-fit=cover"
        return "(function(){" +
            "var m=document.querySelector('meta[name=\"viewport\"]');" +
            "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
            "(document.head||document.documentElement).appendChild(m);}" +
            "m.setAttribute('content','" + content + "');" +
            "})()"
    }

    /**
     * 在当前文档里**临时覆盖** `navigator.userAgent`（v1.6.42）。
     *
     * 切模式不再整页 reload（见 [scheduleViewportReapply]），但已经加载的文档里
     * `navigator.userAgent` 仍停在导航时的值 —— 若引擎侧插件靠 UA 判"是不是手机"，
     * 横屏切到桌面会被它当成手机、继续上手机 UI。所以这里用 `defineProperty` 同步
     * 改一次；切回竖屏时 `delete` 掉自有属性，自动回落到原型链上的真实值。
     *
     * 这只是**即时生效层**：后续导航（真刷新、跳转）由 `settings.userAgentString`
     * 负责（见 [setDesktop]），两者配合才不会出现“半套 UA”。
     * 全链 `try/catch`：拿不到或不允许改就拿不到，页面本身不受影响。
     */
    private fun applyUserAgentOverride(desktop: Boolean) {
        val wv = liveWebView() ?: return
        val js = if (desktop) {
            "try{Object.defineProperty(navigator,'userAgent',{configurable:true," +
                "get:function(){return '$DESKTOP_UA'}});}catch(e){}"
        } else {
            "try{delete navigator.userAgent;}catch(e){}"
        }
        runCatching { wv.evaluateJavascript(js, null) }
    }

    /**
     * 应用当前渲染模式的视口（v1.4.6，v1.5.1 修自由窗口字号，v1.6.43 横屏 1:1）。
     *
     * - 桌面（横屏）：`width=device-width, initial-scale=1.0, maximum-scale=1.0,
     *   user-scalable=no` —— **零缩放**，布局宽 == 窗口宽。「桌面宽布局」不靠缩放
     *   制造宽度，而是由 [app.dsh.mobile.web.FullBleed] 在页面内部解除
     *   `max-width` / 居中约束（v1.6.42 的 `width=max(1024,cw)` 会缩出四周空白）。
     * - 竖屏：`width=窗口CSS宽/R` + `initial-scale=R`（见 [portraitViewportJs]）。
     *
     * ⚠️ v1.5.1 之前的竖屏分支写 `width=device-width` —— 那是**整屏宽**，自由窗口/
     * 分屏下窗口比屏窄时页面被整体缩小塞进窗口，字号按 窗口宽/屏宽 等比缩水。
     * 现在两个分支都以「WebView 自身尺寸」为准，与窗口一致。
     *
     * 两个分支都在**启动遮罩尚未淡出**时执行（`onPageFinished` 里先本函数、后
     * `dismissSplash()`）。竖屏分支若带缩放，随后安排一次整页重栅格化，保证用户看到的
     * 第一帧就是最终缩放，不会出现「旧栅格 + 新缩放」的混合画面（横屏是 1:1，不需要）。
     *
     * 竖屏「对话流被挤压」的修复不在这里，而是引擎侧客户端插件
     * （`assets/android-plugins/narrow-sidebar/`，v1.2.175）：外部注入 JS 会和其它
     * UI 插件抢 DOM，且引擎升级后哈希类名一变就失效；插件走 `dsh-client-modules`
     * 的官方发现机制，卸载时自清理，互不干扰。
     *
     * @param view 已触发 `onPageFinished`、或窗口尺寸刚变化过的 WebView。
     */
    private fun applyViewport(view: WebView) {
        if (desktopMode) {
            // 必须记下宽度：scheduleViewportReapply 靠它判断"只改高度就跳过"，
            // 漏写会让桌面模式下的去抖永远失效（键盘弹出也整页重栅格化 → 白闪）。
            lastViewportWidth = view.width
            val cssW = windowCssWidth(view)
            // v1.6.43：**布局宽 = 窗口宽，1:1 零缩放**。
            //
            // v1.6.42 走的是「伪造宽布局 + initial-scale<1」（width=max(1024,cw)）。
            // 那条路有两个必然代价，用户都在横屏下看到了：
            //   · 缩放后 1 CSS px < 1 物理逻辑 px → 整页变小、四周留白；
            //   · 桌面 UI 里若还有 `max-width` 容器，缩放会把"居中窄栏"一起缩小，
            //     左右空出来的部分就成了大片空白。
            // 现在布局宽就是窗口宽（`width=device-width`），页面拿到的 100vw
            // 与屏幕完全一致；"桌面宽布局"改由 [FullBleed] 在页面内部解除
            // max-width / 居中约束来实现 —— 不靠缩放制造宽度。
            lastLayoutWidth = cssW.toFloat()
            view.evaluateJavascript(desktopViewportJs(cssW), null)
            logWebView(
                "viewport/desktop win=${view.width}px cssW=$cssW layoutW=$cssW scale=1.000",
            )
            return
        }
        val scale = (pageScale / 100f).coerceIn(0.5f, 1.5f)
        val cssW = windowCssWidth(view)
        lastViewportWidth = view.width
        lastLayoutWidth = (cssW / scale).toInt().toFloat()
        view.evaluateJavascript(portraitViewportJs(cssW, scale), null)
        // 窗口尺寸取错是这类问题的唯一来源 —— 留一条日志，真机 `logcat -s DshWebView` 一眼可验：
        // 小窗下 cssW 必须≈窗口宽(dp)，若仍≈屏宽说明 windowCssWidth 取错了来源。
        logWebView(
            "viewport/portrait win=${view.width}px cssW=$cssW scale=$scale " +
                "density=${resources.displayMetrics.density} " +
                "screenDp=${resources.configuration.screenWidthDp} " +
                "multi=$isInMultiWindowMode",
        )
        // scale=1 没有缩放突变，不需要额外重栅格化（也避免无谓的软件渲染帧）。
        if (scale != 1f) scheduleReraster(view, "portrait scale=$scale")
    }

    /**
     * 切换渲染模式（v1.6.42：软切换 + 自校验 + 兜底重载）。
     *
     * ## 为什么不再无条件 reload
     * 旧实现在模式变化时 `reload()`：UA、视口、缩放开关确实“一整套重走”了，但代价是
     * **整页重新导航** —— 转屏一次就白屏一次、对话滚动位置归零、重走一遍首屏绘制。
     * 用户对“横竖屏切换平滑、无闪烁”的诉求正是在这一层破的。
     *
     * 但整页重载确实不是必须的：
     *   · **布局**根本不由 UA 决定 —— 桌面/手机布局是同一份页面 + 响应式 CSS，
     *     引擎侧手机 UI 插件自己按视口宽判定（`width > 900` 就不接管，见
     *     `_test_narrow_sidebar.mjs` 的场景③）；
     *   · **视口**只靠 meta，运行时改写本来就已经在用了（自由窗口拖拽就是这么做的，
     *     见 [scheduleViewportReapply]）相反，重载反而是多余动作。
     *
     * ## 自校验（关键）
     * 如果只 “改 meta 不重载”，一旦某个 WebView 版本不买 meta 的改，页面就会永久停在
     * 旧布局 —— 比白闪一次糟得多。所以改完等一帧后**读回 `documentElement.clientWidth`**，
     * 与应得布局宽比对（±2%）；不符合才回退到整页 reload。也就是说：
     *   正常情况 0 次重载（平滑）；异常情况自动回退成旧行为（不退化）。
     *
     * ## 顺序
     * 1. 改 UA（设置层，后续导航用）+ 即时覆盖 `navigator.userAgent`（当前文档用）；
     * 2. 改手势缩放开关；
     * 3. 立即写新视口 meta（在遮罩/白屏之前就位，不会有“旧栅格+新缩放”的中间态）；
     * 4. 重排 insets（横屏桌面要重算 WebView 让位边距）；
     * 5. 校验；不符才 reload。
     */
    private fun switchRenderMode(view: WebView, wantDesktop: Boolean, reason: String) {
        setDesktop(wantDesktop)
        applyUserAgentOverride(wantDesktop)
        lastViewportWidth = -1
        applyViewport(view)
        // 横屏桌面要重算页面内部的让位（竖屏全为 0）—— insets 回调不会因模式
        // 变化自己再跑一次，必须显式请求，否则切到横屏后页面被岛盖住一整个会话。
        findViewById<View>(android.R.id.content)?.requestApplyInsets()
        // v1.6.43：模式切换必须重装/卸载页面侧的 full-bleed（切回竖屏要 teardown）。
        installFullBleed(view, "modeSwitch")
        verifyModeSwitch(view, wantDesktop, reason)
    }

    /**
     * 软切换后的自校验：读回布局宽 + 手机 UI 插件标记，不符则整页 reload。
     *
     * ## 两道校验
     * 1. **视口是否真的生效**：`documentElement.clientWidth` 必须等于刚写进去的
     *    布局宽（±2%）。少数 WebView 版本对运行时改 meta 的响应不一致，不校验的话
     *    页面会永久停在旧布局 —— 比白闪一次糟得多。
     * 2. **手机 UI 插件是否已让位**：引擎侧 narrow 插件接管时会给列打
     *    `data-android-narrow-sidebar-col` / `-yield-col`（见
     *    `scripts/probes/_test_narrow_sidebar.mjs`）。切到桌面后如果这两个标记还在，
     *    说明插件没跟随宽度变化退出 —— 这时**只有重载**能把它赶走。
     *
     * 3. **是否真的铺满**（v1.6.43）：横向溢出 `scrollWidth - clientWidth` 必须为 0
     *    （容忍 1px 取整）。侧边留空与横向滚动条是同一个病的两面。
     *
     * 三道都过 → 零重载（平滑）；任一不过 → 回退成旧的整页重载（不退化）。
     * 用 token 作废旧校验：快速来回转屏只认最后一次，避免把已经正确的页面又
     * reload 一遍（那正是「切快了就乱」的来源）。
     */
    private fun verifyModeSwitch(view: WebView, wantDesktop: Boolean, reason: String) {
        val token = ++modeSwitchToken
        val expect = lastLayoutWidth
        // 第三项 `s` 是横向溢出量（scrollWidth - clientWidth）：v1.6.43 把视口改成
        // 1:1 满幅后，页面若还留着 `min-width`/固定宽内层，就会出现横向滚动条 ——
        // 视觉上是右侧一条空白，且内容被挤到左边。有溢出说明"没真铺满"，同样要兜底。
        val probe = "(function(){" +
            "var d=document.documentElement;" +
            "var w=d?d.clientWidth:0;" +
            "var s=d?Math.max(0,d.scrollWidth-d.clientWidth):0;" +
            "var n=document.querySelectorAll(" +
            "'[data-android-narrow-sidebar-col],[data-android-narrow-yield-col]').length;" +
            "return w+'|'+n+'|'+s;})()"
        view.postDelayed({
            if (token != modeSwitchToken) return@postDelayed
            val live = liveWebView() ?: return@postDelayed
            runCatching {
                live.evaluateJavascript(probe) { raw ->
                    if (token != modeSwitchToken) return@evaluateJavascript
                    val body = raw?.trim()?.trim('"').orEmpty()
                    val actual = body.substringBefore('|').toFloatOrNull()
                    val narrowMarkers = body.substringAfter('|', "0")
                        .substringBefore('|').toIntOrNull() ?: 0
                    val overflow = body.substringAfterLast('|', "0").toIntOrNull() ?: 0
                    if (actual == null || expect <= 0f) return@evaluateJavascript
                    val widthOk = kotlin.math.abs(actual - expect) <= expect * 0.02f
                    // 横向溢出容忍 1px（亚像素取整）；横屏桌面要求真的铺满，所以这里
                    // 也是硬条件 —— 侧边"空一条"和"溢出滚动条"是同一个问题的两面。
                    val fillOk = !wantDesktop || overflow <= 1
                    // 只有切到桌面时才要求「手机插件已让位」；竖屏下它本来就该在。
                    val layoutOk = widthOk && fillOk && (!wantDesktop || narrowMarkers == 0)
                    if (layoutOk) {
                        logWebView(
                            "viewport switch ok: desktop=$wantDesktop expect=$expect " +
                                "actual=$actual narrow=$narrowMarkers overflow=$overflow " +
                                "($reason)",
                        )
                    } else {
                        logWebView(
                            "viewport switch fallback reload: desktop=$wantDesktop " +
                                "expect=$expect actual=$actual narrow=$narrowMarkers " +
                                "overflow=$overflow ($reason)",
                        )
                        runCatching { live.reload() }
                    }
                }
            }.onFailure { logWebView("viewport verify failed: ${it.message}") }
        }, MODE_SWITCH_VERIFY_MS)
    }

    /**
     * 窗口尺寸变化后延迟重写 viewport meta（v1.5.1）。
     *
     * ## 为什么不 reload
     * 自由窗口**每拖动一像素** `screenWidthDp` 就会变，旧实现是
     * `onConfigurationChanged` 里无条件 `webView.reload()` —— 等于拖动过程中连续整页
     * 重载：卡顿、闪白，而且每次重载都要重走一遍启动遮罩链路。重写 meta 没有这些代价。
     *
     * 去抖 [VIEWPORT_SETTLE_MS]，并在真正执行时比对宽度：软键盘弹出、状态栏 insets
     * 变化只改高度，宽度没变直接跳过，不会误触发重排。
     */
    private fun scheduleViewportReapply(reason: String) {
        // v1.6.37：已退役实例不可再 postDelayed（post 到已 destroy 的 View 上，
        // 既可能丢任务，也可能在片段回收后回调到已死实例）。
        val wv = liveWebView() ?: return
        viewportReapply?.let { wv.removeCallbacks(it) }
        val task = Runnable {
            val live = liveWebView() ?: return@Runnable
            if (live.url == null) return@Runnable
            val w = live.width
            if (w <= 0) return@Runnable
            // 布局已完成 → 此时宽高就是新窗口的真实尺寸，可以安全判断渲染模式。
            val wantDesktop = shouldUseDesktop()
            if (wantDesktop != desktopMode) {
                logWebView("viewport mode switch: desktop=$wantDesktop w=$w ($reason)")
                switchRenderMode(live, wantDesktop, reason)
                return@Runnable
            }
            // 宽度没变（键盘弹出、状态栏 insets 变化只改高度）→ 不必重排
            if (w == lastViewportWidth) return@Runnable
            logWebView("viewport reapply: $reason w=$w")
            applyViewport(live)
        }
        viewportReapply = task
        wv.postDelayed(task, VIEWPORT_SETTLE_MS)
    }

    /**
     * 在启动遮罩之下丢弃整页栅格缓存并重新光栅化一次（v1.4.6）。
     *
     * ## 为什么需要
     * viewport meta 由渲染进程异步应用。页面首帧往往已经按 `initial-scale=1` 画完
     * （登录弹窗就在这一帧里），随后整页缩放突变；Android WebView 可能把部分合成层
     * （圆角裁剪卡片、`backdrop-filter` 遮罩、淡入动画层）留在旧缩放/旧尺寸的栅格里，
     * 表现为弹窗被拉宽、纵向截断、按钮只剩一个字（复现截图见本 PR 描述）。
     *
     * ## 做法
     * 切到 `LAYER_TYPE_SOFTWARE` 会强制丢弃全部已缓存栅格并整页软件重绘，再切回默认
     * （`LAYER_TYPE_NONE`）恢复硬件合成。两次切换都发生在 `dismissSplash()` 的等待
     * 窗口内（遮罩至少 `splashMinMs + 180ms` 后才开始淡出），用户看不到这次刷新。
     *
     * ## v1.6.20：为什么给本方法加撤销与去重
     * 一次 `LAYER_TYPE_SOFTWARE ↔ NONE` 往返 = **整页软件重栅格化**，代价随页面复杂度
     * 与设备而变（本环境无真机，具体毫秒不下结论）。旧实现每调用一次就多 `postDelayed`
     * 一个 Runnable，**不保存句柄、也不撤销旧的**，同一窗口内多次触发就会排队多次。
     * 按 Kotlin 源码语义 1:1 移植的调度仿真（dev/sim_reraster.js）测得的行为差异：
     *   · 连续拖窗口、常规转屏：旧 1 次 / 新 1 次 —— **无差异**，因为
     *     [scheduleViewportReapply] 自身的 120ms 去抖已经把多次 config 回调合并了；
     *   · 交错（已排队的 viewport 重排与紧随其后的 `onPageFinished` 落在同一个 120ms
     *     窗口）：旧 **2 次** → 新 **1 次**；
     *   · 额外一层：光栅状态（模式 × 视口宽 × 缩放）未变时直接跳过。
     * 新页面的首次重栅格化不会被吞：`onPageFinished` 会先把 [lastRerasterKey] 置空。
     *
     * @param view 目标 WebView。
     * @param reason 写入 `engine.log` 的触发来源，便于真机排查。
     */
    private fun scheduleReraster(view: WebView, reason: String) {
        // v1.6.20：撤销未完成的排队 + 光栅状态未发则跳过（行为差异见上方 KDoc）。
        val key = "$desktopMode|${view.width}|$pageScale"
        if (key == lastRerasterKey) {
            logWebView("reraster skipped (unchanged): $reason key=$key")
            return
        }
        rerasterPending?.let { view.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                rerasterPending = null
                if (!::webView.isInitialized) return
                val wv = webView
                lastRerasterKey = key
                wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                wv.post {
                    wv.setLayerType(View.LAYER_TYPE_NONE, null)
                    logWebView("reraster: $reason")
                }
            }
        }
        rerasterPending = task
        view.postDelayed(task, VIEWPORT_SETTLE_MS)
    }

    /** 未完成的重栅格化回调（用于撤销，避免拖动窗口时堆叠多次整页软绘） */
    private var rerasterPending: Runnable? = null

    /** 上一次真正执行过重栅格化的光栅状态键：`桌面模式|视口宽|缩放` */
    private var lastRerasterKey: String? = null

    /** 解压进度：null=不在解压（不确定转圈），有值=真实百分比确定性进度 */
    private fun renderProgress(frac: Float?) {
        val bar = findViewById<ProgressBar>(R.id.installProgress)
        if (frac == null) {
            bar.isIndeterminate = true
        } else {
            bar.isIndeterminate = false
            bar.progress = (frac * 10000).toInt()
        }
        // v1.6.19：**不再**从这里写启动遮罩的进度条。
        //
        // 原先有两条订阅同时写 `splashProgress`：本方法（安装总进度，口径是
        // 「下载 45% + 解压 55%」）与 `renderSplashTrace`（BootTrace 的**当前阶段**
        // 百分比，0..100%）。两个口径不同、回调时机也不同，同一控件被两边交替
        // 赋值 → 百分比来回跳。现在遮罩只由 BootTrace 驱动，顶部细条继续用
        // 安装总进度，各管一个控件。
    }

    // ==================== 启动遮罩（v1.3.1） ====================

    // ==================== 启动遮罩（v1.6.19 重做） ====================

    /**
     * 初始化启动遮罩。
     *
     * v1.6.19 起把状态、渲染、退出全部收进 [SplashController]，这里只做三件事：
     * 拿到根 View、把 debug 包的 `splash_min_ms` 覆盖传进去、把「忙不忙」与
     * 「看门狗触发」两个回调接上。旧实现在本类里堆了 15 个 splash 字段 +
     * 三套渲染路径，改一处要对照三处。
     */
    private fun setupSplash() {
        splashController?.dispose()

        val root = findViewById<android.view.View>(R.id.splash) ?: return
        splash = root
        splashDismissed = false

        // 仅 debug 包：允许用 `am start ... --el splash_min_ms 20000` 拉长遮罩
        // 停留时间，方便验证展开步骤后的布局。release 包这段不生效。
        val debuggable =
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val override = if (debuggable) intent?.getLongExtra("splash_min_ms", 0L) ?: 0L else 0L
        splashMinMs = override.takeIf { it > 0L } ?: 700L

        splashController = app.dsh.mobile.ui.SplashController(
            activity = this,
            root = root,
            minimumMs = splashMinMs,
            isBusy = {
                // 「等用户点下载」也算忙。
                //
                // 这是模拟器上复现出来的 bug：用户停在运行时下载面板上超过 45 秒，
                // 此时引擎状态还是 Idle（用户没点，引擎没起），看门狗判定
                // 「不忙 → 出问题了」→ 收掉遮罩。等用户再点下载，遮罩已经没了，
                // 只剩一片白 WebView + 顶部「Installing runtime…」。
                // 面板还在等用户决策，不该被当成卡死。
                val waitingUser = runtimeImporting
                val state = (application as DshApp).supervisor.state.value
                waitingUser ||
                    state is EngineSupervisor.State.Installing ||
                    state is EngineSupervisor.State.Starting
            },
            onWatchdog = { dismissSplash() },
            onHidden = {
                splash = null
                splashController = null
            },
        )
    }

    /** 更新遮罩上的阶段文案（兜底路径；正常渲染走 BootTrace）。 */
    private fun setSplashStatus(resId: Int) {
        splashController?.setFallbackStatus(resId)
    }

    /** 把 BootTrace 快照交给控制器渲染；遮罩已收时是空操作。 */
    private fun renderSplashTrace(snap: BootTrace.Snapshot) {
        if (!splashDismissed) splashController?.render(snap)
    }

    /**
     * 收起启动遮罩。
     *
     * 必须幂等：它会被 `onPageFinished`、看门狗超时、失败路径、控制器自身
     * 的退出动画多处调用，重复调用不能重复排退出动画。
     *
     * 最短展示时长（[splashMinMs]）由控制器内部计算 —— 它会等
     * `minimumMs + 180ms`（那 180ms 是给整页重栅格化留的窗口）。
     */
    private fun dismissSplash() {
        if (splashDismissed) {
            // 遮罩已被看门狗/失败路径提前收起：只补一次 WebUI 淡入，避免直接跳出。
            if (webUiReady) crossfadeWebUi()
            return
        }
        splashDismissed = true
        val controller = splashController
        val accepted = if (controller != null && webUiReady) {
            controller.dismiss(onFadeStart = { crossfadeWebUi() })
        } else {
            controller?.dismiss() ?: false
        }
        // controller 已 closing/为空时也必须淡入，否则会“割裂”地直接出现。
        if (!accepted && webUiReady) crossfadeWebUi()
    }

    /** 加载界面 → WebUI 的交叉溶解：等首帧绘制完成后再淡入，与遮罩淡出重叠。 */
    private fun crossfadeWebUi() {
        if (webUiCrossfaded) return
        webUiCrossfaded = true
        if (!::webView.isInitialized) return
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            webView.alpha = 1f
            webView.scaleX = 1f
            webView.scaleY = 1f
            return
        }
        val ease = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
        // v1.6.46：这段 360ms 是整个启动过程里唯一一次整屏 alpha 合成，
        // 用帧级数据记录它（debug 包才有实际采集，release 为空操作）。
        app.dsh.mobile.ui.FrameStats.begin("crossfade")
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.alpha = 0f
        webView.scaleX = 1.008f
        webView.scaleY = 1.008f
        // postOnAnimation 只保证过一帧；再等一帧，确保 WebView 首帧已合成，
        // 否则会出现“先黑一下再淡入”的割裂感。
        webView.postOnAnimation {
            webView.postOnAnimation {
                webView.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(360L)
                    .setInterpolator(ease)
                    .withEndAction {
                        webView.setLayerType(View.LAYER_TYPE_NONE, null)
                        app.dsh.mobile.ui.FrameStats.end("crossfade")
                    }
                    .start()
            }
        }
    }



    /**
     * 屏幕朝向：把用户偏好真正落到 requestedOrientation（v1.2.120 三态）。
     *
     * ⚠️ 竖屏必须显式用 SCREEN_ORIENTATION_PORTRAIT。SCREEN_ORIENTATION_UNSPECIFIED
     * 的语义是「交给系统/传感器决定」，**不是**「锁定竖屏」—— 旧实现给竖屏填的是
     * UNSPECIFIED，所以设置里选「竖屏」后物理旋转设备照样变横屏，看起来就是开关失灵。
     * auto 用 USER（跟随传感器且尊重系统旋转锁），见 OrientationPrefs.toActivityInfo。
     */
    private fun applyOrientation(mode: OrientationMode) {
        requestedOrientation = OrientationPrefs.toActivityInfo(mode)
        applyEdgeToEdge()
    }

    /**
     * Edge-to-Edge：状态栏保持显示但透明，导航栏透明，内容绘制到系统栏后方。
     *
     * 在 onCreate / onResume / onConfigurationChanged / onWindowFocusChanged
     * 以及页面加载完成后重复应用，防止部分 OEM 系统重置窗口布局。
     */
    private fun applyEdgeToEdge() {
        val w = window ?: return
        runCatching {
            // v1.6.47：顺序有讲究 —— 先按当前开关把「岛的位置、WebView 让位」
            // 装好（applyIslandInsets 会读一次开关并 requestApplyInsets），
            // 再落窗口级系统栏。反过来会让一次回调用旧开关算出旧位置。
            edgeToEdgeNow = edgeToEdgeEnabled(this)
            applyIslandInsets()
            ThemeManager.applyBars(this)
            // v1.6.50：这里同时决定状态栏可见性（默认隐藏）。
            // EdgeToEdge 门面委托给 Insets.applyWindow，而后者按偏好读
            // statusBarHidden —— 紧凑模式恒为 false（保留状态栏）。
            EdgeToEdge.applyWindow(this)
            updateStatusBarToggle()
            w.decorView.requestApplyInsets()
            webView.requestLayout()
            logWebView(
                "e2e: ${if (edgeToEdgeNow) "on" else "off"} " +
                    "statusBar=${if (statusBarHidden(this)) "hidden" else "shown"} " +
                    "(${describeEdgeToEdge()})",
            )
        }
    }

    /**
     * 状态栏（顶部通知栏）显隐切换（v1.6.50）。
     *
     * ## 为什么必须是"即时"的
     * 用户明确要求：所有空白与状态栏调整都靠 WindowInsets / View 属性实时生效，
     * **严禁重建 Activity、严禁重启引擎**。所以这里只做三件事：
     *   1. 翻偏好（下次冷启动/切页仍一致）；
     *   2. 重设窗口级系统栏可见性（[app.dsh.mobile.ui.Insets.applyWindow]）；
     *   3. 补一次 insets 派发（灵动岛位置、页面安全区都挂在那个回调上）。
     * 中间不调 `recreate()`、不碰 `supervisor`，WebView 里的文档与滚动位置不变。
     *
     * ## 紧凑模式为什么点不动
     * 非边到边（紧凑）模式下状态栏必须保留 —— 那是用户选的"带状态栏"形态。
     * 此时按钮本身是 GONE（见 [updateStatusBarToggle]），设置页里也置灰，
     * 所以这条分支只做一次日志，不会出现"点了没反应"的黑洞。
     */
    private fun toggleStatusBar() {
        if (!edgeToEdgeEnabled(this)) {
            logWebView("statusbar/toggle ignored: edge-to-edge off")
            return
        }
        val wantHidden = !statusBarHidden(this)
        setHideStatusBarEnabled(this, wantHidden)
        // 先落窗口，再让 insets 回调重算（顺序反了会用旧可见性算一次岛位置）
        applyEdgeToEdge()
        updateStatusBarToggle()
        logWebView(
            "statusbar/toggle hidden=$wantHidden " +
                "(immersive=${edgeToEdgeEnabled(this)}) ${describeEdgeToEdge()}",
        )
    }

    /**
     * 同步岛内「状态栏」按钮的外观（v1.6.50）。
     *
     * 三态：
     *   · 紧凑模式（非边到边）→ GONE：这条功能在那种形态下没有意义，
     *     留着只会是"点了没反应"；GONE 也不占胶囊宽度。
     *   · 边到边 + 已隐藏 → 斜杠图标 + 主色（"现在是无状态栏的沉浸态"）；
     *   · 边到边 + 显示中 → 完整状态栏图标 + 中性色。
     *
     * 幂等：每次 applyEdgeToEdge / onResume 都会调，只在值变了时才碰 View。
     */
    private fun updateStatusBarToggle() {
        val btn = findViewById<View>(R.id.btnStatusBar) ?: return
        val immersive = edgeToEdgeEnabled(this)
        val hidden = immersive && hideStatusBarEnabled(this)
        val wantVis = if (immersive) View.VISIBLE else View.GONE
        if (btn.visibility != wantVis) btn.visibility = wantVis
        if (!immersive) return
        val img = btn as? android.widget.ImageView
        if (img != null) {
            val wantIcon = if (hidden) R.drawable.ic_status_bar_off else R.drawable.ic_status_bar_on
            if (img.tag != wantIcon) {
                img.tag = wantIcon
                img.setImageResource(wantIcon)
                img.imageTintList = android.content.res.ColorStateList.valueOf(
                    getColor(if (hidden) R.color.island_accent else R.color.island_text),
                )
            }
        }
        btn.contentDescription = getString(
            if (hidden) R.string.btn_status_bar_show else R.string.btn_status_bar_hide,
        )
    }

    /** 一句话描述当前边到边状态，只用于日志（切换开关时能直接对上）。 */
    private fun describeEdgeToEdge(): String {
        val d = resources.displayMetrics.density
        val row = findViewById<View>(R.id.statusBarOverlay)
        val top = (row?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)
            ?.topMargin ?: -1
        val bottomMargin = (webView.layoutParams as? android.view.ViewGroup.MarginLayoutParams)
            ?.bottomMargin ?: -1
        return "islandTop=${(top / d).toInt()}dp wvBottom=${(bottomMargin / d).toInt()}dp"
    }

    /**
     * 顶部安全内边距：让灵动岛避开**状态栏与挖孔**（v1.4.43）。
     *
     * ## 为什么需要
     * 灵动岛（`statusBarRow` / `handleBar`）在布局里是 `layout_gravity="top"` +
     * `marginTop=10dp`，固定在窗口绝对顶部。开启全屏（隐藏状态栏）后：
     *   · 若不做处理，岛直接压在系统状态栏/挖孔上 —— 用户点不动它；
     *   · 若按「状态栏高度」下移，挖孔屏上会留一条无意义黑边。
     *
     * ## 做法
     * 监听 window insets，取 **statusBars ∪ displayCutout** 的 top 作为岛的下移量：
     *   · 显示状态栏 → top = 状态栏高度（含挖孔安全区），岛在下方；
     *   · 全屏 + 无挖孔 → top = 0，岛贴顶；
     *   · 全屏 + 挖孔屏 → top = 挖孔下沿高度，岛避开摄像头。
     *
     * `displayCutout` 的 insets 天然覆盖各种位置（中心孔 / 左孔 / 右孔 / 刘海）——
     * 系统给的是「该边被遮挡的最大深度」，与孔在哪一侧无关，所以我们不需要
     * 自己判断孔位，取 top 即可。
     *
     * 横屏时挖孔通常在左侧或右侧，此时 `displayCutout` 的 left/right 会大于 0、
     * top 可能为 0；岛是水平居中的，因此只需保证 top 安全，横向由居中布局自行避开。
     */
    /**
     * 把收起态正圆的**触控区**扩到 48dp 见方（v1.4.59）。
     *
     * ## 为什么需要
     * 摄像头中心离屏幕顶端只有 67px（真机），圆要完整可见就不能超过 134px 直径
     * —— 视觉上刚刚好，但换算成 dp 只有 38dp，**低于 Android 的 48dp 最小触控
     * 尺寸**。用户实测「我都点不到」。
     *
     * 单纯把 View 放大不行：直径超过「圆心到屏幕顶距离 × 2」就会溢出屏幕，
     * 而 clamp 到 top=0 又会把圆心推下去（用户报的「溢出 + 位置下移」）。
     *
     * ## 做法
     * View 尺寸保持视觉正确（贴住顶部、罩住摄像头），用 [TouchDelegate] 把可点
     * 区域向外扩到 48dp。父容器（FrameLayout）负责把落在扩展区的触摸事件转发
     * 给这个 View —— 这是 Android 官方支持的「小控件大热区」做法。
     *
     * 扩展区会超出父容器边界时（圆贴顶），TouchDelegate 的 rect 允许负数起点，
     * 父容器仍会按坐标命中；超出屏幕的部分自然无效，不影响可用性。
     */
    private fun applyHandleTouchTarget(handle: android.view.View, dotSize: Int) {
        val minTouch = (48 * resources.displayMetrics.density).toInt()
        if (dotSize >= minTouch) return   // 已经够大，不需要扩展
        val parent = handle.parent as? android.view.ViewGroup ?: return
        val grow = (minTouch - dotSize) / 2
        val rect = android.graphics.Rect(
            -grow,
            -grow,
            handle.width + grow,
            handle.height + grow,
        )
        parent.touchDelegate = android.view.TouchDelegate(rect, handle)
    }

    /**
     * 上一次应用的底部让位高度（物理 px）。
     *
     * 只用于把 `insets/ime …` 诊断行压成「变化时才打」—— insets 回调频率很高，
     * 不去重会把 engine.log 刷满。**不要**拿它当状态源判断当前是否让位。
     */
    private var lastBottomInsetPad = -1

    /**
     * 最近一次写进页面的**布局宽**（CSS px，v1.6.42）。
     *
     * 只用于 [verifyModeSwitch] 的自校验：软切换后读回 `clientWidth` 与它比对，
     * 不符才回退整页重载。不拿它当渲染状态的源 —— 模式真相看 [desktopMode]。
     */
    private var lastLayoutWidth = 0f

    /** 软切换校验的作废 token：快速来回转屏时只认最后一次校验。 */
    private var modeSwitchToken = 0

    /** 上一次是否处于"横屏桌面全幅"（v1.6.43）。用于 insets 变化时判断是否需要 teardown。 */
    private var lastFullBleedOn = false

    /**
     * 页面是否**已经接管顶部安全区**（v1.6.50）。
     *
     * 由 [installFullBleed] 的完成值反推：脚本扫到「贴边且够宽的 fixed/sticky 顶栏」
     * 才会给它加 `padding-top: var(--dsh-safe-top)`，返回串里的 `top=N` 就是命中数。
     *
     * 有两条互斥的让位路（见 insets 回调里 bleedOn 的说明）：
     *  · true → WebView 顶到 y=0，顶部让位由页面自己的顶栏吃下（空条消失）；
     *  · false → 页面结构不可预期（换了 UI 版本/插件改版），退回原生 topMargin，
     *    宁可多一条状态栏高度的空条，也不能让标题被状态栏和灵动岛压住。
     *
     * 新文档加载完会重新判定（导航会丢掉页面里的一切）。
     */
    @Volatile private var pageOwnsTopInset = false

    /**
     * 最近一次算出的页面让位量 `[top, left, right, bottom]`（CSS px，v1.6.43）。
     *
     * insets 回调里算好放在这里，[installFullBleed] 直接复用 —— 避免"重装时另算一遍"
     * 与"每次 insets 更新"两条路径的数值漂移（漂移的表现就是切换后位置对不上）。
     */
    private var lastInsetsCss: FloatArray? = null

    /** 上一次 full-bleed 的日志行（insets 回调频率极高，只在数值变化时打）。 */
    private var lastFullBleedLog = ""

    /** 最近一次注入网页的 safe-area（物理 px）。 */
    private var lastSafeBottom = -1
    private var lastSafeLeft = 0
    private var lastSafeRight = 0
    private var lastSafeTop = 0

    /**
     * 当前「边到边显示」开关状态（v1.6.47）。
     *
     * 由 [applyIslandInsets] 在安装监听器时读一次，供整个 insets 回调使用：
     * 回调在键盘动画、转屏、拖窗时会**每帧**触发，不能在回调里读
     * SharedPreferences（虽然命中内存缓存，但没必要）。
     */
    @Volatile private var edgeToEdgeNow = true

    /** 上一次见到的（未量化的）IME 让位高度，用于判断键盘是否还在动 */
    private var lastImeSeen = -1

    /** 最近一次真实 IME 高度（settle 时按它落精确值） */
    @Volatile private var lastRealImeBottom = 0

    /**
     * IME 让位量化（v1.6.48）。
     *
     * 把「每帧都变」的键盘高度收敛成 `IME_PAD_QUANTUM_PX` 的整数倍：
     * 视觉差 ≤ 8dp（键盘滑动的动画本身就有几十 dp/帧，这点误差看不出来），
     * 但 WebView 的高度变化次数从 30~60 降到 4~8，整页重排同比例减少。
     *
     * 归零必须精确：`want == 0` 时直接返回 0 —— 键盘收起后底部不能留缝隙。
     * 打开时最后会停在量化残差上（例如真实 1117、量化 1112，差 5px），
     * 由 [scheduleExactImeSettle] 在键盘**停稳**后补一次精确值。
     */
    private fun quantizeImePad(want: Int): Int {
        if (want <= 0) return 0
        val q = (IME_PAD_QUANTUM_DP * resources.displayMetrics.density).toInt().coerceAtLeast(8)
        return ((want + q / 2) / q) * q
    }

    /**
     * 紧凑模式（关闭边到边）的底部「触摸地板」（v1.6.50）。
     *
     * ## 为什么不是简单归零
     * 三键/两键导航条是**可点的实体按键区**：页面内容一旦铺到那里，那些像素
     * 既看不见（被按键盖住）也点不到（事件被系统拦走），输入框的发送键可能
     * 正好落在按键底下。所以：
     *   · 手势导航（条高通常 16~30dp）：只留 [COMPACT_BOTTOM_FLOOR_DP] ——
     *     手势区只拦「边缘上滑」，**点击是穿透的**，页面底栏的按钮仍然点得到；
     *   · 三键/两键导航（条高 40dp+）：整条让开（= v1.6.49 及以前的行为），
     *     宁可多一条空白，也不能让末排控件被按键压住。
     *
     * ## 判据
     * 两种导航方式都会上报 `navigationBars` inset（手势条也算在里面），
     * 唯一稳定的差别就是**高度**，所以按高度分流，不去读 `Settings.Secure`
     * （各家 ROM 的键名/取值都不一致，读不到就只能猜）。
     *
     * @return WebView 的 bottomMargin（物理 px）
     */
    private fun compactBottomFloor(navBottom: Int): Int {
        if (navBottom <= 0) return 0
        val d = resources.displayMetrics.density
        val gestureMax = (COMPACT_NAV_GESTURE_MAX_DP * d).toInt()
        return if (navBottom <= gestureMax) {
            (COMPACT_BOTTOM_FLOOR_DP * d).toInt()
        } else {
            navBottom
        }
    }

    /**
     * 键盘停稳后补一次**精确**让位（trailing）。
     *
     * 为什么需要：量化会把最终值停在 ±4dp 的残差上，键盘停住后那条缝隙会一直
     * 留着（页面底部露出 4dp 的空带）。这里在最后一次 insets 之后 90ms 再跑一次
     * `requestApplyInsets`，此时 `imeBottom` 已稳定，量化后的值恰好等于真实值
     * （因为 `want` 不再变化 → 一次命中），页面得到精确高度。
     *
     * 用单个 postDelayed + 覆盖式调度（不是每帧排一个），所以不会堆积。
     */
    private fun scheduleExactImeSettle() {
        if (!::webView.isInitialized) return
        webView.removeCallbacks(imeSettleRunnable)
        webView.postDelayed(imeSettleRunnable, IME_SETTLE_DELAY_MS)
    }

    private val imeSettleRunnable = Runnable {
        val wv = if (::webView.isInitialized) webView else null ?: return@Runnable
        val root = findViewById<android.view.View>(android.R.id.content) ?: return@Runnable
        // 上一次量化结果与「不再量化」的真实值不同时，直接按真实值落一次。
        val real = lastRealImeBottom
        val wlp = wv.layoutParams
        if (wlp is android.view.ViewGroup.MarginLayoutParams && wlp.bottomMargin != real) {
            wlp.bottomMargin = real
            wv.layoutParams = wlp
            lastBottomInsetPad = real
            logWebView("insets/ime settle exact=$real px (quantized to ${IME_PAD_QUANTUM_DP}dp steps)")
        }
        // 让 root 也重算一次（岛位置等仍按精确 insets）
        root.requestApplyInsets()
    }

    /** 比较两组 CSS 安全区是否一致（避免逐帧注入相同的值）。 */
    private fun sameInsetsCss(a: FloatArray?, b: FloatArray): Boolean {
        if (a == null || a.size != b.size) return false
        for (i in a.indices) if (kotlin.math.abs(a[i] - b[i]) > 0.01f) return false
        return true
    }

    private fun applyIslandInsets() {
        val root = findViewById<android.view.View>(android.R.id.content) ?: return
        val row = findViewById<android.view.View>(R.id.statusBarRow) ?: return
        val handle = findViewById<android.view.View>(R.id.handleBar) ?: return
        // v1.6.48：overlay 也在监听器**外**取一次。
        // insets 回调在键盘动画期间每帧触发，`findViewById` 是一次从根开始的
        // 递归树搜索 —— 放在回调里等于每帧白搜一遍（这个视图树还有 WebView，
        // 一旦 WebView 里挂的辅助视图多了，代价更大）。
        val overlay = findViewById<android.view.View>(R.id.statusBarOverlay)
        val d = resources.displayMetrics.density
        // v1.6.47：开关状态在这里**读一次**，供整个 insets 回调使用。
        // 回调在键盘动画/转屏时会每帧触发，不能每次都去读 SharedPreferences。
        edgeToEdgeNow = edgeToEdgeEnabled(this)
        // 灵动岛与它上方那条边的间距（v1.6.50）。
        //
        // 边到边：10dp —— 岛下面就是网页内容，留一点呼吸位看起来才不像"压上去"；
        // 紧凑：4dp —— 此时上方是系统状态栏，島只需要不贴住图标下沿；
        //        用户要的"减少顶部留白"，App 自己能控的就是这 6dp（状态栏本身是
        //        系统的，不属于我们能缩的范围）。
        val baseTop = (
            (if (edgeToEdgeNow) ISLAND_TOP_GAP_E2E_DP else ISLAND_TOP_GAP_COMPACT_DP) * d
            ).toInt()
        val rowH = (44 * d).toInt()
        // 收起态圆点边长：默认 26dp，可由「外观 → 收起态状态栏」调整。
        // 这里作为基准尺寸，下面的 wantDot 再按屏幕安全边界收敛。
        val handleH = (CustomVisualPref.islandDotSizeDp(this) * d).toInt()

        root.setOnApplyWindowInsetsListener { _, insets ->
            // 竖屏取 top：系统给的 top 是「顶边被遮挡的最大深度」。
            val cutInsets = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.displayCutout())
            } else {
                android.graphics.Insets.NONE
            }
            val cutTop = cutInsets.top
            // 横屏时挖孔在左/右；工具栏与网页 safe-area 都要按这个方向避让。
            val cutLeft = cutInsets.left
            val cutRight = cutInsets.right

            // ⚠️ v1.4.54（用户报「灵动岛收起来有时根本对不准摄像头，不同手机摄像头
            // 位置不同」）：只按屏幕水平居中是不够的 —— 挖孔可能在左侧、右侧、
            // 偏左偏右，居中摆放必然对不准。必须读**挖孔的真实矩形**再对齐。
            //
            // `insets.displayCutout` 只给「顶边遮挡深度」，不告诉你在哪个 x；
            // `DisplayCutout.boundingRects` 才是真实位置（实测本机
            // Rect(492,0-610,128)，中心 x=551，而屏幕中心是 540 —— 差 11px）。
            // 取所有 boundingRect 的**并集中心**作为岛的水平中心：
            //   · 单孔（左/中/右）：并集就是那个孔，精确对齐；
            //   · 双孔（如部分机型的药丸+副孔）：并集包住两者，岛罩住整段；
            //   · 无孔：返回 null，保持屏幕居中。
            // v1.4.58（真机反馈「还是不能贴紧摄像头、高度更高一点」）：
            // 之前用 `cutTop * 0.85` 估直径，但 `cutTop` 是「顶边被遮挡深度」，
            // 与摄像头**可见孔**的尺寸不完全一致：
            //   · 模拟器：cutTop=128，孔 Rect(492,0-610,128) → 宽 118 高 128
            //   · 真机（PLZ110）：cutTop=135，孔 Rect(568,0-648,135) → 宽 80 高 135
            // 按 0.85 缩放得到 114px 的圆，顶在 y=0、圆心 y=57，而孔中心在
            // y=67.5 —— 圆比孔中心高 10px，视觉上就被「顶上去」了。
            //
            // 正确做法：直接用 **boundingRect 的真实尺寸**，圆直径取孔的外接
            // 正方形（max(宽,高)），并让圆心对齐孔中心。
            val cutRect = if (android.os.Build.VERSION.SDK_INT >= 30) {
                val cutout = insets.displayCutout
                val rects = cutout?.boundingRects.orEmpty().filter {
                    it.width() > 0 && it.height() > 0
                }
                if (rects.isEmpty()) null else {
                    val l = rects.minOf { it.left }
                    val r = rects.maxOf { it.right }
                    val t = rects.minOf { it.top }
                    val b = rects.maxOf { it.bottom }
                    android.graphics.Rect(l, t, r, b)
                }
            } else null
            val cutCenterX = cutRect?.let { (it.left + it.right) / 2 }
            val cutCenterY = cutRect?.let { (it.top + it.bottom) / 2 }
            val cutHoleH = cutRect?.height() ?: cutTop
            val cutHoleW = cutRect?.width() ?: cutTop

            // ⚠️ v1.4.46（用户实测「关掉全屏后灵动岛被状态栏盖住、连设置都进不去」）：
            // 非全屏时岛绝不能只按固定 10dp 定位 —— 窗口从 y=0 开始，状态栏压在最
            // 上层，10dp 处的岛会被整个盖住、点不到（这是「无法进入设置」的根因）。
            // 必须用状态栏 insets 把岛推到状态栏下方。
            val statusTop = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop
            }

            // 全屏：岛贴屏幕最顶端（top=0），高度至少盖过挖孔 —— 黑色胶囊正好
            // 把摄像头罩住，这才是灵动岛该有的样子。
            // 非全屏：让开状态栏（statusTop）+ 常规 10dp 呼吸位。
            // ⚠️ v1.4.58（真机反馈「看着不跟摄像头」）：
            // `boundingRect` 是「被遮挡区域的外接矩形」，**不等于摄像头的形状**。
            // 真机实测 Rect(568,0-648,135)：宽 80、高 135 —— 摄像头本身是
            // 80 直径的圆，135 是系统为安全留的纵向深度。
            //
            // 早期版本按「高」做直径（135+余量=149），圆心被推到 74.5，
            // 而摄像头中心在 67.5 甚至更靠上 → 视觉上圆「比摄像头低/大一圈」，
            // 所以怎么看都不贴合。
            //
            // 正确做法（v1.4.58）：
            //   · 直径取 **min(宽,高)** = 摄像头真实尺寸（真机 80 → 兜底 91），
            //     不再用高度，避免做成覆盖半个状态栏的大黑饼；
            //   · 圆心 **对齐孔矩形的中心**（cutCenterY），而不是永远贴 y=0。
            //     摄像头靠边缘时，圆跟着上移；孔偏下时圆也跟着下移。
            //
            // 直径下限用 handleH（26dp）保证可点（88dp 触控目标的近似）。
            //
            // ⚠️ v1.4.59（真机反馈「收起来之后的大小怎么跟我摄像头基本一样大？
            // 我都点不到」「全屏模式下灵动岛会直接溢出屏幕，位置下移一点」）：
            // v1.4.58 把直径钉成 `min(宽,高)` = 摄像头本身的 80px，视觉上贴合了，
            // 但 80px ≈ 23dp，远低于 Android 48dp 触控下限 —— 手指根本点不中。
            // 而单纯放大又不行：摄像头中心离屏幕顶端只有 67px，直径一旦超过
            // 134px，圆的上沿就会跑到屏幕外（clamp 到 top=0 后又导致圆心下移，
            // 这就是用户说的「溢出屏幕 + 位置下移」）。
            //
            // 现在的做法是**分离视觉尺寸与触控尺寸**：
            //   · 视觉直径 = min(孔尺寸 + 余量, 圆心到屏幕顶距离 × 2)
            //     —— 保证圆完整可见、不溢出，同时比摄像头大一圈（真机 134px）；
            //   · 触控区由 TouchDelegate 额外扩到 48dp 见方（见 applyHandleTouchTarget）。
            // 这样「看起来比摄像头大一圈、贴住顶部、手指点得中」三者同时成立。
            val coverPad = (4 * d).toInt()
            val holeSize = maxOf(cutHoleW, cutHoleH) + coverPad
            // 圆心到屏幕顶的距离 = 孔中心 Y。直径不能超过它的两倍，否则溢出。
            val maxDotNoOverflow = if (cutCenterY != null && cutCenterY > 0) {
                cutCenterY * 2
            } else {
                Int.MAX_VALUE
            }
            // 尺寸以用户偏好为准（handleH 即偏好值），仅收敛到"不溢出屏幕顶部"的上限。
            // 历史实现会在沉浸态强制放大到能罩住挖孔，那样用户调小就不生效 ——
            // 尺寸既然开放给用户，就以用户意愿优先。
            val wantDot = handleH.coerceAtMost(maxDotNoOverflow)
            // 圆心对齐孔中心：top = 中心Y - 半径。clamp ≥0（孔贴顶时圆也贴顶）。
            // 状态栏保持显示：顶部位置取状态栏与挖孔的最大值，确保胶囊不被遮挡。
            //
            // v1.6.47：关掉边到边时岛不再叠一次状态栏高度（那时内容框原点
            // 已经在状态栏下沿）。
            //
            // v1.6.50：紧凑模式的**坐标原点改回屏幕顶**（两种模式都
            // decorFits=false，见 Insets.applyWindow）—— 所以这个特判必须收回：
            // 现在无论哪种模式，岛都要自己让开状态栏 ∪ 挖孔，否则会直接压在
            // 时钟/摄像头上（这正是"切开关后界面错位"那类回归的源头）。
            val safeTop = maxOf(statusTop, cutTop)
            val wantTop = safeTop + baseTop
            // 展开态是长条工具栏（v1.4.60 真机反馈「展开时还是没有向下移，
            // 会出现屏幕溢出，太贴边缘」）。
            //
            // 历史：早期把工具栏 top 设成 0（贴屏幕最顶），高度取孔高 —— 结果
            // 工具栏顶边正好卡在屏幕边缘，圆角被裁、图标贴着挖孔，视觉上「溢出」。
            //
            // 现在的规则：
            //   · top  = 孔的下沿（cutTop）+ 6dp 呼吸位 → 完全落在摄像头下方，
            //            不再与孔重叠，也不会被屏幕边缘裁掉圆角；
            //   · 高度 = 标准 rowH（44dp），不再为了「避开孔」而拉高 ——
            //            既然已经下移，就不需要额外高度。
            //   · 无挖孔时 top 用 statusTop + baseTop（与非全屏一致）。
            // v1.4.62（宝宝反馈「灵动岛往上移，起码上以灵动岛当前高度的 3/4」）：
            // 之前 top = 挖孔下沿 + 6dp（真机 156），整条工具栏都落在摄像头下方，
            // 视觉上「离屏幕顶太远」，不像灵动岛而像一条独立的工具条。
            //
            // 现在从那个位置**上移自身高度的 3/4**（真机 154×0.75 ≈ 115），
            // 顶边落到 y≈41 —— 工具栏上半部分与摄像头区域重叠，下半部分
            // 仍在孔下方，观感更接近真灵动岛（贴着顶部、包住镜头）。
            //
            // 为什么是 3/4 而不是全移：全移到 y=0 会贴屏幕边、圆角被裁
            // （v1.4.60 修过的「溢出」问题）；3/4 保留了顶部一点余量，
            // 同时把大部分内容提到摄像头高度上。
            val wantRowH = rowH
            val wantRowTop = safeTop + baseTop
            // ── 页面侧让位开关（v1.6.50）───────────────────────────────────
            //
            // 「让位」有两条路，同一时刻只走一条：
            //   A) 原生：缩 WebView（topMargin / bottomMargin）—— 简单，但缩掉的
            //      那条边露出的是窗口底色，于是**必然**是一条空条；
            //   B) 页面内：把量以 CSS 变量交给网页（[app.dsh.mobile.web.FullBleed]），
            //      由页面自己的顶/底栏吃下 padding —— 空条消失，滚动内容仍然铺满。
            //
            // A 只在必要时用：紧凑模式下页面**没接管顶部**（脚本没扫到贴边顶栏）
            // 时兜底 —— 宁可多一条空条，也不能让页面标题被状态栏和灵动岛压住。
            // E2E 竖屏维持 v1.6.43 的既有行为（不装针对竖屏的样式表），
            // 横屏桌面照旧走 B。
            val landDesktop = isLandscapeDesktop()
            val compact = !edgeToEdgeNow
            val bleedOn = landDesktop || (compact && pageOwnsTopInset)
            // 灵动岛下沿（物理 px）：页面顶部让位就按这条带高算。
            val pageBandPx = wantRowTop + wantRowH

            // 横向对齐（v1.4.54）：把「屏幕居中的重力」换成「按挖孔中心偏移」。
            //
            // 布局里是 layout_gravity="top|center_horizontal" —— 居中由 FrameLayout
            // 的 gravity 决定，光改 marginStart 没用（重力会把它再居中一次）。
            // 正确做法：把 gravity 改成纯 top（不居中），再用 marginStart 精确
            // 定位到挖孔中心。无挖孔时恢复居中。
            //
            // 偏移量 = 挖孔中心 - 屏幕中心。只对**收起态的正圆**应用：展开态的
            // 工具栏是全宽胶囊，横向本来就该铺满（且它的左 padding 已经留了
            // 文字空间），偏移反而会让左右留白不对称。
            // 状态栏可见时保持屏幕居中，不按挖孔横向偏移。
            val handleStart: Int? = null
            handle.layoutParams?.let { lp ->
                val m = lp as? android.view.ViewGroup.MarginLayoutParams
                if (m == null) return@let
                val flp = lp as? android.widget.FrameLayout.LayoutParams
                val wantGravity = if (handleStart == null) {
                    (android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL)
                } else {
                    android.view.Gravity.TOP or android.view.Gravity.START
                }
                var dirty = false
                if (m.topMargin != wantTop || lp.height != wantDot || lp.width != wantDot) {
                    m.topMargin = wantTop
                    lp.height = wantDot
                    lp.width = wantDot
                    dirty = true
                }
                if (flp != null) {
                    if (flp.gravity != wantGravity) { flp.gravity = wantGravity; dirty = true }
                    val wantStart = handleStart ?: 0
                    if (m.marginStart != wantStart) { m.marginStart = wantStart; dirty = true }
                }
                if (dirty) {
                    handle.layoutParams = lp
                    // 布局变了 → 触控区要跟着重算（等一次布局完成后再装）
                    handle.post { applyHandleTouchTarget(handle, wantDot) }
                }
            }
            // 工具栏用 wantRowTop（贴顶），圆用 wantTop（对齐孔中心）—— 两者不再混用。
            //
            // ⚠️ 位置与高度设在**外层 statusBarOverlay** 上，不是里面的 statusBarRow：
            // v1.5.x 给胶囊套了一层 FrameLayout 作为贴纸图层，overlay 自己带
            // layout_marginTop="10dp"、高度固定 44dp。若再把 topMargin 加到内层 row，
            // 两段边距叠加 + row 超出 44dp 容器 → 胶囊下半部分被裁掉（用户实测）。
            overlay?.layoutParams?.let { lp ->
                val m = lp as? android.view.ViewGroup.MarginLayoutParams
                val sideBase = (12 * d).toInt()
                // v1.6.47：关掉边到边时内容框已在挖孔内侧，胶囊不必再往里缩。
                val wantStart = sideBase + if (edgeToEdgeNow) cutLeft else 0
                val wantEnd = sideBase + if (edgeToEdgeNow) cutRight else 0
                if (m != null && (
                        m.topMargin != wantRowTop || lp.height != wantRowH ||
                            m.marginStart != wantStart || m.marginEnd != wantEnd
                        )
                ) {
                    m.topMargin = wantRowTop
                    m.marginStart = wantStart
                    m.marginEnd = wantEnd
                    lp.height = wantRowH
                    overlay.layoutParams = lp
                }
            }
            // 内层 row 撑满 overlay，不再自己管位置（否则又会和上面打架）
            row.layoutParams?.let { lp ->
                if (lp.height != android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                    lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    val m = lp as? android.view.ViewGroup.MarginLayoutParams
                    if (m != null) {
                        m.topMargin = 0
                        m.marginStart = 0
                    }
                    row.layoutParams = lp
                }
            }

            // WebView 的底部内边距 = max(IME, 系统导航区)。
            //
            // · IME（v1.4.46）：decorFitsSystemWindows=false 后系统不再替我们缩小
            //   窗口，键盘会直接盖住输入框 → 用 IME 高度把内容顶上去。
            // · 系统导航区（v1.4.51，用户报「部分手机屏幕下面横条会遮挡部分页面」）：
            //   全屏时窗口铺到屏幕最底，手势条 / 三键导航条会压住页面底部。
            //   之前只算 IME，键盘不在时 padding=0，底部内容就被横条挡住。
            //   两者取大：键盘弹出时用键盘高度（已包含导航条），否则用导航条高度。
            //
            // 只改 padding 不改 layoutParams：后者会触发 WebView 重新测量并闪白。
            // 背景色仍会填满 padding 区（WebView 自身背景透出），所以视觉上仍是全屏。
            // （v1.5.0-beta.6 起改为 bottomMargin：让位是否生效不能依赖 padding 是否
            //  被 Chromium 算进内容尺寸，见下方说明。）
            // ⚠️ v1.5.0-beta.6（vivo X300 Pro 真机实测）：`WindowInsets.Type.ime()`
            // **可能是恒 0 的** —— WM 侧 `InsetsSource type=ime visible=true
            // frame=[0,1683][1260,2800]`、`mInputShown=true`、本窗口就是
            // `imeLayeringTarget`，但监听器里 `insets.getInsets(ime()).bottom` 读出来
            // 是 0（诊断日志原文：`insets/ime pad=63 insets=0 frame=0 rootH=2800
            // visibleBottom=2737`）。于是 v1.4.65 的让位链路整条空转：WebView 一直是
            // 整屏，页面 `documentElement.clientHeight` 不变、`visualViewport` 也不变，
            // 引擎侧手机 UI 插件收不到任何键盘信号，输入框卡片停在 2800px 页面的底部
            // 被键盘整个盖住，正文一路顶到键盘工具栏。K70 不复现只是因为它那套
            // WebView/ROM 组合把 ime insets 正常下发了。
            //
            // 兜底：用 `getWindowVisibleDisplayFrame()` 反推被键盘占掉的高度 —— 它取的是
            // 窗口**可见区域**，与 fit-insets、与 ime insets 是否下发都无关，IME 遮住
            // 多少就少多少（实测 visibleBottom=1683 → 1117）。键盘高度不可能小于
            // 96dp，低于此值一律当 0，避免把导航条误判成键盘。
            val imeFromInsets = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            } else {
                // API 30 以下无法从 systemWindowInsetBottom 区分 IME 与导航栏；
                // 误判会让 WebView 底部永久留一条空白，这里交给 imeFromFrame 兜底。
                0
            }
            val visibleRect = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(visibleRect)
            val imeFromFrame = (root.height - visibleRect.bottom).let {
                if (it >= (96 * d).toInt()) it else 0
            }
            val imeBottom = maxOf(imeFromInsets, imeFromFrame)
            val navBottom = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(
                    android.view.WindowInsets.Type.navigationBars()
                        or android.view.WindowInsets.Type.systemGestures(),
                ).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            // 底部让位（v1.4.65，宝宝反馈「全屏模式下打开输入法，输入框没有上移
            // 导致根本看不了输入的文字」）。
            //
            // 旧实现 `if (immersive) maxOf(imeBottom, navBottom) else 0` 有两个错：
            //   ① 非全屏时直接 0 —— 但 adjustResize 在
            //      `setDecorFitsSystemWindows(false)` 之后**不生效**（窗口不再为
            //      任何系统栏让位），键盘照样盖住输入框；
            //   ② 全屏时用 max(IME, 导航区) —— 键盘收起时 navBottom 会留一条空白。
            //
            // v1.6.50：**两种模式的下边规则统一了**。
            //
            // 旧实现里紧凑模式靠 `decorFits=true` 让系统把内容框缩到导航栏上方 ——
            // 用户看到的就是底部一条主题色空条（就是这条被报成"底部留白"）。
            // 现在两种模式都是 decorFits=false，让位只能由我们自己给，于是：
            //   · 键盘弹起 → 让位 imeBottom（IME 高度已含导航区，直接顶到键盘上方）；
            //   · 键盘收起：
            //       - 边到边：0 —— 页面一直铺到底，手势条浮在页面内容上（
            //         页面底栏的位置由 --dsh-safe-bottom 控）；
            //       - 紧凑：只留一道“触摸地板”（[COMPACT_BOTTOM_FLOOR_DP]），
            //         不再让整条导航栏。这样底部空条从 24~48dp 收到 ≤8dp。
            // 页面内 safe-bottom：紧凑模式为 0（地板已由 WebView 边距给出，
            // 再注入就是二次让位）；边到边保持旧行为。
            val safeBottom = if (compact) 0 else if (imeBottom > 0) 0 else navBottom
            // v1.6.50：左右用系统栏 ∪ 挖孔，两种模式都算（紧凑模式也 decorFits=false，
            // 横屏挖孔/三键条会贴在侧边，不让开就会被压住）。
            val sysBars = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.systemBars())
            } else {
                android.graphics.Insets.NONE
            }
            val safeLeft = maxOf(cutLeft, sysBars.left)
            val safeRight = maxOf(cutRight, sysBars.right)
            // 页面侧顶部让位（物理 px）：只在页面接管了顶部时非 0（见 bleedOn）。
            val pageSafeTopPx = if (bleedOn) pageBandPx else 0
            if (safeBottom != lastSafeBottom || safeLeft != lastSafeLeft ||
                safeRight != lastSafeRight || pageSafeTopPx != lastSafeTop
            ) {
                lastSafeBottom = safeBottom
                lastSafeLeft = safeLeft
                lastSafeRight = safeRight
                lastSafeTop = pageSafeTopPx
                injectWebSafeArea(safeBottom, safeLeft, safeRight, pageSafeTopPx)
            }
            // 紧凑模式的底部地板：键盘优先，键盘收起时只留一道触摸余量。
            // 边到边：键盘收起时 0（页面到底，底栏位置交给页面自己）。
            val wantPad = when {
                imeBottom > 0 -> imeBottom
                compact -> compactBottomFloor(navBottom)
                else -> 0
            }
            // ── v1.6.48：IME 让位量化（键盘动画期间的重排风暴）───────────────
            //
            // ime insets 在键盘滑入/滑出的 250~300ms 里**每帧**变化，而改
            // WebView 的 bottomMargin 会让视图真的变矮 → `onSizeChanged` →
            // Chromium 重排整个视口 → 页面（长对话 DOM）整体重算样式与布局。
            // 一次键盘弹出 = 30~60 次整页重排，观感就是「键盘一动，页面就卡」。
            //
            // 量化到 [IME_PAD_QUANTUM_PX] 步进：视觉上（≤8dp）几乎不可辨，
            // 但重排次数从 30+ 降到 4~8 次。最后一次精确值由 trailing 兜底保证
            // （见下方 postDelayed），不会停在量化残差上留下一条缝隙。
            lastRealImeBottom = wantPad
            val bottomPad = quantizeImePad(wantPad)
            // 键盘值一变就重排一次 settle（覆盖式，不堆积）；停稳后补精确值。
            if (wantPad != lastImeSeen) {
                lastImeSeen = wantPad
                scheduleExactImeSettle()
            }

            // v1.4.63（宝宝反馈「网页感觉有点被压缩太狠了，还是改回全屏吧，
            // 到时候被遮挡就收起灵动岛即可」）：
            // 取消 WebView 的顶部留白，网页恢复真正全屏。
            //
            // 权衡（记录一下，避免以后又改回去）：
            //   · 留白（v1.4.61）：网页内容永不被岛遮，但视口矮了一整条工具栏
            //     （真机 154px ≈ 44dp），手机上本就紧张，压缩感明显；
            //   · 全屏（现在）：网页拿满整屏，岛浮在顶部。首屏顶部会被遮住一条，
            //     但**岛可以一键收起**（点岛上的文字区/把手），收起后就是纯净全屏。
            // 用户选择了后者 —— 交互上多一步，但默认视觉更开阔。
            //
            // 底部让位保留：导航条/键盘必须让位，否则输入框会被键盘盖住
            // （v1.4.46 修过的老问题）。
            // 顺手把可能残留的 topMargin 清零（老版本设过留白，升级后必须还原，
            // 否则网页仍被压缩 —— 这类"改回默认却没生效"的坑踩过一次）。
            // ⚠️ v1.5.0-beta.6：让位方式从 `setPadding` 改成 `bottomMargin`。
            // padding 只在 WebView **内部**留白，视图尺寸不变 → 不会触发
            // `onSizeChanged`，Chromium 是否重排视口全看它内部有没有把 padding 算进
            // 内容尺寸（各版本行为不一致，X300 实测就完全没重排）。bottomMargin 让视图
            // **真的变矮**，`onSizeChanged` 必然发生，Chromium 必须重排 —— 跨版本稳。
            // padding 同时归零，避免 padding 与 margin 双重让位。
            // 真机验收（X300，pad=1117）：页面 clientHeight 869 → 534 CSS px，
            // 输入框卡片底边正好落在 534（= 视口底），键盘不再遮挡。
            if (bottomPad != lastBottomInsetPad) {
                lastBottomInsetPad = bottomPad
                logWebView(
                    "insets/ime pad=$bottomPad insets=$imeFromInsets frame=$imeFromFrame " +
                        "rootH=${root.height} visibleBottom=${visibleRect.bottom}",
                )
            }
            val wlp = webView.layoutParams
            if (wlp is android.view.ViewGroup.MarginLayoutParams) {
                // ── 让位：容器永远满幅，让位交给页面内部（v1.6.43）──────────
                //
                // v1.6.42 在这里给 WebView 加了四边 margin，结果露出 Activity 的
                // windowBackground —— 横屏变成"上下左右各一条空白/黑边"。这是用户
                // 报的「大量空白区域」的直接原因。
                //
                // v1.6.50 的分工（两条让位路，见上方 bleedOn 的说明）：
                //   · WebView 的**底部**：键盘让位（必须缩，键盘浮在窗口之上）
                //     + 紧凑模式的一小截触摸地板；键盘收起且边到边时为 0。
                //   · WebView 的**顶部**：只在「页面没接管顶部」时才有值 ——
                //     紧凑模式下脚本没扫到贴边顶栏时，宁可自己缩出状态栏高度，
                //     也不能让页面标题被状态栏/灵动岛压住。
                //   · 左右恒为 0：页面背景永远铺满整屏（v1.6.43 的结论）。
                //   · 页面内部：由 [app.dsh.mobile.web.FullBleed] 注入安全区，
                //     只挪页面自己的顶/底/侧栏，滚动内容仍延伸到屏幕边缘。
                val wantTopMargin = if (compact && !pageOwnsTopInset) safeTop else 0
                val dirty = wlp.topMargin != wantTopMargin || wlp.bottomMargin != bottomPad ||
                    wlp.marginStart != 0 || wlp.marginEnd != 0
                if (dirty) {
                    wlp.topMargin = wantTopMargin
                    wlp.bottomMargin = bottomPad
                    wlp.marginStart = 0
                    wlp.marginEnd = 0
                    webView.layoutParams = wlp
                    if (wantTopMargin != 0) {
                        logFullBleed("insets/top fallback margin=${wantTopMargin}px (页面未接管顶部)")
                    }
                }

                // 页面内部让位量（CSS px）。
                val density = resources.displayMetrics.density
                fun css(px: Int) = (px / density).coerceAtLeast(0f)
                val cssTop = if (bleedOn) css(pageBandPx) else 0f
                // 底部：横屏桌面（边到边）要让开手势条；紧凑模式的地板已由
                // bottomMargin 给出，再注入就是二次让位。
                val cssBottom = if (landDesktop && !compact) css(maxOf(navBottom, 0)) else 0f
                val cssLeft = if (bleedOn) css(safeLeft) else 0f
                val cssRight = if (bleedOn) css(safeRight) else 0f
                val insetsCss = floatArrayOf(cssTop, cssLeft, cssRight, cssBottom)
                // v1.6.48：值没变就不注入。写 4 个 CSS 变量会失效整页样式缓存
                // （`var()` 用在 padding 上），横屏桌面时这曾是一帧一次的整页重排。
                val changed = !sameInsetsCss(lastInsetsCss, insetsCss)
                lastInsetsCss = insetsCss
                if (changed && (bleedOn || lastFullBleedOn)) {
                    app.dsh.mobile.web.FullBleed.updateInsets(webView, insetsCss)
                }
                lastFullBleedOn = bleedOn
                // 日志：仍然只在「值真的变了」时拼串（每帧拼一次字符串也是白费）
                if (changed) {
                    logFullBleed(
                        "insets/fullbleed land=$landDesktop bleed=$bleedOn top=$cssTop " +
                            "start=$cssLeft end=$cssRight bottom=$cssBottom px " +
                            "(island=$pageBandPx nav=$navBottom ime=$bottomPad " +
                            "wvTop=$wantTopMargin)",
                    )
                }
            }
            if (webView.paddingBottom != 0) webView.setPadding(0, 0, 0, 0)
            insets
        }
        root.requestApplyInsets()
        // v1.4.54：handle 与 row 的 insets 都挂在 content 上，但 GONE 的
        // 视图不会因 requestApplyInsets 收到新的布局参数；可见后补一次。
        findViewById<android.view.View>(R.id.handleBar)?.let { h ->
            if (h.visibility == android.view.View.VISIBLE) h.requestApplyInsets()
        }
    }

    /** 把系统安全区以 CSS 变量交给网页；不强制加内边距，避免底部出现空白。 */
    /**
     * 安装/卸载页面侧的「全幅铺满」（v1.6.43，v1.6.50 扩到紧凑竖屏）。
     *
     * 只在两个时机调用：`onPageFinished`（新文档，必须重装）与模式切换。
     * 普通的 insets 变化走 [app.dsh.mobile.web.FullBleed.updateInsets] 的轻量路径，
     * 不会走到这里 —— 否则键盘动画期间每帧重扫 DOM。
     *
     * ## 什么时候装（v1.6.50）
     *  · 横屏桌面（沿用 v1.6.43）：装，且**允许**解除页面外壳的窄化（`shell=true`）；
     *  · 紧凑模式的竖屏：也装，但 `shell=false` —— 只要安全区让位，
     *    不解外壳容器的 `max-width`/居中（竖屏手机本来就没被窄化，动了只会引入
     *    与 v1.6.43 之前一样的排版抖动）；
     *  · 其余情况（边到边竖屏）：`on=false` → teardown，与改动前逐像素一致。
     *
     * ## 完成值的第二个用途：判定页面是否接管了顶部
     * 脚本返回 `on freed=N safe=M vw=W top=.. bottom=..`。`top` 是"贴边顶栏命中数"：
     *  · >0 → 页面的顶栏会自己吃下 `padding-top`，WebView 可以顶到 y=0；
     *  · 0  → 页面没有可识别的顶栏，退回原生 topMargin（见 insets 回调）。
     * 这一步把"能不能把状态栏那条空条还给页面"的决定权交给**实测**，
     * 而不是猜 DOM 结构 —— 引擎换 UI 版本时最多退回旧观感，不会压住标题。
     */
    private fun installFullBleed(view: WebView, reason: String) {
        if (!::webView.isInitialized) return
        // 紧凑模式（关闭边到边）的竖屏也要装：用户要的就是"顶部那条空条还给页面"。
        val compact = !edgeToEdgeEnabled(this)
        val landDesktop = isLandscapeDesktop()
        val on = landDesktop || compact
        // 卸载时同步清掉接管标记：否则下次切回紧凑模式会先按"页面已接管"排一帧，
        // 而那份文档其实还没装上样式表。
        if (!on) pageOwnsTopInset = false
        // 竖向/横向用同一份数值源：优先复用 insets 回调算好的那组；
        // 若回调还没来过（极早的 onPageFinished），退回按布局实测的岛带高估算。
        val density = resources.displayMetrics.density
        fun css(px: Int) = (px / density).coerceAtLeast(0f)
        val insetsCss = if (!on) {
            floatArrayOf(0f, 0f, 0f, 0f)
        } else {
            // v1.6.50：`lastSafeTop` 现在**就是**页面侧顶部让位量（灵动岛下沿），
            // 不能再叠加 islandBandPx()（那是 v1.6.43 的旧语义，会多让一条带高）。
            lastInsetsCss ?: floatArrayOf(
                css(lastSafeTop),
                0f,
                0f,
                css(lastSafeBottom),
            )
        }
        app.dsh.mobile.web.FullBleed.install(
            assets, view, on, insetsCss, shell = landDesktop,
        ) { result ->
            logWebView("fullbleed/install on=$on ($reason) -> $result")
            if (on) applyTopOwnership(result)
        }
        lastFullBleedOn = on
    }

    /**
     * 由脚本完成值判定「页面是否接管了顶部」，必要时切换让位路（v1.6.50）。
     *
     * 只在**紧凑模式**下起作用：边到边模式不装竖屏样式表，页面不可能接管顶部，
     * 一直是原生 topMargin=0（页面顶到 y=0）。横屏桌面同理走 CSS，top 恒 >0。
     *
     * 切换后要补一次 insets 派发：两条路的切换会改 WebView 的 topMargin，
     * 而 insets 值本身没变 —— 不等下一次键盘/转屏就永远不生效。
     */
    private fun applyTopOwnership(result: String) {
        val hit = Regex("""(?:^|\s)top=(\d+)""").find(result)?.groupValues?.get(1)?.toIntOrNull()
        val owns = (hit ?: 0) > 0
        if (owns == pageOwnsTopInset) return
        pageOwnsTopInset = owns
        logFullBleed(
            "top-ownership ${if (owns) "page" else "native"} (topHits=${hit ?: -1})",
        )
        findViewById<View>(android.R.id.content)?.requestApplyInsets()
    }

    /** 灵动岛带高（物理 px）：状态栏留白 + 展开态工具栏。让位量按展开态固定，点岛不重排。 */
    private fun islandBandPx(): Int {
        val row = findViewById<View>(R.id.statusBarOverlay) ?: return 0
        val top = (row.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        return top + row.height
    }

    /**
     * full-bleed 让位日志（v1.6.43）：只在数值变化时打一行。
     *
     * insets 回调在键盘动画期间每帧都可能触发，无条件打会瞬间刷满 logcat 并
     * 拖慢 IO —— 这里做整行去重，真机上 `logcat -s DshWebView | grep fullbleed`
     * 就能看到完整的让位演进序列。
     */
    private fun logFullBleed(line: String) {
        if (line == lastFullBleedLog) return
        lastFullBleedLog = line
        logWebView(line)
    }

    private fun injectWebSafeArea(bottomPx: Int, leftPx: Int, rightPx: Int, topPx: Int) {
        if (!::webView.isInitialized) return
        // v1.6.43：统一由 [app.dsh.mobile.web.FullBleed] 写 --dsh-safe-*，
        // 避免两处各写一遍导致数值互相覆盖（旧实现内联拼 JS，重复且易漂移）。
        val density = resources.displayMetrics.density
        fun css(px: Int) = (px / density).coerceAtLeast(0f)
        app.dsh.mobile.web.FullBleed.updateInsets(
            webView,
            floatArrayOf(css(topPx), css(leftPx), css(rightPx), css(bottomPx)),
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyEdgeToEdge()
        }
    }

    /** 启动阶段秒表：引擎启动本身耗时（真机可达十几秒），显示已用秒数避免被误判为卡死 */
    private var startTicker: Runnable? = null

    private fun stopStartTicker() {
        startTicker?.let { statusBar.removeCallbacks(it) }
        startTicker = null
    }

    private fun startStartTicker() {
        if (startTicker != null) return
        val t0 = System.currentTimeMillis()
        val r = object : Runnable {
            override fun run() {
                val sec = (System.currentTimeMillis() - t0) / 1000
                statusBar.text = getString(R.string.status_starting) + " " + sec + "s"
                statusBar.postDelayed(this, 1000)
            }
        }
        startTicker = r
        statusBar.postDelayed(r, 1000)
    }

    private fun render(state: EngineSupervisor.State) {
        // ⚠️ v1.2.187（用户反馈「状态栏退出不彻底，后台仍以 380MB 运行、耗电」）：
        // 用户在通知栏点「退出」后，引擎停了但 Activity 还活着 —— WebView 与它
        // 的渲染进程继续占着 ~380MB。收到**显式退出**信号就结束任务栈：
        // finishAndRemoveTask → onDestroy → webView.destroy()，内存随之释放。
        // v1.4.30：重启按钮改成图标（文字按钮把顶部胶囊挤满，状态文字被截成
        // "Engine r..."）。图标没有文字位，改用 contentDescription 表达「启动/重启」
        // 两态，保住原来的可发现性（读屏与长按提示都能读到）。
        // v1.6.47：图标钮没有文字位，长按提示（tooltip）与读屏文案必须同步两态 ——
        // 只改 contentDescription 会让长按提示永远停在「重启」，与「启动引擎」矛盾。
        val restartLabel = getString(
            if (state is EngineSupervisor.State.Stopped) R.string.btn_start_engine
            else R.string.btn_restart,
        )
        findViewById<View>(R.id.btnRestart).apply {
            contentDescription = restartLabel
            tooltipText = restartLabel
        }
        val bar = findViewById<ProgressBar>(R.id.installProgress)
        bar.visibility =
            if (state is EngineSupervisor.State.Installing || state is EngineSupervisor.State.Starting)
                View.VISIBLE else View.GONE
        if (state !is EngineSupervisor.State.Starting) stopStartTicker()
        // 启动遮罩：失败/停止时兜底（不能让用户卡在遮罩里看不到错误）。
        // v1.4.8：阶段文案改由 BootTrace 驱动（renderSplashTrace，订阅 + 秒表），
        // 这里只在「没有启动记录」时给一句静态兜底，避免两套文案互相覆盖。
        when (state) {
            is EngineSupervisor.State.Installing, is EngineSupervisor.State.Starting -> {
                if (BootTrace.snapshot.value.startedAt <= 0) {
                    setSplashStatus(
                        if (state is EngineSupervisor.State.Installing) R.string.splash_preparing
                        else R.string.splash_starting,
                    )
                }
            }
            is EngineSupervisor.State.Healthy, is EngineSupervisor.State.SafeMode -> {
                if (BootTrace.snapshot.value.startedAt <= 0) {
                    setSplashStatus(R.string.splash_loading)
                }
            }
            is EngineSupervisor.State.Failed, is EngineSupervisor.State.Backoff,
            is EngineSupervisor.State.Stopped -> dismissSplash()
            else -> Unit
        }
        statusBar.text = when (state) {
            is EngineSupervisor.State.Idle -> getString(R.string.status_idle)
            is EngineSupervisor.State.Installing -> getString(R.string.status_installing)
            is EngineSupervisor.State.Starting -> getString(R.string.status_starting).also { startStartTicker() }
            is EngineSupervisor.State.Healthy -> {
                if (pendingBrowserFallback) {
                    // v1.6.8：用户在门禁里选了「用浏览器打开」，现在有带 token 的地址了。
                    // 走 external browser 而不是 WebView —— 前者内核对，能真正渲染。
                    pendingBrowserFallback = false
                    val u = (application as DshApp).supervisor.webUrl()
                    if (u.isNotEmpty()) openInExternalBrowser(u)
                }
                if (!urlLoaded) {
                    val ps = PluginBootBus.state.value
                    val waitingPlugins = webReloadPending &&
                        (ps == PluginBootBus.State.HOLDING || ps == PluginBootBus.State.LOADING)
                    if (!waitingPlugins) {
                        webReloadPending = false
                        urlLoaded = true
                        // v1.4.46（安全审查 H2）：拿不到带 token 的地址时**不加载**，
                        // 否则可能把 WebView 指向占了 3080 的第三方页面。
                        val u = (application as DshApp).supervisor.webUrl()
                        if (u.isNotEmpty()) loadLocalUrl(u) else urlLoaded = false
                    }
                }
                state.recoveredPlugin?.let { getString(R.string.status_plugin_recovered, it) }
                    ?: getString(R.string.status_healthy)
            }
            is EngineSupervisor.State.SafeMode -> {
                if (pendingBrowserFallback) {
                    pendingBrowserFallback = false
                    val u = (application as DshApp).supervisor.webUrl()
                    if (u.isNotEmpty()) openInExternalBrowser(u)
                }
                if (!urlLoaded) {
                    val ps = PluginBootBus.state.value
                    val waitingPlugins = webReloadPending &&
                        (ps == PluginBootBus.State.HOLDING || ps == PluginBootBus.State.LOADING)
                    if (!waitingPlugins) {
                        webReloadPending = false
                        urlLoaded = true
                        val u = (application as DshApp).supervisor.webUrl()
                        if (u.isNotEmpty()) loadLocalUrl(u) else urlLoaded = false
                    }
                }
                state.recoveredPlugin?.let { getString(R.string.status_plugin_recovered, it) }
                    ?: getString(R.string.status_safe_mode)
            }
            is EngineSupervisor.State.Backoff ->
                getString(R.string.status_backoff, state.delayMs / 1000, state.attempt)
            is EngineSupervisor.State.Failed -> getString(R.string.status_failed, state.reason)
            is EngineSupervisor.State.Stopped -> {
                urlLoaded = false
                getString(R.string.status_idle)
            }
        }
    }

    /**
     * 系统返回：手机形态走三段逻辑，其它形态保持原样。
     *
     * 1. `mobile && consumable && canGoBack()` → `goBack()`。`consumable` 是插件
     *    按「自己压了几条同文档条目」上报的，因此这一步不会退到跨文档条目，
     *    也就不会出现整页重载。
     * 2. `mobile` 但没条目可退 → 双击退出：首次轻提示，2 秒内再按才 `finish()`。
     * 3. 非手机形态（桌面 / 原生预设、纯浏览器场景）→ 完全保持 v1.5.x 行为。
     *
     * 注意：`consumable=true` 但 `canGoBack()=false` 时也要走第 2 段 —— 状态与
     * WebView 历史短暂不同步时，宁可提示一次，也不能退到未知条目上。
     */
    override fun onBackPressed() {
        // v1.6.15：先问页面「有没有东西可关」，再考虑历史，最后才退出。
        //
        // 顺序为什么是这样：WebUI 是单页应用，切页面不写浏览器历史（实测
        // `pushState`/`popstate` 出现 0 次），所以 `canGoBack()` 在插件页、
        // 自动化任务页、SSH 工作区这些整页界面上恒为 false —— 旧逻辑会直接
        // `super.onBackPressed()` 退出到桌面，用户感受是「返回键没用，直接退出了」。
        //
        // 但也不能把「问页面」放到历史之后：历史里可能留着上一轮导航的条目
        // （比如从某个深链接进来），先 goBack() 会把用户带到一个不相干的位置，
        // 而当前浮层还开着。
        askBackGuard {
            // ── 手机形态（PR #95）：插件上报「当前有没有同文档条目可退」 ──
            //
            // 不能只信 canGoBack()：WebView 历史里可能留着跨文档条目（启动重定向、
            // runtime 跳转、外链），退过去就是整页重载。只有插件明确说「有条目」
            // 时才 goBack。
            if (backMobile) {
                if (backConsumable && webView.canGoBack() && backTargetIsRealPage()) {
                    webView.goBack()
                    return@askBackGuard
                }
                // 没有可退条目 → 双击退出：首次轻提示，2 秒内再按才 finish()。
                // 注意 consumable=true 但 canGoBack()=false 也要走这里 —— 状态与
                // WebView 历史短暂不同步时，宁可提示一次，也不能退到未知条目上。
                val now = android.os.SystemClock.elapsedRealtime()
                if (lastBackAt > 0L && now - lastBackAt < BACK_EXIT_WINDOW_MS) {
                    lastBackAt = 0L
                    finish()
                    return@askBackGuard
                }
                lastBackAt = now
                Toast.makeText(this, R.string.back_exit_hint, Toast.LENGTH_SHORT).show()
                return@askBackGuard
            }
            // 关键：`canGoBack()` 为 true 不等于「有上一个界面」。
            //
            // 预热时我们往 WebView 里 loadUrl("about:blank")，它**占掉一条历史条目**。
            // 于是真实首页加载完成后 canGoBack() 恒为 true，而 goBack() 的目标正是
            // 那个空白占位页 —— 用户看到的是「右滑一下整个界面变白，像退出网页」。
            // 必须先确认上一条历史是真实页面，否则一律按「没有上一页」处理。
            if (webView.canGoBack() && backTargetIsRealPage()) {
                webView.goBack()
            } else {
                super.onBackPressed()
            }
        }
    }

    /**
     * 历史栈里的「上一条」是不是真实页面（而非预热占位页）。
     *
     * [WebView.copyBackForwardList] 在当前索引为 0 时返回空列表，所以还要
     * 单独判一次 `currentIndex > 0`：列表为空或索引不大于 0 都说明没有上一页。
     *
     * 为什么不直接用 URL 判断：回环页可能是 `http://127.0.0.1:3080/#/xxx` 等各种
     * 形态，而**预热页只有 `about:blank` 一种**。所以只判「是不是空白页」，
     * 更窄也更稳 —— 任何非 about:blank 的历史条目都当真实页面。
     */
    private fun backTargetIsRealPage(): Boolean {
        val list = runCatching { webView.copyBackForwardList() }.getOrNull() ?: return false
        val idx = list.currentIndex
        if (idx <= 0) return false
        val prev = list.getItemAtIndex(idx - 1) ?: return false
        val u = prev.url ?: return false
        return u != preheatUrl && u != "about:blank"
    }

    /**
     * 调页面里的 `__dshBackGuard.back()`，问它「当前有没有可关闭的界面」。
     *
     * 注意这是**异步**的（`evaluateJavascript` 没有同步返回值），而
     * `onBackPressed()` 必须同步决定是否 `super.onBackPressed()`。所以这里
     * 先同步把「要不要退出」压住，等回调回来再决定 —— 否则会出现「已经退出、
     * 回调才说该关浮层」的竞态。
     *
     * 页面未注入（引擎还没起来、或注入失败）时按「没处理」走原逻辑，
     * 不能因为兜底脚本挂了就让返回键彻底失效。
     */
    private fun askBackGuard(onNotHandled: () -> Unit) {
        if (webView.url.isNullOrEmpty()) {
            onNotHandled()
            return
        }
        var decided = false
        val timeout = Runnable {
            if (decided) return@Runnable
            decided = true
            runOnUiThread { onNotHandled() }
        }
        webView.postDelayed(timeout, 260L)
        runCatching {
            webView.evaluateJavascript(
                "(function(){try{return !!(window.__dshBackGuard&&window.__dshBackGuard.back())}" +
                    "catch(e){return false}})()",
            ) { raw ->
                if (decided) return@evaluateJavascript
                decided = true
                webView.removeCallbacks(timeout)
                val handled = raw.trim().trim('"') == "true"
                if (!handled) runOnUiThread { onNotHandled() }
            }
        }.onFailure {
            if (!decided) {
                decided = true
                webView.removeCallbacks(timeout)
                onNotHandled()
            }
        }
    }

    /**
     * 文件选择器结果回传。
     *
     * ⚠️ 必须调用 onReceiveValue（哪怕是 null）：WebView 在回调前会**挂起页面的文件选择
     * 请求**，不调用则页面永久等待（表现为「点了上传没反应」）。用户取消时传 null 即取消。
     */
    @Deprecated("startActivityForResult is the established contract for the WebView file chooser")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == RuntimeImporter.REQ_PICK_ZIP) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                RuntimeImporter.persistRead(this, uri)
                runtimeImporting = true
                setSplashStatus(R.string.runtime_import_progress)
                RuntimeImporter.import(
                    this,
                    RuntimeImporter.Source("runtime.zip", uri),
                    onDone = {
                        runtimeImporting = false
                        EngineService.start(this)
                    },
                    onError = { e ->
                        runtimeImporting = false
                        Toast.makeText(
                            this,
                            getString(R.string.runtime_import_failed, e.message ?: e.javaClass.simpleName),
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            } else {
                Toast.makeText(this, R.string.runtime_import_cancelled, Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode == app.dsh.mobile.engine.cap.ScreenRecordRelay.REQ_CAPTURE) {
            // 屏幕录制授权：系统弹窗的「开始录制」结果只能在这里收到，转交给 RecordCap。
            // 打日志是因为授权链路一旦断了，从外部只看到「permission_pending 永远 false」，
            // 无法区分「没回调」与「回调了但 resultCode 不是 OK」—— 这两种要修的地方完全不同。
            android.util.Log.i(
                "DshRecord",
                "record auth result: code=$resultCode data=${data != null} " +
                    "extras=${data?.extras?.keySet()?.joinToString(",") ?: ""}",
            )
            app.dsh.mobile.engine.cap.ScreenRecordRelay.onResult(resultCode, data)
            return
        }
        if (requestCode == REQ_FILE_CHOOSER) {
            val cb = pendingFileCallback
            pendingFileCallback = null
            if (cb == null) {
                super.onActivityResult(requestCode, resultCode, data)
                return
            }
            val uris: Array<Uri>? = when {
                resultCode != RESULT_OK -> null
                else -> {
                    val fromData = android.webkit.WebChromeClient.FileChooserParams
                        .parseResult(resultCode, data)
                    // 部分 ROM 的选择器把结果放在 clipData（多选/分享式选择器），parseResult 拿不到
                    if (fromData != null && fromData.isNotEmpty()) fromData
                    else data?.clipData?.let { cd -> Array(cd.itemCount) { cd.getItemAt(it).uri } }
                        ?: data?.data?.let { arrayOf(it) }
                }
            }
            runCatching { cb.onReceiveValue(uris) }
                .onFailure { logWebView("file chooser result delivery failed: ${it.message}") }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    // ==================== 语音输入（v1.2.58 起，v1.2.65 移交服务托管） ====================
    //
    // 麦克风入口、识别编排、输入面板都在 DshAccessibilityService 侧
    // （见其 startVoiceInput / VoicePanel）—— 因为它们需要 TYPE_ACCESSIBILITY_OVERLAY
    // 窗口，且不应依赖本 Activity 存活。
    // 本 Activity 只负责两件事：
    //   ① 注册「把文字送进 WebUI 输入框」的能力（VoiceBridge.registerSender）
    //   ② 用户从主界面发起语音时申请麦克风权限（onRequestPermissionsResult 回调里
    //      转交服务；requestPermissions 是 Activity 才能做的事）

    /**
     * 发送语音文本：写进 WebUI 输入框并触发发送。
     * 走 DOM 注入（而非引擎 API），因为要复用前端既有的发送流程。
     */
    /**
     * 发送语音文本：写进 WebUI 输入框并触发发送。
     *
     * ⚠️ v1.2.65：按**实际结果**反馈，不再无条件弹「已发送」——
     * 旧版注入失败（如停在首页无会话）也报成功，用户实测「实际无作用」却看到「已发送」。
     *  · ok:*            → 已发送
     *  · entering-session→ 已在进入会话，稍后自动重发一次
     *  · no-input-found  → 明确提示「当前没有可发送的会话」
     *  · err:*           → 报具体错误
     */
    /**
     * 发送去重守卫（v1.2.90）。
     *
     * 🔴 主人实测「语音输入还是一次性复制两段发出去」。
     * 除了面板按钮的 consumed 守卫，这里再按**文本内容 + 时间窗**兜一层：
     * 相同文本在 [DEDUP_WINDOW_MS] 内只允许发送一次。
     *
     * 为什么需要两层：重复可能来自不同入口（面板按钮重复触发、服务回调重入、
     * 重试路径），单堵一处不够。这一层是**最终防线**，且对"用户真的想连说两遍
     * 同样的话"影响很小（1.5 秒内几乎不可能刻意重复）。
     */
    @Volatile private var lastSentText: String = ""
    @Volatile private var lastSentAt: Long = 0L

    private fun sendVoiceText(text: String) {
        val now = System.currentTimeMillis()
        if (text == lastSentText && now - lastSentAt < DEDUP_WINDOW_MS) {
            logWebView("voice send deduped: '$text' within ${now - lastSentAt}ms")
            return
        }
        lastSentText = text
        lastSentAt = now
        sendVoiceTextOnce(text, allowRetry = true)
    }

    private fun sendVoiceTextOnce(text: String, allowRetry: Boolean) {
        val js = buildVoiceInputJs(text)
        webView.evaluateJavascript(js) { raw ->
            val result = jsResult(raw)
            logWebView("voice send: $result")
            when {
                result.startsWith("ok:") -> {
                    // v1.2.95：写入（paste）后先**校验是否真的落了地**再提交。
                    // Lexical 等框架编辑器对合成 paste 的处理是异步的，且对
                    // execCommand 会翻倍 —— 所以不能在 JS 里同步回退，改由
                    // Kotlin 在 FIRE_DELAY_MS 后读框内容分派：
                    //   · 框里 == 发送文本 → paste 成功 → 提交
                    //   · 框里 != 文本     → paste 无效 → repair（execCommand）
                    webView.postDelayed({ verifyWriteThenFire(text) }, FIRE_DELAY_MS)
                }
                result == "entering-session" && allowRetry -> {
                    // 刚从首页点进会话：等 UI 渲染出输入框后重试一次。
                    //
                    // ⚠️ v1.2.94：重写前必须确认输入框里**没有我们那段文字**。
                    // 旧版只判断"框里有没有字"，而"有字"既可能是「上次没写进去」，
                    // 也可能是「上次写进去了、前端正在提交但还没清空」——
                    // 后者会让我们把同一句话再写一遍 → 主人实测的「两段」。
                    //
                    // 现在按**文本内容**比对：只有框里既非空、又不是我们刚发的那段
                    // （= 用户自己打的字），才放弃重写；否则按情况重写或补提交。
                    Toast.makeText(this, getString(R.string.voice_opening_session), Toast.LENGTH_SHORT).show()
                    webView.postDelayed({
                        readSendText { inBox ->
                            when {
                                inBox == text -> {
                                    // 正是我们那段、还躺在框里 → 补一次提交（令牌在写入时已发放）
                                    logWebView("retry: our text still in box, submitting it")
                                    fireSendKey()
                                    webView.postDelayed({ pollSendVal(text, attempt = 0) }, POLL_MS)
                                }
                                inBox.isNotEmpty() -> {
                                    // 是别的文字（用户自己打的）→ 绝不覆盖、绝不提交
                                    logWebView("retry skipped: box has unrelated text, leaving it alone")
                                    reportVoiceTarget("")
                                }
                                else -> {
                                    // 框是空的 → 上次确实没写进去，安全重写
                                    sendVoiceTextOnce(text, allowRetry = false)
                                }
                            }
                        }
                    }, 1_500L)
                }
                result == "entering-session" -> {
                    Toast.makeText(this, getString(R.string.voice_session_timeout), Toast.LENGTH_LONG).show()
                }
                result == "no-input-found" -> {
                    Toast.makeText(this, getString(R.string.voice_no_session), Toast.LENGTH_LONG).show()
                }
                else -> {
                    Toast.makeText(
                        this, getString(R.string.voice_send_failed, result), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Web 内核版本门禁（v1.2.100）。
     *
     * ## 为什么需要
     * 引擎前端用了 `??=` / 类字段等**解析期**语法（Chrome 85+），polyfill 只能补
     * **API 层**（13 个现代 API 注入），补不了语法 —— WebView 过旧时页面必白屏。
     * 之前的处理是「照常启动引擎 + 日志里记录 SyntaxError」：用户看到的是
     * 白屏/无限重启，不知道原因（酷安用户反馈的正是这种体验）。
     *
     * ## 做法
     * 直接查 WebView Provider 的包版本（不走 JS，无需加载页面）：
     *   · ≥ [MIN_WEBVIEW_CHROME]：放行（本会话内不再重复检查）
     *   · < 门槛：弹对话框，给出当前版本、升级路径（应用商店/Play），并提供
     *     「仍然继续」—— 用户明确坚持才启动引擎（尊重知情选择）；对话框
     *     不会因 AlertDialog 取消而反复弹出，选择「继续」后本会话记入记忆。
     *
     * @return true = 放行（版本达标或用户坚持）
     */
    /**
     * 当前显示的版本门禁对话框（v1.2.100）。
     * ⚠️ 必须持有引用并判重：`onResume` 会被反复触发（切回前台、息屏亮屏、点按钮后
     * 重入），旧实现每次都 `show()` 一个新对话框 → **无限叠堆**（且 setCancelable(false)
     * 无法取消）。独立审查指出后补上这道守卫。
     */
    private var gateDialog: android.app.AlertDialog? = null

    /**
     * 用户在内核门禁里点了「用浏览器打开」（v1.6.8）。
     *
     * ## 为什么需要延迟
     * 门禁在 `onResume` 里跑，早于引擎 `Healthy`，此刻还没有带 token 的 WebUI 地址，
     * 直接 `startActivity` 只能打开一个 401 页面。所以这里只置标记，等
     * `render()` 拿到可用地址时立刻拉起浏览器 —— 用户点一次就够，不必自己再点。
     *
     * 只在内存里：一次性的动作语义，进程重启后本就该重新协商。
     */
    private var pendingBrowserFallback = false

    private fun checkWebViewGate(): Boolean {
        // 把实例 UA 传进去做第三级兜底（前两级都读不到时才会用到）。
        // 实例此刻已由 onCreate 的 setupWebView 建好，UA 是内核真实版本。
        val ua = runCatching { if (::webView.isInitialized) webView.settings.userAgentString else null }
            .getOrNull()
        val report = WebViewDiagnostics.inspect(this, ua)
        AppGate.applyReport(report)
        if (report.allowed) {
            // 达标，或「读不到版本」（读不到不等于太旧，见 WebViewDiagnostics.Cause）
            logWebView("webview gate pass: ${WebViewDiagnostics.summary(report)}")
            return true
        }

        if (gateDialog?.isShowing == true) {
            logWebView("webview gate: dialog already showing, skip")
            return false
        }
        val msg = getString(
            R.string.webview_gate_body,
            if (report.chrome > 0) report.chrome.toString() else "?",
            AppGate.MIN_WEBVIEW_CHROME,
        )
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.webview_gate_title))
            .setMessage(msg)
            .setPositiveButton(R.string.webview_gate_update) { _, _ ->
                openWebViewUpdate(report)
            }
            .setNegativeButton(R.string.webview_gate_continue) { _, _ ->
                AppGate.overrideWebViewGate()
                onResume()   // 重新走一遍（这次会启动引擎）
            }
            .setCancelable(false)

        // 第三条出路（v1.6.8）：把同一个 WebUI 地址交给系统浏览器渲染。
        // 这是「内核换不了」前提下**唯一**能让老设备真正看到界面的办法 ——
        // 回环地址在任何浏览器里都能访问，功能全在引擎侧，WebView 只是显示器。
        // 没有可用浏览器时（极罕见的无浏览器 ROM）不显示该按钮。
        val hasBrowser = WebViewDiagnostics.browserFallbackIntent(
            this, PROBE_URL_FOR_BROWSER,
        ) != null
        if (hasBrowser) {
            builder.setNeutralButton(R.string.webview_gate_open_browser) { _, _ ->
                // 此刻引擎可能还没起来（门禁在 onResume，早于引擎 Healthy），
                // 拿不到带 token 的地址。置一个待办标记，等首个 Healthy + URL
                // 就绪时立刻拉起浏览器，用户不必自己去点第二次。
                pendingBrowserFallback = true
                AppGate.overrideWebViewGate()
                onResume()
            }
        }

        builder.create().also { gateDialog = it }.show()
        logWebView("webview gate: ${WebViewDiagnostics.summary(report)} → prompting user")
        return false
    }

    /**
     * 把当前 WebUI 地址交给系统浏览器（v1.6.8）。
     *
     * ## 这条路径为什么成立
     * 引擎只监听 `127.0.0.1`，而回环地址在**设备自身的任何浏览器**里都能访问 ——
     * 外部浏览器与 App 共享同一网络命名空间。并且 `http://127.0.0.1` 属于
     * "potentially trustworthy origin"，是 secure context，
     * `crypto.randomUUID` 这类仅安全上下文可用的 API 正常可用
     * （对照：局域网 IP 不是 secure context，所以 LAN WebUI 才需要那个注入
     * randomUUID 的反向代理，见 docs/LAN_WEBUI.md）。
     *
     * 界面与功能全在引擎侧，WebView 只是个显示器，换成浏览器不损失能力。
     * 这是「内核换不了」前提下老设备唯一能真正出界面的出路。
     */
    private fun openInExternalBrowser(url: String) {
        val intent = WebViewDiagnostics.browserFallbackIntent(this, url)
        if (intent == null) {
            logWebView("browser fallback: no activity can handle http")
            Toast.makeText(this, getString(R.string.webview_gate_no_browser), Toast.LENGTH_LONG).show()
            return
        }
        val ok = runCatching { startActivity(intent) }
            .onFailure { logWebView("browser fallback failed: ${it.message}") }
            .isSuccess
        if (ok) logWebView("browser fallback: opened WebUI in external browser")
    }

    /**
     * 按 [WebViewDiagnostics.updateIntents] 给出的顺序尝试打开升级入口，
     * 全部失败时用 Toast 告知（不能静默 —— 用户点了「去更新」却什么都没发生
     * 是最差的体验）。
     */
    private fun openWebViewUpdate(report: WebViewDiagnostics.Report) {
        val intents = WebViewDiagnostics.updateIntents(this, report)
        for (intent in intents) {
            val ok = runCatching { startActivity(intent) }.isSuccess
            if (ok) {
                logWebView("webview gate: opened update target ${intent.data}")
                return
            }
        }
        logWebView("webview gate: every update target failed (report=${WebViewDiagnostics.summary(report)})")
        Toast.makeText(
            this,
            getString(R.string.webview_gate_no_store, report.provider ?: "com.google.android.webview"),
            Toast.LENGTH_LONG,
        ).show()
    }

    /*
     * ⚠️ v1.6.8：原来的 `currentWebViewVersion()` / `WEBVIEW_PROVIDERS` 已移除。
     * 版本探测搬到 [WebViewDiagnostics]（单一真源）：原先它既是 private、
     * 又缺「读不到 ≠ 太旧」的区分，别处想显示同一份信息还得复制一遍。
     * 现在门禁与日志共用同一份 [WebViewDiagnostics.Report]。
     */

    /** evaluateJavascript 的返回值带 JSON 引号与转义，统一清一遍 */
    private fun jsResult(raw: String?): String =
        raw?.trim('"')?.replace("\\u003d", "=")?.replace("\\", "") ?: ""

    /**
     * 解析 `evaluateJavascript` 返回的**字符串值**（v1.2.94）。
     *
     * ⚠️ 不能复用 [jsResult]：它会把所有反斜杠删掉（那是为状态标记这种
     * 纯 ASCII 短串设计的）。用来比对用户文本时，反斜杠/引号会被破坏，
     * 导致"文字相同却判为不同" → 重写一遍 → 又是「两段」。
     * 这里用 JSON 解析，完整保留原字符。
     */
    private fun jsString(raw: String?): String = runCatching {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s == "null") ""
        else org.json.JSONTokener(s).nextValue() as? String ?: s
    }.getOrDefault(raw?.trim('"').orEmpty())

    /**
     * 页面是否只接受图片（v1.4.27，issue #54）。
     *
     * 只看 `accept` 属性：全部条目都是图片类型才算「只要图片」。混合类型
     * （如 `image/…` 混 `application/pdf`）不算——那种场景用户可能要选文档，
     * 走通用文件选择器才不会挡住他。
     */
    private fun wantsOnlyImages(params: android.webkit.WebChromeClient.FileChooserParams?): Boolean {
        val accepts = runCatching { params?.acceptTypes?.toList() }.getOrNull().orEmpty()
            .filter { it.isNotBlank() }
        if (accepts.isEmpty()) return false
        return accepts.all { a ->
            val t = a.trim().lowercase()
            t == "image/*" || t.startsWith("image/")
        }
    }

    /**
     * 构造照片选择器 Intent（v1.4.27，issue #54）。
     *
     * - API 33+：`ACTION_PICK_IMAGES`（系统照片选择器，缩略图网格、免存储权限）；
     * - 更低版本 / 选择器不可用：回退 `ACTION_GET_CONTENT` + 图片类型。
     *
     * 多选只在页面声明 multiple 时开启，避免单选场景「选了多张只回传一张」的困惑。
     */
    private fun buildPhotoPickerIntent(
        params: android.webkit.WebChromeClient.FileChooserParams?,
    ): Intent {
        val multiple = runCatching {
            params?.mode == android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        }.getOrDefault(false)
        if (Build.VERSION.SDK_INT >= 33) {
            val pick = Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
                if (multiple) putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, 20)
            }
            if (pick.resolveActivity(packageManager) != null) return pick
        }
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }

    /**
     * 提交输入框内容：调用 JS 里的**同步钩子** `__dshFire()`。
     *
     * 钩子本身不排任何定时器 —— 谁调用它、什么时候调用，由 Android 侧决定。
     * JS 端带 1.5 秒节流窗与空框守卫，重复调用不会造成重复发送。
     */
    private fun fireSendKey() {
        webView.evaluateJavascript("(window.__dshFire && window.__dshFire()) || 'no-hook'") { raw ->
            logWebView("voice fire: ${jsResult(raw)}")
        }
    }

    /** 读输入框状态：empty/gone = 已提交；text = 仍在框里；none = 找不到元素 */
    private fun readSendVal(cb: (String) -> Unit) {
        webView.evaluateJavascript("window.__dshReadVal ? window.__dshReadVal() : 'none'") { raw ->
            cb(jsResult(raw))
        }
    }

    /**
     * 校验 paste 写入是否落地，落地才提交；没落地走 repair（v1.2.95）。
     *
     * 为什么必须有这一步：Lexical 对合成 paste 的处理是**异步**的——JS 注入返回时
     * 内容还没进编辑器。若不看结果直接提交，消息可能是旧的；若在 JS 里同步回退
     * （execCommand），Lexical 会把文本翻倍（主人实测「你好你好」）。
     * 所以「写入 → 校验 → 提交/补写」全部由 Android 侧时序驱动。
     */
    private fun verifyWriteThenFire(text: String) {
        readSendText { inBox ->
            when {
                inBox == text -> {
                    // paste 已落地 → 发放提交令牌并点击
                    webView.evaluateJavascript("window.__dshTicket = true") { _ ->
                        fireSendKey()
                        webView.postDelayed({ pollSendVal(text, attempt = 0) }, POLL_MS)
                    }
                }
                inBox.isEmpty() || inBox == "none" -> {
                    // paste 无效（编辑器不认合成 paste）→ execCommand 补写
                    logWebView("voice write: paste had no effect, repairing (execCommand)")
                    webView.evaluateJavascript(buildVoiceRepairJs(text)) { raw ->
                        logWebView("voice repair: ${jsResult(raw)}")
                        readSendText { after ->
                            if (after == text) {
                                webView.evaluateJavascript("window.__dshTicket = true") { _ ->
                                    fireSendKey()
                                    webView.postDelayed({ pollSendVal(text, attempt = 0) }, POLL_MS)
                                }
                            } else {
                                logWebView("voice write failed after repair: '$after'")
                                Toast.makeText(this, getString(R.string.voice_not_sent), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                else -> {
                    // 框里是别的内容（用户自己打的字）→ 绝不覆盖，放弃本次发送
                    logWebView("voice write: box holds unrelated text '$inBox', aborting")
                    Toast.makeText(this, getString(R.string.voice_not_sent), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 读输入框里的**实际文字**（v1.2.94）。
     *
     * 与 [readSendVal] 的区别：那个只回答"空不空"，这个给出内容 —— 重试路径
     * 必须靠它区分「我们刚写进去的那段」和「用户自己打的字」，
     * 否则会把用户的输入覆盖掉、或把我们那段提交两遍（主人实测的「两段」）。
     */
    private fun readSendText(cb: (String) -> Unit) {
        webView.evaluateJavascript("window.__dshText ? window.__dshText() : ''") { raw ->
            cb(jsString(raw))
        }
    }

    /**
     * 轮询发送结果（v1.2.94 再修）。
     *
     * 判定源是**同步读值**（`__dshReadVal`），由 Android 侧定时轮询，前台后台一个样。
     * 判定依据：前端提交后会把输入框清空。
     *
     * 🔴 v1.2.93 之前的版本在 4 秒超时后会**再点一次发送**，主人实测
     * 「消息还是会被复制成两段」。那就是重复的来源：
     *  · JS 的 1.5s 节流窗拦不住它（4s > 1.5s）
     *  · Kotlin 的 1.5s 同文本去重也拦不住（重发在 sendVoiceTextOnce 内部，绕过它）
     *  · 前端若因网络慢还没清空输入框，这一下就真的提交了第二遍
     *
     * 现在**绝不重发**：提交动作每段文字只做一次（JS 侧一次性令牌保证），
     * 4 秒后仍没清空就如实报「未发出」，把判断权交回用户 —— 宁可少发，
     * 不可重复发（重复消息会污染对话，用户很难撤销）。
     *
     * @param attempt 已轮询次数（上限 [MAX_POLL] ≈ 4 秒）
     */
    private fun pollSendVal(text: String, attempt: Int) {
        readSendVal { st ->
            when {
                st == "empty" || st == "gone" -> reportVoiceTarget("")
                st == "none" -> {
                    // 输入框不在了（页面被导航走等）：按已提交处理，与旧版语义一致
                    logWebView("voice send: input gone (assume committed)")
                    reportVoiceTarget("")
                }
                attempt < MAX_POLL -> {
                    webView.postDelayed({ pollSendVal(text, attempt + 1) }, POLL_MS)
                }
                else -> {
                    // 超时仍未清空：**不再补发**（那正是「两段」的来源），如实报未发出
                    logWebView("voice send: still in box after ${MAX_POLL * POLL_MS}ms, not retrying")
                    Toast.makeText(this, getString(R.string.voice_not_sent), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 报告语音文本**真正落到了哪个对话**（v1.2.72）。
     *
     * 主人反复问「我都不知道他发哪个对话、究竟能不能发」—— 光靠 DOM 猜标题不可靠，
     * 改用引擎自己的账本：发送成功后该会话的 `lastPromptAt` 会被刷新为刚刚，
     * 于是「最近 20 秒内活跃过、且非空白」的那个会话就是真正的落点，报它的标题。
     *
     * @param domTitle DOM 里捞到的标题（兜底，通常为空或应用名）
     */
    private fun reportVoiceTarget(domTitle: String) {
        Thread({
            val now = System.currentTimeMillis()
            val hit = runCatching {
                app.dsh.mobile.engine.SessionReader.list(this)
                    .firstOrNull { !it.blank && now - it.lastActiveAt < 20_000L }
            }.getOrNull()
            val name = hit?.title?.takeIf { it.isNotBlank() } ?: domTitle
            val msg = if (name.isBlank()) getString(R.string.voice_sent)
            else getString(R.string.voice_sent_to, name)
            webView.post { runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
        }, "voice-target").apply { isDaemon = true; start() }
    }

    /** 授权网页媒体请求（一次性对象，只能调用一次）。 */
    private fun grantAudio(request: android.webkit.PermissionRequest) {
        runCatching {
            request.grant(arrayOf(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        }.onFailure { logWebView("permission: grant audio failed: ${it.message}") }
        if (pendingWebPermission === request) pendingWebPermission = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        // issue #72：网页 getUserMedia 的系统权限回执
        if (requestCode == REQ_WEB_AUDIO) {
            val req = pendingWebPermission
            pendingWebPermission = null
            val ok = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (req == null) return
            if (ok) {
                grantAudio(req)
            } else {
                runCatching { req.deny() }
                Toast.makeText(this, getString(R.string.voice_need_permission), Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (requestCode == REQ_RECORD_AUDIO) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (ok) {
                // 授权成功 → 交给无障碍服务弹面板并开始识别（面板归服务托管）
                DshAccessibilityService.instance?.startVoiceInput()
            } else {
                Toast.makeText(this, getString(R.string.voice_need_permission), Toast.LENGTH_SHORT).show()
            }
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onDestroy() {
        // 注意：引擎由前台服务持有，Activity 销毁不影响后台任务。
        // 首启跳 Onboarding 时本 Activity 立即销毁，webView 尚未初始化——
        // lateinit 直接访问会崩（Android 11 新用户首启闪退实测）。
        // relay 里的 Activity 引用必须先摘掉，否则重建后会留下失效实例。
        runCatching {
            app.dsh.mobile.engine.cap.NfcForegroundRelay.detach(this)
            app.dsh.mobile.engine.cap.ScreenRecordRelay.detach(this)
        }
        uiScope.cancel()
        // 注销显式退出回调（避免持有已销毁 Activity）。
        // ⚠️ 实例感知：只在**当前注册的就是自己**时才清，否则 recreate 的时序
        //（旧 onDestroy 晚于新 onCreate）会把新实例的回调误清掉。
        runCatching {
            val sup = (application as DshApp).supervisor
            if (sup.onExplicitExit === exitCallback) sup.onExplicitExit = null
        }
        // 注销语音发送能力：本 Activity 已销毁，服务侧再发会拿到明确的「需打开 App」提示
        // （而不是把文字注入到一个已销毁的 WebView）
        stopHangWatchdog()
        splashController?.dispose()
        splashController = null
        splash = null
        splashDismissed = true
        VoiceBridge.registerSender(null)
        // 注销悬浮窗刷新能力（本 Activity 已销毁，WebView 随之销毁；悬浮窗再点刷新
        // 会拉起主界面冷启 —— 界面自带的加载流程就是最合理的“刷新”响应）
        WebRefreshBridge.register(null)
        // 释放未完成的文件选择请求，否则 WebView 侧回调悬空
        pendingFileCallback?.let { runCatching { it.onReceiveValue(null) } }
        pendingFileCallback = null
        // v1.6.37：已退役的实例不再 destroy（已 destroy 过，重复调会抛）。
        liveWebView()?.let { runCatching { it.destroy() } }
        super.onDestroy()
    }

    // ==================== WebView 卡死自愈（v1.2.100，issue #8） ====================
    //
    // ## 问题
    // 用户反馈：「运行到一半会话内容卡住，计时器还在正常计数，模型仍在运行，
    // 需要关闭进程重启应用，偶发」。症状 = **后端正常、前端停止更新**。
    // 两种成因：
    //  ① WebView 渲染进程崩溃/被系统回收 → 页面残留但不再刷新
    //     （未实现 onRenderProcessGone 时 Android 甚至会直接杀掉整个 App）
    //  ② 前端 JS 事件循环被长任务卡住（渲染进程还活着，但不再处理消息）
    //
    // ## 做法：两级看门狗
    //  ① 渲染进程崩溃 → onRenderProcessGone 里 [recoverWebView]（重建 WebView + 重载）
    //  ② 页面活着但不响应 → 定时注入轻量探针，连续失败则同样走 [recoverWebView]
    //
    // ⚠️ 引擎**不重启**（它由前台服务持有，会话与进行中的任务都在），
    // 只重建渲染层，用户视角是「页面自己恢复了」。

    private val hangWatchdog = android.os.Handler(android.os.Looper.getMainLooper())

    /** 连续探针失败次数（成功即清零；达阈值判定卡死） */
    private var probeFails = 0

    /** 页面探针：更新一个时间戳；若 eval 回调迟迟不回来，说明主线程/JS 卡住 */
    private val HANG_PROBE_JS =
        "(function(){window.__dshProbe=Date.now();return String(window.__dshProbe);})()"

    private fun startHangWatchdog() {
        stopHangWatchdog()
        hangWatchdog.postDelayed(hangTick, HANG_CHECK_INTERVAL_MS)
    }

    /** 看门狗的一次心跳：探针 → 判定 → 排下一次 */
    private val hangTick = object : Runnable {
        override fun run() {
            if (webViewDead || !::webView.isInitialized) return
            // v1.6.21：页面正在导航（首屏/重载）时不解注入探针。
            // 这段窗口里渲染主线程本来就在解析 HTML + 布局，探针超时是「忙」不是「死」，
            // 把它算成失败会直接造成「刚加载就反复重建 WebView」的假死循环。
            if (pageIsLoading) {
                hangWatchdog.postDelayed(this, HANG_CHECK_INTERVAL_MS)
                return
            }
            // 已失败过一次就放宽等待时长（3s → 6s），给陷入长任务/重试循环的
            // 页面（典型：断网后的模型请求重试）一个自证机会，而不是直接整页重载。
            val probeTimeout =
                if (probeFails == 0) HANG_PROBE_TIMEOUT_MS else HANG_PROBE_TIMEOUT_SLOW_MS
            probePage(probeTimeout) { alive ->
                if (alive) {
                    probeFails = 0
                } else {
                    probeFails++
                    logWebView("hang probe failed ($probeFails/$HANG_FAIL_LIMIT)")
                    if (probeFails >= HANG_FAIL_LIMIT) {
                        logWebView("page unresponsive — recovering webview (engine untouched)")
                        recoverWebView()
                        probeFails = 0
                        return@probePage   // recoverWebView 内部会重启看门狗
                    }
                }
            }
            hangWatchdog.postDelayed(this, HANG_CHECK_INTERVAL_MS)
        }
    }

    private fun stopHangWatchdog() {
        hangWatchdog.removeCallbacks(hangTick)
        probeFails = 0
    }

    /**
     * 探针：注入一段极短的 JS 并等待回调。
     * @param cb true = 页面有响应；false = 超时或异常
     */
    private fun probePage(timeoutMs: Long = HANG_PROBE_TIMEOUT_MS, cb: (Boolean) -> Unit) {
        var done = false
        val timeout = Runnable {
            if (!done) {
                done = true
                cb(false)
            }
        }
        hangWatchdog.postDelayed(timeout, timeoutMs)
        runCatching {
            // v1.6.37：已退役/恢复中的实例不可探（否则就是「对已 destroy 的 WebView 调方法」）
            val wv = liveWebView() ?: run {
                done = true
                hangWatchdog.removeCallbacks(timeout)
                cb(false)
                return@runCatching
            }
            wv.evaluateJavascript(HANG_PROBE_JS) { raw ->
                if (!done) {
                    done = true
                    hangWatchdog.removeCallbacks(timeout)
                    cb(raw != null && raw.contains("1"))   // 时间戳必然含数字
                }
            }
        }.onFailure {
            if (!done) {
                done = true
                hangWatchdog.removeCallbacks(timeout)
                cb(false)
            }
        }
    }

    /**
     * 让当前 WebView 实例彻底退役（v1.6.37）。
     *
     * 流程：置死标志（先）→ 从视图树移除 → `destroy()`。
     * 先置标志是关键：此后所有 `liveWebView()` 都返回 null，
     * 任何并发回调（看门狗、热状态订阅、点击）都不会再碰这个已死实例，
     * 也就不会出现「已 destroy 还调方法」的随机崩溃 / 长期白屏。
     */
    private fun retireDeadWebView() {
        stopHangWatchdog()
        // 记住容器：removeView 之后 parent 就没了
        if (::webView.isInitialized) {
            (webView.parent as? android.view.ViewGroup)?.let { webViewHost = it }
        }
        if (webViewDead) {
            pendingRecovery = true
            return
        }
        webViewDead = true
        pendingRecovery = true
        if (!::webView.isInitialized) return
        val dead = webView
        // v1.6.45：先撤销登记 —— 否则设置页改档位时会把 JS 打到已销毁的实例上。
        runCatching { app.dsh.mobile.web.ThermalRenderGuard.bindLive(null) }
        // v1.6.46：document-start 登记同样要撤（弱引用集合，这里显式清理更快）
        runCatching { app.dsh.mobile.web.RenderTune.detach(dead) }
        runCatching { app.dsh.mobile.web.EventCoalesce.detach(dead) }
        runCatching { (dead.parent as? android.view.ViewGroup)?.removeView(dead) }
            .onFailure { logWebView("remove dead webview failed: ${it.message}") }
        runCatching { dead.destroy() }
            .onFailure { logWebView("destroy dead webview failed: ${it.message}") }
        logWebView("dead webview retired (removed + destroyed), awaiting foreground recovery")
    }

    /** 当前可安全使用的 WebView；已退役/恢复中返回 null。新增代码一律走这里。 */
    private fun liveWebView(): WebView? = if (webViewDead || !::webView.isInitialized) null else webView

    /**
     * 静默恢复（v1.6.37）：创建全新 WebView、重新配置、加载当前地址。
     *
     * **只允许在前台调用**（onResume / 前台自愈路径）。后台调它就是原死循环。
     * 不弹窗、不重建 Activity、不重启引擎；地址优先用死前记录，
     * 没有则等引擎 Healthy 回调用正常流程加载。
     */
    private fun recoverWebViewSilently(reason: String) {
        if (recovering) return
        recovering = true
        runCatching {
            val host = webViewHost
                ?: findViewById<android.view.ViewGroup>(android.R.id.content)
                ?: run { logWebView("recovery aborted: no host for $reason"); return@runCatching }
            webViewHost = host
            // 已退役的不再碰；若参数路径进来（看门狗）先退役旧实例
            if (!webViewDead) retireDeadWebView()

            val fresh = WebView(this)
            fresh.id = R.id.webView
            fresh.visibility = android.view.View.INVISIBLE
            host.addView(fresh, android.view.ViewGroup.LayoutParams(-1, -1))
            webView = fresh
            webViewDead = false

            // 新实例重新走一遍配置（settings / client / 注入 / 优先级…）
            setupWebView()
            // 渲染层是全新的：返回栈、预热标记、导航态全部复位，
            // 否则 isPreheatNavigation() 会把真实页面误判成预热页而跳过注入。
            backMobile = false
            backConsumable = false
            lastBackAt = 0L
            webViewPreheated = false
            pageIsLoading = false
            urlLoaded = false
            probeFails = 0

            val target = runCatching { (application as DshApp).supervisor.webUrl() }
                .getOrNull()?.takeIf { it.isNotBlank() }
            if (target != null) {
                urlLoaded = true
                fresh.loadUrl(target)
                logWebView("webview recovered ($reason) — loading $target")
            } else {
                logWebView("webview recovered ($reason) — engine url pending")
            }
            startHangWatchdog()
            // 恢复到可见：用淡入而非硬切，避免与页面首帧叠加时闪一下
            fresh.alpha = 0f
            fresh.visibility = android.view.View.VISIBLE
            fresh.animate().alpha(1f).setDuration(120L).start()
        }.onFailure {
            logWebView("webview recovery failed: ${it.message}")
        }
        recovering = false
    }

    /**
     * 重建渲染层（前台自愈路径：看门狗判定页面卡死）。
     *
     * 与渲染进程死亡的区别：这里页面还活着（只是不响应），可以安全读 URL；
     * 而 onRenderProcessGone 里**绝不能**读旧实例任何东西，所以两者分开。
     */
    private fun recoverWebView() {
        val oldUrl = runCatching { liveWebView()?.url }.getOrNull()
        recoverWebViewSilently("hang watchdog")
        // 保留旧 URL 的加载语义：若引擎地址暂时为空但旧 URL 还在，用旧 URL
        if (oldUrl.isNullOrBlank() || urlLoaded) return
        val now = liveWebView() ?: return
        urlLoaded = true
        now.loadUrl(oldUrl)
    }

    /** WebView 诊断日志（写 logcat + engine.log，用户可导出） */
    private fun logWebView(message: String) {
        android.util.Log.w("DshWebView", message)
        runCatching {
            val f = app.dsh.mobile.engine.EngineConfig.engineRoot(this).let {
                java.io.File(it, "engine.log")
            }
            // ⚠️ 本方法由 WebView 的 onConsoleMessage 调用，**跑在 UI 线程**：
            // 直接 appendText 是同步磁盘 I/O，会让渲染掉帧；而引擎日志泵线程同时在写
            // 同一文件、两者无锁，落盘内容可能交错。统一交给 EngineLog（串行 + 后台线程）。
            app.dsh.mobile.engine.EngineLog.appendAsync(f, "[webview] $message\n")
        }
    }

    companion object {
        /**
         * 首帧后静态资源预热的延迟（v1.6.25）。
         *
         * 足够让首帧合成层上屏 + 插件 HMR 跑过第一波 —— 那两个阶段是用户唯一能
         * 感知到“卡”的地方，预热不能跟它们抢 CPU/IO。
         */
        private const val ASSET_WARM_DELAY_MS = 3_500L

        /**
         * 取本机引擎静态资源清单（v1.6.25）。
         *
         * 只收 `http://127.0.0.1|localhost` 开头的，且交给 [WebAssetCache] 再按
         * 扩展名筛一遍（那里决定哪些类型可缓存）。这里限 240 条，防止异常页面
         * 把 IPC 返回值撑大。
         */
        private const val ASSET_MANIFEST_JS = """(function(){try{var es=performance.getEntriesByType('resource')||[];var o=[];for(var i=0;i<es.length&&o.length<240;i++){var n=es[i]&&es[i].name;if(!n)continue;if(n.indexOf('http://127.0.0.1')===0||n.indexOf('http://localhost')===0)o.push(n);}return o.join('\n');}catch(e){return '';}})()"""

        /** WebView 文件选择器的请求码（onShowFileChooser → onActivityResult） */
        private const val REQ_FILE_CHOOSER = 1001

        /**
         * 「再按一次退出」的窗口（毫秒）。
         *
         * 与插件侧 `BACK_EXIT_WINDOW_MS` 保持一致：提示 Toast 的时长、
         * 用户的反应节奏、窗口三者对齐，才不会出现「提示已消失但仍算第二次」。
         */
        private const val BACK_EXIT_WINDOW_MS = 2_000L

        /**
         * 预览页自毁守护脚本（v1.6.32，详见 installPreviewJanitor）。
         *
         * 结构：① 追踪器（透传原实现的 MonkeyPatch）② `__dshPreviewTeardown()`。
         * 追踪上限：ws/es/xr 各 512、定时器 4096 —— 长驻页也不会因追踪而膨胀；
         * 对已 close/abort 的重复操作是 no-op，重复调用 teardown 幂等。
         */
        private const val PREVIEW_JANITOR_JS = """try{
if(!window.__dshPreviewJanitor){
window.__dshPreviewJanitor=1;
var S={ws:[],es:[],xr:[],tm:[]};
var OW=window.WebSocket;
if(OW){var NW=function(u,p){var w=p===undefined?new OW(u):new OW(u,p);try{if(S.ws.length<512)S.ws.push(w)}catch(e){}return w};
NW.prototype=OW.prototype;
try{NW.CONNECTING=OW.CONNECTING;NW.OPEN=OW.OPEN;NW.CLOSING=OW.CLOSING;NW.CLOSED=OW.CLOSED}catch(e){}
window.WebSocket=NW}
var OE=window.EventSource;
if(OE){var NE=function(u,c){var e=c===undefined?new OE(u):new OE(u,c);try{if(S.es.length<512)S.es.push(e)}catch(e2){}return e};
NE.prototype=OE.prototype;window.EventSource=NE}
var OO=XMLHttpRequest.prototype.open;
XMLHttpRequest.prototype.open=function(){try{if(S.xr.length<512)S.xr.push(this)}catch(e){}return OO.apply(this,arguments)};
var OT=window.setTimeout,OI=window.setInterval;
window.setTimeout=function(){var i=OT.apply(window,arguments);try{if(S.tm.length<4096)S.tm.push([i,0])}catch(e){}return i};
window.setInterval=function(){var i=OI.apply(window,arguments);try{if(S.tm.length<4096)S.tm.push([i,1])}catch(e){}return i};
window.__dshPreviewTeardown=function(){
try{
for(var i=0;i<S.ws.length;i++){try{var w=S.ws[i];if(w&&w.readyState<2)w.close()}catch(e){}}
for(i=0;i<S.es.length;i++){try{S.es[i].close()}catch(e){}}
for(i=0;i<S.xr.length;i++){try{var x=S.xr[i];if(x&&x.readyState<4)x.abort()}catch(e){}}
for(i=0;i<S.tm.length;i++){try{var t=S.tm[i];t[1]?clearInterval(t[0]):clearTimeout(t[0])}catch(e){}}
S.ws.length=0;S.es.length=0;S.xr.length=0;S.tm.length=0
}catch(e){}
}
}
}catch(e){}"""

        /** 触发预览页自毁的一句话脚本（幂等，可在任意时机安全调用） */
        private const val PREVIEW_TEARDOWN_CALL =
            "window.__dshPreviewTeardown&&window.__dshPreviewTeardown()"

        /** 麦克风权限请求码（语音输入，v1.2.58） */
        private const val REQ_RECORD_AUDIO = 1002

        /** 网页 getUserMedia 的麦克风权限请求（issue #72，与原生语音区分开） */
        private const val REQ_WEB_AUDIO = 1003

        /** 发送去重窗口（v1.2.90）：同文本在此时间内只发一次，防重复 */
        private const val DEDUP_WINDOW_MS = 1_500L

        /**
         * 页面卡死看门狗的时序（v1.2.100，issue #8）。
         * 探针注入极短 JS 并要求回执；连续 [HANG_FAIL_LIMIT] 次无响应即判定卡死。
         * 间隔取 15s：既不会漏掉「跑了很久才发现」的情况，也不会频繁打扰正常页面。
         */
        private const val HANG_CHECK_INTERVAL_MS = 15_000L
        /** 单次探针等待上限（超过即算无响应） */
        private const val HANG_PROBE_TIMEOUT_MS = 3_000L
        /**
         * 已经失败过一次后的放宽超时（v1.6.21）。
         *
         * 为什么需要：断网 / 模型请求超时的时候，前端常会陷入主线程上的重试循环，
         * 渲染主线程会「忙」好几秒 —— 那不是卡死。旧实现固定 3s 三条就重建 WebView，
         * 会把一次可恢复的忙碌直接变成**整页重载**（用户感知：突然白屏、要重新加载）。
         * 已有一次失败后改用 6s，给忙碌的页面一个自证机会。
         */
        private const val HANG_PROBE_TIMEOUT_SLOW_MS = 6_000L
        /**
         * 引擎重启后，WebUI 最多等插件就绪多久（v1.6.21）。
         *
         * 插件状态要等 `awaitPluginHealth` 走完（最长 20s）才离开 LOADING，
         * 而 WebUI 不能为此干等：页面先回来（本地引擎已在，对话可读），
         * 插件随后由 dsh-hmr 流式补上。1.5s 只用于吸收「刚好正在切换」的抖动。
         */
        private const val PLUGIN_WAIT_MAX_MS = 1_500L
        /** 连续失败几次判定卡死（3 次 ≈ 45s 无响应，足够排除偶发抖动） */
        private const val HANG_FAIL_LIMIT = 3

        /**
         * 探测「设备上有没有浏览器」用的占位地址（v1.6.8）。
         *
         * ⚠️ 不能拿真实 WebUI 地址来探测：门禁执行时引擎还没起来，`webUrl()` 是空串，
         * `resolveActivity` 对空 URL 恒为 false，会导致「明明有浏览器却不显示按钮」。
         * 这里只关心有没有 Activity 能处理 http 浏览，所以给一个永远可解析的回环地址。
         */
        private const val PROBE_URL_FOR_BROWSER = "http://127.0.0.1/"


        /*
         * ⚠️ v1.6.8：`WEBVIEW_PROVIDERS` 已迁到 [WebViewDiagnostics.KNOWN_PROVIDERS]。
         * 保留在 MainActivity 的 companion 里会让「哪些包算 provider」出现两份，
         * 一处更新另一处忘记就会静默分叉。
         */

        /**
         * 语音发送的 Android 侧时序（v1.2.92，v1.2.94 调整）。
         *
         * 全部走 Handler，不依赖 JS 定时器或渲染帧 —— 后台 WebView 既不产帧、
         * JS 定时器也可能被节流，只有进程级 Handler 在前后台行为一致。
         */
        /** 写入输入框后、点发送键前的等待：给 React 受控组件落地 state 的时间 */
        private const val FIRE_DELAY_MS = 250L
        /** 结果轮询间隔 */
        private const val POLL_MS = 100L
        /** 轮询上限（× POLL_MS ≈ 4 秒）；超时只报未发出，**绝不重发**（防重复） */
        private const val MAX_POLL = 40
        /** 「输入框是否确定为空」的采样间隔（见 probeInputEmpty） */
        private const val PROBE_INTERVAL_MS = 60L

        /**
         * v1.4.20（issue #43）：剪贴板降级链。页面普遍用
         * `navigator.clipboard.writeText()`（要求文档焦点 + 用户手势），WebView 里
         * 常被焦点/激活限制拒绝，而页面实现多把异常吞掉——用户感知就是「复制按钮
         * 坏了」。这里把 writeText 包装成降级链：
         *   ① 原生 API（有焦点时行为不变）→ ② 原生桥 DshAndroidClipboard（绕开
         *   焦点限制）→ ③ execCommand('copy')（textarea 必须 position:fixed+
         *   opacity:0，display:none 会复制失败）→ ④ 全失败才把原异常抛回。
         * 老内核没有 clipboard API 时直接装一个可用的 writeText。
         */
        private const val CLIPBOARD_FALLBACK_JS = """
(function () {
  if (window.__dshClipboardPatched) return;
  window.__dshClipboardPatched = true;
  function nativeCopy(t) {
    try { return !!(window.DshAndroidClipboard && window.DshAndroidClipboard.copyText(t)); }
    catch (e) { return false; }
  }
  function legacyCopy(t) {
    try {
      var ta = document.createElement('textarea');
      ta.value = t;
      ta.style.cssText = 'position:fixed;top:-999px;left:-999px;opacity:0;';
      document.body.appendChild(ta);
      ta.focus(); ta.select();
      var ok = document.execCommand('copy');
      document.body.removeChild(ta);
      return ok;
    } catch (e) { return false; }
  }
  function viaFallback(t) {
    if (nativeCopy(t)) return;
    if (legacyCopy(t)) return;
    throw new Error('all clipboard paths failed');
  }
  if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
    var orig = navigator.clipboard.writeText.bind(navigator.clipboard);
    navigator.clipboard.writeText = function (text) {
      return Promise.resolve().then(function () { return orig(text); }).catch(function (err) {
        viaFallback(text);
      });
    };
  } else {
    navigator.clipboard = navigator.clipboard || {};
    navigator.clipboard.writeText = function (text) {
      try { viaFallback(text); return Promise.resolve(); }
      catch (e) { return Promise.reject(e); }
    };
  }
})();
"""

        /**
         * 环境自检脚本：报告 WebView 版本与引擎所需关键 API 是否存在。
         * 引擎实际需要 Chrome 126（pdf.js 用 URL.parse），polyfill 可补 API 但补不了语法
         * （`??=` 需 Chrome 85，语法错误会导致白屏 —— 那种情况日志里会看到 SyntaxError）。
         */
        private const val WEBVIEW_SELFCHECK_JS = """
(function () {
  try {
    var ua = navigator.userAgent || '';
    var m = ua.match(/Chrome\/(\d+)/);
    var chrome = m ? parseInt(m[1], 10) : 0;
    var need = {
      'Object.hasOwn': 93, 'replaceChildren': 86, 'replaceAll': 85,
      'findLast': 97, 'structuredClone': 98, 'toSorted': 110,
      'AbortSignal.any': 116, 'Promise.withResolvers': 119, 'URL.parse': 126
    };
    var missing = [];
    var probes = {
      'Object.hasOwn': function(){ return typeof Object.hasOwn === 'function'; },
      'replaceChildren': function(){ return typeof Element !== 'undefined' && !!Element.prototype.replaceChildren; },
      'replaceAll': function(){ return !!String.prototype.replaceAll; },
      'findLast': function(){ return !!Array.prototype.findLast; },
      'structuredClone': function(){ return typeof structuredClone === 'function'; },
      'toSorted': function(){ return !!Array.prototype.toSorted; },
      'AbortSignal.any': function(){ return typeof AbortSignal !== 'undefined' && !!AbortSignal.any; },
      'Promise.withResolvers': function(){ return !!Promise.withResolvers; },
      'URL.parse': function(){ return typeof URL !== 'undefined' && !!URL.parse; }
    };
    for (var k in probes) { try { if (!probes[k]()) missing.push(k + '(need ' + need[k] + ')'); } catch (e) { missing.push(k + '(probe err)'); } }
    return '[dsh-android] webview ok | chrome=' + chrome +
      ' | polyfill=' + (!missing.length ? 'complete' : 'MISSING: ' + missing.join(',')) +
      ' | ua=' + ua.slice(0, 120);
  } catch (e) { return '[dsh-android] selfcheck failed: ' + e.message; }
})();
"""

        /**
         * Android 13+ 通知运行时权限请求（Service 启动路径回调到 Activity）。
         *
         * ⚠️ v1.5.0-beta.9：**只问一次**。此前每次前台服务启动都会调这里，
         * 用户首次点了「不允许」之后仍会被反复弹框 —— 那正是「一上来就弹一堆窗」
         * 最招人烦的形态。现在用 [KEY_ASKED_NOTIF] 记住问过没问过，问过就不问了。
         *
         * 想再授权时用户自己去「权限中心 → 通知」点，那里是显式入口，不打扰。
         */
        fun maybeRequestNotificationPermission(activity: Context) {
            if (Build.VERSION.SDK_INT < 33) return
            val granted = activity.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) return
            if (activity !is Activity) return
            val sp = activity.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            if (sp.getBoolean(KEY_ASKED_NOTIF, false)) return
            sp.edit().putBoolean(KEY_ASKED_NOTIF, true).apply()
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        /**
         * 把识别出的文本写入 WebUI 的输入框并提交（v1.2.58）。
         *
         * 为什么用 JS 注入而不是直接调引擎 API：WebUI 的输入框是前端状态的一部分，
         * 只有走它的 DOM 才能触发正确的发送流程（含附件、@引用等既有逻辑）。
         *
         * 选择器策略（按可靠性排序，逐个尝试）：
         *  ① textarea（当前前端用的是 textarea）
         *  ② [contenteditable]（富文本输入框）
         *  ③ input[type=text]
         * 写入用原生 setter + 派发 input 事件，让 React 的受控组件感知变化
         * （直接改 value 而不派发事件，React 会忽略）。
         */
        fun buildVoiceInputJs(text: String): String {
            val escaped = org.json.JSONObject.quote(text)
            return """
(function(){
  try {
    var t = $escaped;
    // v1.2.65: ensure there is an input box to write into. The user may be sitting
    // on the home screen (no conversation open), where no textarea exists; the old
    // build returned 'no-input-found' and the send silently did nothing.
    // Here we first look for a conversation entry in the DOM, click into it, and
    // let the caller retry once the UI has rendered.
    function findInput() {
      return document.querySelector('textarea')
          || document.querySelector('[contenteditable="true"]')
          || document.querySelector('input[type=text]');
    }
    // Send button: exact selectors first, then a fuzzy sweep over buttons whose
    // aria-label/class mentions send. The old build tried three exact selectors and
    // silently fell back to Enter; if Enter inserts a newline in this frontend,
    // the message never leaves the box (user-reported "it does not send").
    function findSend(el) {
      var sels = ['button[aria-label*="send" i]', 'button[aria-label*="\u53d1\u9001"]',
                  '[data-testid*="send" i]'];
      for (var i = 0; i < sels.length; i++) {
        var b = document.querySelector(sels[i]);
        if (b && b.disabled !== true) return b;
      }
      var cands = document.querySelectorAll('button, [role="button"]');
      for (var k = cands.length - 1; k >= 0; k--) {
        var c = cands[k];
        var label = (c.getAttribute('aria-label') || '') + ' ' + (c.className || '');
        if (/send|\u53d1\u9001/i.test(label) && c.disabled !== true) return c;
      }
      // Fallback: the last enabled button inside the input's own container
      var scope = el.closest('form') || (el.parentElement && el.parentElement.parentElement);
      if (scope) {
        var btns = scope.querySelectorAll('button');
        for (var j = btns.length - 1; j >= 0; j--) {
          if (btns[j].disabled !== true) return btns[j];
        }
      }
      return null;
    }
    var el = findInput();
    if (!el) {
      var cand = document.querySelector('[data-session-id]')
              || document.querySelector('a[href*="session"]')
              || document.querySelector('[role="listitem"]')
              || document.querySelector('[class*="session" i]');
      if (cand instanceof HTMLElement) {
        cand.click();
        return 'entering-session';
      }
      return 'no-input-found';
    }
    el.focus();
    if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
      var proto = el.tagName === 'TEXTAREA'
        ? window.HTMLTextAreaElement.prototype
        : window.HTMLInputElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
      setter.call(el, t);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    } else {
      // contenteditable (rich-text editors: Lexical / ProseMirror / Quill ...).
      //
      // v1.2.95 - REWRITE after live reproduce on the real WebUI (emulator + CDP).
      // Facts established by experiment:
      //  1) execCommand('insertText') on this Lexical editor DOUBLED the text: the
      //     framework imports the DOM mutation AND applies the beforeinput that
      //     execCommand fires. One write became two.
      //  2) Dispatching cancelable 'beforeinput' ALSO doubled.
      //  3) A synthetic ClipboardEvent('paste') with a DataTransfer is processed
      //     by Lexical's native paste pipeline EXACTLY ONCE - but ASYNCHRONOUSLY:
      //     the content lands a beat later. A synchronous check right after the
      //     dispatch still sees the old content, which made the old in-JS fallback
      //     kick in and double the text anyway.
      // So this snippet does ONE thing only: fire the paste. Whether the write
      // landed is checked by Kotlin (readSendText after FIRE_DELAY_MS), which
      // falls back to buildVoiceRepairJs (execCommand, safe for framework-less
      // editors) when the paste had no effect. No in-JS fallback here - any
      // synchronous second write races the paste and doubles the text.
      el.focus();
      // Ctrl+A keydown lets the editor select its own content first.
      el.dispatchEvent(new KeyboardEvent('keydown', {
        key: 'a', code: 'KeyA', keyCode: 65, ctrlKey: true, bubbles: true,
      }));
      var dt = new DataTransfer();
      dt.setData('text/plain', t);
      el.dispatchEvent(new ClipboardEvent('paste', {
        bubbles: true, cancelable: true, clipboardData: dt,
      }));
    }
    // Do NOT click send synchronously: a React controlled input updates its state
    // asynchronously, so right after dispatching the input event the send button may
    // still be disabled and the click is a no-op.
    //
    // v1.2.92 - regression fix. v1.2.88 deferred that click with
    // requestAnimationFrame to shave latency, but rAF callbacks ride on frame
    // production and a backgrounded WebView produces no frames: tapping "send"
    // while the app was in the background left the click queued, so the message
    // only went out the instant the user returned to the foreground (user: "it
    // only sends the moment I come back"). The pre-v1.2.88 setTimeout version
    // did work in the background.
    //
    // Now the app side owns both the commit and the verdict: we expose two
    // synchronous hooks and Kotlin calls them on its own Handler schedule, so no
    // JS timer and no frame is involved and background behaves like foreground.
    window.__dshEl = el;
    // One-shot ticket, handed out per write (see __dshFire). Not a time window:
    // v1.2.92 throttled by elapsed time, and Kotlin's "still in the box after 4s,
    // fire again" retry sailed straight through it (4s > 1.5s) - when the
    // frontend was slow to clear the box that second click sent the same text
    // again, which is exactly the "two copies" the user kept seeing.
    window.__dshTicket = false;
    window.__dshFire = function () {
      if (!window.__dshTicket) return 'consumed';   // already committed this text
      var target = (window.__dshEl && document.body.contains(window.__dshEl))
        ? window.__dshEl : findInput();
      if (!target) return 'no-input';
      var v = (target.tagName === 'TEXTAREA' || target.tagName === 'INPUT')
        ? (target.value || '') : (target.textContent || '');
      if (!v.trim()) return 'empty';   // nothing to send - never click on empty
      var btn = findSend(target);
      // Spend the ticket BEFORE clicking: whatever happens next, this text gets
      // exactly one commit attempt. Retrying a submit that already reached the
      // frontend is what produced duplicate messages.
      window.__dshTicket = false;
      if (btn) { btn.click(); return 'clicked'; }
      target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true }));
      target.dispatchEvent(new KeyboardEvent('keyup',   { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true }));
      return 'enter';
    };
    // Verdict source: the frontend clears the input once the message is committed.
    window.__dshReadVal = function () {
      var e = window.__dshEl;
      if (!e) return 'none';
      if (!document.body.contains(e)) return 'gone';
      var v = (e.tagName === 'TEXTAREA' || e.tagName === 'INPUT') ? (e.value || '') : (e.textContent || '');
      return v.trim() ? 'text' : 'empty';
    };
    // Actual text in the box. Kotlin compares it against what it tried to send:
    // only a match means "this is our text, safe to (re)submit"; anything else is
    // the user's own typing and must be left alone.
    window.__dshText = function () {
      var e = window.__dshEl;
      if (!e) {
        var f = findInput();
        if (!f) return '';
        e = f;
      }
      if (!document.body.contains(e)) return '';
      var v = (e.tagName === 'TEXTAREA' || e.tagName === 'INPUT') ? (e.value || '') : (e.textContent || '');
      return v.trim();
    };
    // Hand out exactly one commit ticket for the text just written. Kotlin calls
    // __dshFire() later (250ms, on its own Handler) to spend it.
    // ⚠️ Grant MUST come after the hook block above: that block re-initialises
    // __dshTicket = false, and in v1.2.94 the grant sat before it - the reset
    // clobbered the grant, every fire returned 'consumed' and nothing was ever
    // submitted (the text just sat in the box). This order is load-bearing.
    window.__dshTicket = true;
    return 'ok:written';
  } catch (e) { return 'err:' + e.message; }
})();
""".trimIndent()
        }

        /**
         * 补写（v1.2.95）：paste 通道无效时的回退写入。
         *
         * 专用场景：编辑器**不响应合成 paste 事件**（无框架的普通 contenteditable）。
         * 此时 execCommand 是标准路径且没有框架去双处理 DOM 变更，安全。
         *
         * ⚠️ 绝不能对 Lexical 这类框架编辑器使用（实测翻倍）——调用方必须先
         * 用 readSendText 确认 paste 没生效才允许走这里（见 sendVoiceTextOnce）。
         */
        fun buildVoiceRepairJs(text: String): String {
            val escaped = org.json.JSONObject.quote(text)
            return """
(function(){
  try {
    var t = $escaped;
    var el = window.__dshEl && document.body.contains(window.__dshEl)
      ? window.__dshEl
      : (document.querySelector('textarea')
         || document.querySelector('[contenteditable="true"]')
         || document.querySelector('input[type=text]'));
    if (!el) return 'no-input';
    el.focus();
    function contentOf(node) { return (node.textContent || '').trim(); }
    function selectAllIn(node) {
      try {
        var sel = window.getSelection();
        var range = document.createRange();
        range.selectNodeContents(node);
        sel.removeAllRanges();
        sel.addRange(range);
      } catch (e) {}
    }
    selectAllIn(el);
    try { document.execCommand('insertText', false, t); } catch (e) {}
    if (contentOf(el) !== t.trim() && (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT')) {
      var proto = el.tagName === 'TEXTAREA'
        ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, t);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }
    return 'repaired:' + contentOf(el).slice(0, 40);
  } catch (e) { return 'err:' + e.message; }
})();
""".trimIndent()
        }



        /** 页面缩放持久化：SharedPreferences 名 + key（设置页与引导共用）。
         *  屏幕朝向偏好（v1.2.120 起三态 portrait/landscape/auto）集中在
         *  OrientationPrefs，此处不再持有布尔键。 */
        private const val PREFS_UI = "dsh_ui"

        /** 通知权限是否已经问过用户（问过就不再问，避免反复弹框）。 */
        private const val KEY_ASKED_NOTIF = "asked_notif_permission"
        private const val KEY_PAGE_SCALE = "page_scale"

        /**
         * 手机 UI 方案（v1.4.67，宝宝要求）：内置两套竖屏布局插件，用哪个由用户选。
         *
         * · false（默认）= 我们自制的 narrow-sidebar / narrow-settings / narrow-rightbar
         *   三件套。更轻、行为可预期，是长期使用的默认。
         * · true = 社区插件 @xiaopb/dsh-mobile-layout（「官方推荐手机 UI」）。
         *   抽屉 / 底部操作条 / 全屏设置页，视觉更接近官方 App。
         *
         * 两者互斥：overlay 里按这个开关把另一个标成 disabled，引擎不会同时加载。
         */
        private const val KEY_OFFICIAL_MOBILE_UI = "official_mobile_ui"

        @JvmStatic
        fun officialMobileUiEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                .getBoolean(KEY_OFFICIAL_MOBILE_UI, false)

        @JvmStatic
        fun setOfficialMobileUiEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_OFFICIAL_MOBILE_UI, on).apply()
        }

        /**
         * 沉浸式状态栏开关（v1.4.64：默认回到「带状态栏」）。
         *
         * 历史上反复过几次，结论记在这里避免再绕：
         *   · v1.4.30 默认关 —— 隐藏状态栏后岛会压到挖孔上；
         *   · v1.4.61 默认开 —— 岛已能精确对齐挖孔，想让岛代替状态栏；
         *   · **v1.4.64 默认关** —— 宝宝实测「怎么改都有点难受」，全屏 + 岛
         *     浮在网页上的组合，无论留白还是叠压都不够自然。带状态栏时窗口
         *     天然让开顶部，岛在状态栏下方，最省心。
         *
         * 想全屏的用户仍可在设置 → 外观与显示里打开（岛会贴住摄像头）。
         */
        private const val KEY_IMMERSIVE = "immersive_statusbar"
        private const val DEFAULT_IMMERSIVE = true

        @JvmStatic
        fun edgeToEdgeEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                .getBoolean(KEY_IMMERSIVE, DEFAULT_IMMERSIVE)

        @JvmStatic
        fun setEdgeToEdgeEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_IMMERSIVE, on).apply()
        }

        /**
         * 状态栏（顶部通知栏）显隐偏好（v1.6.50）。
         *
         * ## 为什么单独一个键，而不是复用 [KEY_IMMERSIVE]
         *
         * 「内容要不要画到系统栏后」与「状态栏本身要不要显示」是两件事：
         *   · 边到边关掉时，用户明确选了"带状态栏"的形态 —— 这时不允许隐藏；
         *   · 边到边开着时，全屏沉浸才是默认期待（岛已经能精确对齐挖孔）。
         * 所以拆成两个偏好：[KEY_IMMERSIVE] 管内容框，本键管状态栏可见性，
         * 后者**只在边到边开启时生效**（见 [statusBarHidden]）。
         *
         * 默认 **true**（用户要求：进入边到边即默认全屏沉浸）。
         * 老用户升级后第一次打开会直接进入沉浸态，岛会自动贴住挖孔 ——
         * 想要带状态栏的，点岛上的状态栏按钮或去设置里关掉即可，都是即时生效。
         */
        private const val KEY_HIDE_STATUS = "hide_status_bar"
        private const val DEFAULT_HIDE_STATUS = true

        @JvmStatic
        fun hideStatusBarEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                .getBoolean(KEY_HIDE_STATUS, DEFAULT_HIDE_STATUS)

        @JvmStatic
        fun setHideStatusBarEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_HIDE_STATUS, on).apply()
        }

        /**
         * "此刻状态栏到底该不该隐藏" —— 开关的**唯一合成点**。
         *
         * 读偏好的地方（[app.dsh.mobile.ui.Insets.applyWindow] 的默认参数、
         * 岛上的按钮、设置页回显）全部走这里，不再各算一遍：
         * 两个布尔的组合一旦分散到多处，必然出现"按钮说隐藏了但设置页勾着"的漂移。
         */
        @JvmStatic
        fun statusBarHidden(ctx: Context): Boolean =
            edgeToEdgeEnabled(ctx) && hideStatusBarEnabled(ctx)

        /** 竖屏页面缩放范围/步长/默认值（等价浏览器 Ctrl- 缩小一点，用户可再调） */
        private const val DEFAULT_PAGE_SCALE = 90
        private const val MIN_PAGE_SCALE = 50
        private const val MAX_PAGE_SCALE = 150
        private const val SCALE_STEP = 5

        /**
         * 视口 meta 应用后，等待渲染进程完成重排/缩放再整页重栅格化的时间（v1.4.6）。
         * 必须小于 `dismissSplash()` 的最短等待（`splashMinMs` + 180ms），
         * 这样重栅格化始终发生在启动遮罩之下，用户看不到软件渲染帧。
         */
        private const val VIEWPORT_SETTLE_MS = 120L


        /**
         * 桌面渲染的最小窗口宽度（dp，v1.5.1）。
         *
         * 桌面视口固定 `width=1280`，窗口比它窄多少，内容就等比缩多少。小窗常见宽度
         * 468dp → 0.366 倍，字号只剩四成。768dp 是桌面三栏的实用下限：低于它即使是
         * 横屏长条，桌面布局也只能靠缩，不如给移动渲染。
         *
         * ⚠️ 只在多窗口下用（见 [isWindowed]），全屏形态不受影响。
         */
        private const val DESKTOP_MIN_WIDTH_DP = 768

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * 软切换后等一帧再校验 `documentElement.clientWidth` 的时延（v1.6.42）。
         *
         * 取 380ms：大于 [VIEWPORT_SETTLE_MS] 的去抖窗，且跨过大多数设备转屏动画的
         * 布局稳定点；只用于“校验失败才 reload”的兜底路径，不阻塞任何可见交互。
         */
        private const val MODE_SWITCH_VERIFY_MS = 380L
    }
}
