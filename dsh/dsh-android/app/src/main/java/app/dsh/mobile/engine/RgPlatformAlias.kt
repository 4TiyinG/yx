package app.dsh.mobile.engine

import java.io.File

/**
 * 伪装平台的**包名对齐**（v1.6.40）—— 纯逻辑，不依赖 Android，便于离线端到端验证。
 *
 * ## 故障链（真机原文 `ripgrep launch failed`）
 *
 * `@vscode/ripgrep` 1.18 的解析逻辑只有三行（`lib/index.js`）：
 *
 * ```js
 * const arch = process.env.npm_config_arch || process.arch;
 * const platformPkg = `@vscode/ripgrep-${process.platform}-${arch}`;
 * resolved = require.resolve(`${platformPkg}/bin/rg`);
 * ```
 *
 * Linux 伪装把 `process.platform` 改写成了 `'linux'`（见 [FakeLinuxEnv] 的 preload），
 * 于是它去找 `@vscode/ripgrep-linux-arm64`；而运行时实际装的是
 * `@vscode/ripgrep-android-arm64`（这个名字在 npm 上并不存在，是本工程自己按 ABI
 * 重打包的）。`require.resolve` 抛
 * `Could not find @vscode/ripgrep-linux-arm64.` → 工具层只看到 `ripgrep launch failed`。
 *
 * ## 为什么不能改工具/引擎代码
 *
 * ① 前端/引擎代码不可改；② `node_modules` 里的任何手改写，都会在 App 升级、
 * 运行时重装、或自检流程覆盖时被冲掉。所以修复必须落在 **App 侧的自愈流程**里：
 * 每次引擎启动都重新确认别名存在 —— 被冲掉就补回来。
 *
 * ## 为什么是「包名别名」而不是换二进制
 *
 * 底层依然是 bionic/Android，绝不能换成真正的 linux 二进制（glibc 链接，跑不起来）。
 * 别名目录里放的**仍然是 Android 原生那一份**（`bin/rg` 是我们的
 * `LD_LIBRARY_PATH` 包装脚本 → 指向同目录 `rg.real`，即 Android 原生 ripgrep）。
 * 也就是说：只补齐「名字解析」这一环，二进制执行路径完全不变。
 */
object RgPlatformAlias {

    /** 运行时实际安装的包名前缀（按 ABI 重打包，如 `ripgrep-android-arm64`）。 */
    const val REAL_PREFIX = "ripgrep-android-"

    /** 伪装成 Linux 后 `@vscode/ripgrep` 期望的包名前缀。 */
    const val FAKE_PREFIX = "ripgrep-linux-"

    /**
     * `ripgrep-android-arm64` → `ripgrep-linux-arm64`；不匹配返回 `null`。
     *
     * 只做「android→linux」这一种映射，别的一律不动（`darwin-*` / `win32-*`
     * 等在 Android 上本来就不该出现，误建只会制造混乱）。
     */
    fun fakeNameOf(realName: String): String? {
        if (!realName.startsWith(REAL_PREFIX)) return null
        val arch = realName.removePrefix(REAL_PREFIX)
        if (arch.isBlank()) return null
        return FAKE_PREFIX + arch
    }

    /** 反向：别名包是否就是某个真实包的伪装名。 */
    fun realNameOf(fakeName: String): String? {
        if (!fakeName.startsWith(FAKE_PREFIX)) return null
        val arch = fakeName.removePrefix(FAKE_PREFIX)
        if (arch.isBlank()) return null
        return REAL_PREFIX + arch
    }

    /** 这个目录名是否是「我们生成的别名」（用于自检/清理判断）。 */
    fun isAliasName(name: String): Boolean = realNameOf(name) != null

    /** 包是否「长得像」一个可被 `require.resolve('<pkg>/bin/rg')` 解析的包。 */
    fun looksComplete(dir: File): Boolean =
        File(dir, "package.json").isFile && File(dir, "bin/rg").isFile

    /**
     * 收集所有形如 `.../node_modules/@vscode` 的作用域目录。
     *
     * 两条布局都要算（理由见 [ensure]）：
     *   · hoisted：`<nm>/@vscode`
     *   · pnpm   ：`<nm>/.pnpm/@vscode+ripgrep@x.y.z/node_modules/@vscode`
     *
     * 刻意**不**递归全树：`node_modules` 动辄上万目录，而这是启动路径上的代码。
     *
     * @param nodeModulesDirs 形如 `.../lib/node_modules`、`.../node_modules`
     */
    fun scopesFor(nodeModulesDirs: List<File>): List<File> {
        val out = LinkedHashSet<File>()
        nodeModulesDirs.forEach { nm ->
            if (!nm.isDirectory) return@forEach
            File(nm, "@vscode").takeIf { it.isDirectory }?.let { out += it }
            File(nm, ".pnpm").takeIf { it.isDirectory }?.listFiles()?.forEach { entry ->
                if (!entry.isDirectory) return@forEach
                File(entry, "node_modules/@vscode").takeIf { it.isDirectory }?.let { out += it }
            }
        }
        return out.toList()
    }

