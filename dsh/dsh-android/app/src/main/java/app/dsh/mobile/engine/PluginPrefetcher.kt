package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * 本地已装插件的**体量枚举**与**并行预读**（v1.6.24）。
 *
 * ## 先测到的事实（它决定了这个文件写什么）
 * 在本地文件系统上复刻本文件同一套算法跑代理测量：**7 个插件 / 28 文件 / 532 KB，
 * 首读 2.6ms，page cache 热了之后 0.9ms**。
 *
 * 所以：**磁盘 I/O 不是「插件加载慢」的瓶颈**。把它预热了能省的是毫秒级，
 * 不是用户感知到的那几秒。那几秒花在引擎重解析插件图、`dsh-hmr` 把客户端
 * bundle 再传一遍、浏览器求值——全在引擎和前端里，原生侧改不到。
 *
 * 因此本对象的首要职责不是「把重流式做快」，而是
 * **「判断这场重流式到底值不值得」**：用 [decide] 量出本地插件体量，
 * 体量小就不搞快速启动那一套（见 `PluginFastBoot.hold()`），插件随引擎
 * 一次到位，**重流式阶段直接不存在** —— 这是唯一能把那几秒真正去掉的办法。
 *
 * [start] 的预读只留给「体量大、确实需要重流式」的分支。它不贵（成本即上
 * 述测量），而手机上 eMMC/UFS 冷读比 SSD 慢几十倍，腾出的空间仍然有用。
 *
 * ## 只读、只预热
 * · **绝不改写任何文件**：profile 的 `package.json` 在引擎运行期被写入会被引擎
 *   退出时的内存写回吞掉（`EngineSupervisor` 有同款注释）。这里只 `read()`，
 *   语义等价于「用户此时点开插件目录看了一眼」。
 * · **绝不解析、绝不求值**：那是引擎的活。只碰字节不碰语义，所以不可能出现
 *   「预读成功但引擎解析失败」的不一致状态。
 * · 失败一律静默降级：读不到就是没预热，HMR 照旧冷读，功能完全不受影响。
 *
 * ## 并发度为什么是 2
 * 本机与目标机都是 **2 核 / 4GB**。预热只是把磁盘内容搬进 page cache，
 * 瓶颈是 IO 不是 CPU；并发 2 已够把 IO 队列喂满，再高只会在 2 核上与正在
 * 启动的 Node 引擎抢 CPU，反而拖慢引擎启动与首帧。
 */
object PluginPrefetcher {
    private const val TAG = "PluginPrefetch"

    /** hold 期间 `package.json` 里的第三方 bundle 已被摘掉，完整清单在这个备份里。 */
    private const val BACKUP = ".fastboot-backup.json"

    /**
     * 体量阈值：**字节数**低于此就不摘插件、不搞重流式。
     *
     * 为什么只看字节、不看个数：重流式贵在「重解析插件图 + 重传客户端 bundle +
     * 重求值」，这些基本跟 payload 体量走，跟个数关系很小；而引擎启动时多带
     * 几个小插件，每插件只是解析 `package.json` + 挂 ctx 的固定小开销。
     * 所以 4 个共 400KB 的小插件不该为此付一次重流式（早期版本按「个数≤3」判，
     * 会把这种集合误送进快启分支）。
     *
     * [FORCE_HOLD_PLUGINS] 是安全阀：个数异常多时就算字节没超也照旧快启，
     * 以免体量枚举本身在启动路径上跑太久。
     *
     * ⚠️ 这个数是**离线代理测量 + 结构推理定的，没有真机计时支撑**。
     * 真机上调参看这行日志：`PluginFastBoot: fast boot decision ...`。
     */
    const val SMALL_MAX_BYTES = 2L * 1024 * 1024

    /** 个数安全阀：超过这么多个插件，无论如何都走快启（限制枚举开销）。 */
    const val FORCE_HOLD_PLUGINS = 40

    /** 预读并发度：2 核机器上正好，再高会与引擎启动抢 CPU。 */
    private const val PARALLEL = 2

    /** 单次 read() 的缓冲区：64KB 是常见的 flash 预读粒度。 */
    private const val CHUNK = 64 * 1024

    /** 单个插件最多枚举/预读的文件数（防插件自带巨型依赖树吃光预算与 CPU）。 */
    private const val MAX_FILES_PER_PLUGIN = 200

    /** 单个插件最多预读的字节数。 */
    private const val MAX_BYTES_PER_PLUGIN = 4L * 1024 * 1024

    /** 一次启动最多预读的总字节数。 */
    private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024

