package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * 对话完成检测（v1.2.57）。
 *
 * ## 为什么需要
 * 用户反馈：「AI 完成任务之后，其实通知栏没有任何信息，能不能直接监测，
 * 只要有对话完成就弹出消息」。此前 `notify` 是**由 AI 主动调用**的，
 * AI 忘了调、或者没意识到任务已结束，用户就收不到任何提示。
 *
 * ## 信号来源（权威、非猜测）
 * dsh 的会话持久化把每轮对话写成 JSONL 事件流，其中 **`turn/end` 是官方定义的
 * 「本轮对话结束」事件**（见 `dsh-session-format-v3-to-v4` 的 RELATIONSHIP_TYPES：
 * `turn/start` / `turn/end` / `step/start` / `step/end` …）。
 * 位置：`$DSH_HOME/sessions/<projectKey>/<sessionId>/` 下的 jsonl 文件
 * （`dsh-base` 里 `session-persistence-jsonl` 的 `root: dshHomePath('sessions')`）。
 *
 * 比「AI 主动调 notify」可靠得多：不依赖模型是否记得，只要对话真的结束就会触发。
 *
 * ## 实现
 * 轻量轮询（默认 1.5s）：扫描 sessions 目录下最新的几个 .jsonl，
 * 记录每个文件的已读偏移，只解析新增的行，发现 `turn/end` 就回调。
 * 轮询而非 FileObserver：Android 上 FileObserver 对多层子目录不可靠，
 * 且引擎写入频繁；1.5s 粒度对「任务完成提示」完全够用。
 *
 * ## v1.6.48：为什么这里曾经是「硬件富余却卡顿」的主要来源
 *
 * 旧实现每 1.5 秒：①`walkTopDown(maxDepth=4)` 遍历**整棵** sessions 树并
 * 对每个文件 `lastModified()` 后**全量排序**（会话越多越慢，且全是新分配的
 * File/lambda/List）；②`.zstd` 会话只要有改动就调 `ZstdTail.read` ——
 * 那是 **`ProcessBuilder` 拉起一个全新的 Node 进程**（实测每个进程 ~40-80MB
 * RSS、上百毫秒启动）。正在聊天 = 会话文件每秒都在变 = **每 1.5 秒 fork 一次
 * Node**，与引擎主进程、WebView 渲染进程抢同一批 CPU 核 —— 表现就是
 * 明明内存 CPU 都空着，界面却在流式输出时一顿一顿。
 *
 * 现在（本版）：
 *  · **目录级缓存**：只在项目目录 mtime 变化时重列该目录；轮询稳态成本
 *    收敛为「项目目录数 + 候选文件数」个 stat，与历史会话总量无关；
 *  · **子进程限流**：解压要求「文件已静置 ≥500ms」，且**全局**最小间隔
 *    （前台 8s / 后台 2s）+ 单飞（同一时刻最多一个，绝不排队堆积）；
 *  · 解压失败/超限（>32MB）不再重试，避免坏文件把 CPU 按住。
 */
object TurnWatcher {

    private const val TAG = "TurnWatcher"

    /** 轮询间隔：够快（用户几乎无感）又不浪费（每次只读增量） */
    private const val POLL_MS = 1_500L

    /** 单次增量读取上限：防异常情况下一次读入巨大文件 */
    private const val MAX_CHUNK = 256 * 1024

    /** 每个项目目录下保留的候选会话文件上限（活跃会话必在最新几个里） */
    private const val MAX_FILES_PER_PROJECT = 4

    /** 全局候选文件上限（排序后取前几名） */
    private const val MAX_CANDIDATES = 6

    /** 扫描的项目目录上限：超多项目的用户也只照顾最近活跃的那些 */
    private const val MAX_PROJECTS = 24

    /** 候选列表缓存上限：超过就整体清空（极便宜的 LRU 替代，避免无界增长） */
    private const val CACHE_MAX_ENTRIES = 96

