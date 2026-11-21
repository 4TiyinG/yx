/*!
 * DSH RenderTune v1 (v1.6.46)
 * ---------------------------------------------------------------------------
 * 目标：把 WebUI 动画的**帧时间**压下来，同时**不引入任何视觉变化**。
 *
 * 设计前提（也是本文件所有取舍的理由）：
 *   1. 前端代码不在本仓库里，我们不能改它的动画写法；能做的只有三件事：
 *      提前把「要动的东西」提升为合成层、把「不该在滚动时干的活」挪走、
 *      以及把「测量」这件事从滚动路径上挪开。
 *   2. 任何**全局**性质的 CSS（`*{will-change:...}`、`transform:translateZ(0)`、
 *      给整个 body 加 `contain`）都是负优化：合成层数量与显存成正比，
 *      一多就掉帧、一满就崩溃。所以本文件的样式规则全部是**显式类名**，
 *      只由我们自己在「有明确意图」的时刻挂上去，并且有时长上限。
 *   3. 幂等：document-start 与 onPageFinished 两条注入路径都会执行本文件，
 *      重复执行必须只留一份状态（`window.__dshRenderTune` 守卫）。
 *
 * 四块能力：
 *   A. 交互意图提升（有预算、限时）—— 按下时给「可能要动」的那个元素挂
 *      `will-change:transform,opacity`，让按压/点击反馈从第一帧就在合成器上跑；
 *      抬手指后 120ms（或硬上限 800ms）撤掉，绝不常驻。
 *   B. 长列表离屏跳过（仅「流畅优先」档，且需要 Native 显式打开）——
 *      对**已经渲染过一次**的行记录真实高度，再用 `content-visibility:auto`
 *      让它们滚出视口后跳过样式/布局/绘制。先量后标，避免滚动条跳动。
 *   C. 读写分批—— B 的测量严格遵循「先统一读、再统一写」，不制造强制同步布局。
 *   D. 观测（仅 debug）—— longtask / layout-shift / 慢事件计数，供原生侧回收，
 *      用来**验证**优化是否真的生效（而不是靠感觉）。
 *
 * 刻意**不做**的事：
 *   · 不给滚动容器常驻 `will-change`（一整屏高度的纹理，收益小、显存贵）；
 *   · 不用 `transform:translateZ(0)` 强制提升（不可撤销、层数失控）；
 *   · 不监听 scroll 做任何样式写入（那才是滚动掉帧的经典来源）；
 *   · 不改写 innerHTML、不引第三方库、不轮询。
 */
