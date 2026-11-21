package app.dsh.mobile

import android.app.AlertDialog
import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.EngineV8Flags
import app.dsh.mobile.engine.ExtensionManager
import app.dsh.mobile.engine.FakeLinuxEnv
import app.dsh.mobile.engine.PluginFastBoot
import app.dsh.mobile.engine.PluginPriority
import app.dsh.mobile.engine.PrivMode
import app.dsh.mobile.engine.Privilege
import app.dsh.mobile.engine.RuntimeDownloadConfig
import app.dsh.mobile.engine.LanWebProxy
import app.dsh.mobile.search.SearchAnchor
import app.dsh.mobile.search.SearchHit
import app.dsh.mobile.search.SearchPage
import app.dsh.mobile.search.SettingsSearchCatalog
import app.dsh.mobile.status.FloatingPrefs
import app.dsh.mobile.status.FloatingStatusService

/**
 * 独立设置页（MIUI 分组卡片风格，替代原先的悬浮菜单）。
 *
 * 三个分组：
 *  - 显示：屏幕朝向（竖屏/横屏/自动，v1.2.120）、页面缩放
 *  - 权限中心（统一管理运行权限与无障碍）：运行权限模式、Root 能力状态、Shizuku 状态、屏幕点击（无障碍）
 *  - 其他：重启引擎、关于
 *
 * 权限切换与缩放均复用 MainActivity 的既有决策（Root 双警告、缩放 −/＋ 步进、
 * 无障碍跳系统设置），落库到同一组 SharedPreferences，MainActivity 在 onResume 重新读取生效。
 */
class SettingsActivity : ThemedActivity() {
    /** 属于设置体系：启用「设置页背景图」（见 ThemedActivity.usesSettingsBackground）。 */
    override val usesSettingsBackground: Boolean get() = true


    /** 上次应用的主题/强调色/布局快照：子页改动后回到本页检测重建（v1.4.56） */
    private var appliedTheme: ThemeMode = ThemeMode.DARK
    private var appliedAccent: Int = 0
    private var appliedLayout: SettingsLayoutMode = SettingsLayoutMode.LIST

    // ─── 头部折叠停靠（v1.6.23）───────────────────────────────────────────
    // 只有一个可变位：正在跑的吸附动画。没有 isCollapsed 开关 —— 头部外观永远是
    // scrollY 的纯函数（applyHeaderTransform），所以拖动、吸附动画、被打断后的
    // 接管，走的是同一条映射，不会跳变。
    private var headerAnim: android.animation.ValueAnimator? = null
    private var settleTick: Runnable? = null
    private var userDragging = false
    private val settleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var headerScrollRef: android.widget.ScrollView? = null

