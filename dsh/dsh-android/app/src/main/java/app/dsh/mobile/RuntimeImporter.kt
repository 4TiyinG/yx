package app.dsh.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import app.dsh.mobile.engine.BootTrace
import app.dsh.mobile.engine.RuntimeInstaller
import java.io.File
import java.io.IOException

/** 从手机本地导入运行时 zip（文件选择器 / 目录扫描）。 */
object RuntimeImporter {
    const val REQ_PICK_ZIP = 9201
    const val REQ_PICK_DIR = 9202

    data class Source(val name: String, val uri: Uri, val modified: Long = 0L)

    fun pickZip(activity: Activity, requestCode: Int = REQ_PICK_ZIP) {
        activity.startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
                )
            },
            requestCode,
        )
    }

    fun pickDirectory(activity: Activity, requestCode: Int = REQ_PICK_DIR) {
        activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), requestCode)
    }

    fun listZips(ctx: Context, treeUri: Uri): List<Source> {
        val out = ArrayList<Source>()

        fun scan(uri: Uri, depth: Int) {
            val parentId = if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.getTreeDocumentId(uri)
            } else {
                DocumentsContract.getDocumentId(uri)
            }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, parentId)
            val cursor = runCatching {
                ctx.contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null, null, null,
                )
            }.getOrNull() ?: return

            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(3).orEmpty()
                    val child = DocumentsContract.buildDocumentUriUsingTree(uri, id)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth <= 0) scan(child, depth + 1)
                    } else if (name.endsWith(".zip", ignoreCase = true)) {
                        out.add(Source(name, child, c.getLong(2)))
                    }
                }
            }
        }

        runCatching { scan(treeUri, 0) }
        return out.sortedByDescending { it.modified }
    }

    fun import(
        ctx: Context,
        source: Source,
        onProgress: (Float) -> Unit = {},
        onDetail: (BootTrace.Install) -> Unit = {},
        onDone: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        Thread({
            val tmp = File(ctx.cacheDir, "runtime-import-${System.currentTimeMillis()}.zip")
            try {
                ctx.contentResolver.openInputStream(source.uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it, bufferSize = 1 shl 20) }
                } ?: throw IOException("cannot open ${source.name}")
                if (tmp.length() < (1L shl 20)) throw IOException("zip too small: ${tmp.length()} bytes")
                val version = RuntimeInstaller(ctx).installFromLocalZip(tmp, onProgress, onDetail)
                tmp.delete()
                android.os.Handler(ctx.mainLooper).post { onDone(version) }
            } catch (e: Throwable) {
                tmp.delete()
                android.os.Handler(ctx.mainLooper).post { onError(e) }
            }
        }, "runtime-import").start()
    }

    fun persistRead(ctx: Context, uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }
}