package app.dsh.mobile.engine

import java.io.File

/**
 * engine.log 的唯一写入入口。
 *
 * ## 为什么需要它
 * engine.log 有**两个生产者**，原本各自直接 `appendText`、彼此没有同步：
 *  · [EngineProcess] 的日志泵线程（引擎 stdout 泵入）
 *  · `MainActivity.logWebView()` —— WebView 的 `onConsoleMessage` 回调，
 *    跑在 **WebView/UI 线程**上，一次 `appendText` 就是一次同步磁盘写。
 *
 * 由此带来三个真实问题：
 *  1. **并发写无锁**：两条线程同时对同一文件 append，落盘内容可能交错。
 *  2. **环形容量的判断是 TOCTOU**：`if (length() < max) appendText(...)` 在两次
 *     系统调用之间文件可能已被另一线程写大，2MB 上限不严格。
 *  3. **主线程同步 I/O**：UI 线程上的磁盘写会在磁盘繁忙/内存紧张时直接掉帧
 *     （本项目目标设备常见 4-8GB 内存、后台挂着多个大型 App）。
 *  4. **环形截断的代价随日志增长**：旧实现超限时 `readText()` 一次性读入 2MB
 *     再整体 `writeText`，在低内存设备上是一个明显卡顿点。
 *
 * ## 做法
 *  · 所有写入串行化（单一锁），从根本上消除交错与 TOCTOU；
 *  · 环形截断改为「按行追加、超限时只保留尾部一半」，且用随机存取定位，
 *    不再把全文读进堆内存；
 *  · UI 线程的写入走 [appendAsync]（交给后台单线程执行器），不阻塞渲染；
 *  · 任何失败都静默（日志本身不该影响主流程），与旧行为一致。
 */
object EngineLog {

    /** 2MB 上限（与既有约定一致） */
    const val MAX_BYTES: Long = 2L shl 20

    /** 所有写操作共用的锁：跨实例、跨线程串行化 */
    private val lock = Any()

    /**
     * UI 线程使用的后台写入执行器（单线程即可保证顺序）。
     *
     * v1.6.48：队列**有界**（[ASYNC_QUEUE]）。旧实现用的是
     * `Executors.newSingleThreadExecutor()`，那是个**无界** LinkedBlockingQueue：
     * 一旦页面开始刷警告（或磁盘一时忙），投递进来的字符串会一直堆在堆里，
     * 既涨内存又让「日志写完了吗」永远追不上 —— 这恰恰是内存富余却 gc 频繁的
     * 一类典型形态（生产速度快于消费速度 + 无背压）。
     * 日志不是业务数据，**宁可丢最旧的也不能无限堆积**（丢弃量见 [dropped]）。
     */
    private const val ASYNC_QUEUE = 512