(function () {
  "use strict";
  try {
    var W = window;

    // iframe 不处理：那里的内容通常是第三方页面，改了可能反而不对。
    // 这条必须在幂等守卫**之前**：iframe 与父页面是两个 window，本来不吃守卫，
    // 但把顺序固定下来可以让"为什么这段跑在子帧"这类问题少一个变量。
    if (W.top !== W.self) return "skip:iframe";

    // 幂等守卫：二次注入只回版本号，不重复挂监听/样式
    if (W.__dshRenderTune && W.__dshRenderTune.version) return W.__dshRenderTune.version;

    var VERSION = "1";
    var doc = document;
    var root = doc.documentElement;

    // 预算：同时最多 3 个提升元素。列表项按下时通常只有 1 个在动。
    var PROMOTE_BUDGET = 3;
    // 抬手指后再保留的时长：覆盖按压动画的尾巴（典型 150~250ms）
    var PROMOTE_TAIL_MS = 120;
    // 硬上限：即使指针一直按着，也不会无限期占着一个合成层
    var PROMOTE_MAX_MS = 800;
    // 长列表门槛：子元素少于这个数，跳过它的收益抵不上观察成本
    var BOOST_MIN_ROWS = 30;
    // 最多标记多少行：超过就停手（防层爆炸/防长时间遍历）
    var BOOST_MAX_ROWS = 800;
    // 扫描节点上限（一次性、空闲时、分帧）
    var SCAN_MAX_NODES = 4000;

    var STYLE_ID = "__dsh_render_tune__";

    /* ── 样式层 ──────────────────────────────────────────────────────────
     * 只有两条规则，都靠类名显式启用：
     *   .dsh-rt-hot → 交互期间临时提升（由 A 挂/撤）
     *   .dsh-rt-cv  → 离屏跳过（由 B 挂/撤）
     * 不含任何后代选择器、不含 `*`，命中成本 O(1)。
     */
    function ensureStyle() {
      if (doc.getElementById(STYLE_ID)) return true;
      // document-start 时 <head>/<body> 还没被解析出来。此时**不能**把 <style>
      // 硬塞给 <html>（解析器随后插入 <head> 时，它会留在 head 之前的位置，
      // 树结构变得不可预测）。样式只在第一次挂类名时才有用，而那时 DOM 一定好了。
      var host = doc.head || doc.body;
      if (!host) return false;
      var s = doc.createElement("style");
      s.id = STYLE_ID;
      s.textContent =
        ".dsh-rt-hot{will-change:transform,opacity}" +
        ".dsh-rt-cv{content-visibility:auto}";
      host.appendChild(s);
      return true;
    }

    /* ── A. 交互意图提升 ──────────────────────────────────────────────── */
    var hot = []; // [{el, prev, timer}]

    function releaseRecord(rec) {
      if (rec.timer) {
        clearTimeout(rec.timer);
        rec.timer = 0;
      }
      var i = hot.indexOf(rec);
      if (i >= 0) hot.splice(i, 1);
      try {
        if (rec.prev) rec.el.style.willChange = rec.prev;
        else rec.el.style.removeProperty("will-change");
        rec.el.classList.remove("dsh-rt-hot");
      } catch (e) {}
    }

    function releaseAt(i) {
      var rec = hot[i];
      if (rec) releaseRecord(rec);
    }

    function scheduleRelease(rec, ms) {
      if (rec.timer) clearTimeout(rec.timer);
      rec.timer = setTimeout(function () {
        rec.timer = 0;
        releaseRecord(rec);
      }, ms);
    }

    /** 提升一个元素（预算满时先释放最老的）。只写 style，不读布局。 */
    function promote(el) {
      if (!el || el.nodeType !== 1) return;
      if (el === doc.body || el === root) return;
      for (var i = 0; i < hot.length; i++) {
        if (hot[i].el === el) {
          scheduleRelease(hot[i], PROMOTE_TAIL_MS);
          return;
        }
      }
      while (hot.length >= PROMOTE_BUDGET) releaseAt(0);
      ensureStyle();
      var prev = "";
      try {
        prev = el.style.willChange || "";
        el.style.willChange = "transform,opacity";
        el.classList.add("dsh-rt-hot");
      } catch (e) {
        return;
      }
      var rec = { el: el, prev: prev, timer: 0 };
      hot.push(rec);
      scheduleRelease(rec, PROMOTE_MAX_MS);
    }

    /**
     * 按下时的「最可能要动」的那个元素。
     * 用 closest 而不是给整条祖先链提升：一次点击只多 1 个层。
     */
    var INTENT_SELECTOR =
      "button,[role=button],a,[class*=btn],[class*=card],[class*=item]," +
      "[class*=tab],[class*=fab],[class*=switch],[class*=cell],[class*=row]";

    function onDown(ev) {
      var t = ev.target;
      if (!t || t.nodeType !== 1) return;
      var el = null;
      try {
        el = t.closest ? t.closest(INTENT_SELECTOR) : null;
      } catch (e) {}
      promote(el || t);
    }

    function onUp() {
      // 抬手指：按压动画还有一小段尾巴，120ms 后撤层
      for (var i = hot.length - 1; i >= 0; i--) {
        if (hot[i].timer) scheduleRelease(hot[i], PROMOTE_TAIL_MS);
      }
    }

    /* ── B. 长列表离屏跳过（opt-in） ──────────────────────────────────── */
    var boost = {
      on: false,
      lists: [],
      io: null,
      mo: null,
      marked: 0,
      scanTimer: 0,
      rescanTimer: 0,
      warned: false
    };

    function supportsCV() {
      try {
        return !!(W.CSS && CSS.supports && CSS.supports("content-visibility", "auto"));
      } catch (e) {
        return false;
      }
    }

    /** 一次性、限量的候选扫描：只做 childElementCount（纯 JS，不读布局）。 */
    function findCandidateLists() {
      var body = doc.body;
      if (!body) return [];
      var stack = [body];
      var seen = 0;
      var cand = [];
      while (stack.length && seen < SCAN_MAX_NODES) {
        var el = stack.pop();
        seen++;
        var n = el.childElementCount;
        // body / html 本身不当容器：它们是整页，标记子节点等于给应用外壳加
        // content-visibility，收益小、风险大（固定定位浮层）
        if (n >= BOOST_MIN_ROWS && el !== body && el !== root) cand.push(el);
        // 已经够“宽”的不再往下钻：它的子节点就是行，不是列表
        if (n < BOOST_MIN_ROWS) {
          for (var i = el.children.length - 1; i >= 0; i--) stack.push(el.children[i]);
        }
      }
      cand.sort(function (a, b) {
        return b.childElementCount - a.childElementCount;
      });
      return cand.slice(0, 2);
    }

    /** 给一批行记录真实高度，再挂 content-visibility（先读后写，同一帧内）。 */
    function markRows(rows) {
      if (!rows.length) return;
      var todo = [];
      for (var i = 0; i < rows.length; i++) {
        var el = rows[i];
        if (!el || el.nodeType !== 1) continue;
        if (el.classList.contains("dsh-rt-cv")) continue;
        if (boost.marked >= BOOST_MAX_ROWS) break;
        // 焦点在里面就不动它（输入框/可编辑区被跳过会丢焦点高亮）
        try {
          if (doc.activeElement && el.contains(doc.activeElement)) continue;
        } catch (e) {}
        todo.push(el);
      }
      if (!todo.length) return;
      requestAnimationFrame(function () {
        // ① 统一读（这一帧只读，不写，避免强制同步布局交替发生）
        var hs = new Array(todo.length);
        for (var j = 0; j < todo.length; j++) {
          try {
            hs[j] = todo[j].getBoundingClientRect().height;
          } catch (e) {
            hs[j] = 0;
          }
        }
        // ② 统一写
        for (var k = 0; k < todo.length; k++) {
          var el2 = todo[k];
          if (!hs[k] || hs[k] < 8) continue;
          try {
            // auto + 实测高度：滚出视口后按**记忆尺寸**占位，滚动条不跳
            el2.style.containIntrinsicSize = "auto " + Math.round(hs[k]) + "px";
            el2.classList.add("dsh-rt-cv");
            boost.marked++;
          } catch (e) {}
        }
        if (boost.marked >= BOOST_MAX_ROWS && !boost.warned) {
          boost.warned = true;
          if (W.console && console.info) {
            console.info("[dsh-render-tune] row budget reached: " + boost.marked);
          }
        }
      });
    }

    /** 把当前所有子节点纳入观察。可重复调用（流式追加后补观察新行）。 */
    function observeRows(list) {
      if (!boost.io) return;
      var rows = list.children;
      for (var i = 0; i < rows.length; i++) {
        try {
          boost.io.observe(rows[i]);
        } catch (e) {}
      }
    }

    function watchList(list) {
      observeRows(list);
      if (list.__dshRtWatched) return;
      list.__dshRtWatched = 1;
      // 只观察容器本身的一层子节点变化：聊天流式追加时增量补观察，
      // 不做 subtree 观察（那是每帧都产生记录的高频回调）。
      try {
        if (!boost.mo) {
          boost.mo = new MutationObserver(function () {
            if (boost.rescanTimer) return;
            boost.rescanTimer = setTimeout(function () {
              boost.rescanTimer = 0;
              if (!boost.on) return;
              for (var k = 0; k < boost.lists.length; k++) observeRows(boost.lists[k]);
            }, 600);
          });
        }
        boost.mo.observe(list, { childList: true });
      } catch (e) {}
    }

    function scanNow() {
      var lists = findCandidateLists();
      if (!lists.length) return;
      boost.lists = lists;
      for (var i = 0; i < lists.length; i++) watchList(lists[i]);
    }

    function armBoost() {
      if (boost.on) return "already";
      if (W.top !== W.self) return "skip:iframe";
      if (!supportsCV()) return "unsupported";
      if (!W.IntersectionObserver) return "unsupported:io";
      ensureStyle();
      boost.on = true;
      try {
        boost.io = new IntersectionObserver(
          function (entries) {
            var hit = [];
            for (var i = 0; i < entries.length; i++) {
              if (entries[i].isIntersecting) hit.push(entries[i].target);
            }
            if (hit.length) markRows(hit);
          },
          // 提前 2 屏开始「预量」：等真正滚到眼前时尺寸已经记好了
          { root: null, rootMargin: "200% 0px", threshold: 0 }
        );
      } catch (e) {
        boost.on = false;
        return "err:io";
      }
      // 空闲时再扫：不占首屏窗口
      boost.scanTimer = setTimeout(function () {
        boost.scanTimer = 0;
        if (boost.on) scanNow();
      }, 800);
      return "on";
    }

    function unboost() {
      if (!boost.on) return "already";
      boost.on = false;
      if (boost.scanTimer) {
        clearTimeout(boost.scanTimer);
        boost.scanTimer = 0;
      }
      if (boost.rescanTimer) {
        clearTimeout(boost.rescanTimer);
        boost.rescanTimer = 0;
      }
      try {
        if (boost.io) boost.io.disconnect();
      } catch (e) {}
      try {
        if (boost.mo) boost.mo.disconnect();
      } catch (e) {}
      boost.io = null;
      boost.mo = null;
      // 撤掉所有标记：把行恢复成普通渲染（O(marked)，一次性的）
      try {
        var markedEls = doc.querySelectorAll(".dsh-rt-cv");
        for (var i = 0; i < markedEls.length; i++) {
          markedEls[i].classList.remove("dsh-rt-cv");
          markedEls[i].style.removeProperty("contain-intrinsic-size");
        }
      } catch (e) {}
      for (var j = 0; j < boost.lists.length; j++) {
        try {
          boost.lists[j].__dshRtWatched = 0;
        } catch (e) {}
      }
      boost.lists = [];
      boost.marked = 0;
      boost.warned = false;
      return "off";
    }

    /* ── D. 观测（只统计，不干预） ────────────────────────────────────── */
    var stats = {
      on: false,
      longTasks: 0,
      longMax: 0,
      longTotal: 0,
      slowEvents: 0,
      cls: 0
    };

    function startStats() {
      if (stats.on) return "already";
      stats.on = true;
      try {
        if (!W.PerformanceObserver) return "unsupported";
        var poLong = new PerformanceObserver(function (list) {
          var es = list.getEntries();
          for (var i = 0; i < es.length; i++) {
            var d = es[i].duration || 0;
            stats.longTasks++;
            stats.longTotal += d;
            if (d > stats.longMax) stats.longMax = d;
          }
        });
        // buffered: 把注册之前已经发生的长任务也收进来（页面早期最值钱）
        poLong.observe({ type: "longtask", buffered: true });
      } catch (e) {}
      try {
        var poCls = new PerformanceObserver(function (list) {
          var es = list.getEntries();
          for (var i = 0; i < es.length; i++) {
            if (!es[i].hadRecentInput) stats.cls += es[i].value || 0;
          }
        });
        poCls.observe({ type: "layout-shift", buffered: true });
      } catch (e) {}
      try {
        var poEv = new PerformanceObserver(function (list) {
          stats.slowEvents += list.getEntries().length;
        });
        poEv.observe({ type: "event", durationThreshold: 48, buffered: true });
      } catch (e) {}
      return "on";
    }

    /* ── 装配 ─────────────────────────────────────────────────────────── */
    // document-start 阶段 DOM 未成形：能挂样式就挂，挂不上就等 DOM 就绪。
    // 样式只在第一次挂类名时才有用，而那时 DOM 一定已经好了。
    if (!ensureStyle()) {
      doc.addEventListener("DOMContentLoaded", ensureStyle, { once: true });
    }

    // capture + passive：不阻断页面自己的监听，也不进滚动关键路径
    var opts = { capture: true, passive: true };
    try {
      doc.addEventListener("pointerdown", onDown, opts);
      doc.addEventListener("touchstart", onDown, opts);
      doc.addEventListener("pointerup", onUp, opts);
      doc.addEventListener("pointercancel", onUp, opts);
    } catch (e) {}

    W.__dshRenderTune = {
      version: VERSION,
      promote: promote,
      boost: armBoost,
      unboost: unboost,
      startStats: startStats,
      stats: function () {
        return JSON.stringify({
          v: VERSION,
          boost: boost.on,
          marked: boost.marked,
          hot: hot.length,
          longTasks: stats.longTasks,
          longMaxMs: Math.round(stats.longMax),
          longTotalMs: Math.round(stats.longTotal),
          slowEvents: stats.slowEvents,
          cls: Math.round(stats.cls * 1000) / 1000
        });
      }
    };

    return VERSION;
  } catch (e) {
    return "err:" + (e && e.message);
  }
})();
