package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import app.dsh.mobile.DshApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 插件快速启动：启动前临时摘除第三方 bundle，核心引擎先起、WebUI 先显示；
 * HTTP 就绪、首帧落定后恢复 profile 并 touch patch 文件，交给 dsh-hmr 流式热加载插件。
 *
 * v1.6.24：这套“摘了再补”的做法只在插件**多或大**时划算，所以 [hold] 会先量
 * 本地插件体量（[PluginPrefetcher.decide]）：小体量直接不摘，插件随引擎一次到位，
 * 重流式阶段根本不存在。见 [hold] 里的注释与那行 `fast boot decision` 日志。
 */
object PluginFastBoot {
    private const val TAG = "PluginFastBoot"
    private const val KEY = "fast_plugin_boot"
    private const val BACKUP = ".fastboot-backup.json"
    private const val PATCH_TOUCH = "\n# dsh-fastboot release "

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
            .getBoolean(KEY, true)

    fun requestSkipNext(ctx: Context) {
        // commit() 而非 apply()：调用方紧接着就 restart()，后台线程可能在同一
        // 毫秒内 consume；apply 的异步落盘 + 读侧竞态会让「跳过快速启动」失效，
        // 于是删除对话后的重启又走 hold 路径（摘第三方插件 + 靠 HMR 补），
        // 正是 directoryPickerController unavailable 的现场。
        ctx.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
            .edit().putBoolean("fast_plugin_boot_skip_once", true).commit()
    }

    fun consumeSkipNext(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
        val v = p.getBoolean("fast_plugin_boot_skip_once", false)
        if (v) p.edit().putBoolean("fast_plugin_boot_skip_once", false).commit()
        return v
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, on).apply()
    }

    private fun profileDir(ctx: Context) = File(EngineConfig.dshHome(ctx), "profiles/web")

    fun hold(ctx: Context): Boolean {
        if (!enabled(ctx)) return false
        val dir = profileDir(ctx)
        val manifest = File(dir, "package.json")
        if (!manifest.isFile) return false
        // v1.6.24：体量小的本地插件集**不做**快速启动。
        //
        // 快速启动拿“引擎启动阶段少装几个插件”换“首帧后靠 dsh-hmr 重流式补上”。
        // 这买卖在插件多/大时才划算：插件只有两三个且体积很小时，省下的启动时间
        // 本来就很小，而重流式要付的代价（引擎重解析插件图 + 客户端 bundle 再传一遍
        // + 浏览器求值）一分不少——用户看到的就是“引擎早就好了，插件还要转几秒”。
        //
        // 不摘之后插件随引擎一次到位，**重流式阶段根本不存在**。
        // 这是唯一能真把那几秒去掉的路径（预热只能省毫秒级，已实测）。
        val decision = PluginPrefetcher.decide(ctx)
        Log.i(
            TAG,
            "fast boot decision: small=${decision.small} plugins=${decision.plugins ?: "?"} " +
                "bytes=${decision.bytes} earlyExit=${decision.earlyExit} " +
                "(holds when payload > ${PluginPrefetcher.SMALL_MAX_BYTES / 1024} KB " +
                "or plugins > ${PluginPrefetcher.FORCE_HOLD_PLUGINS})",
        )
        if (decision.small) {
            // 若上一次异常退出留下了备份，这里要回归一次，否则 package.json 会
            // 停在被摘掉的状态（那不叫“小体量”了，那叫丢插件）。
            rollback(ctx)
            Log.i(TAG, "fast boot skipped: local plugin set is small; plugins load with the engine")
            return false
        }
        val backup = File(dir, BACKUP)
        if (backup.isFile) {
            runCatching {
                atomicWrite(manifest, backup.readText())
                backup.delete()
            }.onFailure { Log.w(TAG, "pending restore failed: ${it.message}") }
        }
        val root = runCatching { JSONObject(manifest.readText()) }.getOrNull() ?: return false
        val profile = root.optJSONObject("dsh")?.optJSONObject("profile") ?: return false
        val bundles = profile.optJSONArray("bundles") ?: return false
        val deps = root.optJSONObject("dependencies") ?: JSONObject()
        // v1.6.29：优先插件**不参与**推迟加载。
        //
        // 快速启动的收益来自「首轮少装第三方插件」，代价是这些插件要等首帧落定
        // 之后由 dsh-hmr 再传一遍客户端 bundle、再求值 —— 用户看到的就是「引擎
        // 早好了，界面还要转几秒」。对 dsh-web-mobile 这种**决定首屏布局**的插件，
        // 这笔账正好是反的：它被推迟，用户第一眼看到的就不是最终布局。
        //
        // 所以把它留在首轮集合里（[PluginPriority] 已把它排到首位），其余第三方
        // 照旧推迟。它因此不需要等重流式，随引擎一起就绪。
        // 开关关掉（或没装）时行为与旧版完全一致：
        val priorityName = if (PluginPriority.enabled(ctx)) PluginPriority.installedName(ctx) else null
        val keep = JSONArray()
        var removed = 0
        var keptPriority = false
        for (i in 0 until bundles.length()) {
            val name = bundles.optString(i)
            val thirdParty = deps.has(name) && !name.startsWith("@deepseek-ai/")
            val priority = thirdParty && priorityName != null && name == priorityName
            if (priority) {
                keptPriority = true
                keep.put(name)
            } else if (thirdParty) {
                removed++
            } else {
                keep.put(name)
            }
        }
        if (removed == 0) return false
        return runCatching {
            backup.writeText(manifest.readText())
            profile.put("bundles", keep)
            atomicWrite(manifest, root.toString(2))
            Log.i(
                TAG,
                "fast boot: deferred $removed third-party bundle(s)" +
                    if (keptPriority) ", kept $priorityName in the first load" else "",
            )
            true
        }.getOrElse {
            Log.w(TAG, "fast boot hold failed: ${it.message}")
            false
        }
    }

    fun release(ctx: Context): Boolean {
        val dir = profileDir(ctx)
        val manifest = File(dir, "package.json")
        val backup = File(dir, BACKUP)
        if (!backup.isFile) return false
        val ok = runCatching {
            atomicWrite(manifest, backup.readText())
            backup.delete()
            true
        }.getOrElse {
            Log.w(TAG, "fast boot release failed: ${it.message}")
            false
        }
        if (ok) touchPatch(dir)
        return ok
    }

    fun rollback(ctx: Context): Boolean {
        val dir = profileDir(ctx)
        val manifest = File(dir, "package.json")
        val backup = File(dir, BACKUP)
        if (!backup.isFile) return false
        return runCatching {
            atomicWrite(manifest, backup.readText())
            backup.delete()
            true
        }.getOrElse { false }
    }

    private fun touchPatch(dir: File) {
        runCatching {
            File(dir, "cordis.patch.yml").appendText(PATCH_TOUCH + System.currentTimeMillis() + "\n")
            Log.i(TAG, "profile patch touched for HMR plugin reload")
        }.onFailure { Log.w(TAG, "touch patch failed: ${it.message}") }
    }

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }
}