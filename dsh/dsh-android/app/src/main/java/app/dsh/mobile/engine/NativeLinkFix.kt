package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 原生工具的动态链接自愈（v1.6.39）。
 *
 * ## 要解决的问题（真机故障原文）
 *
 * ```
 * CANNOT LINK EXECUTABLE "rg": library "libpcre2-8.so" not found
 * ```
 *
 * 搜索走的是 `@vscode/ripgrep` 导出的 `rgPath`，**直接 spawn 二进制**（不经 PATH）。
 * 那个 `rg` 是 Termux 官方构建的动态链接 ELF，实测属性：
 *
 * ```
 * DT_RUNPATH : /data/data/com.termux/files/usr/lib   ← 目标机不存在，等于没有
 * DT_NEEDED  : libpcre2-8.so, libdl.so, libc.so
 * ```
 *
 * `DT_RUNPATH` 不含 `$ORIGIN`，也不是本工程的路径，所以 `libpcre2-8.so`
 * **只能**靠 `LD_LIBRARY_PATH` 或 linker 默认搜索目录找到。而 `dsh-tool-fs-search`
 * 在 spawn 子进程时并不保证把 App 侧的 `LD_LIBRARY_PATH` 透传下去（工具层自定义
 * env 时尤其如此），于是 Node 进程环境里有没有这个变量，直接决定搜索能不能用 ——
 * 这就是「bash 里 `rg` 正常、工具里报 CANNOT LINK」的错位来源。
 *
 * ## 三层防护（与文档里的三种方案一一对应）
 *
 * ① **环境注入**（`EngineConfig.buildEnv` → `LD_LIBRARY_PATH`；`EngineProcess`
 *    在 su/容器包装里补 `export`，因为 `su` 会清空该变量）。
 *    —— 惠及所有原生工具（rg / git / python …），是首选方案。
 * ② **jniLibs 落盘**（`app/src/main/jniLibs/<abi>/libpcre2-8.so`，
 *    配合 `useLegacyPackaging = true` 让它出现在 `nativeLibraryDir`）。
 *    —— 即便运行时 zip 漏打包了库，也有兜底副本，见 [healLibraries]。
 * ③ **包装脚本**（[healWrappers]）。
 *    —— 工具层若自行清理/覆盖了子进程 env，前两层都失效，此时由脚本自己
 *    `export LD_LIBRARY_PATH` 后 `exec` 真二进制。这是唯一不依赖调用方行为的兜底。
 *
 * 三层互不依赖，任一层成立即可让 `rg` 起来；全部幂等，每次引擎启动跑一遍无害。
 *
 * ## 为什么包装脚本是「同名替换」而不是「PATH 前置」
 *
 * 工具层拿的是 `require.resolve('@vscode/ripgrep-android-arm64/bin/rg')` 的**绝对路径**，
 * 再交给 `child_process.spawn`（`shell: false`）。PATH 前置对它完全无效 ——
 * 必须让那个绝对路径上的文件自己变成「会设环境再转发」的入口。
 * 脚本带 `#!/system/bin/sh`，`execve` 能直接执行（bionic 支持 shebang）。
 */
object NativeLinkFix {

    private const val TAG = "NativeLinkFix"

    /** 包装脚本首行标记（模板在 [RgWrapperScript]，这里只做引用）。 */
    private val WRAP_MARK get() = RgWrapperScript.MARK

    /** 真二进制的后缀（与原文件同目录，保证 `dirname` 语义与权限一致）。 */
    private val REAL_SUFFIX get() = RgWrapperScript.REAL_SUFFIX

    /**
     * `rg` 直接点名依赖的库。
     *
     * 只列 `DT_NEEDED` 里**非系统**的那一个：`libdl.so` / `libc.so` 由 bionic 提供，
     * 不需要我们操心。将来若工具链再引入其它非系统库，加到这里即可，
     * [healLibraries] 会自动从 `nativeLibraryDir` 补齐。
     */
    private val REQUIRED_LIBS = listOf("libpcre2-8.so")

