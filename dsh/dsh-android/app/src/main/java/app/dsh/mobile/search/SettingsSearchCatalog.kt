package app.dsh.mobile.search

import android.app.Activity
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import app.dsh.mobile.AboutActivity
import app.dsh.mobile.AppearanceActivity
import app.dsh.mobile.AsrAppActivity
import app.dsh.mobile.BackupActivity
import app.dsh.mobile.ExpandedStatusBarActivity
import app.dsh.mobile.ExtensionStoreActivity
import app.dsh.mobile.ExtensionVoiceActivity
import app.dsh.mobile.IslandStatusActivity
import app.dsh.mobile.MaintenanceActivity
import app.dsh.mobile.PermissionCenterActivity
import app.dsh.mobile.PermissionListActivity
import app.dsh.mobile.PluginStoreActivity
import app.dsh.mobile.R
import app.dsh.mobile.SettingsBgActivity
import app.dsh.mobile.StatusBarActivity
import app.dsh.mobile.UpdateSettingsActivity
import app.dsh.mobile.UserPromptActivity
import app.dsh.mobile.VoiceServiceActivity
import app.dsh.mobile.WipeChatsActivity
import app.dsh.mobile.pet.PetLinesActivity
import app.dsh.mobile.pet.PetSettingsActivity

/**
 * 设置体系里可被搜索命中的**页面**（第一/二/三/四级）。
 *
 * `level` 只用于展示层次与排序：1 = 设置主页，2 = 主页直接进入的子页，
 * 3 = 子页的子页，依此类推。`parentId` 用来拼面包屑（`外观与显示 › 状态栏 › 展开态`）。
 *
 * `layout` 是该页的布局资源名，运行时不用它，但让离线探针能核对
 * 「每个搜索锚点确实存在于目标页的布局里」——这是这套目录唯一的静态约束。
 */
class SearchPage(
    val id: String,
    @StringRes val titleRes: Int,
    /** null = 就是设置主页本身（当前 Activity 内跳转） */
    val activity: Class<out Activity>?,
    val level: Int,
    val layout: String,
    val parentId: String? = null,
    /** 分组图标（只用二级页/主页的，子页沿用祖先的）。0 = 无图标。 */
    val iconRes: Int = 0,
    /** 分组图标底色（ARGB）。与 preview 的 `SG[group][1]` 同用途。 */
    val tint: Int = 0,
)

/**
 * 一个可搜索的设置项。
 *
 * - [anchorId]：目标页里要定位的控件 id（`R.id.*`）。用整型而不是字符串，
 *   这样改名会**编译期报错**，不会静默失效；0 表示该页内容为动态生成、
 *   没有稳定锚点，跳转后停在页首。
 * - [titleRes] / [summaryRes] 直接引用页面上的同一份文案资源，保证
 *   「搜到的字」与「跳过去看到的字」完全一致（5 个语言版本自动跟着走）。
 * - [keywords] 是人工别名：补用户真正会打的词（同义词、英文、英文缩写、
 *   领域黑话），而不是控件叫什么。
 */
class SearchItem(
    val id: String,
    val pageId: String,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int?,
    val keywords: String,
    @IdRes val anchorId: Int,
)

/** 一条命中：[score] 越小越靠前，[order] 保留目录里的原始顺序。 */
class SearchHit(
    internal val item: SearchItem,
    val page: SearchPage,
    internal val score: Int,
    internal val order: Int,
) {
    @get:StringRes val titleRes: Int get() = item.titleRes
    @get:StringRes val summaryRes: Int? get() = item.summaryRes
    @get:IdRes val anchorId: Int get() = item.anchorId
}

/**
 * 设置搜索目录（v1.6.41 深度搜索）。
 *
 * ## 为什么是「声明式目录」而不是「运行时扫布局」
 *
 * 旧实现（v1.6.38）只遍历主页的 `columnPrimary` / `columnSecondary`，
 * 于是只能搜到主页那 16 行 —— 外观、权限、维护、语音这些真正装了几十个
 * 开关的二级/三级页完全搜不到。要在运行时覆盖它们，就得把每个子页的布局
 * 都 inflate 一遍再扫文案：既贵（每个 Activity 的布局都很重），又不可靠
 * （动态生成的页面扫不到、`: ?` 分支的文案扫不到、国际化后同义不同形）。
 *
 * 这里改成**一张显式的表**：每个条目写清「文案资源 + 目标页 + 锚点 id + 别名」。
 * 好处是
 *   1. 覆盖范围与代码同步演进（加设置项就加一行，diff 可见）；
 *   2. 文案直接引用页面上的同一份 string 资源，5 个语言自动一致；
 *   3. 锚点是 `R.id`，改名会编译失败；
 *   4. 离线探针可以逐条核对「锚点是否真的在目标页布局里」，防漏防错。
 *
 * ## 排序
 *
 * 分数档（同档按目录顺序、再按层级）：
 *   0 标题完全相等
 *   1 标题前缀
 *   2 标题包含
 *   3 说明包含
 *   4 别名包含
 * 多词查询按「与」处理：每个词都必须命中某一列，取最差的那一档作为整体档位。
 */