    /** zstd 解压要求文件静置多久（引擎写完这一批才解，避免解半截） */
    private const val ZSTD_SETTLE_MS = 500L

    /** 两次 Node 子进程之间的最小间隔：前台更宽（正在用，别抢 CPU） */
    private const val ZSTD_SPAWN_GAP_FG_MS = 8_000L
    private const val ZSTD_SPAWN_GAP_BG_MS = 2_000L

    /** 超过这个体积的会话不再解压（整体解压的代价与体积成正比，不是本功能的职责） */
    private const val MAX_ZSTD_BYTES = 32L * 1024 * 1024

    /** 每个文件记住已读到的字节偏移 */
    private val offsets = HashMap<String, Long>()

    /** 项目目录缓存（v1.6.48）：目录 mtime → 该目录下最新的几个会话文件 */
    private class DirCache(val mtime: Long, val files: List<File>)

    private val dirCache = HashMap<String, DirCache>()
    private var cachedRootMtime = -1L
    private var projectDirs: List<File> = emptyList()

    /** zstd 解压限流：上次拉起 Node 的时间 + 是否已有一次在飞（绝不排队堆积） */
    private val zstdGate = Any()
    private var lastZstdSpawnAt = 0L
    private var zstdInFlight = false

    /** 是否已完成首次基线扫描（首次只记录偏移，不把历史对话当成新完成） */
    @Volatile private var primed = false

    /**
     * 会话事件类型（v1.6.35）。
     *
     * 同一份 JSONL 里的 `turn/start` / `step/start` / `turn/end` 正好对应
     * 「思考中 / 执行中 / 已完成」三个展示状态 —— 比让 UI 猜要可靠得多，
     * 而且与 `turn/end` 共用同一次增量读，**零额外 IO**。
     */
    enum class Event { TURN_START, STEP_START, TURN_END }

    @Volatile private var thread: Thread? = null

    @Volatile private var running = false