    /**
     * 一键自愈。幂等、异常不外抛（启动流程不该被它拖垮）。
     *
     * 顺序有意如此：
     *   ① 补库（包名别名里的 rg 也要靠它启动，必须先就位）
     *   ② 包名对齐（伪装成 linux 后 @vscode/ripgrep 去找 ripgrep-linux-<arch>）
     *   ③ 包包装脚本（前一步可能产生「复制」型的别名，它自己那份 bin/rg 也得包）
     *
     * @return 供日志/探针读取的摘要，例如 `"lib:copied alias:new(1) kept(0) fixed(0)"`
     */
    fun heal(ctx: Context): String {
        val root = runCatching { EngineConfig.engineRoot(ctx) }.getOrNull()
            ?: return "skip:no-engine-root"
        if (!root.isDirectory) return "skip:no-engine"

        val extRoots = runCatching { ExtensionManager.activeRoots(ctx) }
            .getOrDefault(emptyList<File>())
        val libDirs = EngineConfig.nativeLibDirs(ctx, root, extRoots)
        val scopes = RgPlatformAlias.scopesFor(
            listOf(File(root, "lib/node_modules"), File(root, "node_modules")) +
                extRoots.map { File(it, "node_modules") },
        )

        val libNotes = healLibraries(ctx, root, libDirs)
        val aliasNotes = healPlatformAliases(ctx, scopes)
        val wrapNotes = healWrappers(root, scopes, libDirs)

        val summary = (libNotes + aliasNotes + wrapNotes).joinToString(" ").ifBlank { "ok" }
        Log.i(TAG, "native link heal: $summary")
        return summary
    }

    // ─────────────────── 包名对齐（v1.6.40，Linux 伪装模式） ───────────────────

    /**
     * 补齐 `@vscode/ripgrep-linux-<arch>` → 实际安装的 `@vscode/ripgrep-android-<arch>`。
     *
     * 触发条件：**只有在 Linux 伪装开启时**才需要。原生模式下 `process.platform`
     * 是 `android`，`@vscode/ripgrep` 会正常去找 `ripgrep-android-<arch>`。
     * 不在未开启伪装时建这个别名，是为了不给 pnpm 制造“包库里多了个版本”的困惑。
     *
     * 注意：已经存在的别名无论开关如何都会被**体检**（悬空/不完整则修复），
     * 否则用户关掉伪装又打开时会拿一个坏掉的别名去用。
     */
    private fun healPlatformAliases(ctx: Context, scopes: List<File>): List<String> {
        val enabled = runCatching { FakeLinuxEnv.enabled(ctx) }.getOrDefault(false)
        // 开关关闭且一个别名都不存在 → 完全不必扫（启动路径保持安静）
        val anyAlias = scopes.any { scope ->
            scope.listFiles().orEmpty().any {
                RgPlatformAlias.isAliasName(it.name) && (it.exists() || RgPlatformAlias.isSymlink(it))
            }
        }
        if (!enabled && !anyAlias) return emptyList()

        val report = runCatching { RgPlatformAlias.ensure(scopes) }.getOrNull()
            ?: return listOf("alias:error")
        if (!enabled && report.touched == 0) return emptyList()
        if (report.touched > 0) {
            Log.i(TAG, "rg platform alias: ${report.summary()} ${report.notes.joinToString(",")}")
        }
        return listOf(report.summary())
    }

    // ─────────────────────────── ② jniLibs 兜底副本 ───────────────────────────

    /**
     * 确保 [REQUIRED_LIBS] 在**引擎自己的 lib 目录**里有一份。
     *
     * 为什么需要这一步：`nativeLibraryDir` 虽然在 `LD_LIBRARY_PATH` 里，但
     * `engine/bin/rg` 的 `DT_RUNPATH` 与 `engine/lib` 是历史约定（运行时 zip
     * 自带同一份库）。两者保持同步有两个好处：
     *   · 运行时被 `pnpm` / 扩展安装等操作弄脏（库被删）时能自愈；
     *   · 打包回归（`collect-termux-runtime.sh` 的闭包校验漏项）时不会静默坏掉。
     *
     * 只在「目标缺失」时拷贝，绝不覆盖已有文件（运行时自带的版本优先）。
     */
    private fun healLibraries(ctx: Context, root: File, libDirs: List<File>): List<String> {
        val nativeDir = runCatching { File(ctx.applicationInfo.nativeLibraryDir) }.getOrNull()
        val notes = ArrayList<String>(REQUIRED_LIBS.size)
        REQUIRED_LIBS.forEach { name ->
            val already = libDirs.any { File(it, name).isFile }
            if (already) return@forEach
            val src = nativeDir?.let { File(it, name) }
            if (src == null || !src.isFile) {
                // 两条来源都没有：留给包装脚本去报错，但必须留痕（这是真正的打包缺陷）
                Log.w(TAG, "library $name missing everywhere (engine + nativeLibraryDir)")
                notes += "lib:$name=missing"
                return@forEach
            }
            val dst = File(root, "lib/$name")
            runCatching {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                dst.setReadable(true, false)
                Log.i(TAG, "library $name restored from nativeLibraryDir (${src.length()} bytes)")
                notes += "lib:$name=copied"
            }.onFailure {
                Log.w(TAG, "library $name copy failed: ${it.message}")
                notes += "lib:$name=failed"
            }
        }
        return notes
    }

    // ─────────────────────────── ③ 同名包装脚本 ───────────────────────────

