package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import app.dsh.mobile.update.SignedIndex
import org.json.JSONObject

/** 引擎索引（engine-index.json）客户端：与 runtime-index 共用验签。 */
object EngineIndexClient {
    data class Entry(
        val version: String,
        val builtAt: Long,
        val sha256: String,
        val size: Long,
        val unpackedBytes: Long,
        val urls: List<String>,
    )

    private const val TAG = "EngineIndex"

    fun resolve(
        ctx: Context,
        indexUrl: String?,
        proxyBase: String?,
        abi: String,
        currentVersionCode: Int,
    ): Entry? = runCatching {
        val base = indexUrl?.takeIf { it.isNotBlank() }
            ?: (RepoConfig.RELEASES_LATEST + "/engine-index.json")
        val raw = when (val result = SignedIndex.fetch(
            ctx = ctx,
            url = base,
            allowProxyFallback = true,
            proxy = proxyBase.orEmpty(),
        )) {
            is SignedIndex.Result.Ok -> result.bytes
            SignedIndex.Result.NotModified ->
                throw IllegalStateException("engine-index returned 304 without local cache")
        }
        val doc = JSONObject(String(raw, Charsets.UTF_8))
        val minCode = doc.optInt("minAppVersionCode", 0)
        if (minCode > 0 && currentVersionCode > 0 && currentVersionCode < minCode) {
            throw SecurityException("engine-index requires app versionCode >= $minCode")
        }
        val obj = doc.optJSONObject("engine")?.optJSONObject(abi)
            ?: throw IllegalStateException("engine-index has no entry for $abi")
        val urls = mutableListOf<String>()
        obj.optJSONArray("urls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val u = arr.optString(i)
                if (u.isNotBlank()) urls.add(u)
            }
        }
        val entry = Entry(
            version = obj.optString("version"),
            builtAt = obj.optLong("builtAt", 0L),
            sha256 = obj.optString("sha256"),
            size = obj.optLong("size", 0L),
            unpackedBytes = obj.optLong("unpackedBytes", 0L),
            urls = urls,
        )
        if (entry.version.isBlank()) throw IllegalStateException("engine-index entry missing version")
        if (entry.sha256.isBlank()) throw IllegalStateException("engine-index entry missing sha256")
        if (entry.urls.isEmpty()) throw IllegalStateException("engine-index entry missing urls")
        Log.i(TAG, "resolved engine ${entry.version} ($abi)")
        entry
    }.onFailure {
        Log.w(TAG, "engine-index unavailable: ${it.message}")
    }.getOrNull()
}