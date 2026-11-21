package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * `storages/workspace.json` 不变量自愈。
 *
 * 引擎把该文件当**独占状态文件**：启动时用 zod schema 严格解析，
 * `tables.workspaces[*].sessionIds` 的有序归属、`global.workspaceIds` 的显示顺序、
 * `archivedSessionIds` / `pinnedSessionIds` 的引用完整性三者必须自洽。
 * 任何一项被破坏（悬空 id、重复归属、归档项指向已删除会话）都会让 workspace 域
 * 加载失败 —— 而 `workspace-controller` 正是 `directoryPickerController` 的提供者，
 * 于是 WebUI 报 `active Service "directoryPickerController" is unavailable`。
 *
 * 删除对话必须改写这个文件；本类负责把改写结果收敛回引擎能接受的不变量。
 * 全部操作幂等：没有需要修的东西时不写文件。
 */
object WorkspaceRegistryHealer {

    private const val TAG = "WorkspaceHeal"
    private const val REGISTRY = "storages/workspace.json"

    fun heal(ctx: Context): Boolean = healFile(File(EngineConfig.dshHome(ctx), REGISTRY))

    fun healFile(file: File): Boolean {
        if (!file.isFile) return false
        val root = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (root == null) {
            Log.w(TAG, "registry unparseable; leaving untouched")
            return false
        }
        var changed = false
        val tables = root.optJSONObject("tables")?.optJSONObject("workspaces")
        val known = LinkedHashSet<String>()

        if (tables != null) {
            for (wid in tables.keys().asSequence().toList()) {
                val ws = tables.optJSONObject(wid) ?: continue
                val arr = ws.optJSONArray("sessionIds") ?: JSONArray()
                val uniq = JSONArray()
                val seen = HashSet<String>()
                var localChanged = false
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i)
                    if (id.isBlank() || !seen.add(id)) {
                        localChanged = true
                        continue
                    }
                    uniq.put(id)
                }
                if (localChanged) {
                    ws.put("sessionIds", uniq)
                    changed = true
                }
                for (i in 0 until uniq.length()) known.add(uniq.optString(i))
            }
        }

        val global = root.optJSONObject("global")
        if (global != null) {
            for (key in listOf("archivedSessionIds", "pinnedSessionIds")) {
                val arr = global.optJSONArray(key) ?: continue
                val next = JSONArray()
                val seen = HashSet<String>()
                var localChanged = false
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i)
                    if (id.isBlank() || id !in known || !seen.add(id)) {
                        localChanged = true
                        continue
                    }
                    next.put(id)
                }
                if (localChanged) {
                    global.put(key, next)
                    changed = true
                }
            }

            if (tables != null) {
                val ids = global.optJSONArray("workspaceIds") ?: JSONArray()
                val order = JSONArray()
                val seen = HashSet<String>()
                var localChanged = false
                for (i in 0 until ids.length()) {
                    val wid = ids.optString(i)
                    if (wid.isBlank() || !tables.has(wid) || !seen.add(wid)) {
                        localChanged = true
                        continue
                    }
                    order.put(wid)
                }
                for (wid in tables.keys().asSequence().toList()) {
                    if (seen.add(wid)) {
                        order.put(wid)
                        localChanged = true
                    }
                }
                if (localChanged) {
                    global.put("workspaceIds", order)
                    changed = true
                }

                val defaultId = global.optString("defaultWorkspaceId")
                if (defaultId.isNotBlank() && !tables.has(defaultId)) {
                    global.remove("defaultWorkspaceId")
                    changed = true
                }
            }
        }

        if (!changed) return false
        return runCatching {
            backup(file)
            writeAtomically(file, root)
            Log.i(TAG, "healed workspace registry invariants")
            true
        }.getOrElse {
            Log.e(TAG, "registry heal write failed: ${it.message}")
            false
        }
    }

    private fun backup(file: File) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        file.copyTo(File(file.parentFile, "workspace.json.pre-heal-$stamp"), overwrite = true)
    }

    private fun writeAtomically(file: File, root: JSONObject) {
        val tmp = File(file.parentFile, ".workspace.json.heal-tmp")
        tmp.writeText(root.toString(2) + "\n", StandardCharsets.UTF_8)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}