    /**
     * 给每个「工具层会直接 spawn 的 rg」套上包装脚本。
     *
     * 处理两类入口：
     *   · `<engine>/bin/rg`          —— PATH 里的 rg（bash 工具用）
     *   · `@vscode/ripgrep-<platform>/bin/rg` —— `rgPath` 实际指向的包内副本（搜索工具用）
     *
     * 只动「ELF 本体」；已经是我们的包装（含 [WRAP_MARK]）就只校验路径是否过期。
     */
    private fun healWrappers(root: File, scopes: List<File>, libDirs: List<File>): List<String> {
        val targets = rgCandidates(root, scopes)
        if (targets.isEmpty()) return listOf("rg:none")
        val ld = libDirs.map { it.absolutePath }.filter { it.isNotBlank() }.joinToString(":")
        var wrapped = 0
        var kept = 0
        targets.forEach { file ->
            runCatching {
                when (wrapOne(file, ld)) {
                    WrapResult.WRAPPED -> wrapped++
                    WrapResult.KEPT -> kept++
                    WrapResult.SKIP -> Unit
                }
            }.onFailure { Log.w(TAG, "wrap ${file.name} failed: ${it.message}") }
        }
        return listOf("rg:wrapped($wrapped) kept($kept)")
    }

    private enum class WrapResult { WRAPPED, KEPT, SKIP }

    /**
     * 找出所有需要套包装的 `bin/rg`。
     *
     * **按真实路径去重**：包名别名如果是软链，它的 `bin/rg` 与真实包是同一个文件，
     * 不去重就会在两种路径写法之间来回改写包装脚本（每次启动都写一次）。
     * 去重时优先保留「不是软链」的那个写法，保证写入内容长期稳定。
     */
    private fun rgCandidates(root: File, scopes: List<File>): List<File> {
        val all = ArrayList<File>(4)
        File(root, "bin/rg").takeIf { it.isFile }?.let { all += it }
        scopes.forEach { scope ->
            scope.listFiles().orEmpty().forEach { pkg ->
                if (!pkg.isDirectory || !pkg.name.startsWith("ripgrep")) return@forEach
                File(pkg, "bin/rg").takeIf { it.isFile }?.let { all += it }
            }
        }
        // 同真实路径只留一个代表：非软链优先（反复写入会让包装脚本路径挑来挑去）
        val best = LinkedHashMap<String, File>()
        all.forEach { f ->
            val key = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
            val prev = best[key]
            if (prev == null) {
                best[key] = f
            } else if (RgPlatformAlias.isSymlink(prev.parentFile) &&
                !RgPlatformAlias.isSymlink(f.parentFile)
            ) {
                best[key] = f
            }
        }
        return best.values.toList()
    }

    private fun wrapOne(file: File, ld: String): WrapResult {
        val text = runCatching { file.readText() }.getOrNull()
        val isOurs = text != null && text.contains(WRAP_MARK)
        if (isOurs) {
            // 库路径变了（例如换了运行时/扩展增删）就重写一份，否则什么都不做。
            val expected = wrapperScript(file.absolutePath + REAL_SUFFIX, ld)
            return if (text == expected) WrapResult.KEPT else {
                writeWrapper(file, expected); WrapResult.WRAPPED
            }
        }
        if (!isElf(file)) return WrapResult.SKIP

        // ① 真二进制改名（先清掉上一轮的 .real —— 运行时重装会覆盖 wrapper，
        //    留下陈旧副本，直接覆盖比删了再写少一个失败窗口）
        val real = File(file.absolutePath + REAL_SUFFIX)
        if (real.exists()) real.delete()
        if (!file.renameTo(real)) {
            // rename 失败（跨文件系统/只读）就退回拷贝：语义一致，只是多一次 I/O
            file.copyTo(real, overwrite = true)
            file.delete()
        }
        real.setExecutable(true, false)
        real.setReadable(true, false)
        // ② 原位置写包装
        writeWrapper(file, wrapperScript(real.absolutePath, ld))
        Log.i(TAG, "wrapped ${file.absolutePath} -> ${real.name} (LD_LIBRARY_PATH=$ld)")
        return WrapResult.WRAPPED
    }

    private fun writeWrapper(file: File, script: String) {
        file.delete()
        file.writeText(script)
        file.setExecutable(true, false)
        file.setReadable(true, false)
    }

    /** 包装脚本正文（模板与转义都在 [RgWrapperScript]，便于离线端到端验证）。 */
    private fun wrapperScript(realPath: String, ld: String): String =
        RgWrapperScript.build(realPath, ld)

    /** 前 4 字节是 ELF 魔数（避免误伤已经是脚本/包装的入口）。 */
    private fun isElf(f: File): Boolean = runCatching {
        f.inputStream().use { ins ->
            val head = ByteArray(4)
            if (ins.read(head) < 4) return@use false
            head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() &&
                head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)
}
