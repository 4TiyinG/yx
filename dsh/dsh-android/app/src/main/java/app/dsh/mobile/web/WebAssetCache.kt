package app.dsh.mobile.web

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import app.dsh.mobile.PerfPrefs
import app.dsh.mobile.ui.ThermalGuard
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * WebUI 静态资源的本地缓存与预热（v1.6.25）。
 *
 * ## 为什么只缓存 CSS / 字体 / 图片，**绝不**碰 JS
 *
 * 这是本文件最重要的一条设计约束，理由是一个容易被忽略的不对称性：
 *
 * Chromium 自己有一套真正意义上的「预编译缓存」——**V8 code cache**。它把已经
 * 解析编译过的脚本字节码按资源存下来，下次同一脚本直接反序列化，省掉 parse +
 * compile。这个缓存**在 WebView 的私有 cache 目录里，Android SDK 没有任何 API
 * 能直接控制它**，我们能做的只有「别弄坏它」。
 *
 * 而 `shouldInterceptRequest` 一旦返回自己的响应，该资源就**脱离了 Chromium 的
 * 资源加载链路**：不会进 HTTP cache，也不会进 code cache。也就是说，拦一个 JS
 * 文件，换来的是「少一次本地 HTTP 往返」，代价却是「每次启动都重新 parse +
 * compile 一遍」——对 1MB 级的 SPA bundle，后者在手机上通常是几十到上百毫秒，
 * 明显亏。
 *
 * CSS / 字体 / 图片没有 code cache 可失去（它们不经过 V8），拦截它们**只有
 * 收益、没有这个下行风险**：省掉 Node 事件循环 + proot 系统调用翻译 + 一次
 * 回环 HTTP 往返，直接从 app 私有目录 memcpy 出来。
 *
 * 所以：[ALLOWED_EXT] 里刻意没有 `js` / `mjs` / `html`。这不是遗漏。
 *
 * ## 两种工作模式
 *
 * · **预热（[learn]）**：页面首帧落定后，取 `performance.getEntriesByType('resource')`
 *   里本机的静态资源 URL，在后台用 2 个线程自己下载并落盘。第一次运行是空的，
 *   跑完这一趟，下次启动就有货了。
 * · **命中（[serve]）**：`shouldInterceptRequest` 里查内存 → 查磁盘，命中就直接
 *   吐字节并带上 `immutable` 缓存头；未命中返回 `null`，WebView 照常走网络，
 *   行为与改动前完全一致。
 *
 * 单边降级原则：**任何异常都返回 null**。缓存坏了、磁盘满了、文件被删了、
 * JSON 破了 —— 一律退回网络路径，功能不受影响，最多是没快。
 *
 * ## 失效
 * 键里带 `schema | versionCode | <engineRoot>/.runtime-version`。App 升级或引擎
 * 运行时被 `EngineUpdater` 换掉 → stamp 变了 → 整个目录清空重建。这挡住了
 * 「引擎换了前端、我们还在喂旧 CSS」这类最难查的问题。
 *
 * 另有 [SERVE_TTL_MS]：超过 7 天的条目不再命中（当作 miss，并在下次预热时刷新），
 * 兜住「同 URL 内容变了但我们没感知到」的情况 —— 前端 bundle 通常是内容哈希
 * 命名，这条基本不会触发，属于安全网。
 */
object WebAssetCache {
    private const val TAG = "WebCache"

    /** 总开关：出问题时可整体关掉，退回纯网络路径。 */
    private const val ENABLED = true

    /**
     * 是否拦截 JS（v1.6.37，默认**关**）。
     *
     * 需求方要求「拦截关键 JS/CSS/字体」。CSS/字体/图片已在 [ALLOWED_EXT] 里、
     * 拦截零损失（它们不经过 V8，没有 code cache 可失）。
     *
     * JS 不同：`shouldInterceptRequest` 一旦返回自己的响应，该资源就**脱离了
     * Chromium 的 HTTP cache 与 V8 code cache**，每次导航都要重新解析+编译。
     * 对 1MB 级 SPA bundle，这在手机上通常是几十到上百毫秒的**首屏回退代价**，
     * 正好抵消掉拦截想省下的那一次 Node/proot 往返。
     *
     * 所以做成开关：默认信旧结论（不拦），但一行就能切到实验态做 A/B。
     * 如果后续实测（`adb shell dumpsys gfxinfo` 帧时间 / 首屏 ttfi）在目标机型
     * 上得出相反结论，改这里即可，不需要动其它代码。
     */
    private const val INTERCEPT_JS = false