    /** 引擎求值时会碰的文件类型；二进制不预热（读图片对求值毫无帮助）。 */
    private val WANTED_EXT = setOf("js", "mjs", "cjs", "json", "css")

    /**
     * 一次体量判定的结论。
     *
     * @param small 是否「小到不值得为它做快速启动」
     * @param plugins 第三方插件个数（`null` 表示清单读不到）
     * @param bytes 已枚举到的字节数（小体量时是真实值；[earlyExit] 时为下界）
     * @param earlyExit 是否因已判定「大」而提前停止枚举
     */
    data class Decision(
        val small: Boolean,
        val plugins: Int?,
        val bytes: Long,
        val earlyExit: Boolean,
    )

    /**
     * 判定这批本地插件是否小到**不该**做快速启动。
     *
     * 提前退出：累加字节一越过 [SMALL_MAX_BYTES] 就立刻返回「大」。所以最坏
     * 情况只 stat 到刚好超阈值的那个插件为止，挂在启动路径上的开销有界。
     *
     * 读不到清单（没有 profile / JSON 坏了）→ `small = false`，即**维持原有快启
     * 行为**：不确定的时候不改既有路径。
     */
    fun decide(ctx: Context): Decision {
        val names = installedBundles(ctx) ?: return Decision(false, null, 0, false)
        if (names.isEmpty()) return Decision(true, 0, 0, false)
        if (names.size > FORCE_HOLD_PLUGINS) {
            return Decision(false, names.size, 0, earlyExit = true)
        }
        var bytes = 0L
        for (pkg in names) {
            for (dir in resolveDirs(ctx, pkg)) {
                for (f in readableFiles(dir)) {
                    // 只 stat 不读取：这里要的是体量，不是内容。
                    bytes += runCatching { f.length() }.getOrDefault(0L)
                    if (bytes > SMALL_MAX_BYTES) {
                        return Decision(false, names.size, bytes, earlyExit = true)
                    }
                }
            }
        }
        return Decision(bytes <= SMALL_MAX_BYTES, names.size, bytes, earlyExit = false)
    }

    /**
     * 在给定 scope 里启动预读，立即返回（不阻塞调用方）。
     *
     * @param onProgress 已完成的插件数 / 总插件数，用于向 UI 实时反馈。
     * @return 预读的 Job；调用方在 HMR 触发后取消它。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(
        ctx: Context,
        scope: CoroutineScope,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Job? = runCatching {
        scope.launch(Dispatchers.IO.limitedParallelism(PARALLEL)) {
            try {
                warm(ctx, onProgress)
            } catch (ce: CancellationException) {
                Log.i(TAG, "prefetch cancelled (HMR already started or engine stopping)")
                throw ce
            } catch (t: Throwable) {
                // 预热失败绝不影响启动：HMR 照旧冷读，只是没快而已。
                Log.w(TAG, "prefetch aborted: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }.onFailure { Log.w(TAG, "prefetch not started: ${it.message}") }.getOrNull()

    /** 实际预读。不碰主线程，不持锁，不写盘。 */
    private suspend fun warm(
        ctx: Context,
        onProgress: (Int, Int) -> Unit,
    ): Boolean {
        val names = installedBundles(ctx) ?: emptyList()
        if (names.isEmpty()) {
            Log.i(TAG, "no locally installed third-party plugin; nothing to warm")
            return false
        }
        val t0 = System.nanoTime()
        var files = 0
        var bytes = 0L
        var pluginsHit = 0
        val buf = ByteArray(CHUNK)
        onProgress(0, names.size)

        for ((index, pkg) in names.withIndex()) {
            var pluginBytes = 0L
            var pluginFiles = 0
            var ok = false
            outer@ for (dir in resolveDirs(ctx, pkg)) {
                for (file in readableFiles(dir)) {
                    if (pluginFiles >= MAX_FILES_PER_PLUGIN) break@outer
                    if (bytes + pluginBytes >= MAX_TOTAL_BYTES) break@outer
                    val n = readFully(file, buf)
                    if (n < 0) continue
                    ok = true
                    pluginFiles++
                    pluginBytes += n
                    if (pluginBytes >= MAX_BYTES_PER_PLUGIN) break@outer
                }
            }
            if (ok) {
                pluginsHit++
                files += pluginFiles
                bytes += pluginBytes
            }
            currentCoroutineContext().ensureActive()
            onProgress(index + 1, names.size)
        }

        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i(
            TAG,
            "warmed $pluginsHit/${names.size} plugin(s), $files file(s), " +
                "${bytes / 1024} KB in ${ms}ms (page cache primed for dsh-hmr)",
        )
        return pluginsHit > 0
    }