    /**
     * 启动监听。
     * @param ctx 用于定位 dsh-home
     * @param onTurnEnd 每检测到一次 turn/end 回调（在监听线程上，勿做重活）
     */
    fun start(
        ctx: Context,
        onTurnEnd: (sessionFile: String) -> Unit,
        /** v1.6.35：同时把 turn/start、step/start 上报给状态总线（可为 null） */
        onEvent: ((Event) -> Unit)? = null,
    ) {
        if (running) return
        running = true
        val appCtx = ctx.applicationContext
        thread = Thread({
            Log.i(TAG, "turn watcher started (poll ${POLL_MS}ms)")
            while (running) {
                try {
                    scan(appCtx, onTurnEnd, onEvent)
                } catch (e: Exception) {
                    Log.w(TAG, "scan failed: ${e.message}")
                }
                try {
                    Thread.sleep(POLL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
            Log.i(TAG, "turn watcher stopped")
        }, "dsh-turn-watcher").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    /** 重置基线（引擎重启后调用：把现有文件偏移当基线，不误报历史对话） */
    fun resetBaseline() {
        synchronized(offsets) { offsets.clear() }
        primed = false
    }

    private fun scan(
        ctx: Context,
        onTurnEnd: (String) -> Unit,
        onEvent: ((Event) -> Unit)? = null,
    ) {
        val root = File(EngineConfig.dshHome(ctx), "sessions")
        if (!root.isDirectory) return

        // v1.6.48：不再 walkTopDown 全树遍历 + 全量排序。
        // 改为「项目目录缓存 + 每目录只留最新几个文件」，稳态成本与历史会话
        // 总量无关（见类注释）。活跃会话必然在它所属项目目录的最新几个里。
        val logs = candidates(root)
        if (logs.isEmpty()) return

        // 首次扫描只建基线：避免把历史会话的 turn/end 当成「刚刚完成」
        if (!primed) {
            synchronized(offsets) {
                logs.forEach { offsets[it.absolutePath] = it.length() }
            }
            primed = true
            Log.i(TAG, "baseline primed: ${logs.size} session log(s)")
            return
        }

        logs.forEach { f ->
            val key = f.absolutePath
            val known = synchronized(offsets) { offsets[key] }
            val len = f.length()
            when {
                // 新文件：从 0 开始读（新会话的第一轮完成也要报）
                known == null -> readFrom(ctx, f, 0L, key, onTurnEnd, onEvent)
                // 追加了内容
                len > known -> readFrom(ctx, f, known, key, onTurnEnd, onEvent)
                // 文件被截断/重建（会话迁移）→ 重新建基线
                len < known -> synchronized(offsets) { offsets[key] = len }
            }
        }
    }

    /**
     * 候选会话文件（v1.6.48 的核心：把「每 1.5 秒遍历整棵树」换成「只 stat 已知的几十个」）。
     *
     * 三级缓存：
     *  1. 项目目录列表按 `sessions/` 的 mtime 缓存 —— 没新建项目就完全不重列；
     *  2. 单个项目目录内的文件列表按该目录 mtime 缓存 —— 没有新会话就不重列；
     *     目录 mtime 只在「新增/删除会话目录」时变化，**追加内容不会**，
     *     而追加是靠下面第 3 步的 `length()` 发现的，两者互补；
     *  3. 每次轮询仍然 stat 候选文件的 `length()/lastModified()` —— 但只有
     *     ≤ `MAX_FILES_PER_PROJECT × 项目数` 次，且是有界的。
     *
     * 返回按 mtime 降序的前 [MAX_CANDIDATES] 个。
     */
    private fun candidates(root: File): List<File> {
        // ① sessions/ 目录列表
        val rootMtime = root.lastModified()
        if (rootMtime != cachedRootMtime || projectDirs.isEmpty()) {
            cachedRootMtime = rootMtime
            projectDirs = root.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedByDescending { it.lastModified() }
                ?.take(MAX_PROJECTS)
                .orEmpty()
            // 目录集合变了：把已消失项目的缓存清掉（防无界增长）
            if (dirCache.size > CACHE_MAX_ENTRIES) {
                val alive = projectDirs.mapTo(HashSet()) { it.absolutePath }
                dirCache.keys.retainAll(alive)
            }
        }

        // ② 每个项目目录按自己的 mtime 增量重列
        for (dir in projectDirs) {
            val key = dir.absolutePath
            val mtime = dir.lastModified()
            val hit = dirCache[key]
            if (hit != null && hit.mtime == mtime && hit.files.isNotEmpty()) continue
            val files = dir.listFiles()
                ?.filter { isSessionLog(it) }
                ?.sortedByDescending { it.lastModified() }
                ?.take(MAX_FILES_PER_PROJECT)
                .orEmpty()
            dirCache[key] = DirCache(mtime, files)
        }

        // ③ 合并 + 取最新 N 个
        if (dirCache.size > CACHE_MAX_ENTRIES) dirCache.clear()
        val all = ArrayList<File>(projectDirs.size * MAX_FILES_PER_PROJECT)
        for (dir in projectDirs) dirCache[dir.absolutePath]?.files?.let { all.addAll(it) }
        if (all.isEmpty()) return emptyList()
        all.sortByDescending { it.lastModified() }
        return if (all.size <= MAX_CANDIDATES) all else all.subList(0, MAX_CANDIDATES)
    }

    /** v1.2.105：放宽到同时收 .jsonl.zstd / .json.zstd（引擎两种落盘形态都要认） */
    private fun isSessionLog(f: File): Boolean {
        if (!f.isFile) return false
        val n = f.name
        return n.endsWith(".jsonl") || n.endsWith(".jsonl.zstd") || n.endsWith(".json.zstd")
    }

    /**
     * 从 [from] 读到文件尾，逐行找 `turn/end`，并更新偏移。
     *
     * 只认「事件类型字段精确等于 turn/end」的行：JSONL 每行是一个事件对象，
     * 形如 `{"type":"turn/end",...}`。用字符串包含判断会有误报风险
     * （比如 assistant 回复里提到这个词），故解析出 type 字段再比对。
     */
    private fun readFrom(
        ctx: Context,
        f: File,
        from: Long,
        key: String,
        onTurnEnd: (String) -> Unit,
        onEvent: ((Event) -> Unit)? = null,
    ) {
        var hits = 0
        var starts = 0
        var steps = 0
        var newOffset: Long
        try {
            // ⚠️ v1.2.61 修复：引擎默认用 **zstd 压缩**写会话
            // （dsh-session-persistence-jsonl 的 DEFAULT_COMPRESSION = "zstd"，
            //  文件名是 `.jsonl.zstd`）。初版只找 `.jsonl` → 永远匹配不到文件 →
            // 对话完成通知从不触发（用户实测「还是没有接到系统通知」）。
            // zstd 是**帧压缩**：不能按字节偏移读增量，只能整体解压后比对。
            // 为控制开销，只对「最后修改时间在最近 2 分钟内」的文件做解压。
            if (f.name.endsWith(".zstd")) {
                readZstd(ctx, f, key, onTurnEnd, onEvent)
                return
            }
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(from)
                val avail = (raf.length() - from).coerceAtLeast(0L)
                val toRead = minOf(avail, MAX_CHUNK.toLong()).toInt()
                if (toRead <= 0) return
                val buf = ByteArray(toRead)
                raf.readFully(buf)
                // v1.6.48：先在**字节**里找最后一个换行，只解码完整行。
                // 旧实现是「整块解码 → 切字符串 → 再把完整部分 toByteArray 求长度」，
                // 等于同一块数据被 UTF-8 编解码两次（每次都是整块的新分配）；
                // 活跃会话每 1.5 秒走一遍，是稳定的 GC 压力来源。
                var end = -1
                for (i in buf.size - 1 downTo 0) {
                    if (buf[i] == '\n'.code.toByte()) { end = i; break }
                }
                if (end < 0) return                 // 还没有完整行，等下次
                newOffset = from + end + 1          // 字节偏移，无需重新编码
                val complete = String(buf, 0, end, Charsets.UTF_8)

                // v1.6.48：区间扫描，**零分配**。
                // 旧实现是 `lineSequence()` + 每行 `eventType(line)`（内部 3 次
                // substring）。一段 200KB 的增量 ≈ 500 行 ≈ 2000+ 个临时 String，
                // 每 1.5 秒一次 —— 正是「内存富余却还在频繁 GC」的那种源头。
                val counted = countEvents(complete, 0, complete.length)
                hits += counted[0]; starts += counted[1]; steps += counted[2]
            }
        } catch (e: Exception) {
            Log.w(TAG, "read ${f.name} failed: ${e.message}")
            return
        }
        synchronized(offsets) { offsets[key] = newOffset }
        repeat(starts) { onEvent?.invoke(Event.TURN_START) }
        repeat(steps) { onEvent?.invoke(Event.STEP_START) }
        repeat(hits) { onTurnEnd(f.name) }
    }


    /** 已处理过的 zstd 会话文件签名（路径 → 解压后内容长度），避免重复上报 */
    private val zstdSeen = HashMap<String, Int>()

    /**
     * zstd 会话文件的完成检测。
     *
     * zstd 是帧压缩，无法按字节偏移增量读 —— 只能整体解压。
     * 为控制开销：只解压「最近 2 分钟内修改过」的文件（活跃会话），
     * 并用解压后的**文本长度**做去重（长度变了 = 有新内容 = 可能有新 turn/end）。
     */
    /** zstd 文件的 mtime+大小 缓存（没变化就不解压 —— 每次全解压太重） */
    private val zstdMtime = HashMap<String, Long>()

    private fun readZstd(
        ctx: Context,
        f: File,
        key: String,
        onTurnEnd: (String) -> Unit,
        onEvent: ((Event) -> Unit)? = null,
    ) {
        // ⚡ mtime 去重：文件没更新就不解压（活跃会话少则几十秒写一次）
        val stamp = f.lastModified() xor (f.length() shl 21)
        val prev = synchronized(zstdMtime) {
            if (zstdMtime.size > CACHE_MAX_ENTRIES) zstdMtime.clear()
            zstdMtime[key]
        }
        // ⚠️ v1.6.48：**先判定条件，通过了才写戳**。
        // 旧实现先把 stamp 写进去再解压，于是被限流跳过的那一次会被记成
        // 「已处理」—— 那批内容就永远丢了（turn/end 静默漏报）。
        if (prev != null && prev == stamp) return

        // 体积上限：整体解压的代价与体积成正比，超大会话不是本功能的职责
        if (f.length() > MAX_ZSTD_BYTES) {
            synchronized(zstdMtime) { zstdMtime[key] = stamp }
            Log.i(TAG, "zstd skip (${f.length() / 1024 / 1024}MB > limit): ${f.name}")
            return
        }

        // 静置门槛：引擎正在写这一批时不解（解出来是半截，长度判断会错乱）
        val now = System.currentTimeMillis()
        if (now - f.lastModified() < ZSTD_SETTLE_MS) return

        // 全局限流：Node 子进程（40~80MB RSS + 上百 ms 启动）绝不能跟着轮询节奏跑。
        // 前台 8s / 后台 2s —— 用户正盯着屏幕时优先级最低，因为那时 UI 自己就会更新。
        val gap = if (app.dsh.mobile.status.AppForeground.isForeground()) {
            ZSTD_SPAWN_GAP_FG_MS
        } else {
            ZSTD_SPAWN_GAP_BG_MS
        }
        if (now - lastZstdSpawnAt < gap) return

        // 单飞：同一时刻最多一个解压进程；在飞期间直接跳过（下一轮再来），
        // 绝不排队 —— 排队会让 CPU 一直有事做，那正是「闲不下来」的卡顿长相。
        synchronized(zstdGate) {
            if (zstdInFlight) return
            zstdInFlight = true
            lastZstdSpawnAt = now
        }
        val text = try {
            // v1.2.105：多帧解压。**必须全解**（keepFrames=0）：下面按「解压后
            // 文本长度」判断有无新增，只解尾部帧会让长度不再单调增长，检测会失效。
            ZstdTail.read(ctx, f, keepFrames = 0).first
        } finally {
            synchronized(zstdGate) { zstdInFlight = false }
        } ?: return
        synchronized(zstdMtime) { zstdMtime[key] = stamp }

        val prevLen = synchronized(zstdSeen) { zstdSeen[key] }
        synchronized(zstdSeen) {
            zstdSeen[key] = text.length
            if (zstdSeen.size > CACHE_MAX_ENTRIES) zstdSeen.clear()
        }
        if (prevLen == null) return          // 首次见到：只建基线
        if (text.length <= prevLen) return   // 没有新增内容

        // 只检查「新增部分」里的 turn/end，避免把历史轮次重复上报。
        // v1.6.48：直接在原串上按区间扫描，不再 substring 出整段 delta
        // （活跃会话全解压时 delta 可能有几百 KB，一次 substring 就是一份新拷贝）。
        val from2 = prevLen.coerceIn(0, text.length)
        val counted = countEvents(text, from2, text.length)
        val hits = counted[0]; val starts = counted[1]; val steps = counted[2]
        repeat(starts) { onEvent?.invoke(Event.TURN_START) }
        repeat(steps) { onEvent?.invoke(Event.STEP_START) }
        repeat(hits) { onTurnEnd(f.name) }
    }


    /**
     * 在 [s] 的 `[from, to)` 区间内统计 `turn/end` / `turn/start` / `step/start`。
     *
     * 返回 `intArrayOf(turnEnd, turnStart, stepStart)`。
     *
     * ## 为什么不用「切行 + 切子串」
     * 这里是**每 1.5 秒都会跑一次**的热路径，而会话增量动辄几百 KB。用
     * `lineSequence().map{...}` 那种写法，每行要造 1 个行 String、每个事件再造
     * 3 个 substring，一段 500 行的增量就是 2000+ 个短命对象。它们全都活不过
     * 一次扫描 → 全部进 Young GC，扫描越频繁、GC 越密，主线程越容易在被调起
     * 分配时正好撞上一次 minor GC。改成按索引扫描后，这条路径**不分配任何对象**。
     *
     * ## 语义
     * 与旧实现逐字等价：只在**行首附近**找 `"type"` 键（旧实现是整行 indexOf，
     * 但因为 JSONL 每行一个扁平对象，`type` 只出现一次；这里限定到行首 512 字符内
     * 既保持等价、又避免有人往值里塞超长文本时整行扫描）。
     */
    private fun countEvents(s: CharSequence, from: Int, to: Int): IntArray {
        var turnEnd = 0
        var turnStart = 0
        var stepStart = 0
        var lineStart = from
        var i = from
        while (i <= to) {
            // `to` 视为一个虚拟换行：这样**末尾没有换行的最后一行**也会被统计
            // （zstd 全解压拿到的文本经常不以 \n 结尾；JSONL 增量那侧因为
            //   只在最后一个 \n 之前处理，`lineStart` 恰好等于 `to`，不会重复计）
            val atEnd = i == to
            if (atEnd || s[i] == '\n') {
                if (i > lineStart) {
                    when (typeAt(s, lineStart, i)) {
                        TYPE_TURN_END -> turnEnd++
                        TYPE_TURN_START -> turnStart++
                        TYPE_STEP_START -> stepStart++
                    }
                }
                lineStart = i + 1
            }
            i++
        }
        return intArrayOf(turnEnd, turnStart, stepStart)
    }

    private const val TYPE_NONE = 0
    private const val TYPE_TURN_END = 1
    private const val TYPE_TURN_START = 2
    private const val TYPE_STEP_START = 3

    /** 行内 `"type":"<value>"` 的值归类。无分配（逐字符比较）。 */
    private fun typeAt(s: CharSequence, start: Int, end: Int): Int {
        // 行首可能缩进/空白，跳过
        var p = start
        while (p < end && (s[p] == ' ' || s[p] == '\t' || s[p] == '{')) p++
        // 找 `"type"`：整行扫描，与旧实现（line.indexOf）**完全等价**
        val limit = end
        var k = p
        while (k + 5 < limit) {
            if (s[k] == '"' && s[k + 1] == 't' && s[k + 2] == 'y' && s[k + 3] == 'p' &&
                s[k + 4] == 'e' && s[k + 5] == '"'
            ) {
                var q = k + 6
                while (q < limit && s[q] != ':') q++
                q++
                while (q < limit && (s[q] == ' ' || s[q] == '\t')) q++
                if (q >= limit || s[q] != '"') return TYPE_NONE
                q++
                return when {
                    matches(s, q, end, "turn/end") -> TYPE_TURN_END
                    matches(s, q, end, "turn/start") -> TYPE_TURN_START
                    matches(s, q, end, "step/start") -> TYPE_STEP_START
                    else -> TYPE_NONE
                }
            }
            k++
        }
        return TYPE_NONE
    }

    /** 判断 s 从 pos 起是否以 lit 开头。 */
    private fun matches(s: CharSequence, pos: Int, end: Int, lit: String): Boolean {
        if (pos + lit.length > end) return false
        for (n in lit.indices) if (s[pos + n] != lit[n]) return false
        return true
    }
}
