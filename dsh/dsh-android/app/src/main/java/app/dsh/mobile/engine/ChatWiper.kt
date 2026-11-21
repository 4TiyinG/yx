package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import app.dsh.mobile.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对话删除（v1.6.17，用户需求：「在维护与高级里加一个删除对话内容的新功能，
 * 允许在里面直接让用户删对话，或者一键清空所有对话记录」）。
 *
 * ## 硬约束：不许影响启动速度
 *
 * 用户明确要求「不要启动时读取，一定不要让任何事情影响启动速度」。
 * 因此本类是**纯懒加载**：
 *
 * · 没有任何 `init` 块、没有 `object` 初始化时的工作、没有后台线程自启；
 * · [list] 只在用户点「选择对话」时调一次，且必须在后台线程；
 * · 启动路径（`EngineSupervisor` / `MainActivity` / `DshApp`）**完全不引用本类**。
 *
 * 这一点由探针 `_test_chat_wiper.py` 反向锁定：把本类加进任何启动路径即变红。
 *
 * ## 为什么外部改注册表会被抹掉
 *
 * 引擎把 `storages/workspace.json` 当**独占文件**：启动时读一次进内存，之后每次
 * 变更整份写回，既不监视文件也不比对时间戳。所以运行期删掉会话文件后，引擎
 * 内存里仍持有那些 sessionId，下一次任何变更都会把整份注册表写回去 —— 于是
 * 列表里出现一堆打不开的幽灵条目。`ChatRecovery` 踩过同一个坑（见其注释里
 * 「13:04 写进 7 个工作区，13:07 被回落成 1 个」）。
 *
 * ## 两条删除路径（v1.6.28）
 *
 * 1. **[deleteHot] —— 默认路径，不停引擎**。把「让引擎忘掉这些会话」交给引擎进程内的
 *    `session-admin.mjs` 插件：它直接改内存注册表再让工作区服务写回，引擎全程运行，
 *    WebUI 连接与上下文都不丢。热通道不可用时返回 null。
 * 2. **[delete] / [clearAll] —— 回退路径**。`停引擎 → 删文件 + 清注册表 → 重启引擎`，
 *    由调用方负责 `EngineSupervisor.stopForMaintenance()` 与
 *    `allowStartAfterMaintenance()`。老引擎版本、插件被禁用、或引擎根本没在跑时才走这里。
 *
 * ## 安全边界
 *
 * 1. **删除前必备份**：`workspace.json` 拷一份带时间戳的副本到 `storages/`，
 *    并把被删的会话文件**移动**（不是 unlink）到 `sessions/.trash-<时间戳>/`
 *    下，保留 [TRASH_KEEP_DAYS] 天。删错了还能捞回来。
 * 2. **只删会话本体，不碰工作区**：工作区条目（用户手建的目录）保留，只把
 *    `sessionIds` 里对应项摘掉。清空全部时同理 —— 工作区壳还在，只是没有对话。
 * 3. **归档表也要清**：`global.archivedSessionIds` 里残留的 id 一并摘掉，
 *    否则「已归档」列表里会留下点进去空白的条目。
 * 4. **原子写回**：临时文件 + rename，避免半截 JSON 让引擎彻底读不了注册表。
 */
object ChatWiper {

    private const val TAG = "ChatWiper"
    private const val REGISTRY = "storages/workspace.json"
    private const val SESSIONS = "sessions"
    private const val SESSION_FILE = "session.v4.jsonl.zstd"

    /** 回收站保留天数；超过则由 [purgeTrash] 清掉。 */
    const val TRASH_KEEP_DAYS = 7

    /** 回收站目录名前缀。**列表遍历必须排除它**，否则删掉的对话会重新出现。 */
    private const val TRASH_PREFIX = ".trash-"

    /**
     * 列表里的一个会话。
     *
     * 只带 UI 需要的字段 —— 标题来自投影缓存（明文 JSON），**不解压会话本体**，
     * 否则几百个会话要起几百个 node 进程，点一下按钮等几十秒。
     */
    data class Item(
        val sessionId: String,
        /** 会话文件或目录（删除时用；可能为 null 表示只有注册表条目、文件已丢） */
        val file: File?,
        /** 对话名称：与 WebUI 列表显示的是同一个标题（投影缓存里的 `title`） */
        val title: String,
        /**
         * 对话所属位置（工作区路径）。取不到的为空串。
         *
         * 解析优先级见 [locationOf]：工作区注册表登记 > 投影缓存的 cwd >
         * `sessions/<cwd-slug>/` 目录名。三级兜底之后仍有值的概率极低。
         */
        val location: String,
        val cwd: String,
        val updatedAt: Long,
        val bytes: Long,
        val archived: Boolean,
    )

