package app.dsh.mobile.engine

import android.content.Context
import app.dsh.mobile.DshApp

/**
 * 引擎进程的 V8 参数（v1.6.49）。
 *
 * ## 为什么做成「默认关 + 开关」而不是直接写死
 *
 * 引擎每生成一个 token 都会产生大量短命对象（事件对象、增量字符串、JSON 片段）。
 * 手机上 V8 的 young generation 半空间（semi-space）默认偏小，scavenge 会非常频繁；
 * 适当调大半空间能明显降低 scavenge 次数与总 GC 时间。
 *
 * 但它同时是**内存换 CPU**：`--max-semi-space-size=N`（MiB）会让 V8 预留约
 * 3 × N 的常驻空间（from/to 两个半空间 + 晋升余量）。在 4GB 设备上盲设 32
 * 完全可能得不偿失，甚至把后台应用挤到被杀。所以这里：
 *
 *  · 默认**一个参数都不加**（与旧行为完全一致，零风险）；
 *  · 用户/调试者在设置页打开后才注入；
 *  · 取值 16 MiB —— 计划文档给的起点，够把 scavenge 频率压下来，又不至于在
 *    4-8GB 设备上抢内存。
 *
 * ## 怎么判断该不该打开（别凭感觉）
 *
 * ```
 * adb shell "cd /data/data/app.dsh.mobile/files/engine && \
 *   LD_LIBRARY_PATH=lib ./bin/node --trace-gc lib/node_modules/@deepseek-ai/dsh/lib/bin.js \
 *   web --no-open --patch <patch> 2>&1 | grep -c Scavenge"
 * ```
 *
 *  · scavenge 频率高（每秒多次）且单次停顿 > 5ms → 打开值得；
 *  · scavenge 很少 → 保持关闭，别给设备加内存压力。
 *
 * ## 为什么不能和 NODE_COMPILE_CACHE 一起 A/B
 *
 * 两者都影响冷启动耗时（编译缓存省编译，半空间省 GC），同时上就分不清谁的功劳。
 * 计划文档也是这么要求的：先量一个，再量另一个。
 */
object EngineV8Flags {

    private const val KEY = "v8_big_young_gen"

    /** 打开后注入的半空间大小（MiB） */
    const val SEMI_SPACE_MB = 16

    /** 是否启用更宽的 young generation（设置页开关，默认关）。 */
    fun wideYoungGen(ctx: Context): Boolean =
        runCatching {
            ctx.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
                .getBoolean(KEY, false)
        }.getOrDefault(false)

    fun setWideYoungGen(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, on).apply()
    }

    /**
     * 交给 `node` 的额外 argv（插在 `--expose-internals` 之后、入口脚本之前）。
     *
     * 返回空列表 = 「什么都不加」，即与 v1.6.48 及之前的行为一致。
     */
    fun nodeFlags(ctx: Context): List<String> =
        if (wideYoungGen(ctx)) listOf("--max-semi-space-size=$SEMI_SPACE_MB") else emptyList()
}
