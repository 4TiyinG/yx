package app.dsh.mobile.web

import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 高频事件合帧注入（v1.6.37，v1.6.46 修语义 + 提前到 document-start）。
 *
 * ## 问题
 * WebUI 里 `scroll` / `resize` / `touchmove` 上的监听器，在低端机或页面滚动时
 * 一帧内可能触发多次（触摸采样率高于刷新率）。每个监听器各跑一遍布局读取 +
 * 状态写入，很容易把渲染主线程的一个帧挤爆 —— 表现是滚动掉帧、发热更明显。
 * 前端代码不可修改，所以只能在**宿主侧**做合帧。
 *
 * ## 做法
 * 注入一小段幂等脚本，只做一件事：包装 `addEventListener`，当事件类型属于
 * 可合帧集合时，把回调包进 `requestAnimationFrame` 合帧器 —— 同一帧内多次
 * 触发只执行**最后一次**（保留最新事件对象，语义等价于"我只要最新的滚动位置"）。
 *
 * ## v1.6.46：两条重要修正
 *
 * ### ① 可取消事件必须保留同步 `preventDefault()` 能力
 * v1.6.37 把 `touchmove` / `wheel` 也一律挪进 rAF。这类事件的整条意义就是
 * **在事件派发当下同步调用 `preventDefault()` 阻止滚动**（抽屉/模态锁背景、
 * 自定义下拉刷新、横向轮播）。挪到下一帧再调用时，浏览器早已把这次手势算成
 * 滚动 —— `preventDefault` 变成静默无效，症状是"弹窗背后的列表能跟着滑"。
 * 这是**功能性负优化**，比掉一帧严重得多。
 *
 * 现在的规则：
 * | 事件 | 合帧条件 |
 * |---|---|
 * | `scroll` / `resize` | 总是（不可取消，且天然"只关心最新值"） |
 * | `touchmove` / `wheel` | **仅当该监听器确定是 passive** 时（显式 `{passive:true}`，
 *   或 Chromium 默认被动的那几种 target：window / document / document.body） |
 * | `pointermove` / `mousemove` | 同上（对滚动无取消语义，保守起见一并按被动判定） |
 * 页面自己写的非被动监听器**一个都不包** —— 宁可少省一点，也不能改坏手势。
 *
 * ### ② 从 `onPageFinished` 提前到 document-start
 * 合帧器只有**先于页面脚本**安装才有意义：`onPageFinished` 时页面自己的监听器
 * 早已注册完毕，包装器只能覆盖"之后"注册的那几个，命中率极低（相当于白跑）。
 * 走 `WebViewCompat.addDocumentStartJavaScript`（androidx.webkit 1.14 /
 * Chromium 83+）后，页面从第一行脚本开始的监听器全部经过合帧器。
 * 内核不支持时静默回退到 `onPageFinished`（脚本幂等，两条路径共存无害）。
 *
 * ## 三条安全约束
 * 1. **幂等**：`window.__dshCoalesce` 守卫，重复注入直接返回，不会层层包裹。
 * 2. **透传语义**：不改事件对象、不改 `this`、不改 `removeEventListener`
 *    配对关系（我们把 wrapper 存在原函数上，remove 时能反查回原函数）。
 * 3. **零额外观察器**：不用 IntersectionObserver / ResizeObserver，
 *    只包装已有的监听注册，空闲时零开销。
 *
 * ## Native 侧 in-flight 守卫
 * `evaluateJavascript` 是异步 IPC。如果快速导航（或恢复后重复注入）导致多次调用
 * 排队，回调会堆在一起、把注入完成日志刷乱。这里用 [gate] 做「最多一次在飞」：
 * 在飞期间再来的请求只置 pending，等在飞的回调落地后补一次 ——
 * 既不会丢注入，也不会堆积。
 */
object EventCoalesce {

    private const val TAG = "EventCoalesce"

    /** 不可取消、天然"只要最新值"的事件：总是合帧。 */
    private const val ALWAYS_EVENTS = "scroll,resize"

    /** 可取消事件：只有确定被动时才合帧（见类注释 ①）。 */
    private const val GATED_EVENTS = "touchmove,wheel,pointermove,mousemove"

    /** 最多一次在飞：0 = 空闲，1 = 有请求在飞。 */
    private val gate = AtomicBoolean(false)

    /** 在飞期间又有新请求：落地后补一次。 */
    private val pending = AtomicBoolean(false)