    /**
     * 在 `@vscode` 作用域里补齐所有别名。
     *
     * 关键设计：**别名建在「真实包的同级目录」**，而不是写死某个 node_modules。
     * 原因：pnpm 布局下 `require.resolve` 是从**真实路径**（`.pnpm/.../node_modules/@vscode/ripgrep/`）
     * 往上找的，那里才是必须命中别名的位置；而 hoisted 布局下则是
     * `lib/node_modules/@vscode/`。跟着真实包走，两种布局都自动正确。
     *
     * @param scopes 形如 `.../node_modules/@vscode` 的目录（可含重复）
     * @return 统计与备注
     */
    fun ensure(scopes: List<File>): Report {
        var created = 0
        var kept = 0
        var rebuilt = 0
        var copies = 0
        val notes = ArrayList<String>()

        scopes.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
            .forEach { scope ->
                if (!scope.isDirectory) return@forEach
                val pkgs = scope.listFiles().orEmpty()
                    .filter { it.isDirectory && it.name.startsWith(REAL_PREFIX) }
                pkgs.forEach { androidDir ->
                    val fakeName = fakeNameOf(androidDir.name) ?: return@forEach
                    val target = File(androidDir.parentFile, fakeName)
                    // 厨子先把菜做好：真实包本身不完整时不去造别名（否则只是把坏掉复制一份）
                    if (!File(androidDir, "bin/rg").isFile) {
                        notes += "$fakeName=srcless"
                        return@forEach
                    }
                    when (val r = materialize(androidDir, target)) {
                        Materialize.CREATED -> { created++; notes += "$fakeName=linked" }
                        Materialize.COPIED -> { created++; copies++; notes += "$fakeName=copied" }
                        Materialize.KEPT -> kept++
                        Materialize.REBUILT -> { rebuilt++; notes += "$fakeName=rebuilt" }
                    }
                }
            }
        return Report(created, kept, rebuilt, copies, notes)
    }

    /** [ensure] 的结果。 */
    data class Report(
        val created: Int,
        val kept: Int,
        val rebuilt: Int,
        val copied: Int,
        val notes: List<String>,
    ) {
        val touched: Int get() = created + rebuilt
        fun summary(): String =
            "alias:new($created) kept($kept) fixed($rebuilt)" +
                if (copied > 0) " copy($copied)" else ""
    }

    enum class Materialize { CREATED, COPIED, KEPT, REBUILT }

    /**
     * 建/修一个别名。
     *
     * 判定顺序：
     * 1. 别名不存在 → 建软链；软链失败（少见的文件系统限制）→ 整包复制。
     * 2. 别名是软链 → 解到真实包就 OK；指错/悬空（被清过、升级换了路径）→ 重建。
     * 3. 别名是实体目录 → `package.json` + `bin/rg` 齐全就 OK；缺东西 → 删掉重建
     *    （半截目录比没有更糟：`require.resolve` 会命中一个坏包）。
     *
     * 幂等：重复调用的结果只会是 [Materialize.KEPT]。
     */
    fun materialize(androidDir: File, target: File): Materialize {
        val exists = target.exists() || isSymlink(target)
        if (!exists) {
            return if (trySymlink(androidDir, target)) Materialize.CREATED
            else { copyTree(androidDir, target); Materialize.COPIED }
        }
        if (isSymlink(target)) {
            val same = runCatching {
                target.canonicalPath == androidDir.canonicalPath
            }.getOrDefault(false)
            if (same) return Materialize.KEPT
            // 悬空或指错：先删再建（delete 对 symlink 只摘链接，不动目标）
            target.delete()
            return if (trySymlink(androidDir, target)) Materialize.REBUILT
            else { copyTree(androidDir, target); Materialize.REBUILT }
        }
        if (looksComplete(target)) return Materialize.KEPT
        target.deleteRecursively()
        return if (trySymlink(androidDir, target)) Materialize.REBUILT
        else { copyTree(androidDir, target); Materialize.REBUILT }
    }

    // ─────────────────────────── 实现细节 ───────────────────────────

    /**
     * 建软链。用 `android.system.Os.symlink`（API 21+，本工程 minSdk 26）。
     *
     * 用绝对路径而不是相对路径：`node_modules` 目录整体搬动（运行时迁移）时，
     * 相对链接的基准会变，绝对路径不会。
     */
    fun trySymlink(androidDir: File, target: File): Boolean = runCatching {
        target.parentFile?.mkdirs()
        if (target.exists() || isSymlink(target)) target.delete()
        android.system.Os.symlink(androidDir.absolutePath, target.absolutePath)
        // 立刻验证：某些文件系统会「创建成功但不可解析」，那种情况必须回退复制
        target.canonicalPath == androidDir.canonicalPath && File(target, "bin/rg").isFile
    }.getOrDefault(false)

    /** 整包复制（软链不可用时的退路）。复制后把 `package.json` 的 name 改成别名。 */
    fun copyTree(src: File, dst: File) {
        dst.deleteRecursively()
        src.copyRecursively(dst, overwrite = true)
        rewriteName(File(dst, "package.json"), dst.name)
        File(dst, "bin/rg").takeIf { it.isFile }?.setExecutable(true, false)
        File(dst, "bin/rg.real").takeIf { it.isFile }?.setExecutable(true, false)
    }

    /**
     * 只改 `package.json` 的 `name` 字段（不引 JSON 库：这个文件由 npm 生成，
     * 结构固定，正则替换足够且不会破坏格式）。
     */
    private fun rewriteName(pkgJson: File, name: String) {
        runCatching {
            if (!pkgJson.isFile) return
            val text = pkgJson.readText()
            val re = Regex("\"name\"\\s*:\\s*\"[^\"]*\"")
            if (!re.containsMatchIn(text)) return
            pkgJson.writeText(re.replaceFirst(text, "\"name\": \"$name\""))
        }
    }

    fun isSymlink(f: File): Boolean = runCatching {
        java.nio.file.Files.isSymbolicLink(f.toPath())
    }.getOrDefault(false)
}
