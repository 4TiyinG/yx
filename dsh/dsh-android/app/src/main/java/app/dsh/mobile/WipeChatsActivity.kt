package app.dsh.mobile

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.ChatWiper
import app.dsh.mobile.engine.PluginFastBoot
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.PluginMarket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 删除对话（v1.6.17）。
 *
 * 用户需求原文：「在维护与高级里加一个删除对话内容的新功能，允许在里面直接让
 * 用户删对话，或者一键清空所有对话记录」+「不要启动时读取，一定不要让任何事情
 * 影响启动速度」。
 *
 * ## 懒加载是硬约束，不是优化
 *
 * 会话枚举会遍历 `sessions/` 下所有目录、读投影缓存、统计体积 —— 几百个会话时
 * 是几百次 IO。**绝不能挂在启动路径上**。所以：
 *
 * · 本 Activity 只在用户主动点「删除对话」时创建；
 * · 列表在 [android.os.HandlerThread] 之外的普通 [Thread] 里扫（见 [load]）；
 * · 在此之前 UI 只显示一个 ProgressBar，不预先算任何东西。
 *
 * `MainActivity` / `DshApp` / `EngineSupervisor` 的启动序列完全不引用本类与
 * [ChatWiper]，这一点由探针反向锁定。
 *
 * ## 删除不再重启引擎（v1.6.28）
 *
 * 引擎把 `workspace.json` 当独占文件（启动读一次进内存，之后整份写回）。运行期
 * 删了会话文件，引擎下一次变更会用内存状态把注册表写回来 —— 列表里就会出现一堆
 * 点进去空白的幽灵条目。[ChatWiper] 的类注释里记了 `ChatRecovery` 踩这个坑的实测。
 *
 * 旧实现只能「停引擎 → 删 → 重启」。现在默认走 [ChatWiper.deleteHot]：删除请求交给
 * 引擎进程内的 `session-admin.mjs` 插件，由它直接改内存注册表 —— **引擎全程运行**，
 * 删完只需刷新 WebUI 的列表状态。
 *
 * 热通道不可用时（老引擎、插件被禁用、引擎没在跑）才回退到「停引擎」路径，并由
 * 界面明确告知 —— 用户不会在不知情的情况下被重启引擎。
 *
 * ## 归档筛选
 *
 * 扫描结果本来就带 `archived` 标记（引擎的 `global.archivedSessionIds`），所以
 * 「全部 / 进行中 / 已归档」只是**内存里的一次过滤**，切换标签不重新读盘。
 */
class WipeChatsActivity : ThemedActivity() {

    /** 属于设置体系：启用「设置页背景图」。 */
    override val usesSettingsBackground: Boolean get() = true

    private data class Row(val item: ChatWiper.Item, val check: CheckBox)

    private val rows = ArrayList<Row>()

    /**
     * 选中的会话 id —— **选择的唯一数据源**。
     *
     * 不靠「哪些行已经建好、它们的 CheckBox 是什么状态」来推断选中，原因：
     * 分帧构建意味着任意时刻都只有一部分行存在于视图树里。若以视图为准，
     * 构建过程中点「全选」只会选到已建好的那十几行，剩下的行随后建出来
     * 全是未勾选 —— 用户看到「全选」按钮点了却只选中一小部分，
     * 删的时候还会漏删。这类「状态与视图不同步」正是界面不稳的典型来源。
     *
     * 以集合为准之后：行建出来时按集合回填勾选态，因此**先选后建、先建后选
     * 结果完全一致**，与构建进度无关。
     */
    private val selected = HashSet<String>()
    private var snapshot: ChatWiper.Snapshot? = null
    private var busy = false
    private var loading = false

    /** 列表筛选：全部 / 进行中 / 已归档。 */
    private enum class Filter { ALL, ACTIVE, ARCHIVED }

    private var filter = Filter.ALL

    /** 当前筛选下的会话（[render] 与 [toggleSelectAll] 都基于它，而不是全量）。 */
    private var visible: List<ChatWiper.Item> = emptyList()

    /** 当前筛选下的 id 集合（选择范围以它为准）。 */
    private var visibleIds: Set<String> = emptySet()

