package app.dsh.mobile.engine

import android.content.Context
import android.os.Build
import android.util.Log
import app.dsh.mobile.DshApp
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * 引擎层热更新：只替换 lib/node_modules/@deepseek-ai 子树，基础运行时不动。
 */
object EngineUpdater {
    private const val TAG = "EngineUpdater"
    private const val PREFIX = "lib/node_modules/@deepseek-ai/"
    private const val SCOPE = "lib/node_modules/@deepseek-ai"
    private const val PREFS = "engine_update"
    private const val PREF_LAST_CHECK = "last_check_ms"
    private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    private val LOCK = Any()

    data class State(
        val version: String,
        val builtAt: Long,
        val sha256: String,
        val installedAt: Long,
        val previous: String?,
        val previousVersion: String?,
    )

    sealed interface Result {
        data class Ok(val version: String) : Result
        data class Failed(val message: String) : Result
    }

    fun currentVersion(ctx: Context): String? =
        installedState(ctx)?.version ?: bundledEngineVersion(ctx)

    fun checkForUpdate(ctx: Context, proxy: String? = null): EngineIndexClient.Entry? {
        val entry = EngineIndexClient.resolve(
            ctx = ctx,
            indexUrl = null,
            proxyBase = proxy,
            abi = defaultAbi(),
            currentVersionCode = currentVersionCode(ctx),
        ) ?: return null
        val state = installedState(ctx)
        val current = state?.version ?: bundledEngineVersion(ctx)
        if (current != null && current == entry.version) return null
        if (state != null && entry.builtAt > 0 && state.builtAt >= entry.builtAt) return null
        return entry
    }