object SettingsSearchCatalog {

    private val pages = ArrayList<SearchPage>()
    private val pageMap = HashMap<String, SearchPage>()
    private val items = ArrayList<SearchItem>()

    val allPages: List<SearchPage> get() = pages
    val allItems: List<SearchItem> get() = items

    fun page(id: String): SearchPage? = pageMap[id]

    /**
     * 结果分组：爬回**二级祖先**（主页自己是分组根）。
     *
     * 这正是 preview 的 `SINDEX` 首列用法 —— 结果按分组聚成卡片，
     * 每张卡片一个图标头（`显示与亮度` / `辅助功能` / …），
     * 用户一眼能看出「这一条属于哪个板块、比当前页面深几层」。
     */
    fun groupOf(page: SearchPage): SearchPage {
        var p: SearchPage = page
        var guard = 0
        while (guard++ < 8) {
            if (p.level <= 2) return p
            val up = p.parentId?.let { pageMap[it] } ?: return p
            p = up
        }
        return p
    }

    /** 分组图标与底色：子页向上找最近一个声明了图标的祖先。 */
    fun iconOf(page: SearchPage): Pair<Int, Int> {
        var p: SearchPage? = page
        var guard = 0
        while (p != null && guard++ < 8) {
            if (p.iconRes != 0) return p.iconRes to p.tint
            p = p.parentId?.let { pageMap[it] }
        }
        return 0 to 0
    }

    /**
     * 面包屑：从分组根到该页的标题资源链（含自身，不含主页那一层）。
     *
     * preview 的 `.ssub` 是 `显示与亮度 > 屏幕刷新率`；层级更深时自然变成
     * `外观与显示 › 状态栏 › 展开状态栏`，让用户点之前就知道会落到第几层。
     */
    fun trailOf(page: SearchPage): List<Int> {
        val out = ArrayList<Int>()
        var p: SearchPage? = page
        var guard = 0
        while (p != null && guard++ < 8) {
            out.add(0, p.titleRes)
            if (p.level <= 2) break
            p = p.parentId?.let { pageMap[it] }
        }
        return out
    }

    /** 面包屑：从根到该页的标题资源链（含自身）。 */
    fun pathOf(page: SearchPage): List<Int> {
        val out = ArrayList<Int>()
        var p: SearchPage? = page
        var guard = 0
        while (p != null && guard++ < 8) {
            out.add(0, p.titleRes)
            p = p.parentId?.let { pageMap[it] }
        }
        return out
    }

    // ── 构建 ────────────────────────────────────────────────────────────

    private fun page(
        id: String,
        @StringRes titleRes: Int,
        activity: Class<out Activity>?,
        level: Int,
        layout: String,
        parentId: String? = null,
        iconRes: Int = 0,
        tint: Int = 0,
        block: SearchPage.() -> Unit,
    ) {
        val p = SearchPage(id, titleRes, activity, level, layout, parentId, iconRes, tint)
        pages += p
        pageMap[id] = p
        p.block()
    }

    /**
     * 目录条目。**保持这个写法**：离线探针按 `row(R.id.<anchor>` 解析
     * 「本页有哪些锚点」，并逐个核对目标布局里是否真有这个 id。
     */
    private fun SearchPage.row(
        @IdRes anchor: Int,
        @StringRes title: Int,
        @StringRes summary: Int?,
        keywords: String,
    ) {
        items += SearchItem(
            id = "$id/${items.size}",
            pageId = id,
            titleRes = title,
            summaryRes = summary,
            keywords = keywords,
            anchorId = anchor,
        )
    }

