package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import app.dsh.mobile.DshApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * 插件加载优先级：把**已安装**的 `dsh-web-mobile` 排到最前面，让它最先被加载。
 *
 * ## 为什么是「改 `dsh.profile.bundles` 的顺序」
 *
 * 引擎能被子类代码控制的加载顺序只有一个地方：profile 的 `dsh.profile.bundles`
 * 数组。引擎按这个数组把 bundle 逐个挂成 entry（每个 bundle 的 patch 层也按同一
 * 顺序叠加，见 `EngineConfig` 里关于 `--patch` 永远最后的注释）。除此之外没有
 * 「priority」字段之类的开关 —— 所以想让某个插件排第一，只能把它挪到数组首位。
 *
 * ⚠️ 这条结论是**读代码 + 引擎行为注释**得出的，没有真机计时：本机没有引擎源码，
 * 也没有设备。所以本对象**只做顺序调整**，不声称"提速了 N 毫秒"。真实收益看
 * `engine.log` 里插件加载的时间戳，或看启动阶段 `plugin-priority` 的耗时（那是
 * 本对象自身的开销，毫秒级）。
 *
 * ## 「已安装」的判定（不满足就整体跳过）
 *
 * 1. `dsh.profile.bundles` 里出现 `dsh-web-mobile`（含 `@scope/dsh-web-mobile`
 *    这种写法）；
 * 2. 且它真的能被解析到：`profiles/web/node_modules/<name>` 或
 *    `profiles/node_modules/<name>` 或引擎内置 `lib/node_modules` 下存在目录。
 *
 * 只满足第 1 条、目录却不在的情况是「残留条目」：那种状态下引擎本来就会解析失败，
 * 挪顺序没意义，所以也跳过并在日志里说清楚。
 *
 * ## 为什么每次启动都要重做（而不是一次性写死）
 *
 * 会改写 `bundles` 的地方不止我们：插件安装（追加到尾部）、`PluginKillSwitch`
 * 的应急收敛（按快照整体写回）、`PluginFastBoot.release`（从备份整体还原）。
 * 任何一处都可能把顺序改回去。启动前重排一次是幂等的（已在首位就直接返回，
 * 不写盘），成本是一次 2–20 KB 的 JSON 读 + 一次比较。
 *
 * ## 时机要求
 *
 * **必须在引擎没在跑的时候调用**（本对象的调用点在 `EngineSupervisor` 的
 * spawn 之前）。引擎运行期改 `package.json` 会被它退出时的内存写回吞掉 ——
 * `ProfilePluginProvisioner` / `PluginKillSwitch` 都是同一个约束。
 *
 * ## 关掉这个开关会怎样
 *
 * 不是「不动」，而是把目标插件**降级到列表末尾**（新装插件本来就被追加在末尾）。
 * 为什么不做「还原到原来的下标」：插件可能已经被卸载/新装，按下标还原会把顺序
 * 挪错位置，甚至（若整体还原旧列表）把已卸载的插件塞回去，让引擎起不来。
 * 移到末尾永远安全，且语义清楚：不再享受优先。
 */
object PluginPriority {

    private const val TAG = "PluginPriority"

    /** 开关（默认开）。关掉 = 目标插件不再排首位 [applyBeforeBoot]。 */
    private const val KEY_ENABLED = "plugin_priority_mobile_ui"

    /** 首次调整前记录一次原始顺序，供人工核对/手工还原（本对象不会自动回滚）。 */
    private const val RECORD = ".dsh-plugin-priority.json"

    /** 目标插件名。市场里是 `dsh-web-mobile`（mexiaosqwq/dsh-web-mobile）。 */
    const val TARGET = "dsh-web-mobile"

    /** `dsh-web-mobile` / `@scope/dsh-web-mobile` 都算；`dsh-web-mobile-fix` 不算。 */
    fun isTarget(name: String): Boolean =
        name == TARGET || name.endsWith("/$TARGET")

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /**
     * 一次调整的结论。
     *
     * @param installed 目标插件是否**真装好了**（bundles 里有 + 目录存在）
     * @param changed 是否真的改写了 manifest
     * @param index 目标在**调整后**列表里的下标；-1 表示列表里没有
     * @param order 调整后的完整顺序（诊断用；插件多时只保留前 6 个 + 总数）
     * @param reason 稳定 ASCII 原因码，写日志用
     */
    data class Outcome(
        val installed: Boolean,
        val changed: Boolean,
        val index: Int,
        val order: List<String>,
        val reason: String,
    )

    private fun profileDir(ctx: Context) = File(EngineConfig.dshHome(ctx), "profiles/web")

    fun manifestFile(ctx: Context) = File(profileDir(ctx), "package.json")

    /** 启动前调用：把已安装的目标插件排到最前（关掉开关则排到末尾）。 */
    fun applyBeforeBoot(ctx: Context): Outcome = runCatching {
        applyInternal(ctx)
    }.getOrElse { e ->
        // 任何异常都只意味着「顺序没调整」，绝不能阻断引擎启动。
        Log.w(TAG, "priority pass skipped: ${e.javaClass.simpleName}: ${e.message}")
        Outcome(false, false, -1, emptyList(), "error")
    }