    /** 扫描结果。 */
    data class Snapshot(
        val items: List<Item>,
        val totalBytes: Long,
        /** 注册表里登记了、但磁盘上找不到文件的「幽灵」会话数 */
        val ghosts: Int,
    )

    /**
     * 枚举全部会话（含已归档）。
     *
     * **必须在后台线程调用**（会遍历目录 + 读投影缓存）。
     */
    fun list(ctx: Context): Snapshot {
        val home = EngineConfig.dshHome(ctx)
        val root = registryOf(home)
        val registered = registeredIds(root)
        val archived = archivedIds(root)
        // sessionId → 所属工作区路径。一次性建表，避免每个会话都去翻注册表。
        val wsOf = workspaceMap(root)
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        val sessionsRoot = File(home, SESSIONS)

        sessionsRoot.listFiles()?.forEach { top ->
            if (!top.isDirectory) return@forEach
            // ⚠️ 必须跳过回收站目录本身。
            //
            // 回收站就建在 `sessions/` 下（`.trash-<stamp>-<mode>/`），里面是
            // `<session-id>/session.v4.jsonl.zstd` —— 与正常布局长得一模一样。
            // 不排除的话，用户刚删掉的对话会**重新出现在列表里**（离线复刻脚本
            // 抓到的真 bug：清空全部之后 list 仍返回 1 条）。
            if (top.name.startsWith(TRASH_PREFIX)) return@forEach
            // 布局一（当前）：sessions/<cwd-slug>/<session-id>/
            // 布局二（极早期）：sessions/<cwd-slug>/<session-id>.jsonl
            val nested = top.listFiles() ?: return@forEach
            for (child in nested) {
                val (id, file) = when {
                    child.isDirectory -> {
                        val f = File(child, SESSION_FILE)
                        if (!f.isFile) continue
                        child.name to f
                    }
                    child.isFile && child.name.endsWith(".jsonl") ->
                        child.name.removeSuffix(".jsonl") to child
                    else -> continue
                }
                if (!seen.add(id)) continue
                val meta = metaOf(home, id)
                out += Item(
                    sessionId = id,
                    file = file,
                    title = displayTitle(ctx, meta.title, id),
                    location = locationOf(wsOf[id], meta.cwd, top.name),
                    cwd = meta.cwd,
                    updatedAt = lastActiveOf(file, meta.lastPromptAt),
                    bytes = sizeOf(file),
                    archived = id in archived,
                )
            }
        }

        // 注册表里有、磁盘上没有的幽灵条目：也列出来，让用户能清掉
        val ghosts = registered.filter { it !in seen }
        for (id in ghosts) {
            val meta = metaOf(home, id)
            out += Item(
                sessionId = id,
                file = null,
                title = displayTitle(ctx, meta.title, id),
                location = locationOf(wsOf[id], meta.cwd, ""),
                cwd = meta.cwd,
                updatedAt = meta.lastPromptAt,
                bytes = 0L,
                archived = id in archived,
            )
        }

        val sorted = out.sortedByDescending { it.updatedAt }
        Log.i(TAG, "list: items=${sorted.size} ghosts=${ghosts.size}")
        return Snapshot(sorted, sorted.sumOf { it.bytes }, ghosts.size)
    }

    /**
     * 删除指定会话。
     *
     * @param ids 要删的 sessionId
     * @return 实际删除条数
     */
    fun delete(ctx: Context, ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        return run(ctx, ids.toSet(), "delete")
    }

    /**
     * 清空全部会话（保留工作区壳与本类不认识的其它文件）。
     *
     * @return 实际清掉的会话数
     */
    fun clearAll(ctx: Context): Int {
        val all = list(ctx).items.map { it.sessionId }.toSet()
        return clearAllIds(ctx, all)
    }

    /**
     * 清空指定 id 集合（调用方已扫过列表，避免二次 IO）。
     *
     * 与 [delete] 的区别只在回收站目录名与日志标记（`-clear-all`），
     * 便于事后从磁盘上区分「用户一个个勾着删」和「一键全清」。
     */
    fun clearAllIds(ctx: Context, ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        return run(ctx, ids.toSet(), "clear-all")
    }

