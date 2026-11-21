package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * 会话管理热通道（v1.6.28）：让「删除对话」在**引擎进程内部**完成，从而不需要停引擎。
 *
 * ## 为什么需要它
 *
 * `storages/workspace.json` 是引擎的独占状态文件（启动读一次进内存、之后整份写回）。
 * 从外部改它，改动会被引擎的旧内存状态抹掉，所以旧实现只能「停引擎 → 删 → 重启」，
 * 用户每次删几个对话都要等十几秒，期间 WebUI 断连。
 *
 * 本类把删除请求交给引擎内的 `session-admin.mjs` 插件（见该文件注释），由它直接改
 * **内存里那份注册表** —— 引擎内存与磁盘同时干净，进程不用重启。
 *
 * ## 通道：`$DSH_HOME/.android-session-admin/`
 *
 * ```
 *  App 写 cmd-<id>.json  →  插件轮询执行  →  插件写 res-<id>.json  →  App 读
 * ```
 *
 * 为什么用文件而不是 HTTP：引擎的 webServer 路由注册 API 属于上游私有实现，
 * 换版本就可能失效；文件通道只依赖 `node:fs`，且**插件加载失败也只是「通道不可用」**，
 * 不会影响引擎启动（Cordis 隔离失败插件）。
 *
 * ## 失败即回退，绝不假装成功
 *
 * [requestDelete] 在通道无响应（插件没装上 / 引擎没在跑 / 引擎版本不认这个协议）时
 * 返回 null，调用方据此回退到「停引擎」老路径。插件自己判定「没删掉」时返回
 * `ok = false` 并带原因，同样触发回退 —— 宁可多一次重启，也不留下一堆点进去空白的
 * 幽灵会话。
 */
object HotSessionAdmin {

    private const val TAG = "HotSessionAdmin"

    /** 与 `session-admin.mjs` 约定的目录名。 */
    private const val DIR = ".android-session-admin"

    /** 轮询间隔：插件 400ms 一轮，这里 80ms 拿结果，平均等待 < 250ms。 */
    private const val POLL_MS = 80L

    /** 残留文件清理阈值（Android 侧被杀导致没读走结果 / 没删掉命令）。 */
    private const val STALE_MS = 10 * 60 * 1000L

    private val seq = AtomicInteger(0)

    /**
     * 一次热删除的结果。
     *
     * @param ok 插件确认「目标 id 在引擎内存注册表里已经不存在」
     * @param removed 插件摘掉的引用条数（sessionIds + 归档/固定表）
     * @param strategy 走通的是哪条路：`registry-method(...)` / `state-prune` / `already-absent`
     * @param presentAfter 结构遍历复核的残留数
     * @param rawAfter 序列化复核的残留数（-1 = 无法判定）
     * @param detail 失败原因或补充诊断
     */
    data class Outcome(
        val ok: Boolean,
        val removed: Int,
        val strategy: String,
        val presentAfter: Int,
        val rawAfter: Int,
        val detail: String,
    )

    private fun dirOf(ctx: Context): File = File(EngineConfig.dshHome(ctx), DIR)

    /**
     * 请求引擎在进程内删除这些会话。
     *
     * @return 引擎的答复；null = 通道不可用（插件缺失 / 引擎未运行 / 超时），
     *         调用方应回退到「停引擎」路径
     */
    fun requestDelete(ctx: Context, ids: Collection<String>, timeoutMs: Long = 4000L): Outcome? =
        request(ctx, "delete", ids.toList(), timeoutMs)

    /**
     * 探测通道能力（不删任何东西）。
     *
     * 返回的 [Outcome.detail] 里带上引擎服务的方法名与注册表节点数 —— 用户反馈
     * 「热删除没生效」时，一行日志就能判断是协议没被认、还是引擎状态形状变了。
     */
    fun probe(ctx: Context, timeoutMs: Long = 3000L): Outcome? =
        request(ctx, "probe", emptyList(), timeoutMs)

    private fun request(ctx: Context, op: String, ids: List<String>, timeoutMs: Long): Outcome? {
        val dir = dirOf(ctx)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.i(TAG, "$op skipped: cannot create ${dir.absolutePath}")
            return null
        }
        sweep(dir)

        val id = "${System.currentTimeMillis()}-${seq.incrementAndGet()}"
        val cmd = File(dir, "cmd-$id.json")
        val res = File(dir, "res-$id.json")
        val payload = JSONObject()
            .put("id", id)
            .put("op", op)
            .put("ids", org.json.JSONArray(ids))
            .put("v", 1)

        if (!writeAtomically(cmd, payload.toString())) {
            Log.w(TAG, "$op skipped: cannot write ${cmd.name}")
            return null
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (System.currentTimeMillis() < deadline) {
                if (res.isFile) {
                    val outcome = runCatching { parse(res.readText()) }.getOrNull()
                    if (outcome != null) return outcome
                }
                if (isFinishing(ctx)) return null
                Thread.sleep(POLL_MS)
            }
            Log.i(TAG, "$op timeout after ${timeoutMs}ms (plugin absent or engine down)")
            return null
        } finally {
            // 命令文件由插件自己删；超时/异常时这里兜底清掉，避免下次开机被"补执行"
            runCatching { cmd.delete() }
            runCatching { res.delete() }
        }
    }

    private fun parse(text: String): Outcome? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return Outcome(
            ok = o.optBoolean("ok", false),
            removed = o.optInt("removed", 0),
            strategy = o.optString("strategy", ""),
            presentAfter = o.optInt("presentAfter", -1),
            rawAfter = o.optInt("rawAfter", -1),
            detail = buildString {
                o.optString("note", "").takeIf { it.isNotBlank() }?.let { append(it) }
                val methods = o.optJSONObject("methods")
                if (methods != null) {
                    if (isNotEmpty()) append(" · ")
                    append("services=").append(methods.length())
                }
            },
        )
    }

    /**
     * `File.writeText` 不是原子的：插件可能读到半截 JSON 而报 "bad cmd"。
     * 先写临时文件、`fsync`，再 rename —— rename 在同一目录内是原子操作。
     */
    private fun writeAtomically(file: File, text: String): Boolean = runCatching {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(StandardCharsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        true
    }.getOrElse {
        Log.w(TAG, "write ${file.name} failed: ${it.message}")
        false
    }

    /** 清掉上一次进程残留的命令/结果文件。 */
    private fun sweep(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_MS
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (!f.name.startsWith("cmd-") && !f.name.startsWith("res-")) return@forEach
                if (f.lastModified() < cutoff) f.delete()
            }
        }
    }

    private fun isFinishing(ctx: Context): Boolean = when (ctx) {
        is android.app.Activity -> ctx.isFinishing || ctx.isDestroyed
        else -> false
    }
}
