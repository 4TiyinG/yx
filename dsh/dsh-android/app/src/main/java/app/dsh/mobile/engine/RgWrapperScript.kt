package app.dsh.mobile.engine

/**
 * `rg` 包装脚本的模板（v1.6.39）—— 刻意抽成**不依赖 Android 类**的纯函数。
 *
 * 原因：这段文本的正确性可以完全离线验证（本机用真实动态链接二进制跑一遍
 * 「改名前失败、改名后成功」）。放进 [NativeLinkFix] 里就会被 `android.content.Context`
 * 拖进整个引擎依赖图，`scripts/probes/_test_rg_linkfix.py` 也就没法单独编译它来做
 * 端到端演练。所以这里是「纯文本生产者」，[NativeLinkFix] 只负责文件副作用。
 */
object RgWrapperScript {

    /** 首行标记：用来判断某个文件是不是我们已经生成的包装。 */
    const val MARK = "# DSH-LD-WRAP v1"

    /** 真二进制后缀（同目录，保证权限与 `dirname` 语义一致）。 */
    const val REAL_SUFFIX = ".real"

    /**
     * 生成包装脚本。
     *
     * 关键点：
     *  · interpreter 用 `/system/bin/sh`（Android 上 `/bin/sh` 不存在）；
     *  · 库目录是设备上的绝对路径（本文件在设备上生成，不参与 CI 产物，
     *    因此没有「构建期绝对路径失效」问题）；
     *  · 保留调用方已有的 `LD_LIBRARY_PATH`（追加在后面），不覆盖别的工具链设置；
     *  · `exec "$@"` 让 rg **替换**掉 shell 进程：stdin/stdout/stderr、退出码、
     *    信号语义与原二进制完全一致，工具层读不到多余进程层级；
     *  · 真二进制缺失时**不**回退到 PATH 里的 `rg`（那可能就是我们自己 → 无限递归），
     *    直接 127 退出并把原因写到 stderr，故障可见。
     */
    fun build(realPath: String, ldLibraryPath: String): String {
        val d = '$'
        return buildString {
            append("#!/system/bin/sh\n")
            append(MARK).append(" | 自动生成，请勿编辑\n")
            append("# 由 app.dsh.mobile.engine.NativeLinkFix 维护：\n")
            append("# rg 是 Termux 构建的动态链接 ELF，DT_RUNPATH 指向不存在的 Termux 路径，\n")
            append("# 只能靠 LD_LIBRARY_PATH 找到 libpcre2-8.so。工具层 spawn 时未必继承引擎环境，\n")
            append("# 因此在这里自己 export 一次再 exec 真二进制（多一层 fork，语义完全一致）。\n")
            append("DSH_RG_REAL=").append(sq(realPath)).append('\n')
            append("DSH_RG_LIBS=").append(sq(ldLibraryPath)).append('\n')
            append("if [ -n \"${d}DSH_RG_LIBS\" ]; then\n")
            append("  if [ -n \"${d}LD_LIBRARY_PATH\" ]; then\n")
            append("    LD_LIBRARY_PATH=\"${d}DSH_RG_LIBS:${d}LD_LIBRARY_PATH\"\n")
            append("  else\n")
            append("    LD_LIBRARY_PATH=\"${d}DSH_RG_LIBS\"\n")
            append("  fi\n")
            append("  export LD_LIBRARY_PATH\n")
            append("fi\n")
            append("if [ ! -f \"${d}DSH_RG_REAL\" ]; then\n")
            append("  echo \"rg: 真二进制缺失: ${d}DSH_RG_REAL\" >&2\n")
            append("  exit 127\n")
            append("fi\n")
            append("exec \"${d}DSH_RG_REAL\" \"${d}@\"\n")
        }
    }

    /** POSIX 单引号包裹（路径含空格/特殊字符时的唯一安全写法）。 */
    fun sq(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