    private lateinit var chipAll: TextView
    private lateinit var chipActive: TextView
    private lateinit var chipArchived: TextView
    private lateinit var listChats: LinearLayout
    private lateinit var boxLoading: LinearLayout
    private lateinit var txtEmpty: TextView
    private lateinit var txtSelection: TextView
    private lateinit var btnDeleteSelected: TextView
    private lateinit var btnSelectAll: TextView

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_wipe_chats)

        listChats = findViewById(R.id.listChats)
        boxLoading = findViewById(R.id.boxLoading)
        txtEmpty = findViewById(R.id.txtEmpty)
        txtSelection = findViewById(R.id.txtSelection)
        btnDeleteSelected = findViewById(R.id.btnDeleteSelected)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        chipAll = findViewById(R.id.chipAll)
        chipActive = findViewById(R.id.chipActive)
        chipArchived = findViewById(R.id.chipArchived)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        btnSelectAll.setOnClickListener { toggleSelectAll() }
        btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }
        findViewById<TextView>(R.id.btnClearAll).setOnClickListener { confirmClearAll() }
        chipAll.setOnClickListener { switchFilter(Filter.ALL) }
        chipActive.setOnClickListener { switchFilter(Filter.ACTIVE) }
        chipArchived.setOnClickListener { switchFilter(Filter.ARCHIVED) }
        refreshChips()

        load()
    }

    override fun onResume() {
        super.onResume()
        // 与其它设置页一致：从外观页返回时朝向/开关可能已变，重新对齐一次。
        // 值未变时是空操作。**这里不触发任何会话扫描**（扫描只在 onCreate 与
        // 删除成功后发生），避免用户切出切入时重复 IO。
        OrientationPrefs.applyToSettingsPage(this)
    }

    // ── 扫描 ────────────────────────────────────────────────────────────────

    /**
     * 枚举会话。
     *
     * 首次进入与删除后都会调这个。**始终在后台线程**，UI 只显示进度。
     */
    private fun load() {
        if (loading) return
        loading = true
        boxLoading.visibility = LinearLayout.VISIBLE
        txtEmpty.visibility = TextView.GONE
        btnDeleteSelected.alpha = 0.4f
        btnSelectAll.isEnabled = false

        Thread {
            val snap = runCatching { ChatWiper.list(this) }.getOrNull()
            runOnUiThread {
                loading = false
                boxLoading.visibility = LinearLayout.GONE
                btnSelectAll.isEnabled = true
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (snap == null) {
                    txtEmpty.visibility = TextView.VISIBLE
                    txtSelection.setText(R.string.wipe_chats_load_failed)
                    return@runOnUiThread
                }
                snapshot = snap
                applyFilter(snap)
                render(snap)
            }
        }.apply { name = "wipe-chats-scan" }.start()
    }

    /**
     * 逐帧构建的批次编号。每次 [render] 自增，**旧批次看到编号变了就自己退出**。
     *
     * 没有这个编号，删除后重扫会在旧批次还没跑完时启动新批次，两个批次
     * 交替往 `listChats` 里塞行 → 列表出现重复行、勾选状态错位。
     */
    private var buildGeneration = 0

    /** 待构建的行（跨帧保留在字段上，避免闭包反复捕获）。 */
    private var pendingItems: List<ChatWiper.Item> = emptyList()

    /** 下一个待构建行的下标。 */
    private var pendingIndex = 0

    /**
     * 渲染会话列表。
     *
     * ## 为什么不再一次性插完
     *
     * 旧实现 `items.forEach { addView(buildRow(it)) }` 把**全部行**放在**同一帧**里
     * 同步插入。每个会话约 5–6 个 View（行容器 + 勾选框 + 标题 + 位置 + 副标题，
     * 外加行间分隔线），重度用户 200 个会话就是 1100 个 View 在**一次 traversal**
     * 里同时 measure + layout；而标题列带 `layout_weight=1f`，LinearLayout 对带权
     * 子项会二次测量，实际 measure 次数再翻一倍。
     *
     * 直接后果就是用户能感知的「点进删除页，界面先僵一下再出现列表」。
     *
     * ## 改法：分帧增量构建
     *
     * 每帧最多插 [ROW_CHUNK] 行，插完用 `postOnAnimation` 让出一帧，再插下一批。
     * 单帧工作量从此与会话总数**无关**，只与 [ROW_CHUNK] 有关。
     * 用 `postOnAnimation` 而不是 `post`：前者挂在 Choreographer 的下一帧动画回调上，
     * 天然与 vsync 对齐，不会插在渲染中间造成撕裂式追加。
     */
    private fun render(snap: ChatWiper.Snapshot) {
        // 作废旧批次：任何正在排队的追加都会在下一次回调里发现编号不符而退场
        buildGeneration++
        pendingItems = emptyList()
        pendingIndex = 0

        listChats.removeAllViews()
        rows.clear()
        // 选择范围以当前筛选为准：切标签时把不在本标签里的选择丢掉，
        // 否则「在『全部』里选了一堆，切到『已归档』按删除」会把看不见的会话一起删掉。
        selected.retainAll(visibleIds)

        if (visible.isEmpty()) {
            txtEmpty.setText(
                if (snap.items.isEmpty()) R.string.wipe_chats_empty
                else R.string.wipe_chats_empty_filtered,
            )
            txtEmpty.visibility = TextView.VISIBLE
            updateSelection()
            return
        }
        txtEmpty.visibility = TextView.GONE

        pendingItems = visible
        pendingIndex = 0
        // 首帧同步插第一批：让用户立刻看到内容（不至于白屏等一帧）
        appendChunk(buildGeneration)
    }

    /** 按当前筛选从快照算出 [visible] / [visibleIds]，并刷新标签上的计数。 */
    private fun applyFilter(snap: ChatWiper.Snapshot) {
        visible = when (filter) {
            Filter.ALL -> snap.items
            Filter.ACTIVE -> snap.items.filter { !it.archived }
            Filter.ARCHIVED -> snap.items.filter { it.archived }
        }
        visibleIds = visible.mapTo(HashSet(visible.size * 2)) { it.sessionId }
        refreshChips()
    }

    /** 切换筛选：纯内存重排，不重新扫描磁盘。 */
    private fun switchFilter(next: Filter) {
        if (busy || loading || filter == next) return
        filter = next
        val snap = snapshot ?: run { refreshChips(); return }
        applyFilter(snap)
        render(snap)
    }

    /**
     * 刷新三个筛选标签的文案与选中态。
     *
     * 计数留在标签上是有用的：用户想知道「已归档到底有多少条」才敢点进去管理，
     * 而不是逐个标签点开数。
     */
    private fun refreshChips() {
        val snap = snapshot
        val all = snap?.items?.size ?: 0
        val archived = snap?.items?.count { it.archived } ?: 0
        fun label(res: Int, count: Int): String = "${getString(res)} $count"
        chipAll.text = label(R.string.wipe_chats_tab_all, all)
        chipActive.text = label(R.string.wipe_chats_tab_active, all - archived)
        chipArchived.text = label(R.string.wipe_chats_tab_archived, archived)
        listOf(
            chipAll to Filter.ALL,
            chipActive to Filter.ACTIVE,
            chipArchived to Filter.ARCHIVED,
        ).forEach { (chip, mode) ->
            val on = mode == filter
            chip.setBackgroundResource(if (on) R.drawable.bg_btn_accent else R.drawable.bg_btn_outline)
            chip.setTextColor(getColor(if (on) R.color.on_accent else R.color.accent))
        }
    }

    /**
     * 追加一批行。
     *
     * @param gen 发起时的批次编号；与 [buildGeneration] 不符说明已被新一次渲染取代
     */
    private fun appendChunk(gen: Int) {
        if (gen != buildGeneration) return
        if (isFinishing || isDestroyed) return

        val items = pendingItems
        val end = minOf(pendingIndex + ROW_CHUNK, items.size)
        while (pendingIndex < end) {
            val index = pendingIndex
            if (index > 0) listChats.addView(divider())
            listChats.addView(buildRow(items[index]))
            pendingIndex++
        }
        updateSelection()

        if (pendingIndex < items.size) {
            // 还有剩余：让出一帧再继续（此时界面已经可滚可读，不是白屏）
            listChats.postOnAnimation { appendChunk(gen) }
        } else {
            pendingItems = emptyList()
        }
    }

    private fun divider(): android.view.View = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (1 * resources.displayMetrics.density).toInt(),
        )
        setBackgroundColor(getColor(R.color.divider))
    }

    private fun buildRow(item: ChatWiper.Item): android.view.View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            val p = (14 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            setBackgroundResource(R.drawable.bg_menu_item)
        }

        val check = CheckBox(this).apply {
            isClickable = false
            isFocusable = false
        }
        row.addView(check, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = (10 * resources.displayMetrics.density).toInt() }
        }
        val title = TextView(this).apply {
            text = item.title
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            isSingleLine = true
            // 标题可能很长（真实对话标题是首条用户输入）：截尾，保留开头最有信息量
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        column.addView(title)

        // 位置：与 WebUI 侧栏的分组一致（工作区 > cwd > sessions 目录名三级兜底）。
        // 单独一行、截**头**保留尾段：`/data/user/0/…/workspaces/飞书助手` 这种路径
        // 有辨识度的部分在末尾，MIDDLE/END 截断都会把关键信息切掉。
        if (item.location.isNotBlank()) {
            val loc = TextView(this).apply {
                text = item.location
                setTextColor(getColor(R.color.text_muted))
                textSize = 11f
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.START
            }
            column.addView(loc)
        }

        // 副标题：时间 · 体积 · 归档标记 · 幽灵标记
        val bits = ArrayList<String>(4)
        if (item.updatedAt > 0) bits += fmtDate(item.updatedAt)
        PluginMarket.humanSize(item.bytes).takeIf { it.isNotEmpty() }?.let { bits += it }
        if (item.archived) bits += getString(R.string.wipe_chats_tag_archived)
        if (item.file == null) bits += getString(R.string.wipe_chats_tag_missing)
        val sub = TextView(this).apply {
            text = bits.joinToString(" · ")
            setTextColor(getColor(R.color.text_muted))
            textSize = 11f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        column.addView(sub)
        row.addView(column)

        // 整行可点：点在任意位置都切换勾选（CheckBox 自己不可点，避免双重响应）
        row.setOnClickListener {
            val id = item.sessionId
            if (id in selected) selected.remove(id) else selected.add(id)
            check.isChecked = id in selected
            updateSelection()
        }
        check.isChecked = item.sessionId in selected
        rows += Row(item, check)
        return row
    }

    // ── 选择 ────────────────────────────────────────────────────────────────

    private fun selectedIds(): List<String> = selected.toList()

    /**
     * 全选 / 取消全选。
     *
     * 作用域是**当前筛选**下的全部会话（[visible]），而不是已建好的行 —— 分帧
     * 构建意味着任意时刻只有一部分行在视图树里，以行推断就会漏选。已建好的行
     * 同步刷新勾选态，未建好的行会在 [buildRow] 里按集合自动回填。
     */
    private fun toggleSelectAll() {
        val items = visible
        if (items.isEmpty()) return
        val all = items.all { it.sessionId in selected }
        if (all) {
            selected.removeAll(items.map { it.sessionId }.toSet())
        } else {
            items.forEach { selected.add(it.sessionId) }
        }
        rows.forEach { it.check.isChecked = it.item.sessionId in selected }
        updateSelection()
    }

    /**
     * 刷新底部选中计数。
     *
     * 分帧构建时每帧都会调到这里，而 `setText` 会让该 TextView 重新测量 ——
     * 内容没变就不写，避免分帧期间底部栏跟着每一帧重新布局。
     */
    private fun updateSelection() {
        // 分母是**当前筛选**下的条数：用户看到的列表才是他理解的"全部"。
        val total = if (visible.isNotEmpty() || snapshot != null) visible.size else rows.size
        // 集合里可能残留已不在筛选内的 id（例如删除后重扫），按当前可见数封顶
        val n = minOf(selected.size, total)
        val text = if (n == 0) {
            getString(R.string.wipe_chats_none_selected)
        } else {
            getString(R.string.wipe_chats_selected, n, total)
        }
        if (txtSelection.text?.toString() != text) txtSelection.text = text
        val alpha = if (n == 0) 0.4f else 1f
        if (btnDeleteSelected.alpha != alpha) btnDeleteSelected.alpha = alpha
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    private fun confirmDeleteSelected() {
        val ids = selectedIds()
        if (ids.isEmpty()) return
        val bytes = snapshot?.items?.filter { it.sessionId in selected }?.sumOf { it.bytes } ?: 0L
        AlertDialog.Builder(this)
            .setTitle(R.string.wipe_chats_delete_selected)
            .setMessage(getString(R.string.wipe_chats_confirm_delete, ids.size, PluginMarket.humanSize(bytes)))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> runWipe(ids) }
            .show()
    }

    private fun confirmClearAll() {
        val snap = snapshot
        val count = snap?.items?.size ?: 0
        if (count == 0) return
        val bytes = snap?.totalBytes ?: 0L
        AlertDialog.Builder(this)
            .setTitle(R.string.wipe_chats_clear_all)
            .setMessage(getString(R.string.wipe_chats_confirm_clear, count, PluginMarket.humanSize(bytes)))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                runWipe(snap?.items?.map { it.sessionId } ?: emptyList(), "clear-all")
            }
            .show()
    }

    /**
     * 执行删除。
     *
     * ## 默认路径：热删除（不停引擎）
     *
     * [ChatWiper.deleteHot] 把请求交给引擎进程内的插件，引擎全程运行。成功后：
     * · 本页列表重扫（只看磁盘，快）；
     * · 通过 [ChatChangeBus] 通知 WebUI 侧软刷新 —— 引擎没重启，但列表要跟着变。
     *
     * ## 回退路径：停引擎 → 删 → 重启引擎
     *
     * 只有热通道不可用时才走（老引擎版本、插件被禁用、引擎当前没在跑）。这时
     * 界面上会明确改写进度文案，用户在等待期间知道"正在重启引擎"，而不是以为卡住。
     */
    private fun runWipe(ids: List<String>, mode: String = "delete") {
        if (busy || ids.isEmpty()) return
        busy = true
        showBusy(true, getString(R.string.wipe_chats_working))

        Thread {
            val hotOutcome = runCatching { ChatWiper.deleteHot(this, ids, mode) }.getOrNull()
            val ok: Boolean
            val removed: Int
            if (hotOutcome != null && hotOutcome.ok) {
                ok = true
                removed = hotOutcome.removed
                // 热删除不留回收站积压：删完顺手彻底清空（用户要的是"删掉"；
                // 停引擎路径里已有注册表备份作兜底，热路径没有中断风险）
                runCatching { ChatWiper.purgeAllTrash(this) }
            } else {
                Log.i(
                    "WipeChats",
                    "hot path unavailable (${hotOutcome?.detail ?: "no channel"}) — restarting engine",
                )
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        showBusy(true, getString(R.string.wipe_chats_restarting))
                    }
                }
                val coldRemoved = wipeWithEngineRestart(ids, mode)
                ok = coldRemoved >= 0
                removed = if (ok) coldRemoved else 0
            }
            val hot = hotOutcome != null && hotOutcome.ok

            runOnUiThread {
                busy = false
                showBusy(false, null)
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(
                    this,
                    when {
                        !ok -> getString(R.string.wipe_chats_failed)
                        hot -> getString(R.string.wipe_chats_done_hot, removed)
                        else -> getString(R.string.wipe_chats_done, removed)
                    },
                    Toast.LENGTH_LONG,
                ).show()
                if (ok) {
                    if (hot) ChatChangeBus.notifyRemoved(removed)
                    load()
                } else {
                    updateSelection()
                }
            }
        }.apply { name = "wipe-chats-run" }.start()
    }

    /**
     * 回退路径：停引擎 → 删 → 重启引擎，全程在后台线程顺序执行。
     *
     * 三步必须一起跑完 —— 中间任何一步失败也要保证引擎被重新拉起来，否则用户会停在
     * 「引擎已停」的状态里（界面全白、无法对话）。
     *
     * @return 实际清掉的条数；**-1 表示失败**（引擎仍会被重新拉起）
     */
    private fun wipeWithEngineRestart(ids: List<String>, mode: String): Int {
        val sup = (application as DshApp).supervisor
        var removed = -1
        try {
            sup.stopForMaintenance()
            removed = runCatching {
                if (mode == "clear-all") ChatWiper.clearAllIds(this, ids)
                else ChatWiper.delete(this, ids)
            }.getOrElse {
                Log.e("WipeChats", "wipe failed: ${it.message}", it)
                -1
            }
            // 顺手清掉过期回收站，避免删了几百个之后 trash 无限长
            runCatching { ChatWiper.purgeAllTrash(this) }
        } catch (e: Exception) {
            Log.e("WipeChats", "wipe failed: ${e.message}", e)
        } finally {
            // 顺序有讲究：restart() 内部第一件事就是 `maintenanceStop = false`，
            // 所以必须**先** allowStartAfterMaintenance() 再 restart()。
            // restart() 是异步的（内部 scope.launch），这里不等它 —— 用户会在
            // 列表刷新后看到一个「引擎启动中」的正常启动流程。
            sup.allowStartAfterMaintenance()
            PluginFastBoot.requestSkipNext(this)
            runCatching { sup.restart() }
        }
        return removed
    }

    private fun showBusy(on: Boolean, message: String?) {
        val bar = findViewById<LinearLayout>(R.id.boxLoading)
        bar.visibility = if (on) LinearLayout.VISIBLE else LinearLayout.GONE
        if (on && message != null) {
            (bar.getChildAt(1) as? TextView)?.text = message
        } else {
            (bar.getChildAt(1) as? TextView)?.setText(R.string.wipe_chats_loading)
        }
        listChats.alpha = if (on) 0.4f else 1f
        btnDeleteSelected.isEnabled = !on
        btnSelectAll.isEnabled = !on
    }

    private fun fmtDate(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

    private companion object {
        /**
         * 每帧最多追加多少行。
         *
         * 取 12 而不是更大值：一行约 5–6 个 View，12 行≈70 个 View/帧，
         * 即便在低端机上也是个位数毫秒。总行数 ≤ 12 时**一次就插完**（不分帧），
         * 常见情况的观感与旧实现完全一致，只有长列表才分帧 —— 不为了极端情况
         * 牺牲绝大多数用户的即时感。
         */
        const val ROW_CHUNK = 12
    }
}
