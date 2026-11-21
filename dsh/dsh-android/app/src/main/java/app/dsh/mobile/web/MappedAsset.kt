package app.dsh.mobile.web

import android.util.Log
import java.io.File
import java.io.InputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * 大资源的 mmap 直读（v1.6.36）。
 *
 * ## 为什么不用 `File.readBytes()` / `ByteArrayInputStream`
 * 那条路径的成本是 **文件 → Java 堆 byte[] → Chromium 缓冲** 两次拷贝，而且
 * 整个文件（模型、长图动辄几十 MB）会在 Java 堆里留下一个与文件等大的数组 ——
 * 触发 GC、抬高水位，热受限时就变成卡顿源。
 *
 * mmap 之后：
 * · 文件页由内核按需调入（lazy paging），**只有真正被读到的部分**进入内存；
 * · `MappedByteBuffer.get(dst, off, len)` 是「页缓存 → 调用方缓冲」的**一次**拷贝，
 *   不经过 Java 堆中转；
 * · 页缓存由内核在内存压力下自行回收，不会像 byte[] 那样被 GC 反复搬运。
 *
 * ## 为什么还要「块大小」参数
 * `WebResourceResponse` 的流是被 Chromium 用 JNI 反复 `read(buf, off, len)` 拉取的。
 * 每次调用都跨一次 JNI。给较大的内部块，可以让一次 JNI 调用搬更多字节，
 * 减少高频小片传输带来的固定开销 —— 这正是「拦截路径的 JNI 拷贝」那条要点。
 *
 * ## 何时不该用
 * 小文件（几 KB 的图标、字体子集）mmap 的 `map()` 建映射开销反而大于直接读字节。
 * 调用方按 [MMAP_THRESHOLD_BYTES] 分流，小的走内存层。
 */
internal object MappedAsset {

    /** 超过这个大小才值得 mmap（建映射 + 页对齐有固定成本）。 */
    const val MMAP_THRESHOLD_BYTES = 256L * 1024

    /** 单次读的内部块：一次 JNI 调用尽量搬满。 */
    const val READ_CHUNK = 64 * 1024

    /**
     * 打开 [file] 的 [offset, offset+len) 区间为只读流。
     *
     * @return 失败返回 null（调用方退回普通文件读，功能不受影响）
     */
    fun open(file: File, offset: Long = 0L, len: Long = file.length()): InputStream? = runCatching {
        if (len <= 0L) return@runCatching null
        val ch = FileChannel.open(file.toPath(), StandardOpenOption.READ)
        val buffer: MappedByteBuffer = ch.map(FileChannel.MapMode.READ_ONLY, offset, len)
        // 提示内核：这份数据会被顺序读一遍（对长图/模型是准确的预判）
        runCatching { buffer.load() }
        ch.close()
        MappedStream(buffer)
    }.onFailure { Log.w("MappedAsset", "mmap ${file.name} failed: ${it.message}") }.getOrNull()

    /**
     * 直接把映射区间喂给 Chromium 的流。
     *
     * 只实现 `read(byte[], off, len)`（Chromium 只用这个）与 `skip`。
     * 不持有 FileChannel：映射建立后即可关闭通道，映射仍然有效。
     */
    private class MappedStream(private val buf: MappedByteBuffer) : InputStream() {

        override fun read(): Int {
            if (!buf.hasRemaining()) return -1
            return buf.get().toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!buf.hasRemaining()) return -1
            // 单次最多搬 READ_CHUNK，避免一次性写入超大数组时长时间占住映射
            val n = minOf(len, buf.remaining(), READ_CHUNK)
            buf.get(b, off, n)
            return n
        }

        override fun available(): Int = buf.remaining()

        override fun skip(n: Long): Long {
            if (n <= 0) return 0
            val step = minOf(n, buf.remaining().toLong()).toInt()
            buf.position(buf.position() + step)
            return step.toLong()
        }

        override fun close() {
            // 映射由 GC 在不可达后回收（MappedByteBuffer 的 unmap 无公开 API）。
            // 这里只把位置推到末尾，避免调用方误以为还能继续读。
            runCatching { buf.position(buf.limit()) }
        }
    }
}