    fun updateFromIndex(
        ctx: Context,
        entry: EngineIndexClient.Entry,
        onProgress: (Float) -> Unit = {},
    ): Result = synchronized(LOCK) {
        try {
            requireBase(ctx)
            val zip = File(ctx.cacheDir, "engine-update.zip")
            zip.delete()
            HttpDownloader(ctx).download(
                urls = entry.urls,
                dest = zip,
                expectedSha256 = entry.sha256,
                expectedSize = entry.size,
                onProgress = { onProgress(it * 0.6f) },
            )
            installZip(ctx, zip, entry.version, entry.builtAt, entry.sha256, onProgress, 0.6f)
        } catch (e: Throwable) {
            Log.w(TAG, "engine update failed: ${e.message}")
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun installFromLocalZip(
        ctx: Context,
        zip: File,
        onProgress: (Float) -> Unit = {},
    ): Result = synchronized(LOCK) {
        try {
            requireBase(ctx)
            val sha = RuntimeInstaller.sha256(zip)
            installZip(ctx, zip, "local-" + System.currentTimeMillis(), 0L, sha, onProgress, 0f)
        } catch (e: Throwable) {
            Log.w(TAG, "local engine import failed: ${e.message}")
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun confirmHealthy(ctx: Context) {
        val state = installedState(ctx) ?: return
        if (state.previous == null) return
        runCatching {
            ctx.filesDir.listFiles()
                ?.filter { it.name.startsWith("engine.old-engine-") }
                ?.forEach { it.deleteRecursively() }
        }
        writeState(ctx, state.copy(previous = null))
    }

    fun rollback(ctx: Context): Boolean = synchronized(LOCK) {
        val state = installedState(ctx) ?: return@synchronized false
        val previous = state.previous?.let(::File) ?: return@synchronized false
        if (!previous.isDirectory) return@synchronized false
        val target = scopeDir(ctx)
        val tmpRoot = File(ctx.filesDir, "engine.new-engine-" + System.currentTimeMillis())
        val tmpScope = File(tmpRoot, SCOPE)
        val movedNew = if (target.exists()) {
            tmpRoot.mkdirs()
            target.renameTo(tmpScope)
        } else true
        if (!movedNew) return@synchronized false
        if (!previous.renameTo(target)) {
            runCatching { if (tmpScope.exists()) tmpScope.renameTo(target) }
            return@synchronized false
        }
        writeState(
            ctx,
            State(
                version = state.previousVersion ?: "unknown",
                builtAt = 0L,
                sha256 = "",
                installedAt = System.currentTimeMillis(),
                previous = null,
                previousVersion = null,
            ),
        )
        runCatching { tmpRoot.deleteRecursively() }
        true
    }

    fun autoCheckInBackground(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(PREF_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        if (!EngineConfig.nodeBin(app).isFile) return
        prefs.edit().putLong(PREF_LAST_CHECK, now).apply()
        Thread({
            val entry = runCatching { checkForUpdate(app) }.getOrNull() ?: return@Thread
            val result = updateFromIndex(app, entry)
            if (result is Result.Ok) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { (app as DshApp).supervisor.restart() }
                }
            }
        }, "engine-auto-update").start()
    }

    private fun installZip(
        ctx: Context,
        zip: File,
        version: String,
        builtAt: Long,
        sha256: String,
        onProgress: (Float) -> Unit,
        progressStart: Float,
    ): Result {
        val staging = File(ctx.filesDir, "engine.staging-engine")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            unzipEngine(zip, staging, onProgress, progressStart)
            val newScope = File(staging, SCOPE)
            check(File(newScope, "dsh/lib/bin.js").isFile) { "engine zip missing dsh entry" }
            check(File(newScope, "dsh-web-app/cordis.patch.yml").isFile) {
                "engine zip missing dsh-web-app"
            }
            val state = installedState(ctx)
            val currentVersion = state?.version ?: bundledEngineVersion(ctx)
            val target = scopeDir(ctx)
            val backupRoot = File(ctx.filesDir, "engine.old-engine-" + System.currentTimeMillis())
            val backupScope = File(backupRoot, SCOPE)
            var movedOld = false
            try {
                if (target.exists()) {
                    backupRoot.mkdirs()
                    check(target.renameTo(backupScope)) { "cannot move old engine aside" }
                    movedOld = true
                }
                check(newScope.renameTo(target)) { "cannot place new engine" }
                writeState(
                    ctx,
                    State(
                        version = version,
                        builtAt = builtAt,
                        sha256 = sha256,
                        installedAt = System.currentTimeMillis(),
                        previous = if (movedOld) backupScope.absolutePath else null,
                        previousVersion = currentVersion,
                    ),
                )
                staging.deleteRecursively()
                zip.delete()
                return Result.Ok(version)
            } catch (e: Throwable) {
                runCatching { if (target.exists()) target.deleteRecursively() }
                runCatching { if (movedOld) backupScope.renameTo(target) }
                throw e
            }
        } finally {
            runCatching { staging.deleteRecursively() }
        }
    }

    private fun unzipEngine(
        zip: File,
        staging: File,
        onProgress: (Float) -> Unit,
        progressStart: Float,
    ) {
        var total = 0L
        runCatching {
            ZipFile(zip).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (!e.isDirectory && e.name.startsWith(PREFIX)) total += e.size
                }
            }
        }
        var done = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                check(name == SCOPE || name.startsWith(PREFIX)) {
                    "engine zip contains unexpected entry: $name"
                }
                val out = File(staging, name).canonicalFile
                check(out.path.startsWith(staging.canonicalPath)) { "zip-slip: $name" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { done += zis.copyTo(it) }
                    if (total > 0) {
                        val frac = (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
                        onProgress(progressStart + frac * (1f - progressStart))
                    }
                }
                zis.closeEntry()
            }
        }
    }

    private fun requireBase(ctx: Context) {
        check(EngineConfig.nodeBin(ctx).isFile) {
            "基础运行时未安装，请先在维护页导入 runtime zip"
        }
        check(File(EngineConfig.engineRoot(ctx), "lib/node_modules").isDirectory) {
            "运行时目录不完整，请重新导入 runtime zip"
        }
    }

    private fun scopeDir(ctx: Context) = File(EngineConfig.engineRoot(ctx), SCOPE)
    private fun stateFile(ctx: Context) = File(EngineConfig.engineRoot(ctx), ".engine.json")

    private fun installedState(ctx: Context): State? = runCatching {
        val f = stateFile(ctx)
        if (!f.isFile) return@runCatching null
        val o = JSONObject(f.readText())
        State(
            version = o.optString("version"),
            builtAt = o.optLong("builtAt", 0L),
            sha256 = o.optString("sha256"),
            installedAt = o.optLong("installedAt", 0L),
            previous = o.optString("previous").takeIf { it.isNotBlank() },
            previousVersion = o.optString("previousVersion").takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    private fun writeState(ctx: Context, state: State) {
        val o = JSONObject()
            .put("version", state.version)
            .put("builtAt", state.builtAt)
            .put("sha256", state.sha256)
            .put("installedAt", state.installedAt)
            .put("previous", state.previous ?: "")
            .put("previousVersion", state.previousVersion ?: "")
        stateFile(ctx).writeText(o.toString())
    }

    private fun bundledEngineVersion(ctx: Context): String? = runCatching {
        val f = File(EngineConfig.engineRoot(ctx), "BUILD.json")
        if (!f.isFile) return@runCatching null
        JSONObject(f.readText()).optString("engineVersion").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun defaultAbi(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "arm64-v8a"
        "x86_64" -> "x86_64"
        else -> Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    }

    private fun currentVersionCode(ctx: Context): Int = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }.getOrDefault(0)
}