    // ── 头部控件引用（v1.6.45）──────────────────────────────────────────────
    // 旧实现在**每次滚动回调**里做两次 findViewById（标题 + 搜索栏）。
    // ScrollView 一秒能给几十次回调，两次树查找/次是纯粹的白烧。
    // 这里在 setupScrollEffect 里取一次。
    private var headerTitle: TextView? = null
    private var headerBar: app.dsh.mobile.ui.CollapsingTitleBar? = null
    private var headerSearchBar: android.view.View? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v1.5.x：大屏跟随偏好、手机锁竖屏；用户可在外观页打开「设置页跟随屏幕方向」
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_settings)
        findViewById<LinearLayout>(R.id.rowAppProfile)?.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        setupSearch()
        applyResponsiveLayout()


        // —— 扩展：局域网访问 WebUI（v1.2.160/v1.2.161）——
        // 独立端口 3090 的反向代理，让同网段设备用原生 WebUI 连上本机引擎。
        // v1.2.161（用户要求）：验证码改为 **6 位数字 + 每次开启重新随机**，
        // 生成后**自动复制到剪贴板**，且**只在应用内显示一次**（退出再进设置不显示）。
        val swLan = findViewById<android.widget.Switch>(R.id.swLanWeb)
        val lanSub = findViewById<android.widget.TextView>(R.id.lanWebSub)
        // ⚠️ v1.2.162 实测修复（用户反馈"关闭时显示网址、开启时反而不显示"）：
        // 旧实现用 LanWebProxy.isRunning() 判断 —— 但代理的启停都是**异步**的
        // （拉起要 1-3s，停止要等进程回收）→ 点开关的瞬间读到的还是旧状态，
        // 表现正好相反。改为**以开关自身的状态为准**（用户看到的就是他点的那个状态），
        // 地址只在该状态为"开"时展示。
        // v1.2.162（用户要求）：**行内不再保留验证码** —— 验证码只在开启时的
        // 提示框里显示一次，避免长期驻留。行内只显示说明与访问地址。
        fun lanSubText() {
            val on = swLan.isChecked
            val addr = "http://" + (LanWebProxy.lanAddress() ?: "?") + ":" + LanWebProxy.PORT
            lanSub.text = if (!on) getString(R.string.setting_lan_web_sub)
            else getString(R.string.setting_lan_web_sub) + "\n\n" + addr
        }
        /** 复制到剪贴板（6 位码，方便直接粘到电脑上） */
        fun copyCode(code: String) {
            runCatching {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("DSH LAN code", code))
            }
        }
        swLan.isChecked = DshApp.lanWebEnabled(this)
        // v1.2.162：验证码只在**刚生成时**用提示框展示一次（行内不再保留）。
        val pendingShown = DshApp.lanWebPwPending(this)
        if (pendingShown && DshApp.lanWebEnabled(this)) {
            val c = DshApp.lanWebPassword(this)
            runCatching {
                LanAccessDialog.show(
                    this, c,
                    "http://" + (LanWebProxy.lanAddress() ?: "?") + ":" + LanWebProxy.PORT,
                )
            }
            DshApp.markLanWebPwShown(this)   // 只显示一次
        }
        lanSubText()
        swLan.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.setting_lan_web)
                    .setMessage(R.string.lan_web_consent)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        DshApp.setLanWebEnabled(this, true)
                        // 每次开启都重新生成 6 位验证码
                        val code = DshApp.regenerateLanWebPassword(this)
                        // v1.2.162（用户要求）：改用「提示框」展示 —— 验证码显著、可点复制；
                        // 访问地址紧贴其下（小字、也可复制）；行内不再保留验证码。
                        runCatching {
                            LanAccessDialog.show(
                                this, code,
                                "http://" + (LanWebProxy.lanAddress() ?: "?") + ":" + LanWebProxy.PORT,
                            )
                        }
                        lanSubText()
                        DshApp.markLanWebPwShown(this)
                        Thread({
                            runCatching { LanWebProxy.start(code) }
                                .onFailure { android.util.Log.w("Settings", "lan proxy: ${it.message}") }
                        }, "lan-start").apply { isDaemon = true }.start()
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> swLan.isChecked = false }
                    .setOnCancelListener { swLan.isChecked = false }
                    .show()
            } else {
                DshApp.setLanWebEnabled(this, false)
                Thread({ runCatching { LanWebProxy.stop() } }, "lan-stop").apply { isDaemon = true }.start()
                lanSubText()
            }
        }

        // —— 系统悬浮窗状态条（v1.6.35）——
        // 需求 1：总开关；需求 4：权限缺失给引导，授权后自动恢复。
        setupFloatStatusRow()

        // —— 扩展：运行时下载代理（v1.4.21）——
        // 运行时 zip / 签名索引都从 GitHub Releases 取；国内直连经常超时。
        // 默认 gh-proxy.org，可换自建前缀，留空则直连。
        refreshRuntimeProxyRow()
        findViewById<LinearLayout>(R.id.rowRuntimeProxy).setOnClickListener {
            showRuntimeProxyDialog()
        }


        // —— 顶部返回 ——

        // —— 个性化入口（v1.4.56）：外观/扩展整组收进子页，主页只留入口 ——
        val savedLayout = SettingsLayoutMode.current(this)
        appliedTheme = ThemePref.get(this)
        appliedAccent = Accent.current(this).argb
        appliedLayout = savedLayout
        findViewById<LinearLayout>(R.id.rowAppearance).setOnClickListener {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }
        // v1.4.58（宝宝要求）：扩展中心不再分组，直接显示在设置第一屏
        findViewById<LinearLayout>(R.id.rowExt).setOnClickListener {
            startActivity(Intent(this, ExtensionStoreActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.rowExtensionVoice).setOnClickListener {
            startActivity(Intent(this, ExtensionVoiceActivity::class.java))
        }
        if (savedLayout != SettingsLayoutMode.LIST) {
            // 布局参数改动需要在视图完成布局后进行
            findViewById<View>(R.id.columnPrimary)?.post { applyLayoutMode(savedLayout) }
        }

        // —— 权限与能力：运行身份 + 系统能力合并为同一页 ——
        findViewById<LinearLayout>(R.id.rowPermEngine).setOnClickListener {
            startActivity(Intent(this, PermissionCenterActivity::class.java))
        }


        // 自定义指令（v1.3.2，issue #19）：在 App 的环境说明之上补写用户自己的长期指令。
        // 落点是 AGENTS.md 的用户段 —— 引擎只加载这一个 user-global 指令文件，
        // 分段管理后升级 App 不会丢用户内容，用户改指令也不会让环境说明变旧。
        findViewById<LinearLayout>(R.id.rowUserPrompt).setOnClickListener {
            startActivity(Intent(this, UserPromptActivity::class.java))
        }

        // —— 数据：备份与恢复子页（v1.4.46：导出/导入/恢复对话/配置文件收拢）——
        findViewById<LinearLayout>(R.id.rowBackup).setOnClickListener {
            startActivity(Intent(this, BackupActivity::class.java))
        }

        

        // —— Linux 环境伪装：原生 Android 上模拟 Debian/Linux 识别层 ——
        val fakeLinux = findViewById<android.widget.Switch>(R.id.swFakeLinux)
        fakeLinux?.isChecked = FakeLinuxEnv.enabled(this)
        fakeLinux?.setOnCheckedChangeListener { _, on ->
            FakeLinuxEnv.setEnabled(this, on)
            Toast.makeText(this, R.string.fake_linux_restarting, Toast.LENGTH_SHORT).show()
            (application as DshApp).supervisor.restart()
        }

        // —— V8 年轻代加宽（v1.6.49）：默认关，明确告知「内存换 CPU」——
        // 为什么不默认开：`--max-semi-space-size=N` 会让 V8 预留约 3×N 的常驻空间，
        // 4 GB 设备上盲设反而会把后台应用挤到被杀。判定标准写在 EngineV8Flags 的
        // KDoc 里（先 --trace-gc 量 scavenge 频率，再决定）。
        val wideYoung = findViewById<android.widget.Switch>(R.id.swV8WideYoung)
        wideYoung?.isChecked = EngineV8Flags.wideYoungGen(this)
        wideYoung?.setOnCheckedChangeListener { _, on ->
            EngineV8Flags.setWideYoungGen(this, on)
            Toast.makeText(this, R.string.v8_wide_young_changed, Toast.LENGTH_SHORT).show()
            (application as DshApp).supervisor.restart()
        }

        // —— 插件快速启动：先显示 WebUI，第三方插件后台流式加载 ——
        val fastBoot = findViewById<android.widget.Switch>(R.id.swFastPluginBoot)
        fastBoot?.isChecked = PluginFastBoot.enabled(this)
        fastBoot?.setOnCheckedChangeListener { _, on ->
            PluginFastBoot.setEnabled(this, on)
            Toast.makeText(this, R.string.fast_plugin_boot_changed, Toast.LENGTH_SHORT).show()
        }

        // —— 插件加载优先级（v1.6.29）：已装的 dsh-web-mobile 排到最前、首轮就绪 ——
        // 关掉 = 它不再排首位、也不排除在「推迟加载」之外（下次启动引擎生效）。
        val priority = findViewById<android.widget.Switch>(R.id.swPluginPriority)
        priority?.isChecked = PluginPriority.enabled(this)
        priority?.setOnCheckedChangeListener { _, on ->
            PluginPriority.setEnabled(this, on)
            Toast.makeText(this, R.string.plugin_priority_changed, Toast.LENGTH_SHORT).show()
            (application as DshApp).supervisor.restart()
        }

        // —— 维护与高级（v1.4.45）：诊断与高风险操作收进子页，主页只留入口 ——
        findViewById<LinearLayout>(R.id.rowMaintenance).setOnClickListener {
            startActivity(Intent(this, MaintenanceActivity::class.java))
        }

        // —— 其他：语言 Language（v1.2.120：en/de/zh 三选一，默认 en）——
        // 语言名一律用该语言自身的写法（endonym）：中文恒为「中文」、德语恒为
        // 「Deutsch」、葡语恒为「Português」，泰语恒为「ไทย」。这样无论当前界面是哪种语言，用户在
        // 列表里都能靠母语认出自己要选的那一项（旧实现按当前 locale 翻译成
        // 「Chinese / Chinesisch / Chinês」，中文用户看到的反而是外语）。
        val langCodes = listOf("en", "de", "zh", "pt", "th")
        fun langLabel(code: String): String = when (code) {
            "zh" -> getString(R.string.lang_zh)
            "de" -> getString(R.string.lang_de)
            "pt" -> getString(R.string.lang_pt)
            "th" -> getString(R.string.lang_th)
            else -> getString(R.string.lang_en)
        }
        val langSub = findViewById<TextView>(R.id.appLanguageSub)
        fun refreshLangSub() {
            langSub.text = getString(
                R.string.app_language_sub_current,
                langLabel(LocaleHelper.get(this)),
            )
        }
        refreshLangSub()
        findViewById<LinearLayout>(R.id.rowLanguage).setOnClickListener {
            val choices = arrayOf(
                getString(R.string.lang_en),
                getString(R.string.lang_de),
                getString(R.string.lang_zh),
                getString(R.string.lang_pt),
                getString(R.string.lang_th),
            )
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_language))
                .setSingleChoiceItems(choices, langCodes.indexOf(LocaleHelper.get(this))) { dlg, which ->
                    val code = langCodes[which]
                    if (code == LocaleHelper.get(this)) { dlg.dismiss(); return@setSingleChoiceItems }
                    LocaleHelper.set(this, code)
                    dlg.dismiss()
                    // 立即生效：本页原地重建（recreate 会重走 attachBaseContext →
                    // 按新语言重新 inflate 布局），用户马上看到文案变化。
                    //
                    // 旧实现是「清任务栈 + 重新拉起 Launcher」——那是跨进程重建，
                    // 用户实测「切换后 UI 不能及时响应」（要等整个应用重开）。
                    // 现在只重建当前页；MainActivity 在 onResume 会检测到语言变化
                    // 并自行重建，返回主界面时也是新语言。
                    recreate()
                }
                .showStyled()
        }

        // —— 其他：关于 ——
        findViewById<TextView>(R.id.subAbout).text =
            getString(R.string.setting_about_sub, versionName())
        findViewById<LinearLayout>(R.id.rowAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    /** 从包管理器读取 versionName（AGP 8+ 默认关闭 BuildConfig，避免依赖它） */
    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "?"

    

    /**
     * 转屏不重建（Manifest 已声明 configChanges）：只切换宽/窄布局，
     * 保留滚动位置、已打开的对话框和进行中的数据导出/扩展安装任务。
     *
     * ⚠️ 必须把 [newConfig] 传下去：此刻 `resources.configuration` 仍是**转屏前**的
     * 那一份（实测 newCfg 800dp/竖屏、res 1280dp/横屏），照着 resources 算会把
     * 横屏双栏硬塞进竖屏里。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyResponsiveLayout(newConfig)
    }

    override fun onResume() {
        super.onResume()
        // 从外观页返回时偏好可能已变（开关/朝向），重新对齐一次；值未变时是空操作
        OrientationPrefs.applyToSettingsPage(this)
        // 子页里改过主题/强调色/布局：回到本页立即重建，保证视觉与偏好一致（v1.4.56）
        if (appliedTheme != ThemePref.get(this) ||
            appliedAccent != Accent.current(this).argb ||
            appliedLayout != SettingsLayoutMode.current(this)
        ) {
            recreate()
            return
        }
        refreshAppearanceEntry()
        refreshExtEntry()
        // v1.6.35：从系统设置授权回来 → 自动拉起悬浮窗服务并刷新行状态
        refreshFloatStatusRow()
    }

    // ================= 系统悬浮窗状态条（v1.6.35） =================

    /**
     * 悬浮窗状态显示开关。
     *
     * ## 权限与开关的关系（需求 4）
     * 「开」是用户的**意图**，权限是系统门禁 —— 两者分开存：
     *  · 用户打开开关但没有权限：仍然记下 enabled=true，弹引导去授权；
     *  · 授权回来后 onResume 自动拉起服务（无需再手动点一次）；
     *  · 权限被中途关闭：服务自己撤窗并保留开关，恢复后自动挂回。
     */
    private fun setupFloatStatusRow() {
        val sw = findViewById<android.widget.Switch>(R.id.swFloatStatus) ?: return
        sw.isChecked = FloatingPrefs.enabled(this)
        sw.setOnCheckedChangeListener { _, on ->
            if (on) {
                FloatingPrefs.setEnabled(this, true)
                if (android.provider.Settings.canDrawOverlays(this)) {
                    FloatingStatusService.start(this)
                    Toast.makeText(this, R.string.float_status_on, Toast.LENGTH_SHORT).show()
                } else {
                    // 没有权限：先记下意图，再引导（不把开关拨回去 —— 那样恢复后还得再点一次）
                    FloatingPrefs.setGuideShown(this, true)
                    showFloatPermissionGuide()
                }
            } else {
                FloatingStatusService.stop(this)
                Toast.makeText(this, R.string.float_status_off, Toast.LENGTH_SHORT).show()
            }
            refreshFloatStatusRow()
        }
        // 点整行：已开启但缺权限 → 再弹一次引导；否则提示当前状态
        findViewById<android.widget.LinearLayout>(R.id.rowFloatStatus)?.setOnClickListener {
            if (FloatingPrefs.enabled(this) && !android.provider.Settings.canDrawOverlays(this)) {
                showFloatPermissionGuide()
            }
        }
    }

    /** 刷新副标题：正常显示说明；缺权限时变成醒目的引导文案。 */
    private fun refreshFloatStatusRow() {
        val sub = findViewById<TextView>(R.id.floatStatusSub) ?: return
        val enabled = FloatingPrefs.enabled(this)
        val canDraw = android.provider.Settings.canDrawOverlays(this)
        sub.text = when {
            enabled && !canDraw -> getString(R.string.float_status_need_permission)
            else -> getString(R.string.float_status_sub)
        }
        // 缺权限时把副标题染成警示色（与设置页其它告警一致的做法：强调色）
        sub.setTextColor(
            if (enabled && !canDraw) Accent.current(this).argb
            else getColor(R.color.text_muted),
        )
        // 授权后自动恢复（从系统设置返回 → onResume → 这里）
        if (enabled && canDraw) FloatingStatusService.restoreIfEnabled(this)
    }

    /** 权限引导弹框：说明 + 直达系统「显示在其他应用上层」页。 */
    private fun showFloatPermissionGuide() {
        AlertDialog.Builder(this)
            .setTitle(R.string.float_status_perm_title)
            .setMessage(R.string.float_status_perm_msg)
            .setPositiveButton(R.string.float_status_perm_go) { _, _ ->
                runCatching {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName"),
                        ),
                    )
                }.onFailure {
                    // 部分 ROM 不认带 package 的 URI，退回总列表页
                    runCatching {
                        startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            ),
                        )
                    }
                }
            }
            .setNegativeButton(R.string.float_status_perm_later, null)
            .show()
    }

    /** 个性化入口副标题：当前主题 · 强调色（v1.4.56） */
    private fun refreshAppearanceEntry() {
        findViewById<TextView>(R.id.subAppearanceEntry)?.text = getString(
            R.string.setting_appearance_sub,
            getString(ThemePref.get(this).labelRes),
            getString(Accent.current(this).labelRes),
        )
    }

    /** 扩展中心行副标题：已激活扩展数（v1.4.58 起该行回到主页第一屏） */
    private fun refreshExtEntry() {
        val count = runCatching { ExtensionManager(this).activeCount() }.getOrDefault(0)
        findViewById<TextView>(R.id.valExt)?.text =
            getString(R.string.setting_ext_active_count, count)
    }

    // ================= 设置搜索（v1.6.38 重构，对齐 settings-preview） =================
    //
    // ## 旧实现为什么会「取消后残留 + 组件叠加」
    //
    // 旧版把三样东西各自当成**固定悬浮兄弟节点**叠在 ScrollView 之上：
    //   ① 顶栏底色 62dp ② 大标题 ③ 首页搜索栏（marginTop=125dp）
    // 然后用手写的 scaleY / 子 View alpha 去模拟「随滚动收缩」，靠
    // `openSearch()` / `closeSearch()` 里成对的赋值来还原。于是状态是**散落**的：
    //   · `openSearch()` 先把 `searchOpen = true`，再 `findViewById(overlay) ?: return`
    //     —— 一旦 overlay 取不到（布局被裁剪/主题重建中），函数提前返回，
    //     但 `searchOpen` 已经是 true，且标题已被 `titleFlight(true)` 打成 alpha=0。
    //   · `closeSearch()` 同样是「先置 false 再 ?: return」，overlay 取不到时
    //     连 alpha 都不收，搜索页就以 alpha≈1 永远盖在设置页上。
    //   · 恢复动作写在 `overlay.postDelayed(320ms)` 里，被取消/Activity 重入就永久丢失。
    //   · `applyHeaderTransform()` 只写 scale/translationY，**从不写 alpha** ——
    //     标题的 alpha 一旦被搜索动画改成 0 就再也没人负责把它写回 1。
    //   结果就是截图里那一幕：34sp 的「设置」以 1.0 缩放压在列表上、
    //   首页栏的放大镜/麦克风残留在固定层里，与列表条目互相叠。
    //
    // ## 新实现的三条原则
    //  1. **搜索入口移进滚动内容**（见 XML）：它随内容滚走，收缩只是几何变换，
    //     不存在需要还原的视觉状态 —— 从根上消掉「残留在列表上方」的可能。
    //  2. **头部外观是 scrollY 的纯函数**：`applyHeaderTransform()` 每次写全
    //     scale/translationY **以及 alpha=1**。任何一次调用之后，头部一定处于
    //     一个完整、自洽的状态，不存在"半截"。
    //  3. **收尾只有一个入口**：`resetSearchUi()` 幂等，动画 onEnd、兜底延时、
    //     onDestroy 都会调它；先取控件再改状态，任何一步失败都不会留下中间态。
    //     搜索页的可见性只由 `overlay.visibility` 决定，alpha 只是包装。

    private var searchOpen = false
    /** 标题飞行动画（打开时收起、关闭时沿滚动轨迹展开）。 */
    private var titleAnim: android.animation.ValueAnimator? = null

    private fun setupSearch() {
        // 首页入口现在是普通内容行：整行 + 语音键都能点开搜索。
        val home = findViewById<View>(R.id.homeSearchBar)
        home?.setOnClickListener { openSearch() }
        findViewById<View>(R.id.searchSettings)?.setOnClickListener { openSearch() }
        findViewById<View>(R.id.btnVoiceSearch)?.setOnClickListener { startVoiceSearch() }

        val input = findViewById<EditText>(R.id.searchInput)
        input?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                renderSearch(s?.toString().orEmpty())
            }
        })
        input?.setOnEditorActionListener { _, actionId, event ->
            val isSearch = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            val isEnter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN
            if (isSearch || isEnter) {
                // 回车 = 跳到第一条结果（与点击结果同一套跳转逻辑，
                // 因此跨页结果也会带着锚点落到目标子项）。
                lastHits.firstOrNull()?.let { jumpTo(it) }
                true
            } else false
        }
        findViewById<View>(R.id.btnSearchClear)?.setOnClickListener {
            input?.setText("")
            input?.requestFocus()
        }
        findViewById<View>(R.id.btnSearchCancel)?.setOnClickListener { closeSearch() }
        setupScrollEffect()
        setupPerfRows()
    }

    // ─── 性能与流畅度（v1.6.45）────────────────────────────────────────────

    /**
     * 四个旋钮的绑定。
     *
     * ## 为什么是这四个
     *
     * 它们各自对应一个**已经存在但此前写死**的性能策略 —— 也就是"把已经在跑的
     * 优化暴露出来"，而不是新造一套逻辑去赌收益：
     *
     * | 旋钮 | 接到哪里 | 真实收益 |
     * |---|---|---|
     * | 界面动效 | ui.Motion / ThemedActivity / 头部吸附 | 低端机上少掉全屏位移合成 |
     * | 内核渲染 | web.ThermalRenderGuard 的触发条件 | 卸模糊/阴影/强制合成层 |
     * | 首屏预载 | web.WebAssetCache.preloadHot | 首屏首次命中内存，少一次磁盘/回环 |
     * | 大图预解码 | web.ImagePreScaler | 少一次原始分辨率解码 + 纹理上传 |
     *
     * 摘要文字直接显示**当前值**，用户不用点进去就知道现在是什么档。
     */
    private fun setupPerfRows() {
        findViewById<View>(R.id.rowPerfMotion)?.setOnClickListener {
            pickPerf(
                titleRes = R.string.perf_motion,
                options = PerfPrefs.MotionLevel.entries.map { it.labelRes },
                current = PerfPrefs.MotionLevel.entries.indexOf(PerfPrefs.motion(this)),
            ) { idx ->
                PerfPrefs.setMotion(this, PerfPrefs.MotionLevel.entries[idx])
                perfSummary()
            }
        }
        findViewById<View>(R.id.rowPerfRender)?.setOnClickListener {
            pickPerf(
                titleRes = R.string.perf_render,
                options = PerfPrefs.RenderProfile.entries.map { it.labelRes },
                current = PerfPrefs.RenderProfile.entries.indexOf(PerfPrefs.render(this)),
            ) { idx ->
                val v = PerfPrefs.RenderProfile.entries[idx]
                PerfPrefs.setRender(this, v)
                perfSummary()
                // 立刻对当前 WebUI 生效，不等下次导航 —— 否则用户改完看不到任何变化。
                runCatching {
                    app.dsh.mobile.web.ThermalRenderGuard.applyProfileToLivePage(this, v)
                }
            }
        }
        findViewById<View>(R.id.rowPerfPreload)?.setOnClickListener {
            PerfPrefs.setPreload(this, !PerfPrefs.preload(this))
            perfSummary()
        }
        findViewById<View>(R.id.rowPerfPrescale)?.setOnClickListener {
            val on = !PerfPrefs.preScale(this)
            PerfPrefs.setPreScale(this, on)
            app.dsh.mobile.web.ImagePreScaler.setEnabled(on)   // 立即生效，不等重启
            perfSummary()
        }
        perfSummary()
    }

    /** 把四个摘要文字刷成当前值（幂等，任意时刻调用都对）。 */
    private fun perfSummary() {
        findViewById<TextView>(R.id.subPerfMotion)?.text =
            getString(PerfPrefs.motion(this).labelRes)
        findViewById<TextView>(R.id.subPerfRender)?.text =
            getString(PerfPrefs.render(this).labelRes)
        findViewById<TextView>(R.id.subPerfPreload)?.text = getString(
            if (PerfPrefs.preload(this)) R.string.perf_on else R.string.perf_off,
        )
        findViewById<TextView>(R.id.subPerfPrescale)?.text = getString(
            if (PerfPrefs.preScale(this)) R.string.perf_on else R.string.perf_off,
        )
    }

    /** 通用「单选 → 立即生效」对话框（复用主题里的弹窗动画）。 */
    private fun pickPerf(
        titleRes: Int,
        options: List<Int>,
        current: Int,
        onPick: (Int) -> Unit,
    ) {
        val labels = options.map { getString(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setSingleChoiceItems(labels, current) { d, which ->
                onPick(which)
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 大标题收缩吸顶、搜索入口压缩淡出（对齐 preview 的 115 / 60 两个进度）。 */
    private fun setupScrollEffect() {
        val scroller = findViewById<android.widget.ScrollView>(R.id.settingsScroller) ?: return
        headerScrollRef = scroller
        headerTitle = findViewById(R.id.settingsTitle)
        headerBar = findViewById(R.id.settingsTitleBar)
        headerSearchBar = findViewById(R.id.homeSearchBar)
        headerBar?.reset()
        applyHeaderTransform(0)
        scroller.setOnScrollChangeListener { _, _, y, _, _ ->
            // 搜索打开时不改头部：此时标题由 titleAnim 单独接管，
            // 两边同时写同一批属性就是旧版闪烁的成因。
            if (searchOpen || titleAnim != null) return@setOnScrollChangeListener
            applyHeaderTransform(y)
        }
        installHeaderSnap(scroller)
    }

    /**
     * 折叠区间的两个停靠点。
     *
     * v1.6.45 重新推导（旧值 115 / 60 是给"浮层标题连续缩放"那套用的）：
     *
     * · 大标题占据 60~100dp（34sp，行高约 40dp）；
     * · 不透明标题条 62dp；
     * · 内容相对标题条的"完全收起"位置 = 搜索栏顶边（125dp）落到标题条下沿（62dp）
     *   → scrollY = 125 - 62 = 63dp。
     *
     * 用 63 而不是 115：收起后内容刚好齐平标题条下沿，不多滚 52dp 的空白，
     * 用户"轻轻一划"就完成收起，观感更利落。
     */
    private val collapseEndPx get() = dp(63)
    private val snapAtPx get() = dp(32)

    /** 交叉淡化起点：大标题底边（100dp）越过标题条下沿（62dp）→ scrollY = 38dp。 */
    private val fadeStartPx get() = dp(38)

    /**
     * 接入停靠判定。返回 false 不消费事件 —— 滚动、fling、点击分发仍由
     * ScrollView 自己做，我们只在旁边看一眼手指何时离屏。
     */
    private fun installHeaderSnap(scroller: android.widget.ScrollView) {
        scroller.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    userDragging = true
                    // 规格 5：用户输入优先。动画只写过 scrollY，这里 cancel 后
                    // 从当前 scrollY 接着拖，不需要任何补齐。
                    cancelHeaderAnim()
                    cancelSettle()
                }

                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    userDragging = false
                    // 规格 6：松手不立即判定。fling 还在跑时抢着 scrollTo，会和
                    // OverScroller 互拉，表现为“半路被拽一下”或二次回弹。
                    if (!searchOpen) armSettle(scroller)
                }
            }
            false
        }
    }

    /**
     * 等惯性自然停稳：每 8ms 采样一次 scrollY，连续 [SETTLE_IDLE_MS] 不变就认为 fling 结束。
     * 只靠时间阈值而不靠“fling 完毕”回调：ScrollView 没有对外暴露 OverScroller 状态。
     */
    private fun armSettle(scroller: android.widget.ScrollView) {
        cancelSettle()
        var lastY = -1
        var stableSince = 0L
        var waited = 0L
        lateinit var tick: Runnable
        tick = Runnable {
            if (searchOpen || userDragging || headerAnim != null) { cancelSettle(); return@Runnable }
            val y = scroller.scrollY
            val now = android.os.SystemClock.uptimeMillis()
            if (y != lastY) {
                lastY = y
                stableSince = now
            } else if (stableSince > 0 && now - stableSince >= SETTLE_IDLE_MS) {
                cancelSettle()
                decideSnap(y)
                return@Runnable
            }
            waited += SETTLE_TICK_MS
            // 万一 fling 一直不进入静止（长列表到底/到顶的微振荡），1.2s 后也要收场，
            // 不能让这个采样循环常驻。
            if (waited < SETTLE_MAX_WAIT_MS) settleHandler.postDelayed(tick, SETTLE_TICK_MS)
            else { cancelSettle(); decideSnap(scroller.scrollY) }
        }
        settleTick = tick
        settleHandler.postDelayed(tick, SETTLE_TICK_MS)
    }

    private fun cancelSettle() {
        settleTick?.let { settleHandler.removeCallbacks(it) }
        settleTick = null
    }

    private fun cancelHeaderAnim() {
        headerAnim?.cancel()
        headerAnim = null
    }

    /** 规格 2/3/4：同一个判定的三个分支 —— 回 0、吸到 115、或不接管。 */
    private fun decideSnap(off: Int) {
        if (off > collapseEndPx) return          // 区间外：滚动已属于内容，不拽回
        animateHeaderTo(if (off < snapAtPx) 0 else collapseEndPx)
    }

    /**
     * 吸附动画只改滚动偏移（规格 7）。外观仍走 applyHeaderTransform 那一套映射，
     * 因为 scrollTo() 会触发 onScrollChangeListener —— 所以标题与搜索入口是“继续
     * 连续变形过去”，不会先跳到终点再补齐。
     */
    private fun animateHeaderTo(target: Int) {
        val scroller = headerScrollRef ?: return
        val from = scroller.scrollY
        if (from == target) return
        if (headerMotionDisabled()) {                    // 规格 8：减弱动效直接瞬跳
            scroller.scrollTo(0, target)
            return
        }
        cancelHeaderAnim()
        headerAnim = android.animation.ValueAnimator.ofInt(from, target).apply {
            duration = HEADER_SNAP_MS
            interpolator = android.view.animation.PathInterpolator(0.215f, 0.61f, 0.355f, 1f) // ease-out cubic
            addUpdateListener { scroller.scrollTo(0, it.animatedValue as Int) }
            // 不用 core-ktx 的 doOnEnd：cancel() 同样会走到这里，用引用比较避开
            // “旧动画的结束回调把新动画的引用摸掉”。
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (headerAnim === animation) headerAnim = null
                }
            })
            start()
        }
    }

    /** 系统“移除动画”开关（开发者选项里的 transition/global animation scale） */
    private fun headerMotionDisabled(): Boolean =
        // v1.6.45：用户的动效档位为「关闭」时，头部吸附与标题淡出也一律瞬切。
        PerfPrefs.motion(this) == PerfPrefs.MotionLevel.OFF || systemAnimationsOff()

    private fun systemAnimationsOff(): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(
            contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) == 0f
    }.getOrDefault(false)

    /**
     * 头部外观 = scrollY 的**纯函数**。
     *
     * 关键点（v1.6.38）：这里每次都会把 `alpha` 一并写成 1。旧版不碰 alpha，
     * 所以搜索动画留下的 `alpha=0` 没有任何人能够还原 —— 那正是「取消后大字压在列表上」。
     * 现在只要这个函数被调用一次（滚动、取消搜索、恢复、重建），头部就必然是完整状态。
     */
    private fun applyHeaderTransform(scrollY: Int) {
        // ★ 大标题（在滚动内容里）：只还原 alpha，绝不写 scale。
        //   旧版在这里每帧做 scaleX/scaleY → 字形逐帧重栅格化 → 文字"抖"。
        //   现在它就是普通内容，随内容自然上移、被裁、被不透明标题条盖住。
        headerTitle?.let {
            it.alpha = 1f
            it.translationY = 0f
        }
        // ★ 小标题（吸顶条内）：由 scrollY 映射成一个 0..1 的进度，只改 alpha+平移。
        headerBar?.setProgress(
            (scrollY - fadeStartPx).toFloat() / (collapseEndPx - fadeStartPx).toFloat(),
        )
        headerSearchBar?.let { bar ->
            val cp = (scrollY / collapseEndPx.toFloat()).coerceIn(0f, 1f)
            // preview：以底边为原点纵向压缩（43 → 0）。pivot 放底边，视觉上
            // 是「收进下方卡片里」，比顶边收缩更连贯。
            bar.pivotX = bar.width / 2f
            bar.pivotY = bar.height.toFloat()
            bar.scaleY = 1f - cp
            bar.alpha = 1f                      // ★ 同上（旧版在 openSearch 里设 0 后靠延时还原）
            val a = (1f - cp * 1.8f).coerceIn(0f, 1f)
            (bar as? android.view.ViewGroup)?.let { bg ->
                for (i in 0 until bg.childCount) bg.getChildAt(i).alpha = a
            }
        }
    }

    private fun openSearch() {
        if (searchOpen) return
        // ★ 先把要用的控件全部取到，再改状态 —— 旧版是「先置 searchOpen=true 再 ?: return」，
        //   取不到 overlay 时状态就永久停在"搜索中"，头部再也没人恢复。
        val overlay = findViewById<View>(R.id.searchOverlay) ?: return
        val input = findViewById<EditText>(R.id.searchInput) ?: return

        searchOpen = true
        // 搜索展开后头部交给 titleAnim 接管，残留的停靠动画/采样循环必须收掉，
        // 否则会一边盖搜索页一边把 scrollY 往 115 拽。
        cancelHeaderAnim()
        cancelSettle()
        // v1.6.41：重新打开时**恢复上一次的关键词**（未点「取消」前不丢）。
        // 只有「取消」路径（resetSearchUi(clearQuery = true)）才会把 memory 清空，
        // 所以这里拿到的要么是上一次的查询，要么是空。
        // setText 会自己触发 TextWatcher → renderSearch，空串时才需要显式渲染。
        val keep = searchQueryMemory
        if (keep.isNotEmpty()) {
            input.setText(keep)
            input.setSelection(keep.length)
        } else {
            input.setText("")
            renderSearch("")
        }

        overlay.animate().cancel()
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(200L)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f))
            .start()

        // 搜索框从首页栏的位置起飞（preview 的 --bar-dy）：先布局再量，
        // 这样不依赖任何写死的 60/62/125 常量，也不受状态栏 inset 影响。
        val wrap = findViewById<View>(R.id.searchBarWrap)
        val home = findViewById<View>(R.id.homeSearchBar)
        if (wrap != null) {
            wrap.animate().cancel()
            wrap.translationY = 0f
            if (home != null) {
                wrap.post {
                    if (!searchOpen) return@post
                    val a = IntArray(2)
                    val b = IntArray(2)
                    home.getLocationInWindow(a)
                    wrap.getLocationInWindow(b)
                    // 首页栏已滚出屏幕时不要从屏幕外飞进来，夹在合理范围
                    val dy = (a[1] - b[1]).toFloat().coerceIn(-dp(28).toFloat(), dp(220).toFloat())
                    if (dy > dp(1)) {
                        wrap.translationY = dy
                        wrap.animate().translationY(0f).setDuration(420L)
                            .setInterpolator(
                                android.view.animation.PathInterpolator(0.1f, 0.8f, 0.2f, 1f),
                            )
                            .start()
                    }
                }
            }
        }
        findViewById<View>(R.id.btnSearchCancel)?.apply {
            animate().cancel()
            alpha = 0f
            translationX = dp(6).toFloat()
            animate().alpha(1f).translationX(0f).setDuration(220L).start()
        }
        titleFlight(true)

        input.requestFocus()
        input.post { showIme(input) }
    }

    /**
     * 收起搜索页。
     *
     * @param keepQuery v1.6.41：从结果跳到**主页某一行**时用 true ——
     *   搜索页得让位（否则盖住目标行），但关键词要留着，用户再打开搜索
     *   还是同一组结果。从「取消」按钮 / 返回键进来的路径一律 false，
     *   此时才真正清空（无残留）。
     */
    private fun closeSearch(keepQuery: Boolean = false) {
        if (!searchOpen) return
        val overlay = findViewById<View>(R.id.searchOverlay)
        val input = findViewById<EditText>(R.id.searchInput)
        val wrap = findViewById<View>(R.id.searchBarWrap)

        if (keepQuery) {
            searchQueryMemory = input?.text?.toString().orEmpty()
        } else {
            searchQueryMemory = ""
        }

        searchOpen = false
        // 1) 立即收焦点与键盘：等动画再收会有明显的键盘滞留感
        input?.clearFocus()
        hideIme(input)

        // 2) 淡出搜索页
        if (overlay != null) {
            overlay.animate().cancel()
            overlay.animate().alpha(0f)
                .setDuration(180L)
                .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f))
                .withEndAction {
                    // ★ 唯一收敛点：期间若又被打开，交给 openSearch，绝不覆盖
                    if (!searchOpen) resetSearchUi(clearQuery = !keepQuery)
                }
                .start()
            // 3) 兜底：View 脱离窗口（转屏/退后台/被重建）时 withEndAction 不一定执行，
            //    用延时再收敛一次。resetSearchUi 幂等，多跑一次无害。
            overlay.postDelayed({ if (!searchOpen) resetSearchUi(clearQuery = !keepQuery) }, 260L)
        } else {
            resetSearchUi(clearQuery = !keepQuery)
        }
        wrap?.animate()?.cancel()
        wrap?.animate()?.translationY(-dp(10).toFloat())?.setDuration(160L)?.start()
        titleFlight(false)
    }

    /**
     * 把搜索相关的所有视图拉回初始状态（幂等）。
     *
     * 这里是「取消后不残留」的唯一出口：可见性、alpha、输入、结果、标题、首页栏
     * 一次全部落到确定值。先取控件再改值，任何一步为空都不会留下中间态。
     *
     * @param clearQuery false = 保留输入框里的关键词（从结果跳到主页某行时）；
     *   true = 连关键词一起清空（用户真的取消了搜索）。
     */
    private fun resetSearchUi(clearQuery: Boolean = true) {
        val overlay = findViewById<View>(R.id.searchOverlay)
        if (overlay != null) {
            overlay.animate().cancel()
            overlay.alpha = 0f
            overlay.visibility = View.GONE
        }
        if (clearQuery) {
            searchQueryMemory = ""
            lastHits = emptyList()
            findViewById<EditText>(R.id.searchInput)?.apply {
                setText("")
                clearFocus()
            }
            (findViewById<View>(R.id.searchResults) as? android.view.ViewGroup)?.removeAllViews()
            findViewById<View>(R.id.searchSuggestions)?.visibility = View.VISIBLE
            findViewById<View>(R.id.btnSearchClear)?.visibility = View.GONE
        } else {
            // 保留关键词与结果列表：重新打开搜索就是原样（含滚动位置以外的全部状态）
            findViewById<EditText>(R.id.searchInput)?.clearFocus()
        }
        findViewById<View>(R.id.searchBarWrap)?.translationY = 0f
        findViewById<View>(R.id.btnSearchCancel)?.apply {
            animate().cancel()
            alpha = 1f
            translationX = 0f
        }
        // 头部按「当前滚动位置」重算 —— 这一步让取消后的界面与"从没进过搜索"完全一致。
        //
        // ⚠️ 标题飞行（titleFlight）还在跑时**不要**抢它：它是 260ms+50ms，比搜索页淡出
        // 长，硬抢会让标题在中途跳一下。就让它自己跑完，它的 onEnd 会把头部
        // 交回 applyHeaderTransform。两个写入者永不重叠。
        cancelHeaderAnim()
        if (titleAnim == null) applyHeaderTransform(headerScrollRef?.scrollY ?: 0)
    }

    private fun cancelTitleAnim() {
        titleAnim?.cancel()
        titleAnim = null
    }

    /**
     * 标题飞行（对齐 preview 的 titleFlight）：
     * 打开时沿**滚动收缩的同一条轨迹**（34sp→19sp、上移 46dp）收起并淡出，
     * 关闭时同一条轨迹反向展开并淡入 —— 而不是突然消失/突然出现。
     */
    private fun titleFlight(on: Boolean) {
        val title = headerTitle ?: return
        cancelTitleAnim()
        val scrollY = headerScrollRef?.scrollY ?: 0
        val fromAlpha = if (on) 1f else 0f
        val toAlpha = if (on) 0f else 1f
        // ★ v1.6.45：只做 alpha + 8dp 平移。旧版把"滚动收起"的 34sp→19sp 缩放轨迹
        //   也套在飞行动画上，等于在搜索开合期间再连续重栅格化 420ms 的字形 ——
        //   这正是"打开搜索时标题明显抖一下"的来源。字号交给吸顶条的小标题去承担，
        //   大标题只负责淡出。
        val travel = dp(8).toFloat()
        if (headerMotionDisabled()) {
            title.alpha = toAlpha
            title.translationY = if (on) -travel else 0f
            if (!on) applyHeaderTransform(scrollY)
            return
        }
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (on) 240L else 200L
            startDelay = if (on) 0L else 50L
            interpolator = android.view.animation.PathInterpolator(0.1f, 0.8f, 0.2f, 1f)
            addUpdateListener { va ->
                val k = va.animatedValue as Float
                title.alpha = fromAlpha + (toAlpha - fromAlpha) * k
                title.translationY = if (on) -travel * k else -travel * (1f - k)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (titleAnim === animation) titleAnim = null
                    // 收尾后立刻把头部交回 scrollY 的纯函数，杜绝"停在中间值"
                    if (!searchOpen) applyHeaderTransform(headerScrollRef?.scrollY ?: 0)
                }
            })
            start()
        }
        titleAnim = anim
    }

    override fun onBackPressed() {
        // 搜索态下返回键 = 取消搜索，而不是退出设置页（对齐 preview 的 Esc）
        if (searchOpen) {
            closeSearch()
            return
        }
        super.onBackPressed()
    }

    // ─── 搜索索引（v1.6.41：升级为全局深度搜索）──────────────────────────
    //
    // v1.6.38 的索引只扫主页那两张卡片里的 `setting_row`，所以只有主页的 16 行
    // 能被搜到 —— 外观、权限中心、维护这些真正装了几十个开关的子页全是盲区。
    // 现在索引改由 [SettingsSearchCatalog] 提供（**声明式目录**，覆盖一到四级页面），
    // 每个条目写清「文案资源 + 目标页 + 页内锚点」，因此：
    //   · 文案与页面上的同一份 string 资源一致（5 个语言自动跟随）；
    //   · 锚点是 R.id，控件改名则编译失败；
    //   · 跳转能落到具体的设置子项，而不只是「打开那一页」。

    /** 上一次的查询词。**只有取消搜索才清空** —— 这是「未取消前关键词不丢」的载体。 */
    private var searchQueryMemory: String = ""

    /** 最近一次渲染的命中列表（回车键跳第一条用）。 */
    private var lastHits: List<SearchHit> = emptyList()

    private fun renderSearch(query: String) {
        val results = findViewById<android.widget.LinearLayout>(R.id.searchResults) ?: return
        val suggestions = findViewById<View>(R.id.searchSuggestions)
        findViewById<View>(R.id.btnSearchClear)?.visibility =
            if (query.isNotEmpty()) View.VISIBLE else View.GONE
        searchQueryMemory = query
        results.removeAllViews()
        (findViewById<View>(R.id.searchResultsScroll) as? android.widget.ScrollView)
            ?.scrollTo(0, 0)
        val q = query.trim()
        if (q.isEmpty()) {
            lastHits = emptyList()
            suggestions?.visibility = View.VISIBLE
            buildSuggestions()
            return
        }
        suggestions?.visibility = View.GONE

        // 目录检索：一到四级页面的全部设置项，按「标题相等 > 标题前缀 > 标题包含
        // > 说明 > 别名」分档，同档按层级（浅的在前）再按目录顺序。
        val hits = SettingsSearchCatalog.search(q) { getString(it) }
        lastHits = hits

        if (hits.isEmpty()) {
            results.addView(TextView(this).apply {
                text = getString(R.string.settings_no_results) + "\n“" + q + "”"
                textSize = 14.5f
                gravity = android.view.Gravity.CENTER
                setTextColor(getColor(R.color.settings_text_secondary))
                setPadding(0, dp(64), 0, dp(64))
            })
            return
        }

        // 按**分组**聚成卡片（同 preview 的 `.scard` + `.sghead`）：
        // 一张卡片 = 一个板块（外观与显示 / 权限与能力 / …），卡片头是图标 + 板块名，
        // 卡内每行是「设置项标题（命中高亮）+ 面包屑路径 + chevron」。
        // 这样用户点之前就知道：这条属于哪块、在第二层还是第三层。
        var card: LinearLayout? = null
        var cardRows = 0
        var currentGroupId: String? = null
        hits.forEach { hit ->
            val group = SettingsSearchCatalog.groupOf(hit.page)
            if (group.id != currentGroupId) {
                currentGroupId = group.id
                card = newResultCard(group)
                results.addView(card)
                cardRows = 0
            }
            (card ?: return@forEach).addView(buildResultRow(hit, q, cardRows > 0))
            cardRows++
        }
    }

    /**
     * 一张分组卡片：圆角底 + 分组头（21dp 图标方块 + 14sp 板块名）。
     * 尺寸与间距对齐 preview 的 `.scard` / `.sghead`：左右 16dp、卡片间距 14dp。
     */
    private fun newResultCard(group: SearchPage): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_settings_card)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) }
            clipToPadding = true
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(16), dp(6))
        }
        // 图标方块：只做「板块色底 + 白色字形」，与 preview 的 .sico 一致。
        val (iconRes, tint) = SettingsSearchCatalog.iconOf(group)
        if (iconRes != 0) {
            head.addView(android.widget.FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(21), dp(21)).apply { marginEnd = dp(8) }
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(7).toFloat()
                    setColor(tint)
                }
                addView(ImageView(context).apply {
                    setImageResource(iconRes)
                    setColorFilter(0xFFFFFFFF.toInt())
                    layoutParams = android.widget.FrameLayout.LayoutParams(dp(13), dp(13)).apply {
                        gravity = Gravity.CENTER
                    }
                })
            })
        }
        head.addView(TextView(this).apply {
            text = getString(group.titleRes)
            textSize = 14f
            // preview：分组名是次要信息（opacity .62）
            alpha = 0.62f
            setTextColor(getColor(R.color.settings_text_primary))
        })
        card.addView(head)
        return card
    }

    private fun buildResultRow(hit: SearchHit, q: String, withDivider: Boolean): View {
        val title = getString(hit.titleRes)
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (withDivider) {
            // preview 的 `.srow + .srow::before`：1dp 分隔线，左端内缩 16dp
            holder.addView(View(this).apply {
                setBackgroundColor(getColor(R.color.settings_divider))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1),
                ).apply { marginStart = dp(16) }
            })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(11), dp(14), dp(12))
            isClickable = true
            background = selectableItemBackground()
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = highlight(title, q)
            textSize = 16.5f
            setTextColor(getColor(R.color.settings_text_primary))
        })
        // 面包屑：`外观与显示 › 状态栏 › 展开状态栏`（preview 的 .ssub 单行省略）
        texts.addView(TextView(this).apply {
            text = trailOf(hit)
            textSize = 12.5f
            setTextColor(getColor(R.color.settings_text_secondary))
            setPadding(0, dp(4), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(texts)
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron)
            setColorFilter(getColor(R.color.settings_chevron))
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(10) }
        })
        row.setOnClickListener { jumpTo(hit) }
        holder.addView(row)
        return holder
    }

    /**
     * 结果行的路径文字：分组根之后的完整层级 + 自身标题。
     *
     * 分组头已经写了板块名，但这里**仍然带上板块**（同 preview 的
     * `显示与亮度 > 屏幕刷新率`）—— 因为用户滚动时卡片头会离开视野，
     * 每行自带完整路径才不会失去参照。
     */
    private fun trailOf(hit: SearchHit): String {
        val parts = SettingsSearchCatalog.trailOf(hit.page)
            .map { getString(it) }
            .toMutableList()
        // 末段是目标页；把设置项标题接在最后，形成「页 › 子项」的落地路径
        val title = getString(hit.titleRes)
        if (parts.isEmpty() || parts.last() != title) parts.add(title)
        return parts.joinToString(" › ")
    }

    /**
     * 结果点击 = 精准跳转。
     *
     * - **主页条目**（`page.activity == null`）：收起搜索页但**保留关键词**，
     *   然后滚到那一行并闪一下高亮。重新打开搜索，关键词与结果都还在。
     * - **子页条目**：**不关闭搜索**，直接带锚点起新页面。用户返回时搜索页
     *   原样还在（关键词、结果列表、滚动位置都不动），因此不存在「跳转后
     *   搜索状态丢一半」；只有在用户点「取消」时 [resetSearchUi] 才真正清空。
     */
    private fun jumpTo(hit: SearchHit) {
        val q = findViewById<EditText>(R.id.searchInput)?.text?.toString().orEmpty().trim()
        searchQueryMemory = q
        val target = hit.page.activity

        // ── 跨页条目：**不关闭搜索**，直接带锚点起新页面 ──
        // 用户返回时搜索页原样还在（关键词、结果列表、卡片分组都没动），
        // 因此不存在「跳转后搜索状态丢一半」。只有点「取消」才真正清空。
        if (target != null) {
            val input = findViewById<EditText>(R.id.searchInput)
            input?.clearFocus()
            hideIme(input)
            // 带上条目文案：目标是「程序化生成的行」时（如宠物设置），
            // 落地后能在容器里按文字精确定位到那一行，而不是只停在页首。
            startActivity(
                SearchAnchor.intent(this, target, hit.anchorId, q, getString(hit.titleRes)),
            )
            return
        }

        // ── 主页条目：收起搜索页（否则它盖住目标行），但保留关键词 ──
        closeSearch(keepQuery = true)
        findViewById<View>(hit.anchorId)?.let { scrollToRow(it) }
    }

    /**
     * 搜索建议 chips（同 preview 的 `.chips`：可换行、每个带一个小色块图标）。
     *
     * 为什么不用固定的 5 个硬编码字符串：preview 的 `SUG` 是数据（词 + 颜色 + 图标），
     * 这里同样从目录的分组里取 —— 于是「建议的板块」与「结果的板块」永远一致，
     * 加了新板块也不用回来改这段。
     */
    private fun buildSuggestions() {
        val chips = findViewById<android.view.ViewGroup>(R.id.searchChips) ?: return
        if (chips.childCount > 0) return

        // 每个分组出一个建议词（用分组标题本身），最多 8 个 —— 与 preview 的 SUG 同量级
        val groups = SettingsSearchCatalog.allPages
            .filter { it.level == 2 }
            .distinctBy { it.id }
            .take(8)
        if (groups.isEmpty()) return

        var row: LinearLayout? = null
        groups.forEachIndexed { index, group ->
            if (index % 2 == 0) {
                // preview 的 flex-wrap：这里按「每行 2 个」折叠，窄屏也不会挤出屏幕
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { if (index > 0) topMargin = dp(8) }
                }
                chips.addView(row)
            }
            row?.addView(buildSuggestionChip(group))
        }
    }

    private fun buildSuggestionChip(group: SearchPage): View {
        val label = getString(group.titleRes)
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_settings_chip)
            setPadding(dp(7), dp(7), dp(12), dp(7))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(8) }
        }
        val (iconRes, tint) = SettingsSearchCatalog.iconOf(group)
        if (iconRes != 0) {
            chip.addView(android.widget.FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(6) }
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(tint)
                }
                addView(ImageView(context).apply {
                    setImageResource(iconRes)
                    setColorFilter(0xFFFFFFFF.toInt())
                    layoutParams = android.widget.FrameLayout.LayoutParams(dp(13), dp(13)).apply {
                        gravity = Gravity.CENTER
                    }
                })
            })
        }
        chip.addView(TextView(this).apply {
            text = label
            textSize = 14.5f
            setTextColor(getColor(R.color.settings_text_primary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        chip.setOnClickListener {
            val input = findViewById<EditText>(R.id.searchInput)
            input?.setText(label)
            input?.setSelection(label.length)
            input?.requestFocus()
        }
        return chip
    }

    /** 标题里的命中片段高亮（与 preview 的 .hl 一致）。 */
    private fun highlight(text: String, query: String): CharSequence {
        val idx = text.lowercase().indexOf(query.lowercase())
        if (idx < 0 || query.isEmpty()) return text
        val sp = android.text.SpannableString(text)
        sp.setSpan(
            android.text.style.ForegroundColorSpan(getColor(R.color.icon_blue)),
            idx, idx + query.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return sp
    }

    /** 现取 `?selectableItemBackground`：不依赖 theme 属性继承，暗色模式也正确。 */
    private fun selectableItemBackground(): android.graphics.drawable.Drawable? = runCatching {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val d = a.getDrawable(0)
        a.recycle()
        d
    }.getOrNull()

    private fun showIme(input: View) {
        runCatching {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideIme(input: View?) {
        runCatching {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            val token = input?.windowToken ?: window.decorView.windowToken
            imm?.hideSoftInputFromWindow(token, 0)
        }
    }

    private fun scrollToRow(row: View) {
        val scroller = findViewById<android.widget.ScrollView>(R.id.settingsScroller) ?: return
        val rowLoc = IntArray(2)
        row.getLocationInWindow(rowLoc)
        val scLoc = IntArray(2)
        scroller.getLocationInWindow(scLoc)
        scroller.smoothScrollTo(0, scroller.scrollY + rowLoc[1] - scLoc[1] - dp(72))
        val old = row.background
        row.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(getColor(R.color.accent_soft))
            cornerRadius = dp(12).toFloat()
        }
        row.postDelayed({ row.background = old }, 700)
    }

    private fun startVoiceSearch() {
        runCatching {
            startActivityForResult(
                Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(
                        android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    .putExtra(
                        android.speech.RecognizerIntent.EXTRA_PROMPT,
                        getString(R.string.settings_search_hint),
                    ),
                REQ_VOICE_SEARCH,
            )
        }.onFailure {
            Toast.makeText(this, R.string.settings_voice_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE_SEARCH && resultCode == RESULT_OK) {
            val text = data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                if (!searchOpen) openSearch()
                val input = findViewById<android.widget.EditText>(R.id.searchInput)
                input?.setText(text)
                input?.setSelection(text.length)
            }
        }
    }

    /**
     * 平板/横屏响应式布局（v1.4.18，v1.4.27 改为按高度均衡分列）：
     * - 宽窗：两栏，**按分区行数把 8 个分区就近对半分**（断点见 [useTwoColumns]）；
     * - 窄窗：单列，顺序不变（显示 → 权限中心 → 语音 → 扩展 → 数据 → 网络 → 维护 → 关于）。
     *
     * v1.5.x：断点不再只看「横屏 840dp 一个值」，改为**按当前配置的宽度 + 朝向**判定，
     * 所以平板竖屏（800dp）也能双栏、手机竖屏仍是单列，转屏时实时切换。
     *
     * ## 为什么不再写死「左显示+权限 / 右其余」
     * 旧实现把列归属硬编码在 XML 里（columnPrimary 放前两个分区，其余全塞
     * columnSecondary），行数一多就歪：v1.4.24 扩到 8 个分区后是左 12 行 / 右 18 行，
     * 右边要滚很久、左边一屏未完就空了。而且每加一行设置项都会继续加剧。
     *
     * 现在：把 XML 里的原始阅读顺序读出来（先 primary 再 secondary），按行数找
     * 一个**连续切分点**让两列高度最接近（保持"从上往下读"的语义，不做瀑布流乱序），
     * 再重新挂到两列上。以后加行/加分区自动重新均衡，不用再手工调 XML。
     *
     * 只改容器方向/权重与子视图归属，不重建页面。限宽交给
     * [MaxWidthLinearLayout]，避免平板横屏下单列内容被拉成超宽行。
     *
     * @param cfg **当前**配置。转屏路径必须传入 `onConfigurationChanged` 的 newConfig，
     *   默认值只适用于 onCreate（那时 resources 与实屏一致）。
     */
    private fun applyResponsiveLayout(cfg: Configuration = resources.configuration) {
        val wide = useTwoColumns(cfg)
        val columns = findViewById<LinearLayout>(R.id.settingsColumns)
        val primary = findViewById<LinearLayout>(R.id.columnPrimary)
        val secondary = findViewById<LinearLayout>(R.id.columnSecondary)
        findViewById<MaxWidthLinearLayout>(R.id.settingsContentWidth).maxWidthDp =
            if (wide) WIDE_CONTENT_MAX_WIDTH_DP else NARROW_CONTENT_MAX_WIDTH_DP

        val sections = sections()
        if (wide) {
            columns.orientation = LinearLayout.HORIZONTAL
            primary.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            ).apply { marginEnd = dp(16) }
            secondary.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
            distributeSections(sections, primary, secondary)
        } else {
            columns.orientation = LinearLayout.VERTICAL
            primary.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            secondary.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            // 单列时必须把分区全部收回 primary，否则右列内容会跑到屏幕外
            detachSections(sections)
            sections.forEach { addSection(primary, it) }
        }
    }

    /**
     * 单列 / 双栏判定（v1.5.x）：只看**传入配置**的可用宽度与朝向。
     *
     *  · 横屏：>= 840dp（Material 3 的 expanded 断点）—— 手机横屏 780~800dp
     *    仍是单列，避免两栏各 380dp 把说明文字挤成三行；
     *  · 竖屏：>= 600dp —— 平板竖屏（实测 1600x2560 @320dpi = 800dp）双栏，
     *    手机竖屏（360~430dp）单列。
     *
     * 这两个值都是「内容区宽度」的代理：判的是 screenWidthDp（已扣除系统栏），
     * 不依赖实测像素，所以在 onCreate（尚未布局）时也能算出正确结果。
     */
    private fun useTwoColumns(cfg: Configuration): Boolean {
        val min = if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            TWO_COLUMN_MIN_WIDTH_LANDSCAPE_DP
        } else {
            TWO_COLUMN_MIN_WIDTH_PORTRAIT_DP
        }
        return cfg.screenWidthDp >= min
    }

    /**
     * 一个「分区单元」：标题 + 卡片 + 行数权重。
     *
     * 权重用**卡片内的行数**而不是实测像素：两列等宽、行高一致，行数是最稳的
     * 代理；实测像素要求先布局，而本方法在 onCreate 阶段就会被调用。
     */
    private class Section(val header: TextView?, val card: LinearLayout, val weight: Int)

    /** XML 原始阅读顺序（先 columnPrimary 再 columnSecondary），只在首次调用时收集 */
    private var cachedSections: List<Section>? = null

    private fun sections(): List<Section> = cachedSections ?: run {
        val out = ArrayList<Section>()
        out += collectSections(findViewById(R.id.columnPrimary))
        out += collectSections(findViewById(R.id.columnSecondary))
        out.also { cachedSections = it }
    }

    /** 形如「标题 TextView + 卡片 LinearLayout」交替出现；别的节点忽略 */
    private fun collectSections(column: LinearLayout): List<Section> {
        val out = ArrayList<Section>()
        for (i in 0 until column.childCount) {
            val child = column.getChildAt(i) as? LinearLayout ?: continue
            if (child.tag != "setting_card" && child.tag != "setting_profile") continue
            val rows = (0 until child.childCount).count {
                (child.getChildAt(it) as? View)?.tag == "setting_row"
            }
            out += Section(null, child, rows.coerceAtLeast(1))
        }
        return out
    }

    /**
     * 连续切分：找一个 k，让前 k 个分区与其余分区的行数差最小。
     * 保持阅读顺序（不做「往短的那列随手塞」的瀑布流），分列结果可预测。
     */
    private fun distributeSections(
        sections: List<Section>,
        primary: LinearLayout,
        secondary: LinearLayout,
    ) {
        if (sections.isEmpty()) return
        val total = sections.sumOf { it.weight }
        var best = 1
        var bestDiff = Int.MAX_VALUE
        var acc = 0
        for (k in 1 until sections.size) {
            acc += sections[k - 1].weight
            val diff = kotlin.math.abs(total - 2 * acc)
            if (diff < bestDiff) {
                bestDiff = diff
                best = k
            }
        }
        detachSections(sections)
        sections.forEachIndexed { index, section ->
            addSection(if (index < best) primary else secondary, section)
        }
    }

    private fun detachSections(sections: List<Section>) {
        sections.forEach { section ->
            (section.header?.parent as? ViewGroup)?.removeView(section.header)
            (section.card.parent as? ViewGroup)?.removeView(section.card)
        }
    }

    private fun addSection(column: LinearLayout, section: Section) {
        column.addView(section.card)
    }

    override fun onDestroy() {
        // 头部停靠的采样循环与吸附动画都挂在本 Activity 的 Handler/Animator 上，
        // 不收干净会拖住 ScrollView 引用。
        cancelHeaderAnim()
        cancelTitleAnim()
        cancelSettle()
        settleHandler.removeCallbacksAndMessages(null)
        headerScrollRef = null
        // v1.6.38：离开页面时把搜索页彻底收敛（幂等）。
        // 这层保险主要针对「搜索开着 → 转屏/被系统重建」：
        // 新实例 onCreate 一定从 GONE 开始，而旧实例不得留下半透明遮罩。
        if (searchOpen) {
            searchOpen = false
            resetSearchUi()
        }
        super.onDestroy()
    }

    // 权限中心代码（v1.4.58）已整体迁入 PermissionCenterActivity —— 含 Shizuku
    // 监听、麦克风权限回调、运行权限模式对话框（双警告 + 引擎重启）。
    // 本页只保留两个分类入口行（rowPermEngine / rowPermCaps）。

    // ================= 扩展：运行时下载代理 =================

    /** 刷新「运行时下载代理」行的说明文字（默认 / 自定义 / 直连）。 */
    private fun refreshRuntimeProxyRow() {
        val sub = findViewById<TextView>(R.id.subRuntimeProxy)
        val current = RuntimeDownloadConfig.proxy(this)
        sub.text = when {
            current.isBlank() -> getString(R.string.setting_runtime_proxy_value_direct)
            RuntimeDownloadConfig.isDefault(this) ||
                current.equals(RuntimeDownloadConfig.DEFAULT_PROXY.trimEnd('/'), ignoreCase = true) ->
                getString(R.string.setting_runtime_proxy_value_default)
            else -> getString(R.string.setting_runtime_proxy_value_custom, current)
        }
    }

    /**
     * 运行时下载代理编辑框（v1.4.21）。
     *
     * 运行时 zip 与签名索引都从 GitHub Releases 取；默认 gh-proxy.org，
     * 国内网络不用先等一轮直连超时。用户可换自建镜像，或留空直连。
     */
    private fun showRuntimeProxyDialog() {
        val current = RuntimeDownloadConfig.proxy(this)
        val input = EditText(this).apply {
            setText(current)
            hint = RuntimeDownloadConfig.DEFAULT_PROXY
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setSelection(text.length)
        }
        val tip = TextView(this).apply {
            text = getString(
                R.string.setting_runtime_proxy_dialog_hint,
                RuntimeDownloadConfig.DEFAULT_PROXY,
            )
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(8), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(4))
            addView(input)
            addView(tip)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.setting_runtime_proxy)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.setting_runtime_proxy_reset, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text.toString()
                if (!RuntimeDownloadConfig.isValid(raw)) {
                    input.error = getString(R.string.setting_runtime_proxy_invalid)
                    return@setOnClickListener
                }
                RuntimeDownloadConfig.setProxy(this, raw)
                refreshRuntimeProxyRow()
                Toast.makeText(
                    this, getString(R.string.setting_runtime_proxy_saved), Toast.LENGTH_SHORT,
                ).show()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                RuntimeDownloadConfig.setProxy(this, null)
                refreshRuntimeProxyRow()
                Toast.makeText(
                    this, getString(R.string.setting_runtime_proxy_reset_done), Toast.LENGTH_SHORT,
                ).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
    /** dp → px 小工具（对话框内边距用） */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 统一弹窗样式：覆盖自绘圆角背景，贴近原生安卓对话框的圆润观感 */
    private fun AlertDialog.Builder.showStyled(): AlertDialog =
        create().apply {
            setOnShowListener {
                window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            }
            show()
        }



    private companion object {
        const val REQ_VOICE_SEARCH = 0x5A1

        /** 吸附时长与曲线（v1.6.23 规格 8：约 340ms、ease-out cubic） */
        const val HEADER_SNAP_MS = 340L

        /** 松手后 scrollY 静默多久算“惯性停稳”（规格 6） */
        const val SETTLE_IDLE_MS = 60L

        /** 停稳采样间隔：8ms 在 120Hz 上仍是每帧一次，不会漏判 */
        const val SETTLE_TICK_MS = 8L

        /** 采样循环的封顶时长，防长列表微振荡时循环常驻 */
        const val SETTLE_MAX_WAIT_MS = 1200L

        /** 双栏断点（横屏）：Material 3 的 expanded 宽度（840dp） */
        const val TWO_COLUMN_MIN_WIDTH_LANDSCAPE_DP = 840

        /** 双栏断点（竖屏）：平板竖屏 800dp 也应双栏，手机竖屏远达不到 */
        const val TWO_COLUMN_MIN_WIDTH_PORTRAIT_DP = 600

        /** 宽窗内容最大宽度：两栏各约 516dp，平板横屏不拉满 */
        const val WIDE_CONTENT_MAX_WIDTH_DP = 1080

        /** 窄窗内容最大宽度：手机/平板竖屏保持易读行宽 */
        const val NARROW_CONTENT_MAX_WIDTH_DP = 680
    }

    /**
     * 「去开启」按钮（v1.4.45~v1.4.57）已整体移除（v1.4.58）：按钮点击行为与
     * 整行点击本就一致（跳系统设置），纯视觉冗余且在窄屏上挤压标题/说明文字。
     * 现在整行即入口，未授权指引由各行的 sub 文案承担（"点击去系统设置开启"）。
     */

    /** 把当前 Activity 的视图按模式重排（不改 XML，运行时改布局参数） */
    private fun applyLayoutMode(mode: SettingsLayoutMode) {
        // 分区横跨两列：Display/Permissions 在 columnPrimary，Extensions/Other 在
        // columnSecondary（v1.2.79 双栏改造的产物）。只重排第一列会漏掉后两个分区
        // ——实测网格模式下 Extensions 仍是一列。
        val columns = listOfNotNull(
            findViewById<LinearLayout>(R.id.columnPrimary),
            findViewById<LinearLayout>(R.id.columnSecondary),
        )
        if (columns.isEmpty()) return
        val sections = columns.flatMap { sectionCards(it) }
        when (mode) {
            SettingsLayoutMode.LIST -> {
                sections.forEach { card ->
                    if (card.tag == "setting_profile") return@forEach
                    (card.layoutParams as? LinearLayout.LayoutParams)?.let {
                        it.bottomMargin = dp(4); card.layoutParams = it
                    }
                    card.orientation = LinearLayout.VERTICAL
                    rowsOf(card).forEach { row ->
                        row.layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                        setDescriptionsVisible(row, true)
                    }
                }
            }
            SettingsLayoutMode.GRID -> {
                // 真正两列：每两行包进一个横向容器（卡片的父方向是 vertical，
                // 直接把行设 weight=1/width=0 会得到宽度 0 —— 实测踩过）。
                val gap = dp(6)
                sections.forEach { card ->
                    if (card.tag == "setting_profile") return@forEach
                    (card.layoutParams as? LinearLayout.LayoutParams)?.let {
                        it.bottomMargin = dp(18); card.layoutParams = it
                    }
                    val rows = rowsOf(card)
                    card.removeAllViews()
                    rows.chunked(2).forEach { pair ->
                        val wrap = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                        }
                        pair.forEachIndexed { idx, row ->
                            row.layoutParams = LinearLayout.LayoutParams(
                                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                            ).apply { if (idx == 0 && pair.size == 2) marginEnd = gap }
                            setDescriptionsVisible(row, false)
                            // 网格里每行只剩图标 + 标题 + 当前值：文字必须单行省略，
                            // 否则会被逐字换行（实测「Landscape mode」竖排成一列）
                            compactRowForGrid(row)
                            wrap.addView(row)
                        }
                        if (pair.size == 1) {
                            // 奇数行：补占位保持左对齐，避免最后一项被拉满整行
                            wrap.addView(View(this).apply {
                                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                            })
                        }
                        card.addView(wrap)
                    }
                }
            }
        }
    }

    /**
     * 找 columnPrimary 下的分区卡片（v1.4.23）。
     *
     * ⚠️ 不能只认「有 background 的 LinearLayout」：Extensions / Other 两个分区
     * 的卡片没有 bg_card 背景（实测漏识 → 网格模式下它们不参与重排）。
     * 这里改为「分区标题 TextView 之后、且包含至少一个可点击行的容器」。
     */
    private fun sectionCards(primary: LinearLayout): List<LinearLayout> =
        (0 until primary.childCount)
            .mapNotNull { primary.getChildAt(it) as? LinearLayout }
            .filter { it.tag == "setting_card" || it.tag == "setting_profile" }

    /** 卡片内的行容器（直接子 View 里可点击的 LinearLayout） */
    private fun rowsOf(card: LinearLayout): List<LinearLayout> =
        (0 until card.childCount)
            .mapNotNull { card.getChildAt(it) as? LinearLayout }
            .filter { it.tag == "setting_row" }

    /**
     * 网格模式下隐藏「说明文字」（v1.4.23）。
     *
     * v1.4.58 起行结构是「标题行 + 说明行」两段式（任意深度嵌套），
     * 按直接子节点索引的老判据失效 → 改为**递归遍历**整棵行子树。
     * 判据：**小字号（≤12sp）且无 id 的 TextView** 才是说明文字——
     * 有 id 的都是有身份的控件（标题 16sp / 状态徽章 13-14sp / 箭头）；
     * 唯一例外 subAccess（带 id 的说明文字，照藏）。
     */
    private fun setDescriptionsVisible(row: LinearLayout, visible: Boolean) {
        walkRowViews(row) { v ->
            if (v is TextView) {
                val sp = v.textSize / resources.displayMetrics.scaledDensity
                val isDesc = v.tag == "setting_summary" || (sp <= 12.5f && v.id == View.NO_ID)
                if (isDesc) v.visibility = if (visible) View.VISIBLE else View.GONE
            }
        }
    }

    /** 深度优先遍历行内全部 View（含嵌套的标题行/说明行）。 */
    private fun walkRowViews(root: View, fn: (View) -> Unit) {
        fn(root)
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) walkRowViews(root.getChildAt(i), fn)
        }
    }

    /**
     * 网格模式下行内压缩（v1.4.23；v1.4.58 改递归）：图标缩到 28dp、
     * 全部文字单行省略，保证 2 列宽度下仍可读（实测不处理会逐字竖排）。
     * 说明文字由 [setDescriptionsVisible] 先行隐藏，说明行随之塌缩，
     * 网格行只剩「图标 + 标题 + 状态徽章」单行。
     */
    private fun compactRowForGrid(row: LinearLayout) {
        row.setPadding(dp(8), dp(10), dp(8), dp(10))
        row.minimumHeight = dp(56)
        walkRowViews(row) { v ->
            when (v) {
                is android.widget.ImageView -> {
                    (v.layoutParams as? LinearLayout.LayoutParams)?.let {
                        if (it.width > dp(28)) {
                            it.width = dp(28); it.height = dp(28)
                            v.layoutParams = it
                        }
                    }
                    v.setPadding(dp(6), dp(6), dp(6), dp(6))
                }
                is TextView -> {
                    v.maxLines = 1
                    v.ellipsize = android.text.TextUtils.TruncateAt.END
                    v.setSingleLine(true)
                }
            }
        }
    }

}