    /** 已通过 document-start 安装的实例（弱引用集合，不强持有 Activity 级对象）。 */
    private val armed = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<WebView, Boolean>(),
    )

    @Volatile private var installCount = 0

    private val ALWAYS_OBJ: String = ALWAYS_EVENTS.split(',').joinToString(",") { "\"$it\":1" }

    private val GATED_OBJ: String = GATED_EVENTS.split(',').joinToString(",") { "\"$it\":1" }

    private val SCRIPT: String = """
        (function(){
          try{
            if(window.__dshCoalesce) return 'skip';
            window.__dshCoalesce=1;
            // 跨域 iframe 不碰：那里是第三方页面，改写它的监听器语义不在我们的
            // 授权范围内（同域 iframe 照常处理，那属于 WebUI 自己的一部分）。
            if(window.top!==window.self){
              try{
                if(window.top.location.origin!==window.location.origin) return 'skip:cross-origin-frame';
              }catch(e){ return 'skip:cross-origin-frame'; }
            }
            var always={$ALWAYS_OBJ};
            var gated={$GATED_OBJ};
            // Chromium 的默认被动区域：这些目标上的 touchmove/wheel 即使调用方
            // 没写 passive，实际也是被动的（阻止滚动本来就不生效）。
            function defaultPassiveTarget(t){
              return t===window||t===document||t===document.body||t===document.documentElement;
            }
            function effectivelyPassive(t,type,opt){
              if(opt===true) return true;
              if(opt && typeof opt==='object' && opt.passive===true) return true;
              if(type==='touchmove'||type==='wheel') return defaultPassiveTarget(t);
              return false;
            }
            var origAdd=EventTarget.prototype.addEventListener;
            EventTarget.prototype.addEventListener=function(type,fn,opt){
              try{
                var coalesce = always[type]===1 || (gated[type]===1 && effectivelyPassive(this,type,opt));
                if(!coalesce || typeof fn!=='function' || fn.__dshCoalesced){
                  return origAdd.call(this,type,fn,opt);
                }
                var target=this, latest=null, scheduled=false;
                var wrapper=function(ev){
                  latest=ev;
                  if(scheduled) return;
                  scheduled=true;
                  requestAnimationFrame(function(){
                    scheduled=false;
                    var e=latest; latest=null;
                    try{ fn.call(target,e); }catch(err){}
                  });
                };
                // 让 removeEventListener 仍能成对移除（反查回原函数）
                wrapper.__dshCoalesced=1;
                try{ fn.__dshWrapped=wrapper; }catch(e){}
                return origAdd.call(this,type,wrapper,opt);
              }catch(err){
                return origAdd.call(this,type,fn,opt);
              }
            };
            var origRemove=EventTarget.prototype.removeEventListener;
            EventTarget.prototype.removeEventListener=function(type,fn,opt){
              try{
                if(fn && fn.__dshWrapped) return origRemove.call(this,type,fn.__dshWrapped,opt);
              }catch(err){}
              return origRemove.call(this,type,fn,opt);
            };
            return 'on';
          }catch(e){ return 'err:'+e.message; }
        })()
    """.trimIndent()

    /**
     * 在 WebView 建立后、**任何 loadUrl 之前**调用：把合帧器挂到 document-start。
     *
     * 返回是否挂上（false = 内核不支持 / 异常，调用方在 onPageFinished 走 [install]）。
     * 只对**引擎页面**有意义，但脚本本身对预览页也无害（只包监听器，不改内容）。
     */
    fun attach(view: WebView?): Boolean {
        if (view == null) return false
        if (armed.contains(view)) return true
        val supported = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }.getOrDefault(false)
        if (!supported) return false
        return runCatching {
            WebViewCompat.addDocumentStartJavaScript(view, SCRIPT, setOf("*"))
            armed.add(view)
            Log.i(TAG, "document-start armed")
            true
        }.onFailure {
            Log.w(TAG, "document-start arm failed: ${it.message} — falling back")
        }.getOrDefault(false)
    }

    /** 实例退役：解除登记。 */
    fun detach(view: WebView?) {
        if (view == null) return
        armed.remove(view)
    }

    /**
     * 页面加载完成后的兼容注入（幂等）。
     *
     * document-start 已挂上的实例直接返回 —— 那次注入已经覆盖了整页脚本，
     * 再来一次只是多花一次 IPC。
     *
     * 只对**引擎页面**注入：预览页是用户/AI 的第三方内容，不该被我们改写监听器。
     */
    fun install(view: WebView?) {
        if (view == null) return
        if (armed.contains(view)) return
        if (!gate.compareAndSet(false, true)) {
            // 有请求在飞：只置 pending，等落地后补一次，绝不堆积 IPC
            pending.set(true)
            return
        }
        runCatching {
            view.evaluateJavascript(SCRIPT) { raw ->
                installCount++
                Log.i(TAG, "coalesce install #$installCount -> $raw")
                gate.set(false)
                if (pending.compareAndSet(true, false)) {
                    // 期间有被合并掉的请求：补做一次（幂等，脚本自己会 skip）
                    install(view)
                }
            }
        }.onFailure {
            gate.set(false)
            Log.w(TAG, "install failed: ${it.message}")
        }
    }

    fun stats(): String = "coalesce installs=$installCount"
}