    /**
     * 清掉超过 [TRASH_KEEP_DAYS] 天的回收站目录。
     *
     * @return 清掉的目录数
     */
    /** 立即清空所有回收站（彻底清理，不走 7 天保留）。 */
    fun purgeAllTrash(ctx: Context): Int {
        val sessionsRoot = File(EngineConfig.dshHome(ctx), SESSIONS)
        var n = 0
        sessionsRoot.listFiles()?.forEach { f ->
            if (f.isDirectory && f.name.startsWith(TRASH_PREFIX)) {
                if (f.deleteRecursively()) n++
            }
        }
        if (n > 0) Log.i(TAG, "purged $n trash dir(s) immediately")
        return n
    }

    fun purgeTrash(ctx: Context): Int {
        val sessionsRoot = File(EngineConfig.dshHome(ctx), SESSIONS)
        val cutoff = System.currentTimeMillis() - TRASH_KEEP_DAYS * 24L * 3600_000L
        var n = 0
        sessionsRoot.listFiles()?.forEach { f ->
            if (!f.isDirectory) return@forEach
            if (!f.name.startsWith(TRASH_PREFIX)) return@forEach
            if (f.lastModified() > cutoff) return@forEach
            if (f.deleteRecursively()) n++
        }
        if (n > 0) Log.i(TAG, "purged $n expired trash dir(s)")
        return n
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 真正的删除流程。调用方**必须先停引擎**。
     *
     * @param mode 仅用于归档目录命名与日志，便于事后追查是单删还是清空
     */
    private fun run(ctx: Context, ids: Set<String>, mode: String): Int {
        val moved = moveToTrash(ctx, ids, mode)
        val cacheCleared = clearProjectionCache(ctx, ids)
        val registryRemoved = pruneRegistryOnDisk(ctx, ids)
        healRegistry(ctx)
        Log.i(
            TAG,
            "$mode(cold): requested=${ids.size} filesMoved=$moved cacheCleared=$cacheCleared " +
                "registryEntries=$registryRemoved",
        )
        return maxOf(moved, registryRemoved)
    }

    // ── 热删除（不停引擎）────────────────────────────────────────────────────

    /** 热删除结果。`detail` 只进日志/诊断，不上屏。 */
    data class HotOutcome(
        /** 引擎确认目标 id 已从内存注册表消失，且磁盘也已收拾干净 */
        val ok: Boolean,
        /** 实际清掉的会话数（文件 + 注册表条目的较大值） */
        val removed: Int,
        /** 诊断串：走了哪条路、清了多少缓存、有没有残留 */
        val detail: String,
    )

    /**
     * **不停引擎**删除会话（v1.6.28）。
     *
     * ## 与 [delete] 的区别
     *
     * [delete] 要求调用方先 `stopForMaintenance()`，删完再 `restart()` —— 引擎重启
     * 十几秒、WebUI 断连，用户每次删几个对话都要付这个代价。
     *
     * 本方法把「让引擎忘掉这些会话」这件事交给**引擎进程内**的 `session-admin.mjs`
     * 插件：它直接改内存里的注册表，再让引擎自己的工作区服务写回。因此引擎全程
     * 保持运行，WebUI 的连接与上下文都在。
     *
     * 顺序：**先让引擎放手，再收拾磁盘**。反过来的话，引擎可能在两步之间用旧内存
     * 状态把注册表写回来，磁盘上刚摘掉的 id 又长回去（这正是旧实现必须停引擎的原因）。
     *
     * @return null = 热通道不可用（插件未部署 / 引擎未运行 / 协议不认），调用方
     *         应回退到「停引擎」路径；否则返回结果，`ok = false` 时同样应回退
     */
    fun deleteHot(ctx: Context, ids: Collection<String>, mode: String = "delete"): HotOutcome? {
        if (ids.isEmpty()) return HotOutcome(true, 0, "noop")
        val set = ids.toSet()

        // 引擎没在跑时不需要热通道：没有内存状态可被"抹回"，也不该为了删几个对话
        // 把引擎拉起来（用户可能刚在通知栏退出，正在省电）。
        if (!engineRunning(ctx)) {
            val moved = moveToTrash(ctx, set, mode)
            val cacheCleared = clearProjectionCache(ctx, set)
            val registryRemoved = pruneRegistryOnDisk(ctx, set)
            healRegistry(ctx)
            Log.i(
                TAG,
                "$mode(idle): requested=${set.size} filesMoved=$moved cacheCleared=$cacheCleared " +
                    "registryEntries=$registryRemoved",
            )
            return HotOutcome(
                ok = true,
                removed = maxOf(moved, registryRemoved).takeIf { it > 0 } ?: set.size,
                detail = "engine-idle moved=$moved cache=$cacheCleared pruned=$registryRemoved",
            )
        }

        val answer = HotSessionAdmin.requestDelete(ctx, set) ?: return null
        if (!answer.ok) {
            Log.w(
                TAG,
                "$mode(hot): engine refused strategy=${answer.strategy} present=${answer.presentAfter} " +
                    "raw=${answer.rawAfter} ${answer.detail}",
            )
            return HotOutcome(false, 0, "refused: ${answer.strategy} ${answer.detail}".trim())
        }

        val moved = moveToTrash(ctx, set, mode)
        val cacheCleared = clearProjectionCache(ctx, set)
        // 引擎内存已经干净，磁盘这一份只是"顺手对齐"：即便引擎稍后自己整份写回，
        // 写回的也是已经不含这些 id 的新状态，不会再长出幽灵会话。
        val registryRemoved = pruneRegistryOnDisk(ctx, set)
        healRegistry(ctx)

        val removed = maxOf(moved, registryRemoved).takeIf { it > 0 } ?: set.size
        Log.i(
            TAG,
            "$mode(hot): requested=${set.size} strategy=${answer.strategy} removed=$removed " +
                "filesMoved=$moved cacheCleared=$cacheCleared registryEntries=$registryRemoved " +
                "presentAfter=${answer.presentAfter} rawAfter=${answer.rawAfter}",
        )
        return HotOutcome(
            ok = true,
            removed = removed,
            detail = "${answer.strategy} moved=$moved cache=$cacheCleared pruned=$registryRemoved",
        )
    }

    /**
     * 探测热通道是否可用，供设置页显示「免重启删除：可用/不可用」。
     *
     * 成功时把引擎服务的方法名写进日志 —— 引擎升级后形状变了，从这一行就能看出来。
     */
    fun probeHotChannel(ctx: Context): Boolean {
        val answer = HotSessionAdmin.probe(ctx) ?: return false
        Log.i(TAG, "hot channel probe: ok=${answer.ok} strategy=${answer.strategy} ${answer.detail}")
        return answer.ok
    }

    /**
     * 引擎是否正在运行（含安全模式）。
     *
     * 只有运行中的引擎才持有那份会"抹掉外部修改"的内存注册表；Idle/Stopped/Failed
     * 时删磁盘就够了。
     */
    private fun engineRunning(ctx: Context): Boolean {
        val supervisor = runCatching {
            (ctx.applicationContext as? app.dsh.mobile.DshApp)?.supervisor
        }.getOrNull() ?: return false
        return when (runCatching { supervisor.state.value }.getOrNull()) {
            is EngineSupervisor.State.Healthy, is EngineSupervisor.State.SafeMode -> true
            else -> false
        }
    }

    // ── 共用步骤 ────────────────────────────────────────────────────────────

    /** 把会话本体移动到回收站（移动而非 unlink：用户可能误删）。 */
    private fun moveToTrash(ctx: Context, ids: Set<String>, mode: String): Int {
        val home = EngineConfig.dshHome(ctx)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val trash = File(File(home, SESSIONS), "$TRASH_PREFIX$stamp-$mode")
        var moved = 0
        val sessionsRoot = File(home, SESSIONS)
        sessionsRoot.listFiles()?.forEach { top ->
            if (!top.isDirectory || top.name.startsWith(TRASH_PREFIX)) return@forEach
            val nested = top.listFiles() ?: return@forEach
            for (child in nested) {
                val id = when {
                    child.isDirectory -> child.name
                    child.isFile && child.name.endsWith(".jsonl") -> child.name.removeSuffix(".jsonl")
                    else -> continue
                }
                if (id !in ids) continue
                val dest = File(trash, id)
                val ok = runCatching {
                    trash.mkdirs()
                    if (!child.renameTo(dest)) {
                        // 跨文件系统 rename 会失败：退化成 copy + delete
                        child.copyRecursively(dest, overwrite = true)
                        child.deleteRecursively()
                    }
                    true
                }.getOrElse {
                    Log.w(TAG, "move $id to trash failed: ${it.message}")
                    false
                }
                if (ok) moved++
            }
        }
        return moved
    }

    /** 清引擎会话投影缓存：残留会让 WebUI/引擎继续拿到已删元数据。 */
    private fun clearProjectionCache(ctx: Context, ids: Set<String>): Int {
        val projSessions = File(EngineConfig.dshHome(ctx), "storages/session_projcache/sessions")
        var cleared = 0
        for (id in ids) {
            if (File(projSessions, "$id.json").delete()) cleared++
        }
        return cleared
    }

    /** 从 `workspace.json` 摘掉给定会话 id（备份 + 原子写），返回摘掉的条目数。 */
    private fun pruneRegistryOnDisk(ctx: Context, ids: Set<String>): Int {
        val registryFile = File(EngineConfig.dshHome(ctx), REGISTRY)
        if (!registryFile.isFile) return 0
        val root = runCatching { JSONObject(registryFile.readText()) }.getOrNull()
        if (root == null) {
            Log.e(TAG, "registry unreadable, skipped id cleanup")
            return 0
        }
        val removed = pruneRegistry(root, ids)
        if (removed > 0) {
            backupRegistry(registryFile)
            writeAtomically(registryFile, root)
        }
        return removed
    }

    /**
     * 收敛注册表不变量。摘掉 sessionIds 槽位后，archived/pinned 引用、
     * workspaceIds 顺序、defaultWorkspaceId 都可能悬空。引擎启动时严格
     * 校验这些关系，任一不自洽都会让 workspace-controller 激活失败 ——
     * 表现就是 WebUI 报 directoryPickerController is unavailable。
     */
    private fun healRegistry(ctx: Context) {
        runCatching { WorkspaceRegistryHealer.heal(ctx) }
            .onFailure { Log.w(TAG, "registry invariant heal failed: ${it.message}") }
    }

    /** 从注册表里摘掉给定会话 id，返回摘掉的条目数。 */
    private fun pruneRegistry(root: JSONObject, ids: Set<String>): Int {
        var removed = 0
        val tables = root.optJSONObject("tables")?.optJSONObject("workspaces")
        if (tables != null) {
            for (wid in tables.keys().asSequence().toList()) {
                val ws = tables.optJSONObject(wid) ?: continue
                val arr = ws.optJSONArray("sessionIds") ?: continue
                val next = JSONArray()
                var changed = false
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i)
                    if (id in ids) {
                        removed++
                        changed = true
                    } else {
                        next.put(id)
                    }
                }
                if (changed) {
                    ws.put("sessionIds", next)
                    ws.put("updatedAt", isoNow())
                }
            }
        }
        // 归档表与固定表也要清，否则「已归档」里留下点进去空白的条目
        for (key in listOf("archivedSessionIds", "pinnedSessionIds")) {
            val g = root.optJSONObject("global") ?: continue
            val arr = g.optJSONArray(key) ?: continue
            val next = JSONArray()
            var changed = false
            for (i in 0 until arr.length()) {
                val id = arr.optString(i)
                if (id in ids) {
                    removed++
                    changed = true
                } else {
                    next.put(id)
                }
            }
            if (changed) g.put(key, next)
        }
        return removed
    }

    private fun backupRegistry(file: File) {
        runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            file.copyTo(File(file.parentFile, "workspace.json.before-wipe-$stamp"), overwrite = true)
        }.onFailure { Log.w(TAG, "registry backup failed: ${it.message}") }
    }

    private fun writeAtomically(file: File, root: JSONObject) {
        val tmp = File(file.parentFile, ".workspace.json.wipe-tmp")
        runCatching {
            tmp.writeText(root.toString(2) + "\n", StandardCharsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure {
            runCatching { tmp.delete() }
            Log.e(TAG, "registry write failed: ${it.message}", it)
        }
    }

    private fun registryOf(home: File): JSONObject? {
        val f = File(home, REGISTRY)
        if (!f.isFile) return null
        return runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    private fun registeredIds(root: JSONObject?): Set<String> {
        if (root == null) return emptySet()
        val out = HashSet<String>()
        root.optJSONObject("tables")?.optJSONObject("workspaces")?.let { tables ->
            for (wid in tables.keys().asSequence().toList()) {
                val arr = tables.optJSONObject(wid)?.optJSONArray("sessionIds") ?: continue
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf(String::isNotBlank)?.let(out::add)
                }
            }
        }
        return out
    }

    private fun archivedIds(root: JSONObject?): Set<String> {
        val arr = root?.optJSONObject("global")?.optJSONArray("archivedSessionIds")
            ?: return emptySet()
        val out = HashSet<String>()
        for (i in 0 until arr.length()) {
            arr.optString(i).takeIf(String::isNotBlank)?.let(out::add)
        }
        return out
    }

    /**
     * 投影缓存里解析出来的会话元信息。
     *
     * [title] 为空表示「缓存里确实没有标题」，由调用方决定怎么兜底 ——
     * 不在这里直接塞 sessionId，免得把「没标题」和「标题恰好等于 id」混为一谈。
     */
    private data class Meta(
        val title: String = "",
        val cwd: String = "",
        val lastPromptAt: Long = 0L,
    )

    /**
     * 从投影缓存读标题 / cwd / 最后活跃时间。
     *
     * ## 为什么必须按 `record.rows.<key>.val` 取值
     *
     * v1.6.17 的初版在**根对象**上 `optString("title")` / `optString("cwd")`，
     * 而引擎的真实结构是：
     *
     * ```json
     * { "record": { "rows": {
     *     "title":                 { "val": "重构登录流程" },
     *     "titleInput":            { "val": { "first": "帮我改一下登录…" } },
     *     "sessionListMetadata":   { "val": { "lastPromptAt": 1794502798000, "blank": false } }
     * } } }
     * ```
     *
     * 根对象上**根本没有** title/cwd 这两个键，于是每次都落空、退回 sessionId ——
     * 这正是用户看到的 `session-fecf2126…`。同一份结构 `SessionReader` 早就按
     * 正确路径解析了（那是在真机上验证过的实现），这里对齐它。
     *
     * 不解压 `.zstd` 会话本体：几百个会话要起几百个 node 进程，点一下按钮等到超时。
     */
    private fun metaOf(home: File, sessionId: String): Meta {
        val f = File(home, "storages/session_projcache/sessions/$sessionId.json")
        if (!f.isFile) return Meta()
        return runCatching { parseProj(JSONObject(f.readText())) }.getOrDefault(Meta())
    }

    /** 解析投影缓存 JSON（结构见 [metaOf] 注释）。 */
    private fun parseProj(o: JSONObject): Meta {
        val rows = o.optJSONObject("record")?.optJSONObject("rows")

        /** `rows.<key>.val`，且 val 是字符串 */
        fun strRow(key: String): String {
            val row = rows?.optJSONObject(key) ?: return ""
            val v = row.opt("val") ?: return ""
            return if (v is String) v.trim() else ""
        }

        /** `rows.<key>.val`，且 val 是对象 */
        fun objRow(key: String): JSONObject? =
            rows?.optJSONObject(key)?.optJSONObject("val")

        val title = strRow("title")
        val firstPrompt = objRow("titleInput")?.optString("first").orEmpty().trim()
        val listMeta = objRow("sessionListMetadata")
        val lastPromptAt = listMeta?.optLong("lastPromptAt", 0L) ?: 0L
        val blank = listMeta?.optBoolean("blank", true) ?: true

        // 标题：引擎算好的 title 优先；没有就用首条用户输入（WebUI 也是这么显示的）
        val shown = title.ifBlank { firstPrompt }

        // cwd：投影缓存里没有单独的顶层 cwd 键，逐个可能是位置来源的地方试探。
        // 都不在时返回空串，由 [locationOf] 用 sessions/<slug> 兜底。
        val cwd = sequenceOf(
            { strRow("cwd") },
            { strRow("workspace") },
            { strRow("workspacePath") },
            { objRow("cwd")?.optString("path").orEmpty() },
            { o.optString("cwd") },
        ).map { it() }.firstOrNull { it.isNotBlank() }.orEmpty()

        // 空白会话本身没有标题，别把空串当标题丢给 UI
        return Meta(if (blank && shown.isBlank()) "" else shown, cwd, lastPromptAt)
    }

    /**
     * 会话标题的最终展示值。
     *
     * 三级兜底：投影缓存标题 → （无）→ 「未命名对话」。
     * **绝不退回 sessionId** —— 用户要的就是「别再显示那串没意义的长字符串」。
     * sessionId 已经在我们需要的地方（删除时按它定位文件），不必摆给用户看。
     */
    private fun displayTitle(ctx: Context, title: String, sessionId: String): String {
        if (title.isNotBlank()) return title
        Log.d(TAG, "no title in projection cache for $sessionId")
        return ctx.getString(R.string.wipe_chats_untitled)
    }

    /**
     * 会话的「最后活跃时间」。
     *
     * 不能用 `session.v4.jsonl.zstd.lastModified()` —— 实测该文件**只在建会话时写一次**
     * （`SessionReader` 里有同样的记录：模拟器上 434 字节、mtime 停在创建时刻），
     * 用它排序会让所有对话的时间集体偏旧、与 WebUI 的顺序对不上（用户说的「错位」）。
     *
     * 真实活跃信号：会话目录 / `session.lock` / 投影缓存的 `lastPromptAt`，取最大。
     */
    private fun lastActiveOf(file: File, lastPromptAt: Long): Long {
        var t = maxOf(file.lastModified(), lastPromptAt)
        runCatching {
            file.parentFile?.let { dir ->
                if (dir.lastModified() > t) t = dir.lastModified()
                File(dir, "session.lock").takeIf { it.isFile }?.lastModified()?.let { if (it > t) t = it }
            }
        }
        return t
    }

    /**
     * 位置显示值：工作区注册表 → 投影缓存 cwd → `sessions/<slug>` 目录名。
     *
     * 取 **basename** 而不是全路径：设置页那一行宽度只有几百 dp，
     * `/data/user/0/…/workspaces/飞书助手` 这种全路径会把真正有辨识度的尾段挤掉。
     * 全路径仍保留在 [Item.cwd] 里，需要时可展示。
     *
     * 三级兜底都拿不到时返回空串，UI 就不画这一行（不显示「未知」这类噪音）。
     */
    private fun locationOf(registryPath: String?, cwd: String, slug: String): String {
        val reg = registryPath?.trim().orEmpty()
        if (reg.isNotEmpty()) return baseName(reg)
        val c = cwd.trim()
        if (c.isNotEmpty()) return if (c.contains('/') || c.contains('\\')) baseName(c) else c
        return slugAlias(slug)
    }

    private fun baseName(path: String): String =
        path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }

    /**
     * 从 `sessions/` 下的 cwd-slug 目录名还原一个可读别名。
     *
     * 引擎把 cwd 的 `/` 换成 `-` 当目录名：`/…/workspaces/飞书助手` →
     * `-data-user-0-…-workspaces-飞书助手`。这个转换**不可逆**（原名里的 `-`
     * 与分隔符混在一起），所以只做「够用」的还原：
     *
     * · 先找 `-workspaces-` 这个引擎工作区根标记，取其后半段 —— 对引擎自己
     *   管理的工作区（绝大多数情况）就是准确别名；
     * · 找不到就退回最后一段；
     * · 还不行就原样返回 slug（宁可难看，也不猜错）。
     */
    private fun slugAlias(slug: String): String {
        if (slug.isBlank()) return ""
        val marker = "-workspaces-"
        val i = slug.lastIndexOf(marker)
        if (i >= 0) {
            val tail = slug.substring(i + marker.length)
            if (tail.isNotBlank()) return tail
        }
        return slug.substringAfterLast('-').ifBlank { slug }
    }

    /**
     * 建 sessionId → 工作区路径 的映射，来源是 `storages/workspace.json` 的
     * `tables.workspaces.<wid>.sessionIds`。
     *
     * 这就是 WebUI 侧栏用来分组的那份登记表，所以按它显示位置能与 WebUI 一致。
     * 一个会话若被登记在多个工作区（历史遗留），取第一个 —— 与 WebUI 分组时
     * 「先命中先归属」的行为一致。
     */
    private fun workspaceMap(root: JSONObject?): Map<String, String> {
        val tables = root?.optJSONObject("tables")?.optJSONObject("workspaces") ?: return emptyMap()
        val out = HashMap<String, String>()
        for (wid in tables.keys().asSequence().toList()) {
            val ws = tables.optJSONObject(wid) ?: continue
            val path = ws.optString("path").trim()
            if (path.isEmpty()) continue
            val arr = ws.optJSONArray("sessionIds") ?: continue
            for (i in 0 until arr.length()) {
                val id = arr.optString(i)
                if (id.isNotBlank()) out.putIfAbsent(id, path)
            }
        }
        return out
    }

    private fun sizeOf(f: File): Long = when {
        f.isFile -> f.length()
        f.isDirectory -> f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        else -> 0L
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(Date())
}