    private val asyncExec = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(ASYNC_QUEUE),
        { r -> Thread(r, "dsh-engine-log").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    /** 因队列满而丢弃的异步日志条数（只用于自检，不参与逻辑） */
    private val droppedCount = java.util.concurrent.atomic.AtomicLong()

    /** 已被丢弃的异步日志条数（跨线程安全读取） */
    val dropped: Long get() = droppedCount.get()

    /** 同步追加（日志泵线程用：它本就是后台线程，直接写即可保持顺序） */
    fun append(file: File, text: String) {
        synchronized(lock) { writeLocked(file, text) }
    }

    /**
     * 同步追加**原始字节**（v1.6.49，引擎 stdout 直落盘用）。
     *
     * ## 为什么必须有这条路径
     * 引擎 stdout 走的是真 PTY（`dsh_pty.c` 的 `dup2(slave, STDOUT_FILENO)`），而按 Node
     * 文档，POSIX 下 **TTY 的 `process.stdout` 写是同步的** —— 每条 `console.log` 都要
     * 等 tty 缓冲被读走才返回，期间**整个事件循环阻塞**。tty 缓冲只有几 KB，所以读端
     * 每慢一拍，引擎的 WS/HTTP/定时器就整体停一拍（而 CPU 采样看起来是空的，因为它是
     * 阻塞不是计算）。
     *
     * 旧泵循环每 4KB 做：`String(buf, UTF_8)` 解码 → `Log.d`（一次 logd binder 往返）
     * → `append(file, text)` 内部再 `toByteArray()` 编回去。等于**每个块两次整块复制**，
     * 外加一次跨进程日志调用 —— 流式输出时日志最密，这是读端跟不上的直接原因。
     *
     * 现在：64KB 一块、原始字节直接写盘（零解码零重编码），logcat 回显由
     * `Log.isLoggable` 门控（默认关，现场排查时 `setprop log.tag.EngineProcess DEBUG` 打开）。
     *
     * 附带修掉一个潜在乱码：旧实现按 4KB 块独立 `String(..., UTF_8)`，多字节字符
     * 恰好跨块时会被解成替换符 U+FFFD 落进 engine.log；按字节写永远不会有这个问题。
     */
    fun appendBytes(file: File, buf: ByteArray, len: Int) {
        if (len <= 0) return
        synchronized(lock) { writeLocked(file, buf, 0, len) }
    }

    /** 异步追加（UI 线程用：绝不阻塞渲染） */
    fun appendAsync(file: File, text: String) {
        // 队列满时 DiscardOldestPolicy 会静默丢掉最旧的一条，避免无限堆积
        val queued = runCatching {
            asyncExec.queue.size < ASYNC_QUEUE
        }.getOrDefault(true)
        if (!queued) droppedCount.incrementAndGet()
        runCatching { asyncExec.execute { synchronized(lock) { writeLocked(file, text) } } }
    }

    /**
     * 调用方必须已持有 [lock]。
     *
     * 环形策略：若追加后超过 [MAX_BYTES]，只保留尾部一半再追加新内容。
     * 相比旧实现（`readText()` 把整个 2MB 读进堆再 `writeText`），这里只做一次
     * 文件内的区段读取，避免在大日志时产生整块堆分配与复制。
     */
    private fun writeLocked(file: File, text: String) {
        // ⚠️ 长度必须取**字节数**：String.length 是字符数，中文/emoji 下两者不等，
        // 传错会把日志截断在半个字符上（这里曾经写错过，留注释防止再犯）。
        val add = text.toByteArray(Charsets.UTF_8)
        writeLocked(file, add, 0, add.size)
    }

    /**
     * 字节版（v1.6.49）：环形截断逻辑与字符串版**完全一致**，只是不再经过 String。
     * [off]/[len] 允许直接把调用方的读缓冲写出去，不做第二次复制。
     */
    private fun writeLocked(file: File, src: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        try {
            file.parentFile?.mkdirs()
            val cur = if (file.exists()) file.length() else 0L
            if (cur + len <= MAX_BYTES) {
                java.io.FileOutputStream(file, true).use { it.write(src, off, len) }
                return
            }
            // 截断：保留尾部 keep 字节
            val keep = MAX_BYTES / 2
            val start = (cur - keep).coerceAtLeast(0L)
            var tail = java.io.RandomAccessFile(file, "rw").use { raf ->
                raf.seek(start)
                val buf = ByteArray((cur - start).toInt())
                raf.readFully(buf)
                buf
            }
            // 截断点可能落在多字节 UTF-8 字符中间：跳过续字节（10xxxxxx），
            // 否则日志开头会出现替换符。只在尾部判断一次，代价可以忽略。
            var i = 0
            while (i < tail.size && (tail[i].toInt() and 0xC0) == 0x80) i++
            if (i > 0) tail = tail.copyOfRange(i, tail.size)
            file.writeBytes(tail)
            java.io.FileOutputStream(file, true).use { it.write(src, off, len) }
        } catch (_: Throwable) {
            // 日志失败不影响主流程（与旧行为一致）
        }
    }
}