    init {
        // ═══════════════════ 一级：设置主页 ═══════════════════
        page(
            "main", R.string.settings_title, null, 1, "activity_settings",
            iconRes = R.drawable.ic_settings, tint = 0xFF8E8F92.toInt(),
        ) {
            row(
                R.id.rowAppProfile, R.string.app_name, R.string.settings_profile_sub,
                "关于本机 应用信息 账号 用户 个人 profile account app version 版本",
            )
            row(
                R.id.rowAppearance, R.string.settings_section_appearance, R.string.setting_appearance_sub,
                "外观 显示 主题 深色 夜间 暗色 配色 强调色 字号 缩放 布局 网格 背景图 appearance theme dark light accent",
            )
            row(
                R.id.rowExt, R.string.setting_ext, R.string.setting_ext_sub,
                "扩展 插件 插件市场 安装 启用 extension plugin market",
            )
            row(
                R.id.rowExtensionVoice, R.string.settings_section_voice_plugins, null,
                "语音 朗读 插件 声音 语音服务 tts voice plugin",
            )
            row(
                R.id.rowPermEngine, R.string.settings_section_permission, R.string.settings_perm_all_sub,
                "权限 root shizuku 运行身份 提权 无障碍 屏幕点击 授权 能力 permission",
            )
            row(
                R.id.rowUserPrompt, R.string.setting_user_prompt, R.string.setting_user_prompt_sub,
                "自定义指令 提示词 长期 记忆 prompt agents 用户指令",
            )
            row(
                R.id.rowBackup, R.string.setting_backup, null,
                "备份 恢复 导出 导入 数据 配置文件 backup restore export import",
            )
            row(
                R.id.rowFloatStatus, R.string.float_status_title, R.string.float_status_sub,
                "悬浮窗 悬浮 状态 灵动岛 后台 通知 overlay floating",
            )
            row(
                R.id.rowLanWeb, R.string.setting_lan_web, R.string.setting_lan_web_sub,
                "局域网 网页 浏览器 webui 端口 验证码 电脑 访问 lan web remote",
            )
            row(
                R.id.rowRuntimeProxy, R.string.setting_runtime_proxy, null,
                "代理 运行时 网络 镜像 源 加速 proxy mirror",
            )
            row(
                R.id.rowFastPluginBoot, R.string.fast_plugin_boot_title, R.string.fast_plugin_boot_sub,
                "插件 快速 启动 预载 加速 流式 fast boot preload",
            )
            row(
                R.id.rowPluginPriority, R.string.plugin_priority_title, R.string.plugin_priority_sub,
                "插件 优先级 排序 首位 priority order",
            )
            row(
                R.id.rowFakeLinux, R.string.fake_linux_title, R.string.fake_linux_sub,
                "linux 环境 容器 伪装 沙箱 仿真 proot debian",
            )
            row(
                R.id.rowMaintenance, R.string.setting_maintenance, R.string.setting_maintenance_sub,
                "维护 诊断 体检 日志 重启 清理 修复 maintenance diagnose log",
            )
            row(
                R.id.rowLanguage, R.string.app_language, R.string.app_language_sub_current,
                "语言 中文 英文 德语 葡语 泰语 切换 language english locale i18n",
            )
            row(
                R.id.rowAbout, R.string.setting_about, R.string.setting_about_sub,
                "关于 版本 更新 开源 作者 群 反馈 about version update",
            )
        }

        // ═══════════════════ 二级：外观与显示 ═══════════════════
        page(
            "appearance", R.string.settings_section_appearance, AppearanceActivity::class.java,
            2, "activity_appearance", "main",
            iconRes = R.drawable.ic_palette, tint = 0xFFFE7400.toInt(),
        ) {
            row(
                R.id.rowTheme, R.string.setting_theme, R.string.setting_theme_sub,
                "主题 深色 浅色 跟随系统 夜间 暗色 theme dark light system",
            )
            row(
                R.id.optLightTheme, R.string.theme_light, null,
                "浅色 亮色 白天 light",
            )
            row(
                R.id.optDarkTheme, R.string.theme_dark, null,
                "深色 暗色 夜间 dark night",
            )
            row(
                R.id.optFollowTheme, R.string.theme_system, null,
                "跟随系统 自动 system auto",
            )
            row(
                R.id.rowLandscape, R.string.setting_landscape, R.string.setting_landscape_sub,
                "屏幕 旋转 横屏 竖屏 方向 landscape orientation rotate",
            )
            row(
                R.id.rowSettingsFollow, R.string.setting_follow_orientation,
                R.string.setting_follow_orientation_sub,
                "设置页 跟随 屏幕 方向 settings orientation",
            )
            row(
                R.id.rowScale, R.string.setting_scale, R.string.setting_scale_sub,
                "缩放 字号 大小 比例 显示大小 scale font size dpi",
            )
            row(
                R.id.rowImmersive, R.string.setting_immersive, R.string.setting_immersive_sub,
                "全屏 沉浸 状态栏 隐藏 导航条 immersive fullscreen",
            )
            row(
                R.id.rowOfficialUi, R.string.setting_official_ui, R.string.setting_official_ui_sub,
                "官方界面 原生 ui webview 界面模式 official",
            )
            row(
                R.id.rowFilesDir, R.string.setting_files_dir, R.string.setting_files_dir_sub,
                "文件目录 数据 位置 存储 files dir storage path",
            )
            row(
                R.id.rowAccent, R.string.setting_accent, R.string.setting_accent_sub,
                "强调色 主题色 配色 颜色 accent color",
            )
            row(
                R.id.rowSettingsBg, R.string.setting_settings_bg, R.string.setting_settings_bg_sub,
                "设置页 背景图 壁纸 模糊 background image blur wallpaper",
            )
            row(
                R.id.rowIslandDotImage, R.string.setting_status_bar, R.string.setting_status_bar_sub,
                "状态栏 灵动岛 挖孔 圆点 图片 status bar island dot",
            )
            row(
                R.id.rowLayoutMode, R.string.setting_layout_mode, R.string.setting_layout_mode_sub,
                "布局 模式 列表 网格 单列 双列 layout mode grid list",
            )
        }

        // ═══════════════════ 三级：状态栏 ═══════════════════
        page(
            "statusbar", R.string.setting_status_bar, StatusBarActivity::class.java,
            3, "activity_status_bar", "appearance",
        ) {
            row(
                R.id.rowExpanded, R.string.setting_status_expanded, R.string.setting_status_expanded_sub,
                "展开 状态栏 下拉 背景 贴纸 expanded status bar sticker",
            )
            row(
                R.id.rowCollapsed, R.string.setting_status_collapsed, R.string.setting_status_collapsed_sub,
                "收起 灵动岛 圆点 形状 大小 collapsed island dot",
            )
        }

        // ═══════════════════ 四级：展开态状态栏 ═══════════════════
        page(
            "status_expanded", R.string.setting_status_expanded, ExpandedStatusBarActivity::class.java,
            4, "activity_status_expanded", "statusbar",
        ) {
            row(
                R.id.btnPickBg, R.string.setting_status_bg, null,
                "背景图 选择 图片 background image pick",
            )
            row(
                R.id.btnRemoveBg, R.string.action_remove_image, null,
                "移除 背景图 删除 remove image",
            )
            row(
                R.id.btnAddSticker, R.string.action_add_sticker, R.string.setting_sticker_desc,
                "贴纸 添加 表情 sticker add emoji",
            )
            row(
                R.id.btnClearStickers, R.string.action_clear_stickers, null,
                "清空 贴纸 全部 删除 clear stickers",
            )
        }

        // ═══════════════════ 四级：收起态圆点 ═══════════════════
        page(
            "island_dot", R.string.setting_island_dot_image, IslandStatusActivity::class.java,
            4, "activity_island_status", "statusbar",
        ) {
            row(
                R.id.btnPickDot, R.string.action_choose_image, null,
                "圆点 图片 选择 摄像头 island dot image pick",
            )
            row(
                R.id.btnRemoveDot, R.string.action_remove_image, null,
                "移除 圆点 图片 remove dot",
            )
            row(
                R.id.rowShapeCircle, R.string.setting_dot_shape_circle, null,
                "形状 圆形 圆点 shape circle",
            )
            row(
                R.id.rowShapeSquare, R.string.setting_dot_shape_square, null,
                "形状 方形 圆角 shape square",
            )
            row(
                R.id.seekDotSize, R.string.setting_dot_size, R.string.setting_dot_size_sub,
                "圆点 大小 尺寸 直径 dot size",
            )
        }

        // ═══════════════════ 三级：设置页背景图 ═══════════════════
        page(
            "settings_bg", R.string.setting_settings_bg, SettingsBgActivity::class.java,
            3, "activity_settings_bg", "appearance",
        ) {
            row(
                R.id.btnPickBg, R.string.action_choose_image, null,
                "背景图 选择 相册 图片 background image pick gallery",
            )
            row(
                R.id.btnRemoveBg, R.string.action_remove_image, null,
                "移除 背景图 remove background",
            )
            row(
                R.id.seekBgAlpha, R.string.setting_bg_alpha, R.string.setting_bg_alpha_sub,
                "透明度 不透明度 浓度 opacity alpha",
            )
            row(
                R.id.seekBgBlur, R.string.setting_bg_blur, R.string.setting_bg_blur_sub,
                "模糊 高斯 虚化 blur",
            )
        }

        // ═══════════════════ 二级：权限中心 ═══════════════════
        page(
            "permission", R.string.settings_section_permission, PermissionCenterActivity::class.java,
            2, "activity_permission_center", "main",
            iconRes = R.drawable.ic_shield, tint = 0xFF0066FE.toInt(),
        ) {
            row(
                R.id.rowPriv, R.string.setting_priv_mode, R.string.setting_priv_mode_sub,
                "运行身份 root shizuku 提权 授权模式 privilege",
            )
            row(
                R.id.rowRootStatus, R.string.setting_root_status, R.string.setting_root_sub,
                "root 状态 检测 超级用户 status",
            )
            row(
                R.id.rowShizukuStatus, R.string.setting_shizuku_status, R.string.setting_shizuku_sub,
                "shizuku 状态 服务 status",
            )
            row(
                R.id.rowAccess, R.string.setting_access, R.string.setting_access_sub,
                "无障碍 辅助 服务 屏幕点击 accessibility",
            )
            row(
                R.id.rowStorage, R.string.setting_storage, R.string.setting_storage_sub,
                "存储 文件 访问 权限 storage",
            )
            row(
                R.id.rowNotif, R.string.setting_notif, R.string.setting_notif_sub,
                "通知 权限 notification",
            )
            row(
                R.id.rowMic, R.string.setting_mic, R.string.setting_mic_sub,
                "麦克风 录音 语音 权限 microphone",
            )
            row(
                R.id.rowCap20Entry, R.string.settings_perm_group_device, R.string.settings_perm_group_device,
                "设备能力 权限 一览 android 11 12 13 capability",
            )
            row(
                R.id.rowCapStatus, R.string.setting_cap_status, null,
                "状态栏 读取 通知 权限 status bar access",
            )
            row(
                R.id.rowCapSensor, R.string.setting_cap_sensor, null,
                "传感器 运动 计步 sensor",
            )
            row(
                R.id.rowCapNotify, R.string.setting_cap_notify, null,
                "通知 发送 推送 notification post",
            )
            row(
                R.id.rowCapNotifyRead, R.string.setting_cap_notify_read, null,
                "通知 使用权 读取 已发通知 notification listener",
            )
            row(
                R.id.rowCapNotifyAction, R.string.setting_cap_notify_action, null,
                "通知 操作 按钮 回复 notification action",
            )
            row(
                R.id.rowCapMedia, R.string.setting_cap_media, null,
                "媒体 控制 播放 音乐 media control",
            )
            row(
                R.id.rowCapControl, R.string.setting_cap_control, null,
                "控制 其他应用 悬浮 control apps",
            )
            row(
                R.id.rowCapSched, R.string.setting_cap_sched, null,
                "定时 提醒 精确 闹钟 schedule alarm",
            )
            row(
                R.id.rowCapWifi, R.string.setting_cap_wifi, null,
                "wifi 无线 网络 扫描 wifi",
            )
            row(
                R.id.rowCapBt, R.string.setting_cap_bt, null,
                "蓝牙 连接 扫描 bluetooth bt",
            )
            row(
                R.id.rowCapCalendar, R.string.setting_cap_personal_calendar, null,
                "日历 日程 calendar",
            )
            row(
                R.id.rowCapContacts, R.string.setting_cap_personal_contacts, null,
                "通讯录 联系人 contacts",
            )
            row(
                R.id.rowCapSms, R.string.setting_cap_personal_sms, null,
                "短信 消息 sms message",
            )
            row(
                R.id.rowCapCalls, R.string.setting_cap_personal_calls, null,
                "电话 通话 拨打 calls phone",
            )
            row(
                R.id.rowCapScreenCapture, R.string.setting_cap_screen_capture, null,
                "截屏 录屏 投屏 屏幕捕获 screen capture",
            )
            row(
                R.id.rowCapHid, R.string.setting_cap_hid, null,
                "外接 键盘 鼠标 hid 输入设备",
            )
            row(
                R.id.rowCapClip, R.string.setting_cap_clip, null,
                "剪贴板 复制 粘贴 clipboard",
            )
            row(
                R.id.rowCapRf, R.string.setting_cap_rf, null,
                "射频 nfc 读卡 rf",
            )
            row(
                R.id.rowCapNet, R.string.setting_cap_net, null,
                "网络 流量 数据 network",
            )
            row(
                R.id.rowCapRoot, R.string.setting_cap_root, null,
                "root 命令 执行 root shell",
            )
            row(
                R.id.rowGrantAll, R.string.setting_grant_perms, R.string.setting_grant_perms_sub,
                "一键 授权 全部 权限 grant all",
            )
            row(
                R.id.rowPermList, R.string.perm_list_title, R.string.setting_perm_list_sub,
                "权限 清单 列表 明细 permission list",
            )
        }

        // ═══════════════════ 三级：权限清单 ═══════════════════
        page(
            "permission_list", R.string.perm_list_title, PermissionListActivity::class.java,
            3, "activity_permission_list", "permission",
        ) {
            row(
                R.id.editPermSearch, R.string.perm_list_tab_all, null,
                "权限 搜索 筛选 查找 filter search permission",
            )
            row(
                R.id.listPerms, R.string.perm_list_title, null,
                "权限 清单 全部 已允许 已拒绝 list allowed denied",
            )
        }

        // ═══════════════════ 二级：语音与插件 ═══════════════════
        page(
            "voice", R.string.settings_section_voice_plugins, ExtensionVoiceActivity::class.java,
            2, "activity_extension", "main",
            iconRes = R.drawable.ic_mic, tint = 0xFF00BD15.toInt(),
        ) {
            row(
                R.id.rowPlugin, R.string.setting_plugin, R.string.setting_plugin_sub,
                "插件 管理 安装 plugin manager",
            )
            row(
                R.id.rowAsrApps, R.string.asrapp_entry, R.string.asrapp_entry_sub,
                "语音 输入 识别 第三方 应用 asr app speech",
            )
            row(
                R.id.rowVoiceService, R.string.setting_voice_service, R.string.setting_voice_service_sub,
                "语音 服务 识别 合成 voice service",
            )
            row(
                R.id.rowTts, R.string.setting_tts, R.string.setting_tts_sub,
                "朗读 语音合成 试听 tts speak",
            )
            row(
                R.id.rowPet, R.string.setting_pet, R.string.setting_pet_sub,
                "宠物 桌宠 悬浮 pet",
            )
        }

        // ═══════════════════ 三级：语音服务 ═══════════════════
        page(
            "voice_service", R.string.setting_voice_service, VoiceServiceActivity::class.java,
            3, "activity_voice_service", "voice",
        ) {
            row(
                R.id.asrEngineSpinner, R.string.asr_engine_label, R.string.asr_engine_hint,
                "识别 引擎 选择 asr engine",
            )
            row(
                R.id.asrKey, R.string.cloud_asr_key, R.string.cloud_asr_free_hint,
                "密钥 api key 云 识别 cloud",
            )
            row(
                R.id.asrEndpoint, R.string.cloud_asr_endpoint, null,
                "接口 地址 端点 endpoint url",
            )
            row(
                R.id.asrModel, R.string.cloud_asr_model, null,
                "模型 model",
            )
            row(
                R.id.ttsOn, R.string.voice_tts_on, null,
                "朗读 开启 启用 tts on",
            )
            row(
                R.id.ttsOff, R.string.voice_tts_off, null,
                "朗读 关闭 tts off",
            )
            row(
                R.id.ttsVoiceSpinner, R.string.cloud_tts_voice, null,
                "音色 声音 发音 voice",
            )
            row(
                R.id.ttsRateSpinner, R.string.cloud_tts_rate, null,
                "语速 速度 rate speed",
            )
            row(
                R.id.rowTestTts, R.string.cloud_tts_test, null,
                "试听 测试 朗读 test tts",
            )
            row(
                R.id.rowSave, R.string.cloud_asr_save, null,
                "保存 应用 save",
            )
        }

        // ═══════════════════ 三级：语音输入应用 ═══════════════════
        page(
            "asr_app", R.string.asrapp_title, AsrAppActivity::class.java,
            3, "activity_asr_app", "voice",
        ) {
            row(
                R.id.oursCard, R.string.asrapp_guide_title, R.string.asrapp_subtitle,
                "语音 输入 设置 教程 引导 步骤 asr guide setup",
            )
            row(
                R.id.repoCard, R.string.asrapp_repo_title, R.string.asrapp_repo_desc,
                "开源 仓库 项目 地址 repo github",
            )
        }

        // ═══════════════════ 四级：宠物设置 ═══════════════════
        page(
            "pet", R.string.setting_pet, PetSettingsActivity::class.java,
            3, "activity_pet", "voice",
        ) {
            row(
                R.id.petContent, R.string.pet_enable, R.string.pet_enable_sub,
                "宠物 开启 桌宠 启用 pet enable",
            )
            row(
                R.id.petContent, R.string.pet_permission, R.string.pet_permission_sub,
                "宠物 悬浮窗 权限 显示 overlay permission",
            )
            row(
                R.id.petContent, R.string.pet_lines, null,
                "宠物 台词 对话 文本 lines",
            )
        }

        // ═══════════════════ 四级：宠物台词 ═══════════════════
        page(
            "pet_lines", R.string.pet_lines, PetLinesActivity::class.java,
            4, "activity_pet", "pet",
        ) {
            row(
                R.id.petContent, R.string.pet_lines, null,
                "宠物 台词 编辑 对话 lines edit",
            )
        }

        // ═══════════════════ 二级：插件市场 ═══════════════════
        page(
            "plugin_store", R.string.plugin_center_title, PluginStoreActivity::class.java,
            3, "activity_plugin_store", "voice",
        ) {
            row(
                R.id.pluginStoreBody, R.string.plugin_center_title, R.string.plugin_center_sub,
                "插件 市场 社区 安装 搜索 plugin market community",
            )
        }

        // ═══════════════════ 二级：扩展中心 ═══════════════════
        page(
            "extension_store", R.string.ext_title, ExtensionStoreActivity::class.java,
            2, "activity_extension_store", "main",
            iconRes = R.drawable.ic_puzzle, tint = 0xFFFE7400.toInt(),
        ) {
            row(
                R.id.extensionStoreBody, R.string.ext_title, R.string.setting_ext_sub,
                "扩展 中心 下载 安装 启用 extension center",
            )
        }

        // ═══════════════════ 二级：数据备份 ═══════════════════
        page(
            "backup", R.string.setting_backup, BackupActivity::class.java,
            2, "activity_backup", "main",
            iconRes = R.drawable.ic_export, tint = 0xFFFFB000.toInt(),
        ) {
            row(
                R.id.rowExportData, R.string.setting_export_data, R.string.setting_export_data_sub,
                "导出 数据 备份 打包 export data",
            )
            row(
                R.id.rowImportData, R.string.setting_import_data, R.string.setting_import_data_sub,
                "导入 数据 恢复 备份 import data",
            )
            row(
                R.id.rowRecoverChats, R.string.setting_recover_chats, R.string.setting_recover_chats_sub,
                "恢复 对话 聊天 记录 recover chats",
            )
            row(
                R.id.rowConfigFiles, R.string.setting_config_files, R.string.setting_config_files_sub,
                "配置 文件 管理 config files",
            )
        }

        // ═══════════════════ 二级：维护与高级 ═══════════════════
        page(
            "maintenance", R.string.setting_maintenance, MaintenanceActivity::class.java,
            2, "activity_maintenance", "main",
            iconRes = R.drawable.ic_health, tint = 0xFF3A7BFD.toInt(),
        ) {
            row(
                R.id.rowPluginKill, R.string.setting_plugin_kill, R.string.setting_plugin_kill_sub_idle,
                "插件 停止 关闭 熔断 kill plugin",
            )
            row(
                R.id.rowImportRuntime, R.string.runtime_import_zip, R.string.runtime_import_zip_sub,
                "导入 运行时 zip 安装 runtime import",
            )
            row(
                R.id.rowImportRuntimeDir, R.string.runtime_import_dir, R.string.runtime_import_dir_sub,
                "导入 运行时 目录 runtime dir",
            )
            row(
                R.id.rowCheckEngine, R.string.engine_check, R.string.engine_check_sub,
                "检查 引擎 状态 健康 engine check",
            )
            row(
                R.id.rowRollbackEngine, R.string.engine_rollback, R.string.engine_rollback_sub,
                "回滚 引擎 版本 rollback",
            )
            row(
                R.id.rowSelfCheck, R.string.setting_self_check, R.string.setting_self_check_sub,
                "自检 体检 检查 self check",
            )
            row(
                R.id.rowFixOwner, R.string.setting_fix_owner, R.string.setting_fix_owner_sub,
                "修复 权限 所有者 owner fix",
            )
            row(
                R.id.rowWipeChats, R.string.setting_wipe_chats, R.string.setting_wipe_chats_sub,
                "清理 删除 对话 聊天 归档 wipe chats",
            )
            row(
                R.id.rowGrantPerms, R.string.setting_grant_perms, R.string.setting_grant_perms_sub,
                "授权 权限 一键 grant permissions",
            )
            row(
                R.id.rowBootTrace, R.string.setting_boot_trace, R.string.setting_boot_trace_sub,
                "启动 追踪 耗时 分析 boot trace",
            )
            row(
                R.id.rowViewLog, R.string.setting_view_log, R.string.setting_view_log_sub,
                "日志 查看 引擎 log view",
            )
            row(
                R.id.rowRestart, R.string.setting_restart_engine, R.string.setting_restart_engine_sub,
                "重启 引擎 restart engine",
            )
            row(
                R.id.rowDeepClean, R.string.setting_deep_clean, R.string.setting_deep_clean_sub,
                "深度 清理 缓存 垃圾 deep clean cache",
            )
            row(
                R.id.rowResetHome, R.string.setting_reset_home, R.string.setting_reset_home_sub,
                "重置 主界面 首页 reset home",
            )
        }

        // ═══════════════════ 三级：对话清理 ═══════════════════
        page(
            "wipe_chats", R.string.wipe_chats_title, WipeChatsActivity::class.java,
            3, "activity_wipe_chats", "maintenance",
        ) {
            row(
                R.id.btnSelectAll, R.string.wipe_chats_select_all, null,
                "全选 选择 全部 select all",
            )
            row(
                R.id.chipAll, R.string.wipe_chats_title, null,
                "筛选 全部 对话 filter all",
            )
            row(
                R.id.btnDeleteSelected, R.string.wipe_chats_delete_selected, null,
                "删除 选中 对话 delete selected",
            )
            row(
                R.id.btnClearAll, R.string.wipe_chats_clear_all, null,
                "清空 全部 对话 clear all",
            )
        }

        // ═══════════════════ 二级：更新设置 ═══════════════════
        page(
            "update", R.string.settings_update_group, UpdateSettingsActivity::class.java,
            2, "activity_update_settings", "about",
        ) {
            row(
                R.id.rowUpdate, R.string.about_check_update, R.string.about_update_idle,
                "检查 更新 应用 升级 update check",
            )
            row(
                R.id.rowRuntime, R.string.about_runtime, R.string.about_runtime_idle,
                "运行时 更新 版本 runtime",
            )
            row(
                R.id.rowUpdateAutoCheck, R.string.settings_update_auto_check,
                R.string.settings_update_auto_check_desc,
                "自动 检查 更新 auto check",
            )
            row(
                R.id.rowUpdateWifiOnly, R.string.settings_update_wifi_only,
                R.string.settings_update_wifi_only_desc,
                "仅 wifi 流量 下载 wifi only",
            )
            row(
                R.id.rowUpdateIndexProxy, R.string.settings_update_proxy_fallback,
                R.string.settings_update_proxy_fallback_desc,
                "更新 代理 镜像 index proxy",
            )
            row(
                R.id.rowUpdateStrategy, R.string.settings_update_strategy,
                R.string.settings_update_strategy_desc,
                "更新 策略 方式 strategy",
            )
            row(
                R.id.rowUpdateIgnored, R.string.settings_update_ignored,
                R.string.settings_update_ignored_none,
                "忽略 版本 跳过 ignored skip",
            )
            row(
                R.id.rowUpdateCheckNow, R.string.settings_update_check_now,
                R.string.settings_update_check_now_desc,
                "立即 检查 更新 check now",
            )
        }

        // ═══════════════════ 二级：自定义指令 ═══════════════════
        page(
            "user_prompt", R.string.setting_user_prompt, UserPromptActivity::class.java,
            2, "activity_user_prompt", "main",
        ) {
            row(
                R.id.editorPrompt, R.string.setting_user_prompt, R.string.user_prompt_intro,
                "自定义 指令 提示词 编辑 编辑框 prompt agents",
            )
            row(
                R.id.btnSave, R.string.user_prompt_save, null,
                "保存 指令 save",
            )
        }

        // ═══════════════════ 二级：关于 ═══════════════════
        page(
            "about", R.string.setting_about, AboutActivity::class.java,
            2, "activity_about", "main",
        ) {
            row(
                R.id.txtVersion, R.string.about_app_name, null,
                "版本 版本号 当前 version",
            )
            row(
                R.id.rowRepo, R.string.about_open_source, null,
                "开源 仓库 代码 地址 repo github open source",
            )
            row(
                R.id.rowQqGroup, R.string.about_qq_group, R.string.about_qq_group_id,
                "qq 群 交流 反馈 group",
            )
            row(
                R.id.rowTelegram, R.string.about_telegram, R.string.about_telegram_sub,
                "telegram tg 群 频道 交流",
            )
            row(
                R.id.rowStar, R.string.about_star_title, R.string.about_star_sub,
                "star 点赞 支持 收藏",
            )
        }
    }