    /**
     * 本地已装的第三方插件名。
     *
     * 判据与 `PluginFastBoot.hold()` **逐字一致**（`dependencies` 里有、且不是
     * `@deepseek-ai/` 内置命名空间）。否则枚举的集合与实际被摘/被补的集合会不同，
     * 等于给一批引擎根本不会读的插件做无用功。
     *
     * 顺序上**先看 backup**：hold 期间 `package.json` 里第三方已被摘掉，
     * 只有 backup 是完整清单 —— 这正是「本地已安装」场景要分析的那份文件。
     *
     * @return `null` 表示清单完全读不到（区别于「读到了但没有第三方插件」的空列表）。
     */
    private fun installedBundles(ctx: Context): List<String>? {
        val dir = File(EngineConfig.dshHome(ctx), "profiles/web")
        var sawAnyManifest = false
        for (f in listOf(File(dir, BACKUP), File(dir, "package.json"))) {
            if (!f.isFile) continue
            val root = runCatching { JSONObject(f.readText()) }.getOrNull() ?: continue
            val bundles = root.optJSONObject("dsh")
                ?.optJSONObject("profile")
                ?.optJSONArray("bundles") ?: continue
            sawAnyManifest = true
            val deps = root.optJSONObject("dependencies") ?: JSONObject()
            val out = LinkedHashSet<String>()
            for (i in 0 until bundles.length()) {
                val name = bundles.optString(i)
                if (name.isEmpty()) continue
                if (deps.has(name) && !name.startsWith("@deepseek-ai/")) out.add(name)
            }
            if (out.isNotEmpty()) return out.toList()
        }
        return if (sawAnyManifest) emptyList() else null
    }

    /**
     * 一个包可能落在两处：profile 自己的 `node_modules`（社区插件中心安装）
     * 与 `$DSH_HOME/profiles/node_modules`（内置插件的软链层）。两处都算。
     */
    private fun resolveDirs(ctx: Context, pkg: String): List<File> {
        val home = EngineConfig.dshHome(ctx)
        return listOf(
            File(File(home, "profiles/web/node_modules"), pkg),
            File(File(home, "profiles/node_modules"), pkg),
        ).filter { it.isDirectory }
    }

    /**
     * 该插件目录下值得枚举/预热的文件，**入口优先**。
     *
     * 排序按「路径深度 → 路径长度 → 名字」：`package.json`、`lib/index.js`、
     * `client.js` 这类浅层入口排前面。于是预算被截断时，截掉的只会是深处那些
     * 引擎可能根本不会碰的文件，而不是入口本身。
     */
    private fun readableFiles(dir: File): List<File> {
        val found = ArrayList<File>(64)
        // 手工有界遍历：walkTopDown() 会先走完整棵树才轮到我们限制，
        // 插件自带巨型依赖时那一下就已经把预算和 CPU 花掉了。
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        var visited = 0
        while (stack.isNotEmpty() && found.size < MAX_FILES_PER_PLUGIN && visited < 4000) {
            val cur = stack.removeLast()
            val children = runCatching { cur.listFiles() }.getOrNull() ?: continue
            for (c in children) {
                visited++
                if (c.isDirectory) {
                    // 与求值无关的整棵子树直接跳过
                    val n = c.name
                    if (n == ".git" || n == ".bin" || n == "test" || n == "tests") continue
                    stack.addLast(c)
                } else if (c.extension.lowercase() in WANTED_EXT) {
                    found.add(c)
                }
            }
        }
        return found.sortedWith(
            compareBy<File> { it.absolutePath.count { ch -> ch == '/' } }
                .thenBy { it.absolutePath.length }
                .thenBy { it.name },
        )
    }

    /**
     * 把文件完整读进 [buf]（丢弃内容，只为让内核把它放进 page cache）。
     *
     * 用 `FileInputStream.read(buf)` 循环而非 `readBytes()`：后者按文件大小新分配
     * 一个数组，预读几十个文件就是几 MB 的无谓分配 —— 这段代码跑在引擎启动期，
     * 堆压力和 GC 停顿会直接影响首帧。
     *
     * @return 读到的字节数；失败返回 -1（权限/竞态删除/目录等）。
     */
    private fun readFully(file: File, buf: ByteArray): Long {
        if (!file.isFile || !file.canRead()) return -1
        return runCatching {
            var total = 0L
            FileInputStream(file).use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    total += n
                }
            }
            total
        }.getOrElse { -1L }
    }
}