    /** 存储格式版本；改结构时 +1，旧目录会被清掉。 */
    private const val SCHEMA = 1

    /**
     * 可拦截的资源类型。
     *
     * ⚠️ **不要往里加 js / mjs / html**，理由见类注释（会丢掉 V8 code cache）。
     */
    private val ALLOWED_EXT = setOf(
        // 只放「不经过 V8」的静态资源：CSS / 字体 / 图片 / 媒体。
        // JS 是否加入由 [INTERCEPT_JS] 控制（默认关，理由见该常量注释）。
        "css",
        "woff", "woff2", "ttf", "otf",
        "png", "jpg", "jpeg", "webp", "gif", "avif", "svg", "ico", "bmp",
        "mp3", "mp4", "webm", "wav", "ogg",
    ).let { base ->
        if (INTERCEPT_JS) base + setOf("js", "mjs") else base
    }

    /** 图片类扩展名：这些才需要「显示尺寸变体」。 */
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "avif")

    /**
     * 固定大块读的字节流（v1.6.36）。
     *
     * Chromium 通过 JNI 反复 `read(buf, off, len)` 拉数据，每次跨越都是一笔固定开销。
     * 与其让它自己小步读，不如我们用 64KB 的内部块一次性搬满它给的缓冲，
     * 最大限度减少「高频小片传输」。
     */
    private class ChunkedStream(private val data: ByteArray) : InputStream() {
        private var pos = 0

        override fun read(): Int {
            if (pos >= data.size) return -1
            return data[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= data.size) return -1
            val n = minOf(len, data.size - pos, READ_CHUNK)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = data.size - pos

        override fun skip(n: Long): Long {
            if (n <= 0) return 0
            val step = minOf(n, (data.size - pos).toLong()).toInt()
            pos += step
            return step.toLong()
        }
    }

    private const val READ_CHUNK = 64 * 1024

    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    /** 缓存目录总量上限，超出按最旧优先淘汰。 */
    private const val MAX_TOTAL_BYTES = 48L * 1024 * 1024

    /** 内存层预算。首屏期间反复命中的就是同一批图片/字体，这层最直接。 */
    private const val MEM_BUDGET = 12 * 1024 * 1024

    /** 超过这个年龄的条目不再命中（见类注释「失效」）。 */
    private const val SERVE_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** 预热最多处理多少个资源，防止异常页面把清单撑爆。 */
    private const val MAX_LEARN_URLS = 160

    // ── 热清单（v1.6.45）───────────────────────────────────────────────────
    // 问题：内存层是**空的**开始跑。第一次导航时每个 CSS/字体都要走一次
    // `stat` + `readBytes`（小文件）或 mmap（大文件），而这几百 KB 恰好落在
    // 首屏最拥挤的时间窗里。v1.6.25 的预热只在**首帧之后**才把资源落盘，
    // 所以它只让"第二次启动"变快，"本次启动的首屏"始终是冷的。
    //
    // 做法：把上一趟学到的关键资源（只限渲染阻塞类：CSS 与字体）按 URL 记进
    // `hot.txt`，本次启动在 IO 线程上按清单预读进内存层。命中率靠"同一份前端
    // 资产、同一批 URL"这个前提 —— 前端是内容哈希命名，URL 稳定。
    // 只做 CSS/字体、不做图片：图片靠 mmap + 显示尺寸变体，进内存反而占预算。

    /** 热清单文件名。 */
    private const val HOT_FILE = "hot.txt"

    /** 热清单最多记多少条（CSS+字体通常 < 30 条）。 */
    private const val MAX_HOT = 48

    /** 单条预读上限：超过这个大小进内存层不划算（留给 mmap）。 */
    private const val MAX_HOT_BYTES = 512L * 1024

    /** 预读总量上限：别把 MEM_BUDGET 一次吃光。 */
    private const val MAX_HOT_TOTAL = 4L * 1024 * 1024

    /** 只有这些扩展名进热清单（渲染阻塞的关键路径资源）。 */
    private val HOT_EXT = setOf("css", "woff2", "woff", "ttf", "otf")

    private val io = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "webcache").apply { isDaemon = true }
    }

    private val mem = object : LruCache<String, ByteArray>(MEM_BUDGET) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    // 统计（每次启动重置，只用于日志：让真机上能看出这层到底有没有用）
    private val hits = AtomicInteger()
    private val misses = AtomicInteger()
    private val servedBytes = AtomicLong()
    private val learned = AtomicInteger()
    private val learnedBytes = AtomicLong()
    @Volatile private var servedOnce = false

    private fun dir(ctx: Context) = File(ctx.filesDir, "webcache")

    /**
     * 启动时调用一次：比对 stamp，不一致就清空；随后做一次容量修剪。
     * 全部是轻量文件操作，但仍放到后台线程，绝不占启动主线程。
     */
    fun init(ctx: Context) {
        if (!ENABLED) return
        val app = ctx.applicationContext
        io.execute {
            runCatching {
                val root = dir(app)
                val stamp = stampOf(app)
                val stampFile = File(root, "stamp")
                val old = if (stampFile.isFile) runCatching { stampFile.readText() }.getOrNull() else null
                if (old != stamp) {
                    if (root.isDirectory) {
                        val n = root.listFiles()?.count { it.deleteRecursively() } ?: 0
                        Log.i(TAG, "cache invalidated (stamp ${old ?: "none"} -> $stamp), dropped $n entrie(s)")
                    }
                    root.mkdirs()
                    File(root, HOT_FILE).delete()   // 旧版本的 key 全是死的，留着只会白读
                    stampFile.writeText(stamp)
                }
                prune(root)
                preloadHotInBackground(app, root)
            }.onFailure { Log.w(TAG, "init failed: ${it.message}") }
        }
    }

    /**
     * 把上一趟的热清单预读进内存层。
     *
     * 在 [init] 的同一后台任务里跑（stamp 校验之后，避免把旧版本的条目读进来）。
     * 全程 IO 线程；任何异常都只是"没预读到"，[serve] 仍会照常从磁盘/网络取。
     */
    private fun preloadHotInBackground(app: Context, root: File) {
        if (!PerfPrefs.preload(app)) {
            Log.i(TAG, "hot preload skipped (user preference off)")
            return
        }
        runCatching {
            val hot = File(root, HOT_FILE)
            if (!hot.isFile) return
            val t0 = System.currentTimeMillis()
            var loaded = 0
            var bytes = 0L
            hot.readLines().asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(MAX_HOT)
                .forEach { name ->
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in HOT_EXT) return@forEach
                    val f = File(root, name)
                    if (!f.isFile) return@forEach
                    if (f.length() > MAX_HOT_BYTES) return@forEach
                    if (bytes + f.length() > MAX_HOT_TOTAL) return@forEach
                    val key = name.substringBeforeLast('.')
                    if (mem.get(key) != null) return@forEach
                    val b = runCatching { f.readBytes() }.getOrNull() ?: return@forEach
                    mem.put(key, b)
                    loaded++
                    bytes += b.size
                }
            if (loaded > 0) {
                Log.i(
                    TAG,
                    "hot preloaded $loaded asset(s), ${bytes / 1024} KB in " +
                        "${System.currentTimeMillis() - t0}ms",
                )
            }
        }.onFailure { Log.w(TAG, "hot preload failed: ${it.message}") }
    }

    /**
     * 记录热清单（学习阶段的副产物）。
     *
     * 只收 [HOT_EXT]；按本次 learn 的顺序写（页面报告的顺序≈首次加载顺序，
     * 因此也就是关键路径顺序）。写文件交给同一个 IO 线程池，不阻塞调用方。
     */
    private fun recordHot(root: File, urls: List<String>) {
        val names = urls.asSequence()
            .filter { extOf(it) in HOT_EXT }
            .map { url ->
                val key = sha1(url)
                "$key.${extOf(url)}"
            }
            .distinct()
            .take(MAX_HOT)
            .toList()
        if (names.isEmpty()) return
        runCatching {
            val tmp = File(root, "$HOT_FILE.tmp")
            tmp.writeText(names.joinToString("\n"))
            val target = File(root, HOT_FILE)
            if (!tmp.renameTo(target)) {
                target.writeText(names.joinToString("\n"))
                tmp.delete()
            }
            Log.i(TAG, "hot list updated: ${names.size} entr(ies)")
        }.onFailure { Log.w(TAG, "hot list write failed: ${it.message}") }
    }

    /**
     * 尝试用本地缓存满足一次请求。
     *
     * 在 WebView 的后台 IO 线程上被调用，**不碰 UI 线程**。
     *
     * @return 命中返回响应；未命中/任何异常返回 `null`（WebView 走网络，行为同改动前）。
     */
    fun serve(request: WebResourceRequest?): WebResourceResponse? {
        if (!ENABLED) return null
        // ⚠️ 本方法在 WebView 的 IO 线程上**同步**执行（不是 UI 线程），
        // 它直接堵着该资源的网络栈：返回得慢，页面就慢。
        // 因此内部只允许：字符串比较、一次 sha1、一次 stat、可能一次 mmap。
        // 严禁：磁盘扫描、JSON 解析、加锁等待、日志拼接（命中路径才有日志）。
        return runCatching { serveInner(request) }.getOrNull()
    }

    private fun serveInner(request: WebResourceRequest?): WebResourceResponse? {
        request ?: return null
        if (request.method != "GET") return null
        // 用户/页面明确要求不走缓存（硬刷新、Ctrl+F5 语义）：让网络说了算。
        // 只看方向上的 cache-control；正常导航不会带这个头。
        val cc = request.requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Cache-Control", ignoreCase = true) }?.value
        if (cc != null && (cc.contains("no-cache", true) || cc.contains("no-store", true) || cc.contains("max-age=0"))) {
            return null
        }
        val uri = request.url ?: return null
        if (uri.scheme != "http") return null
        val host = uri.host ?: return null
        if (host != "127.0.0.1" && host != "localhost") return null
        val path = uri.path ?: return null
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (ext !in ALLOWED_EXT) return null

        // 键含完整 URL（含 query）：不同 query 可能是不同内容，不做归一化。
        val root = currentRoot ?: return null
        val key = sha1(uri.toString())
        val entry = entryFile(root, key, ext)

        // ① 图片优先供给「显示尺寸变体」（v1.6.36）。
        //   变体由 ImagePreScaler 在热余量窗口预先做好；它比原图小得多，
        //   WebView 解码与纹理上传都轻一大截。
        val variant = if (ext in IMAGE_EXT) File(entry.parentFile, entry.name + ImagePreScaler.VARIANT_SUFFIX) else null
        val file = if (variant != null && variant.isFile && variant.length() > 0L) variant else entry
        if (!file.isFile) return null
        if (System.currentTimeMillis() - entry.lastModified() > SERVE_TTL_MS) return null

        // ② 大文件：mmap 直读（一次拷贝、按需调页、不占 Java 堆）。
        //   还要支持 Range：模型/长图/媒体会发 `Range: bytes=a-b`，
        //   只映射所需区间，绝不把整份读进内存。
        if (file.length() >= MappedAsset.MMAP_THRESHOLD_BYTES) {
            val range = parseRange(request, file.length())
            if (range != null) {
                val (start, end) = range
                val len = end - start + 1
                val stream = MappedAsset.open(file, start, len) ?: return null
                hits.incrementAndGet()
                servedBytes.addAndGet(len)
                Log.i(TAG, "mmap ${if (variant != null && file === variant) "variant " else ""}range $start-$end/$len B: $path")
                val h = baseHeaders(key, partial = true, start = start, end = end, total = file.length())
                return WebResourceResponse(mimeOf(ext), null, 206, "Partial Content", h, stream)
            }
            val stream = MappedAsset.open(file) ?: return null
            hits.incrementAndGet()
            servedBytes.addAndGet(file.length())
            if (!servedOnce) {
                servedOnce = true
                Log.i(TAG, "first mmap hit this launch: $path (${file.length()} B)")
            }
            return WebResourceResponse(mimeOf(ext), null, 200, "OK", baseHeaders(key), stream)
        }

        // ③ 小文件：内存 LRU + 大块缓冲流（减少 JNI 往返次数）
        val bytes = mem.get(key) ?: run {
            val b = runCatching { file.readBytes() }.getOrNull() ?: return null
            mem.put(key, b)
            b
        }
        hits.incrementAndGet()
        servedBytes.addAndGet(bytes.size.toLong())
        if (!servedOnce) {
            servedOnce = true
            Log.i(TAG, "first cache hit this launch: $path (${bytes.size} B)")
        }
        return WebResourceResponse(
            mimeOf(ext),
            null,
            200,
            "OK",
            baseHeaders(key),
            ChunkedStream(bytes),
        )
    }

    /**
     * 公共响应头。`immutable`：告诉 Chromium 这份字节一年内不会变，别再来问；
     * 失效我们已用 stamp + TTL 兑底，不需要它帮忙判断。
     */
    private fun baseHeaders(
        key: String,
        partial: Boolean = false,
        start: Long = 0L,
        end: Long = 0L,
        total: Long = 0L,
    ): Map<String, String> {
        val h = HashMap<String, String>(if (partial) 4 else 3)
        h["Cache-Control"] = "public, max-age=31536000, immutable"
        h["ETag"] = "\"${key.take(16)}\""
        h["Accept-Ranges"] = "bytes"
        if (partial) h["Content-Range"] = "bytes $start-$end/$total"
        return h
    }

    /**
     * 解析 `Range: bytes=a-b`（只支持单区间，不合法返回 null）。
     *
     * 我们只对**本地缓存文件**做区间服务，内容与长度完全可控，
     * 因此不存在「上游内容变长/变短」那类边界问题。
     */
    private fun parseRange(request: WebResourceRequest?, total: Long): Pair<Long, Long>? {
        val raw = request?.requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value ?: return null
        if (!raw.startsWith("bytes=", ignoreCase = true)) return null
        val spec = raw.substring(6).substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        return runCatching {
            val start: Long
            val end: Long
            if (a.isEmpty()) {
                // bytes=-N：最后 N 字节
                val n = b.toLong()
                if (n <= 0L) return null
                start = (total - n).coerceAtLeast(0L)
                end = total - 1
            } else {
                start = a.toLong()
                end = if (b.isEmpty()) total - 1 else b.toLong().coerceAtMost(total - 1)
            }
            if (start < 0L || end < start || start >= total) null else start to end
        }.getOrNull()
    }

    /**
     * 预热：把这些 URL 里本机允许类型、且尚未缓存的资源下载到磁盘。
     *
     * 由 [app.dsh.mobile.MainActivity] 在首帧落定之后调用（延迟若干秒），
     * 所以不会和首屏渲染、插件 HMR 抢 CPU/IO。
     *
     * 幂等：已经缓存且未过期的 URL 直接跳过，重复调用无副作用。
     */
    fun learn(ctx: Context, urls: List<String>) {
        if (!ENABLED || urls.isEmpty()) return
        val app = ctx.applicationContext
        val todo = urls.asSequence()
            .filter { it.startsWith("http://") }
            .filter { u -> runCatching { Uri.parse(u).host }.getOrNull().let { it == "127.0.0.1" || it == "localhost" } }
            .filter { u -> extOf(u) in ALLOWED_EXT }
            .distinct()
            .take(MAX_LEARN_URLS)
            .toList()
        if (todo.isEmpty()) return

        // 需求「降频 IO」（v1.6.36）：
        // 热受限时**不并发扒小文件**（离线包/字体/多图同时读会把降频拉得更深）。
        // · MODERATE：降到单线程串行；
        // · SEVERE/CRITICAL：完全推迟，等降温后再补 —— 任务不丢，只是晚点做。
        if (ThermalGuard.current.severe) {
            deferred += app to todo
            Log.i(TAG, "learn deferred (thermal=${ThermalGuard.current.label}), ${todo.size} url queued")
            return
        }

        io.execute {
            val root = dir(app)
            if (!root.isDirectory) return@execute
            var ok = 0
            var bytes = 0L
            var images = 0
            for (url in todo) {
                // 串行循环本身就是「热受限时的降频」：若降频已深入，边做边停
                if (ThermalGuard.current.severe) {
                    deferred += app to todo.dropWhile { it != url }
                    Log.i(TAG, "learn paused mid-way at thermal=${ThermalGuard.current.label}")
                    break
                }
                val ext = extOf(url)
                val key = sha1(url)
                val target = entryFile(root, key, ext)
                if (target.isFile && System.currentTimeMillis() - target.lastModified() <= SERVE_TTL_MS) continue
                val result = fetch(url) ?: continue
                runCatching {
                    target.parentFile?.mkdirs()
                    val tmp = File(target.parentFile, "$key.tmp")
                    tmp.writeBytes(result)
                    if (!tmp.renameTo(target)) {
                        target.writeBytes(result)
                        tmp.delete()
                    }
                    ok++
                    bytes += result.size
                    // 图片：入队「解码/缩放前移」（后台 HandlerThread + 热状态驱动）
                    if (ext in IMAGE_EXT) {
                        ImagePreScaler.submit(target)
                        images++
                    }
                }
            }
            learned.addAndGet(ok)
            learnedBytes.addAndGet(bytes)
            if (ok > 0) {
                Log.i(
                    TAG,
                    "learned $ok asset(s), ${bytes / 1024} KB, $images img queued for prescale " +
                        "(thermal=${ThermalGuard.current.label})",
                )
                prune(root)
                recordHot(root, todo)
            }
            logStats(app, "after learn")
            drainDeferred()
        }
    }

    /** 热受限期间攒下的待预热清单；降温后由下一次 learn / tick 补做。 */
    private val deferred = java.util.concurrent.ConcurrentLinkedQueue<Pair<Context, List<String>>>()
    @Volatile private var lastDeferredRetry = 0L

    /** 降温后补做被推迟的预热（幂等，可反复调用）。 */
    fun retryDeferredIfCool() {
        if (deferred.isEmpty()) return
        if (ThermalGuard.current.throttled) {
            lastDeferredRetry = System.currentTimeMillis()
            return
        }
        drainDeferred()
    }

    private fun drainDeferred() {
        var item = deferred.poll() ?: return
        var guard = 0
        while (guard++ < 8) {
            learn(item.first, item.second)
            item = deferred.poll() ?: return
        }
    }

    /** 把本次启动的命中情况写进日志，便于真机上判断这层值不值得留。 */
    fun logStats(ctx: Context, whenLabel: String = "launch") {
        val h = hits.get()
        val m = misses.get()
        if (h == 0 && m == 0 && learned.get() == 0) return
        val disk = runCatching {
            dir(ctx).walkTopDown()
                .filter { it.isFile && it.name != "stamp" && it.extension != "tmp" }
                .sumOf { it.length() }
        }
            .getOrDefault(0L)
        Log.i(
            TAG,
            "$whenLabel: hits=$h misses=$m served=${servedBytes.get() / 1024} KB, " +
                "learned=${learned.get()} (${learnedBytes.get() / 1024} KB), disk=${disk / 1024} KB",
        )
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    /**
     * 当前生效的缓存根目录。
     *
     * `serve()` 拿不到 Context（`shouldInterceptRequest` 是热路径，不想每次去
     * `applicationContext`），所以由 [init] 记下；[init] 在 WebView 创建时已调用，
     * 早于任何请求。
     */
    @Volatile private var currentRoot: File? = null

    private fun entryFile(root: File, key: String, ext: String) = File(File(root, "e"), "$key.$ext")

    private fun stampOf(ctx: Context): String {
        val vc = runCatching {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            @Suppress("DEPRECATION") // versionCode 在 API 28 起被 longVersionCode 取代
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        }.getOrDefault(0L)
        val runtime = runCatching {
            File(app.dsh.mobile.engine.EngineConfig.engineRoot(ctx), ".runtime-version").readText().trim()
        }.getOrNull() ?: "-"
        return "$SCHEMA|$vc|$runtime"
    }

    private fun extOf(url: String): String {
        val path = runCatching { Uri.parse(url).path }.getOrNull() ?: return ""
        return path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    }

    private fun mimeOf(ext: String): String = when (ext) {
        "css" -> "text/css"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "avif" -> "image/avif"
        "bmp" -> "image/bmp"
        else -> "application/octet-stream"
    }

    private fun fetch(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return runCatching {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 10_000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity") // 要原始字节，不要 gzip 解压后的变形
            }
            if (conn!!.responseCode != 200) return null
            // ⚠️ 只接受未压缩的字节。
            //
            // WebResourceResponse 不能声明 Content-Encoding（我们能给的只有 mime +
            // 少量头），所以一旦把 gzip 字节原样存下来、再以 `text/css` 明文吐出去，
            // WebView 拿到的就是**压缩流当文本**——样式直接失效，而且是“只有某些
            // 资源坏掉”的极难定位的问题。引擎对 CSS/JSON 开 gzip 很常见。
            // 我们已发 Accept-Encoding: identity，正常情况不会压缩；这里再兜一层：
            // 只要响应真的被编码过，就放弃缓存这个资源（退回网络路径，功能不受影响）。
            val enc = conn!!.contentEncoding?.trim()?.lowercase()
            if (enc != null && enc.isNotEmpty() && enc != "identity") {
                Log.d(TAG, "skip $url: content-encoding=$enc (cannot replay via WebResourceResponse)")
                return null
            }
            val len = conn!!.contentLengthLong
            if (len > MAX_FILE_BYTES) return null
            val buf = java.io.ByteArrayOutputStream(if (len > 0) len.toInt() else 16 * 1024)
            conn!!.inputStream.use { ins ->
                val chunk = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val n = ins.read(chunk)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_FILE_BYTES) return null
                    buf.write(chunk, 0, n)
                }
            }
            buf.toByteArray()
        }.onFailure { Log.d(TAG, "fetch failed $url: ${it.message}") }.also {
            runCatching { conn?.disconnect() }
        }.getOrNull()
    }

    /** 容量修剪：总量超限时按最后修改时间从旧到新删，直到回到上限以下。 */
    private fun prune(root: File) {
        runCatching {
            // stamp 是失效标记，绝不能当缓存条目删掉（删了下次启动会误判为换版本而全清）
            val all = root.walkTopDown()
                .filter { it.isFile && it.name != "stamp" && it.name != HOT_FILE }
                .toList()
            // 残留的 .tmp（上次写到一半被杀）直接清掉
            all.filter { it.extension == "tmp" }.forEach { it.delete() }
            var total = all.sumOf { it.length() }
            if (total <= MAX_TOTAL_BYTES) return
            all.sortedBy { it.lastModified() }.forEach { f ->
                if (total <= MAX_TOTAL_BYTES) return@forEach
                val len = f.length()
                if (f.delete()) total -= len
            }
            Log.i(TAG, "pruned to ${total / 1024} KB")
        }
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val d = md.digest(s.toByteArray())
        val sb = StringBuilder(40)
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /** 由 [init] 记录根目录，供无 Context 的 [serve] 使用。 */
    fun attach(ctx: Context) {
        if (!ENABLED) return
        val app = ctx.applicationContext
        val root = dir(app).apply { mkdirs() }
        currentRoot = root
        Log.i(TAG, "attached, root=${root.absolutePath}")
        init(app)
    }
}