    private fun applyInternal(ctx: Context): Outcome {
        val file = manifestFile(ctx)
        if (!file.isFile) return Outcome(false, false, -1, emptyList(), "no-profile")
        val root = runCatching { JSONObject(file.readText()) }.getOrElse {
            Log.w(TAG, "manifest unreadable: ${it.message}")
            return Outcome(false, false, -1, emptyList(), "bad-json")
        }
        val profile = root.optJSONObject("dsh")?.optJSONObject("profile")
            ?: return Outcome(false, false, -1, emptyList(), "no-profile-section")
        val arr = profile.optJSONArray("bundles")
            ?: return Outcome(false, false, -1, emptyList(), "no-bundles")

        val names = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) names.add(arr.optString(i))

        val index = names.indexOfFirst { isTarget(it) }
        if (index < 0) return Outcome(false, false, -1, names, "not-installed")

        val name = names[index]
        if (!resolvable(ctx, name)) {
            Log.i(
                TAG,
                "$name is listed in bundles but not resolvable on disk; " +
                    "leaving the order untouched (the engine cannot load it either way)",
            )
            return Outcome(false, false, index, names, "stale-entry")
        }

        val wantFirst = enabled(ctx)
        val reordered = ArrayList(names)
        reordered.removeAt(index)
        if (wantFirst) reordered.add(0, name) else reordered.add(name)

        if (reordered == names) {
            return Outcome(true, false, reordered.indexOf(name), reordered, "unchanged")
        }
        // 安全闸：只允许「同一个多重集合换序」，绝不允许增删条目。
        if (reordered.size != names.size || reordered.toSet() != names.toSet()) {
            Log.w(TAG, "reorder would alter the bundle set; refusing to write")
            return Outcome(true, false, index, names, "refused")
        }

        writeOrder(file, root, reordered)
        recordOriginalOnce(ctx, names, index, name)
        val newIndex = reordered.indexOf(name)
        Log.i(
            TAG,
            "$name moved ${index} -> $newIndex (${if (wantFirst) "promoted" else "demoted"}) " +
                "of ${reordered.size} bundle(s): ${preview(reordered)}",
        )
        return Outcome(true, true, newIndex, reordered, if (wantFirst) "promoted" else "demoted")
    }

    /** 目标插件是否已安装（供 [PluginFastBoot] 判断要不要把它留在首轮加载里）。 */
    fun installedName(ctx: Context): String? = runCatching {
        val file = manifestFile(ctx)
        if (!file.isFile) return@runCatching null
        val arr = JSONObject(file.readText())
            .optJSONObject("dsh")?.optJSONObject("profile")?.optJSONArray("bundles")
            ?: return@runCatching null
        for (i in 0 until arr.length()) {
            val n = arr.optString(i)
            if (isTarget(n) && resolvable(ctx, n)) return@runCatching n
        }
        null
    }.getOrNull()

    /**
     * 包目录是否真的在。三种落点：profile 自己的 node_modules（插件中心安装）、
     * `$DSH_HOME/profiles/node_modules`（内置插件软链层）、引擎 `lib/node_modules`。
     * `isDirectory` 会跟随软链，pnpm 的 `.pnpm` 链接因此也能算到。
     */
    private fun resolvable(ctx: Context, name: String): Boolean {
        val home = EngineConfig.dshHome(ctx)
        return listOf(
            File(File(home, "profiles/web/node_modules"), name),
            File(File(home, "profiles/node_modules"), name),
            File(File(EngineConfig.engineRoot(ctx), "lib/node_modules"), name),
        ).any { it.isDirectory }
    }

    private fun preview(names: List<String>): String =
        if (names.size <= 6) names.joinToString(",")
        else names.take(6).joinToString(",") + ",…(+" + (names.size - 6) + ")"

    /** 只改写 `dsh.profile.bundles` 一个字段，其余字段原样写回（与 PluginKillSwitch 同款）。 */
    private fun writeOrder(file: File, root: JSONObject, names: List<String>) {
        val arr = JSONArray()
        names.forEach(arr::put)
        root.optJSONObject("dsh")?.optJSONObject("profile")?.put("bundles", arr)
        atomicWrite(file, root.toString(2) + "\n")
    }

    /**
     * 第一次调整前把原始顺序记到旁边的小文件里。
     *
     * 只记不改：本对象不会自动回滚（原因见类注释「关掉这个开关会怎样」），
     * 这份记录是给人看的 —— 出问题时能一眼看出原来是第几位、周围是哪些插件。
     */
    private fun recordOriginalOnce(ctx: Context, names: List<String>, index: Int, name: String) {
        runCatching {
            val rec = File(profileDir(ctx), RECORD)
            if (rec.isFile) return@runCatching
            val json = JSONObject()
                .put("target", name)
                .put("originalIndex", index)
                .put("originalOrder", JSONArray(names))
                .put("recordedAt", System.currentTimeMillis())
            atomicWrite(rec, json.toString(2) + "\n")
        }.onFailure { Log.w(TAG, "cannot record original order: ${it.message}") }
    }

    /** tmp → fsync → rename。与 [PluginKillSwitch] / [PluginRecovery] 里的写法一致。 */
    private fun atomicWrite(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp-${System.nanoTime()}")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(StandardCharsets.UTF_8))
            out.flush()
            // fsync 失败（部分 FUSE 挂载）不影响 rename 的原子性，只影响断电持久性
            runCatching { out.fd.sync() }
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("rename failed for ${file.name}")
        }
    }
}