    // ── 查询 ────────────────────────────────────────────────────────────

    /**
     * 按关键词检索。多词按「与」处理；`null` 表示没有命中。
     * 返回结果已按「档位 → 层级 → 目录顺序」排好。
     */
    fun search(raw: String, titleOf: (Int) -> String): List<SearchHit> {
        val tokens = raw.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()

        val out = ArrayList<SearchHit>()
        items.forEachIndexed { order, item ->
            val page = pageMap[item.pageId] ?: return@forEachIndexed
            val title = titleOf(item.titleRes).lowercase()
            val summary = item.summaryRes?.let { titleOf(it).lowercase() }.orEmpty()
            val keys = item.keywords.lowercase()

            var worst = 0
            var matchedAll = true
            for (t in tokens) {
                val score = when {
                    title == t -> 0
                    title.startsWith(t) -> 1
                    title.contains(t) -> 2
                    summary.contains(t) -> 3
                    keys.contains(t) -> 4
                    else -> {
                        matchedAll = false
                        break
                    }
                }
                if (score > worst) worst = score
            }
            if (!matchedAll) return@forEachIndexed
            out += SearchHit(item, page, worst, order)
        }
        return out.sortedWith(
            compareBy({ it.score }, { it.page.level }, { it.order }),
        )
    }

    /** 诊断用：目录规模与层级分布（`engine.log` / 探针可读）。 */
    fun stats(): String = buildString {
        append("pages=").append(pages.size)
        append(" items=").append(items.size)
        val byLevel = pages.groupingBy { it.level }.eachCount().toSortedMap()
        append(" levels=").append(byLevel)
    }
}
