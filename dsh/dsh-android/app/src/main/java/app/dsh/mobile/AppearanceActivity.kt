package app.dsh.mobile

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.ui.ImagePipeline
import app.dsh.mobile.ui.Insets

/**
 * 外观与显示（v1.4.56）：从设置主页收拢的个性化子页。
 *
 * 七项：主题、屏幕朝向、页面缩放、沉浸式、文件目录、强调色、设置页布局。
 * 改动后本页 recreate 立即生效；设置主页在 onResume 用 applied* 快照比对，
 * 检测到主题/强调色/布局变化会自行重建。
 */
class AppearanceActivity : ThemedActivity() {
    /** 属于设置体系：启用「设置页背景图」（见 ThemedActivity.usesSettingsBackground）。 */
    override val usesSettingsBackground: Boolean get() = true


    private var orientMode: OrientationMode = OrientationMode.PORTRAIT
    private var pageScale = DEFAULT_PAGE_SCALE
    /** 主题选中后已排队一次 recreate（v1.6.22：连点只重建一次） */
    private var themeRecreateScheduled = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_appearance)
        ThemeTransition.reveal(this)

        orientMode = OrientationPrefs.get(this)
        pageScale = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
            .getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // —— 屏幕朝向（v1.2.120：竖屏/横屏/自动 三选一）——
        findViewById<LinearLayout>(R.id.rowLandscape).setOnClickListener {
            showOrientDialog()
        }
        findViewById<TextView>(R.id.valLandscape).text = getString(orientLabel(orientMode))

        // —— 设置页跟随屏幕方向（v1.5.x）——
        // 默认关：手机上的设置页固定竖屏（v1.4.64 的诉求），平板跟随上面的朝向选择。
        // 打开后：任何尺寸的设置页都跟随朝向偏好（等同主界面）。
        val swSettingsFollow = findViewById<Switch>(R.id.swSettingsFollow)
        swSettingsFollow.isChecked = OrientationPrefs.followInSettings(this)
        swSettingsFollow.setOnCheckedChangeListener { _, checked ->
            OrientationPrefs.setFollowInSettings(this, checked)
            // 立即生效：本页当场跟着转（关掉时手机立刻转回竖屏），
            // 其余设置页在各自 onCreate / onResume 读同一份偏好。
            OrientationPrefs.applyToSettingsPage(this)
        }

        // —— 页面缩放 ——
        findViewById<LinearLayout>(R.id.rowScale).setOnClickListener {
            showScaleDialog()
        }
        findViewById<TextView>(R.id.valScale).text = "$pageScale%"

        // —— 边到边显示（状态栏保持透明，网页/界面内容延伸到底部栏后方）——
        //
        // v1.6.47：这个开关以前**只写不读**（全工程没有一处消费它），所以
        // 拨开/关掉界面毫无变化 —— 永远处于边到边。现在它由
        // [app.dsh.mobile.ui.Insets] 的默认参数统一读取，这里只负责
        // 「写偏好 + 当场对**本页**生效」，返回主界面时主界面自己
        // 在 onResume / onWindowFocusChanged 里重读，链路两端都不断。
        val swImmersive = findViewById<Switch>(R.id.swImmersive)
        swImmersive.isChecked = MainActivity.edgeToEdgeEnabled(this)
        swImmersive.setOnCheckedChangeListener { _, checked ->
            MainActivity.setEdgeToEdgeEnabled(this, checked)
            // 立刻切本页：系统栏颜色/透明、内容框是否画到栏后、内容 padding
            // 三件事一起换，用户才能"看到开关真的有用"。
            // 系统栏那一次必须重新走 applyWindow（decorFitsSystemWindows 变了），
            // 而 applyContent 负责把可能残留的旧 padding 还原（关闭时）。
            runCatching {
                EdgeToEdge.applyWindow(this)
                Insets.applyContent(this)
                window.decorView.requestApplyInsets()
            }.onFailure {
                android.util.Log.w("DshAppearance", "e2e switch apply failed: ${'$'}{it.message}")
            }
            // v1.6.50：状态栏那一行依赖本开关（关掉边到边就必须保留状态栏）——
            // 拨完立刻同步它的可用状态，否则会出现"上面说带状态栏、下面还能勾隐藏"。
            syncHideStatusRow()
        }

        // —— 隐藏状态栏（v1.6.50）——
        //
        // 只在边到边模式下有意义：紧凑模式（用户明确选了"带状态栏"）里状态栏必须保留。
        // 所以这里的做法是「置灰 + 换副标题」，而不是让它看起来能点却没反应。
        //
        // 生效方式与岛上的按钮完全一致：写偏好 → 重设本页系统栏 → 重算内容内边距 →
        // 主动派发一次 insets。**不 recreate、不碰引擎** —— 用户在设置页里拨一下就能
        // 当场看到状态栏消失/回来。
        val rowHideStatus = findViewById<LinearLayout>(R.id.rowHideStatus)
        val swHideStatus = findViewById<Switch>(R.id.swHideStatus)

        syncHideStatusRow()
        swHideStatus.setOnCheckedChangeListener { _, checked ->
            MainActivity.setHideStatusBarEnabled(this, checked)
            runCatching {
                EdgeToEdge.applyWindow(this)
                Insets.applyContent(this)
                window.decorView.requestApplyInsets()
            }.onFailure {
                android.util.Log.w("DshAppearance", "hide-status apply failed: ${'$'}{it.message}")
            }
        }
        // 整行可点：与同页其它行一致（点标题等于拨开关）
        rowHideStatus.setOnClickListener {
            if (swHideStatus.isEnabled) swHideStatus.toggle()
        }

        // —— 官方推荐手机 UI（v1.4.67）：两套竖屏布局插件二选一 ——
        // 切换需要重写 android-overlay.yml 并重启引擎才生效（插件在启动时加载）。
        val swOfficialUi = findViewById<Switch>(R.id.swOfficialUi)
        swOfficialUi.isChecked = MainActivity.officialMobileUiEnabled(this)
        swOfficialUi.setOnCheckedChangeListener { _, checked ->
            MainActivity.setOfficialMobileUiEnabled(this, checked)
            // v1.5.0：mobile-layout 已改为用户态插件（profile bundle），overlay 里
            // 不再有它的 insert 行 —— 这个开关必须同时把插件挂上/摘下 bundles，
            // 否则拨开也只会得到「三件套关掉、什么布局都没上」的半残状态。
            // 引擎在跑，package.json 不能当场改，只记意图，下次启动落地。
            app.dsh.mobile.engine.ProfilePluginProvisioner.requestEnabled(
                this,
                app.dsh.mobile.engine.ProfilePluginProvisioner.MOBILE_LAYOUT_PKG,
                checked,
            )
            // 立刻重写 overlay，让「重启引擎」时用的是新方案
            runCatching { app.dsh.mobile.engine.EngineConfig.ensureAndroidOverlay(this) }
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.setting_official_ui)
                .setMessage(R.string.setting_official_ui_restart_hint)
                .setPositiveButton(R.string.action_restart_now) { _, _ ->
                    (application as app.dsh.mobile.DshApp).supervisor.restart()
                    Toast.makeText(this, getString(R.string.engine_restarting), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // —— 文件保存目录（v1.4.26，DSHA 风格 Download/DSH MOBILE）——
        val filesDir = app.dsh.mobile.engine.EngineConfig.workspaces(this)
        findViewById<TextView>(R.id.valFilesDir)?.text = filesDir.absolutePath
        findViewById<LinearLayout>(R.id.rowFilesDir)?.setOnClickListener {
            runCatching {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("files dir", filesDir.absolutePath),
                )
            }
            Toast.makeText(this, R.string.files_dir_copied, Toast.LENGTH_SHORT).show()
        }

        // —— 外观主题：预览卡（亮/暗）+ 跟随系统；保留 rowTheme 作为完整选择入口 ——
        setupThemePreview()

        // —— 配色方案（v1.4.23）——
        findViewById<LinearLayout>(R.id.rowAccent)?.setOnClickListener {
            showAccentDialog()
        }

        // —— 设置页布局（v1.4.23）——
        findViewById<LinearLayout>(R.id.rowLayoutMode)?.setOnClickListener {
            showLayoutModeDialog()
        }

        // —— 自定义素材（v1.5.x）：设置页背景图 / 收起态圆点图 ——
        findViewById<LinearLayout>(R.id.rowSettingsBg)?.setOnClickListener {
            // 选图 / 不透明度 / 模糊都在子页里
            startActivity(Intent(this, SettingsBgActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.rowIslandDotImage)?.setOnClickListener {
            // 状态栏设置：里面再分展开态 / 收起态两个子页
            startActivity(Intent(this, StatusBarActivity::class.java))
        }

        findViewById<TextView>(R.id.valLayoutMode)?.text =
            getString(SettingsLayoutMode.current(this).labelRes)
        findViewById<TextView>(R.id.valAccent)?.text = getString(Accent.current(this).labelRes)
        findViewById<TextView>(R.id.valLayoutMode)?.deprecatedAccent()
        findViewById<TextView>(R.id.valAccent)?.deprecatedAccent()
    }

    /** 回到本页时重新对齐朝向：外观页可能刚改过开关/偏好。 */
    override fun onResume() {
        super.onResume()
        // 从外观页返回时偏好可能已变（开关/朝向），重新对齐一次；值未变时是空操作
        OrientationPrefs.applyToSettingsPage(this)
        // 选图是异步回填的（系统相册在另一个 Activity），回来时刷新"已设置/未设置"
        refreshImageRows()
    }

    /**
     * 刷新素材行的状态文字与缩略图预览。
     *
     * 预览直接读已落盘的私有文件（导入时就已缩到上限尺寸），不需要再解码原图。
     *
     * ## v1.6.31：为什么要改成异步 + 按控件尺寸解码
     *
     * 旧写法是本方法内同步 `decodeFile`，而“已缩到上限尺寸”是 **1440 宽**：
     * 一个约 56dp 高的缩略图框，却要解一张 1440×1080 的位图（6.2 MB 分配），
     * 然后交给 `ImageView` 再缩下去画。进设置→外观页那一瞬，主线程就在做这件事
     * —— 用户看到的就是「点进外观页后顿一下」。
     *
     * 现在交给 [ImagePipeline]：后台解码，按缩略图实际尺寸降采样，回调回主线程
     * 合帧交付。图没换时直接命中内存缓存，连解码都没有。
     */
    private fun refreshImageRows() {
        val none = getString(R.string.setting_image_none)
        val yes = getString(R.string.setting_image_set)

        val bgFile = CustomVisualPref.settingsBgFile(this)
        findViewById<TextView>(R.id.valSettingsBg)?.text = if (bgFile != null) yes else none
        val bgPreview = findViewById<ImageView>(R.id.imgSettingsBgPreview)
        if (bgFile != null && bgPreview != null) {
            val px = thumbPx()
            ImagePipeline.requestThumb(THUMB_TAG, bgFile, px, px) { bmp ->
                if (isFinishing || isDestroyed) return@requestThumb
                bgPreview.setImageBitmap(bmp)
                bgPreview.visibility = View.VISIBLE
            }
        } else {
            ImagePipeline.cancel(THUMB_TAG)
            bgPreview?.visibility = View.GONE
        }
    }

    /** 缩略图目标边长（px）：控件末尾 56dp，取 [THUMB_DP] 给足够的容错裕量。 */
    private fun thumbPx(): Int =
        (THUMB_DP * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    override fun onDestroy() {
        ImagePipeline.cancel(THUMB_TAG)
        super.onDestroy()
    }

    private fun orientLabel(mode: OrientationMode): Int = when (mode) {
        OrientationMode.PORTRAIT -> R.string.setting_orient_portrait
        OrientationMode.LANDSCAPE -> R.string.setting_orient_landscape
        OrientationMode.AUTO -> R.string.setting_orient_auto
    }

    /**
     * 屏幕朝向选择：竖屏 / 横屏 / 自动 单选列表。
     * 落库后由 MainActivity.onResume 检测并统一应用。
     */
    private fun showOrientDialog() {
        val modes = listOf(
            OrientationMode.PORTRAIT,
            OrientationMode.LANDSCAPE,
            OrientationMode.AUTO,
        )
        val labels = modes.map { getString(orientLabel(it)) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.setting_landscape))
            .setSingleChoiceItems(labels, modes.indexOf(orientMode)) { dlg, which ->
                val picked = modes[which]
                dlg.dismiss()
                if (picked == orientMode) return@setSingleChoiceItems
                orientMode = picked
                OrientationPrefs.set(this, picked)
                // v1.5.x：大屏（或用户打开了「设置页跟随屏幕方向」）时本页也跟着转，
                // 手机则保持 v1.4.64 的竖屏。判定与所有设置页共用同一个入口。
                OrientationPrefs.applyToSettingsPage(this)
                findViewById<TextView>(R.id.valLandscape).text = getString(orientLabel(picked))
                Toast.makeText(
                    this,
                    getString(orientLabel(picked)) + " · " + getString(R.string.setting_applied_now),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .showStyled()
    }

    /**
     * 页面缩放对话框：SeekBar 滑条连续拖动（范围 50–150，步长 5），实时显示百分比。
     * 落库后 MainActivity onResume 读取并 reload 生效。
     */
    private fun showScaleDialog() {
        val value = TextView(this).apply {
            textSize = 26f
            gravity = Gravity.CENTER
            text = "$pageScale%"
            setPadding(0, dp(8), 0, dp(4))
        }
        val bar = SeekBar(this).apply {
            max = (MAX_PAGE_SCALE - MIN_PAGE_SCALE) / SCALE_STEP   // 索引 0..20 → 50..150 步长5
            progress = (pageScale - MIN_PAGE_SCALE) / SCALE_STEP
            progressTintList = android.content.res.ColorStateList.valueOf(Accent.current(this@AppearanceActivity).argb)
            thumbTintList = android.content.res.ColorStateList.valueOf(Accent.current(this@AppearanceActivity).argb)
            setPadding(dp(24), 0, dp(24), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    value.text = "${MIN_PAGE_SCALE + progress * SCALE_STEP}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(value)
            addView(bar)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.scale_title))
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                pageScale = MIN_PAGE_SCALE + bar.progress * SCALE_STEP
                getSharedPreferences(PREFS_UI, MODE_PRIVATE)
                    .edit().putInt(KEY_PAGE_SCALE, pageScale).apply()
                findViewById<TextView>(R.id.valScale).text = "$pageScale%"
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showStyled()
    }

    /**
     * 预览卡：亮色 / 暗色 + 跟随系统。
     *
     * v1.6.22 交互重构（回应用户反馈的「水波纹扩散成一团大圆」）：
     *
     * · **按压**：三项都改为 `@animator/press_scale` 的 0.96 缩放，不再叠加任何
     *   ripple。原来的 `selectableItemBackgroundBorderless` 是无边界波纹，在
     *   78×134dp 的预览卡上半径约等于对角线 —— 点一下就是一整块浅色圆盖住设置卡，
     *   连旁边未选中的那张都看不清，跟这套「深灰卡 + 小圆点」的极简风格冲突。
     * · **选中**：预览卡换成带 2dp 强调色描边的 `*_sel` 背景，描边色与选中圆点
     *   同色（@color/icon_blue），「卡片描边 + 圆点」读成同一套标记；
     *   跟随系统也补了同尺寸圆点，三个选项不再用两套语言。
     * · **时机**：先落库并刷新标记（当帧就能看到描边/圆点变化），再留
     *   [SELECTION_SETTLE_MS] 让结果上屏后才 recreate；旧实现只有一次缩放、
     *   选中的变化要等重建完才出现，点击与结果之间是断的。
     */
    private fun setupThemePreview() {
        refreshThemePreview()
        findViewById<View>(R.id.optLightTheme)?.setOnClickListener {
            applyThemeWithAnimation(ThemeMode.LIGHT)
        }
        findViewById<View>(R.id.optDarkTheme)?.setOnClickListener {
            applyThemeWithAnimation(ThemeMode.DARK)
        }
        // 点击目标改到容器（圆点 + 文字整体响应），文字本身不再单独可点，
        // 避开「点圆点和点文字反应不一致」的问题。
        findViewById<View>(R.id.optFollowTheme)?.setOnClickListener {
            applyThemeWithAnimation(ThemeMode.SYSTEM)
        }
        findViewById<LinearLayout>(R.id.rowTheme)?.setOnClickListener { showThemeDialog() }
        findViewById<TextView>(R.id.valTheme)?.text = getString(ThemePref.get(this).labelRes)
    }

    /**
     * 把当前主题刷成可见的选中态。
     *
     * 选中标记只有两种元格：预览卡描边、圆点填充；两者同色，且未选中时
     * 只留灰描边圆点 —— 三选一里任何时刻都只有一个选项带强调色，不会出现
     * 「两张卡都是蓝的」这种含糊状态（选「跟随系统」时两张卡的圆点均未选中）。
     */
    private fun refreshThemePreview() {
        val mode = ThemePref.get(this)
        val light = mode == ThemeMode.LIGHT
        val dark = mode == ThemeMode.DARK
        val system = mode == ThemeMode.SYSTEM

        findViewById<View>(R.id.previewLight)?.setBackgroundResource(
            if (light) R.drawable.bg_theme_preview_light_sel else R.drawable.bg_theme_preview_light,
        )
        findViewById<View>(R.id.previewDark)?.setBackgroundResource(
            if (dark) R.drawable.bg_theme_preview_dark_sel else R.drawable.bg_theme_preview_dark,
        )
        findViewById<View>(R.id.radioLight)?.setBackgroundResource(
            if (light) R.drawable.bg_radio_selected else R.drawable.bg_radio_unselected,
        )
        findViewById<View>(R.id.radioDark)?.setBackgroundResource(
            if (dark) R.drawable.bg_radio_selected else R.drawable.bg_radio_unselected,
        )
        // 跟随系统与上面两张卡共用同一个圆点
        findViewById<View>(R.id.radioSystem)?.setBackgroundResource(
            if (system) R.drawable.bg_radio_selected else R.drawable.bg_radio_unselected,
        )
        findViewById<TextView>(R.id.valThemeFollow)?.setTextColor(
            getColor(if (system) R.color.icon_blue else R.color.settings_text_secondary),
        )
    }

    private fun applyThemeWithAnimation(mode: ThemeMode) {
        if (ThemePref.get(this) == mode) return
        // 先抓旧主题快照：recreate 后在新页面上淡出（对齐 Web 预览的交叉淡化）
        ThemeTransition.capture(this)
        ThemePref.set(this, mode)
        // 当帧刷新选中态：点击的即时反馈不再只依赖缩放动画
        refreshThemePreview()
        // 连点时只重建一次：否则喜欢比较的用户会把 180ms 窗内的几次点击
        // 攒成多次 recreate（白屏闪两下）。
        if (themeRecreateScheduled) return
        themeRecreateScheduled = true
        // 等选中态先上屏，再重建以真正切换 values(-night) 资源。
        // 180ms 的依据：圆点/描边的切换是瞬时绘制，但缩放回弹要 170ms，
        // 两者叠在一起才是一段完整的「点击→选中」反馈，重建插在中间会把动画截断。
        window?.decorView?.postDelayed({
            themeRecreateScheduled = false
            // Activity 已在退出（用户点了返回）时不再重建，否则会 IllegalStateException
            if (!isFinishing && !isDestroyed) recreate()
        }, SELECTION_SETTLE_MS)
    }

    /** 外观主题：深色 / 浅色 / 跟随系统。选完 recreate 才会重新取 values(-night) 资源。 */
    private fun showThemeDialog() {
        val modes = ThemeMode.entries
        val labels = modes.map { getString(it.labelRes) }.toTypedArray()
        val checked = modes.indexOf(ThemePref.get(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_theme)
            .setSingleChoiceItems(labels, checked) { d, which ->
                ThemePref.set(this, modes[which])
                d.dismiss()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 配色方案选择：5 种强调色，默认项目原本的天蓝 */
    private fun showAccentDialog() {
        val modes = Accent.entries
        val labels = modes.map { getString(it.labelRes) }.toTypedArray()
        val checked = modes.indexOf(Accent.current(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_accent)
            .setSingleChoiceItems(labels, checked) { d, which ->
                Accent.set(this, modes[which])
                d.dismiss()
                // 配色影响整个原生壳（含本页所有强调色），重建本页最省事且无状态丢失风险
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 设置页布局选择：LIST（单列）/ GRID（两列图标网格）。
     * 落库后设置主页在 onResume 检测到变化会重建并按新模式重排。
     *
     * 旧的 CARD 档已在 v1.5.0 去掉（与 LIST 只差分区间距 4dp→18dp，几乎看不出
     * 区别）；存过 `"card"` 的偏好由 [SettingsLayoutMode.fromId] 回落到 LIST。
     */
    private fun showLayoutModeDialog() {
        val modes = SettingsLayoutMode.entries
        val labels = modes.map { getString(it.labelRes) }.toTypedArray()
        val checked = modes.indexOf(SettingsLayoutMode.current(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_layout_mode)
            .setSingleChoiceItems(labels, checked) { d, which ->
                SettingsLayoutMode.set(this, modes[which])
                d.dismiss()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** dp → px 小工具（对话框内边距用） */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 给设置项的当前值文字上强调色（v1.4.23，配色可切换后动态取色） */
    private fun TextView.deprecatedAccent() {
        setTextColor(Accent.current(this.context).argb)
    }

    /**
     * 同步「隐藏状态栏」这一行的可用状态与回显（v1.6.50）。
     *
     * 依赖关系：本开关只在边到边模式下生效。所以边到边关掉时整行置灰（alpha + 不可点），
     * 副标题换成"需先开启边到边显示" —— 比留一个能拨但没反应的开关诚实得多
     *（v1.6.47 的教训：拨了没反应会被当成 bug）。
     *
     * 幂等：每次都按当前偏好重算，可被边到边开关的监听器与 onResume 反复调用。
     */
    private fun syncHideStatusRow() {
        val row = findViewById<LinearLayout>(R.id.rowHideStatus) ?: return
        val sw = findViewById<Switch>(R.id.swHideStatus) ?: return
        val sub = findViewById<TextView>(R.id.valHideStatusSub)
        val immersive = MainActivity.edgeToEdgeEnabled(this)
        sw.isChecked = MainActivity.hideStatusBarEnabled(this)
        row.isEnabled = immersive
        row.alpha = if (immersive) 1f else 0.45f
        sw.isEnabled = immersive
        sub?.text = getString(
            if (immersive) R.string.setting_hide_status_sub
            else R.string.setting_hide_status_needs_e2e,
        )
    }

    /** 统一弹窗样式：覆盖自绘圆角背景 */
    private fun AlertDialog.Builder.showStyled(): AlertDialog =
        create().apply {
            setOnShowListener {
                window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            }
            show()
        }

    private companion object {
        const val PREFS_UI = DshApp.PREFS_UI
        const val KEY_PAGE_SCALE = "page_scale"
        const val DEFAULT_PAGE_SCALE = 90
        const val MIN_PAGE_SCALE = 50
        const val MAX_PAGE_SCALE = 150
        const val SCALE_STEP = 5

        /**
         * 选中态上屏到 recreate 之间的稳定窗（v1.6.22）。
         *
         * 宁可晚一点重建，也不要让用户看不到自己刚点的那一项变了：
         * 描边与圆点的切换是瞬时绘制，但缩放回弹要 170ms，两者叠在一起才是一段
         * 完整的「点击→选中」反馈，重建插在中间就会把动画截断。
         */
        const val SELECTION_SETTLE_MS = 180L

        /** 缩略图解码 tag：同一控件只保留最新一条请求 */
        const val THUMB_TAG = "appearance-thumb"

        /**
         * 缩略图目标边长（dp）。
         *
         * 控件本身约 56dp；取 3 倍是给 P3/高密度屏与「偶尔被拉高」留裕量，
         * 同时把 1440 宽的原图挡在门外 —— 解码字节数从 ~6.2 MB 降到 ~0.3 MB。
         */
        const val THUMB_DP = 168
    }
}
