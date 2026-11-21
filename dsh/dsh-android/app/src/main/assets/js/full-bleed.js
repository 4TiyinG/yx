/**
 * [dsh-android] 横屏桌面全幅铺满（v1.6.43）
 *
 * ## 为什么需要这个脚本
 * v1.6.42 为了「横屏不被灵动岛遮挡」，把 WebView 四边各缩了一圈（margin）。
 * 露出来的是 Activity 的 `windowBackground`，于是横屏变成「上下左右各一条空白/
 * 黑边」—— 用户看到的正是这个。根因是：**让位做在了容器外面**。
 *
 * 正确的做法是「容器满幅、让位做在页面里面」：
 *   · WebView 永远 match_parent（页面背景自己铺满，不会有窗口底色露出）；
 *   · 安全区以 CSS 变量 + 样式表的形式注入页面，只把「页面自己的顶栏/底栏」
 *     挪开，滚动内容仍然延伸到屏幕边缘（这才是 Edge-to-Edge 的沉浸感）。
 *
 * ## 同时要解决的三件事
 * 1. **固定最大宽度**：桌面 UI 常给主容器 `max-width: 1280px; margin: 0 auto`，
 *    在横屏窗口上就是「居中窄栏 + 左右大量空白」。这里把外壳级容器的
 *    `max-width` / 居中 margin / `aspect-ratio` 解开，让它吃满 100%。
 * 2. **只碰外壳**：扫描限定在「body 的浅层直系后代」里，且跳过
 *    `position: fixed/absolute`（弹窗、浮层、下拉菜单都在这一层，绝不能动）。
 * 3. **可完全撤销**：`on=false` 时样式表、root 属性、所有内联覆盖全部还原 ——
 *    竖屏必须与改动前逐像素一致（用户硬约束）。
 *
 * ## 配置（由 MainActivity 写入，注入前设置）
 * ```js
 * window.__dshFullBleed = {
 *   on: true,                 // 是否启用
 *   top: 0, left: 0, right: 0, bottom: 0,   // CSS px 安全区
 *   shell: true,              // 是否解除外壳窄化（v1.6.50；竖屏紧凑模式传 false）
 *   maxShellDepth: 3,         // 外壳扫描深度上限（body 起算）
 *   minFill: 0.96             // 宽度低于视口 96% 才认为"被窄化"
 * }
 * ```
 *
 * ## 完成值（v1.6.50 起带四边命中数）
 * ```
 * on freed=<解除窄化的容器数> safe=<让位命中总数> vw=<视口宽>
 *    top=<贴边顶栏命中数> bottom=<贴边底栏命中数> left=<...> right=<...>
 * ```
 * 调用方（MainActivity.applyTopOwnership）只关心 `top`：命中 0 说明页面没有
 * 可识别的贴边顶栏，这时**不能**让 WebView 顶到 y=0（标题会被状态栏压住），
 * 必须退回原生边距。这是"页面结构未知"和"零空白"之间的安全阀。
 * ```
 */
(function () {
  "use strict";

  var STYLE_ID = "dsh-fullbleed-style";
  var ATTR = "data-dsh-fullbleed";
  var MARK = "data-dsh-fullbleed-shell";
  var MARK_SAFE = "data-dsh-fullbleed-safe";

  var cfg = window.__dshFullBleed || {};
  var on = !!cfg.on;
  var top = +cfg.top || 0;
  var left = +cfg.left || 0;
  var right = +cfg.right || 0;
  var bottom = +cfg.bottom || 0;
  // shell=false：只做安全区让位，不碰外壳容器的 max-width / 居中 / 宽高比。
  // 手机竖屏的紧凑模式用它 —— 竖屏本来就没被窄化，解锁外壳只会引入排版抖动。
  var shell = cfg.shell !== false;
  var maxDepth = cfg.maxShellDepth == null ? 3 : Math.max(0, +cfg.maxShellDepth);
  var minFill = cfg.minFill == null ? 0.96 : +cfg.minFill;

  var root = document.documentElement;
  if (!root) return "no-root";

  function px(v) {
    return (Math.round(v * 100) / 100) + "px";
  }

  // ── 撤销：竖屏 / 关闭时必须逐项还原（零残留）────────────────────────
  function teardown() {
    root.removeAttribute(ATTR);
    var st = document.getElementById(STYLE_ID);
    if (st && st.parentNode) st.parentNode.removeChild(st);
    var marked = root.querySelectorAll("[" + MARK + "]");
    for (var i = 0; i < marked.length; i++) {
      var el = marked[i];
      el.removeAttribute(MARK);
      el.style.removeProperty("max-width");
      el.style.removeProperty("width");
      el.style.removeProperty("margin-left");
      el.style.removeProperty("margin-right");
      el.style.removeProperty("aspect-ratio");
      if (!el.getAttribute("style")) el.removeAttribute("style");
    }
    var safe = root.querySelectorAll("[" + MARK_SAFE + "]");
    for (var j = 0; j < safe.length; j++) {
      var s = safe[j];
      s.removeAttribute(MARK_SAFE);
      s.style.removeProperty("padding-top");
      s.style.removeProperty("padding-left");
      s.style.removeProperty("padding-right");
      s.style.removeProperty("padding-bottom");
      if (!s.getAttribute("style")) s.removeAttribute("style");
    }
    window.__dshFullBleedState = "off";
    return "off";
  }

  if (!on) {
    var r = teardown();
    // 安全区变量仍要写（页面可选地自己用），但样式表与容器覆盖必须撤掉
    writeSafeVars();
    return r;
  }

  // ── 安全区变量（页面可自行消费，保留既有契约）────────────────────────
  function writeSafeVars() {
    root.style.setProperty("--dsh-safe-top", px(top));
    root.style.setProperty("--dsh-safe-left", px(left));
    root.style.setProperty("--dsh-safe-right", px(right));
    root.style.setProperty("--dsh-safe-bottom", px(bottom));
  }

  // ── 样式表 ───────────────────────────────────────────────────────────
  // 只做"解除限制"，不主动设定具体布局：宽高由页面自己的 flex/grid 决定。
  // 每一条都带 html[ATTR] 前缀，关掉即全线失效。
  var css = [];
  if (shell) {
    // 根与 body：满幅、去默认边距、禁横向滚动（100vw 会带出横向溢出条）。
    // shell=false 时**整条不下发**：它会把页面的 margin/width 一并改掉，
    // 而"只让安全区"的调用方要的是「除了顶/底栏位置，别的都不变」。
    css.push(
      'html[' + ATTR + '],html[' + ATTR + '] body{' +
        'max-width:none!important;min-width:0!important;' +
        'margin:0!important;width:100%!important;' +
        'overflow-x:hidden!important;' +
        'box-sizing:border-box!important;}',
    );
  }
  css = css.concat([
    // 背景铺满：即使页面没设背景色，也不让窗口底色透出来
    'html[' + ATTR + ']{background-color:var(--dsh-bleed-bg,initial);}',
    // 外壳容器：解除固定最大宽度 / 居中 / 固定宽高比
    'html[' + ATTR + '] [' + MARK + ']{' +
      'max-width:none!important;width:100%!important;' +
      'margin-left:0!important;margin-right:0!important;' +
      'aspect-ratio:auto!important;}',
    // 安全区：只给页面自己的顶/底/侧栏让位（由脚本按 position 判定后打标记）
    // 用 var() 而不是把数值烘进样式表：insets 每次变化只需改 4 个变量，
    // 不必重扫 DOM / 重建样式表（见 FullBleed.updateInsets）。
    'html[' + ATTR + '] [' + MARK_SAFE + '="top"]{padding-top:var(--dsh-safe-top,0px)!important;}',
    'html[' + ATTR + '] [' + MARK_SAFE + '="bottom"]{padding-bottom:var(--dsh-safe-bottom,0px)!important;}',
    'html[' + ATTR + '] [' + MARK_SAFE + '="left"]{padding-left:var(--dsh-safe-left,0px)!important;}',
    'html[' + ATTR + '] [' + MARK_SAFE + '="right"]{padding-right:var(--dsh-safe-right,0px)!important;}',
    // 滚动容器：让"滚到底"时最后一行不被底部导航条压住（不改变布局尺寸）
    'html[' + ATTR + '] body{scroll-padding-top:var(--dsh-safe-top,0px);' +
      'scroll-padding-bottom:var(--dsh-safe-bottom,0px);}'
  ]).join("\n");

  root.setAttribute(ATTR, "1");
  writeSafeVars();
  var style = document.getElementById(STYLE_ID);
  if (!style) {
    style = document.createElement("style");
    style.id = STYLE_ID;
    (document.head || root).appendChild(style);
  }
  style.textContent = css;

  // ── 外壳容器解除窄化 ─────────────────────────────────────────────────
  // 判据（全部满足才动）：
  //   1. 在 body 起的 maxDepth 层以内；
  //   2. 自身不是 fixed/absolute（弹窗/浮层/下拉都不碰）；
  //   3. 计算宽 < 视口宽 × minFill（说明它被窄化了）；
  //   4. 有 max-width 限制，或左右 margin 都是 auto 且合计 > 0。
  // 满足就用内联样式覆盖，并打 MARK 以便 teardown 精确还原。
  var vw = root.clientWidth || window.innerWidth || 0;
  var freed = 0;

  function isShellCandidate(el) {
    var cs;
    try {
      cs = window.getComputedStyle(el);
    } catch (e) {
      return false;
    }
    if (!cs) return false;
    if (cs.position === "fixed" || cs.position === "absolute" || cs.position === "sticky") {
      return false;
    }
    // 明确是浮层的常见语义标签
    var role = el.getAttribute && el.getAttribute("role");
    if (role === "dialog" || role === "menu" || role === "tooltip") return false;
    if (el.hasAttribute && el.hasAttribute("aria-modal")) return false;

    var w = el.getBoundingClientRect ? el.getBoundingClientRect().width : 0;
    if (!vw || w >= vw * minFill) return false;

    var capped = cs.maxWidth && cs.maxWidth !== "none";
    var centered =
      (cs.marginLeft === "auto" || parseFloat(cs.marginLeft) > 0) &&
      (cs.marginRight === "auto" || parseFloat(cs.marginRight) > 0);
    return capped || centered;
  }

  function clearCap(el) {
    el.style.setProperty("max-width", "none", "important");
    el.style.setProperty("width", "100%", "important");
    el.style.setProperty("margin-left", "0", "important");
    el.style.setProperty("margin-right", "0", "important");
    el.setAttribute(MARK, "1");
    freed++;
  }

  function scan(depth, node) {
    if (depth > maxDepth || !node || !node.children) return;
    for (var i = 0; i < node.children.length; i++) {
      var el = node.children[i];
      var tag = (el.tagName || "").toLowerCase();
      if (tag === "script" || tag === "style" || tag === "link" || tag === "template") continue;
      if (isShellCandidate(el)) clearCap(el);
      scan(depth + 1, el);
    }
  }

  if (shell) {
    try {
      if (document.body) scan(0, document.body);
    } catch (e) {
      /* 扫描失败不影响 CSS 与安全区，页面仍可用 */
    }
  }

  // ── 顶/底/侧栏让开安全区（沉浸但不遮挡控件）──────────────────────────
  // 判据：fixed/sticky 且贴边（top<=1 / bottom<=1 / left<=1 / right<=1），
  // 宽度覆盖了 >=60% 视口（排除小浮动按钮）。命中则打对应 MARK_SAFE。
  var safeHit = 0;
  // 逐边计数（v1.6.50）：调用方要知道"顶部到底有没有让成功"，
  // 只有一个总数是判不出来的。
  var edgeHit = { top: 0, bottom: 0, left: 0, right: 0 };

  function markSafeBars() {
    if (!document.body) return;
    var all = document.body.querySelectorAll("*");
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      var cs;
      try {
        cs = window.getComputedStyle(el);
      } catch (e) {
        continue;
      }
      if (!cs) continue;
      if (cs.position !== "fixed" && cs.position !== "sticky") continue;
      var r = el.getBoundingClientRect();
      if (!r || r.width < vw * 0.6) continue;

      var which = null;
      if (r.top <= 1 && top > 0) which = "top";
      else if (bottom > 0 && r.bottom >= (window.innerHeight || 0) - 1) which = "bottom";
      else if (left > 0 && r.left <= 1) which = "left";
      else if (right > 0 && r.right >= vw - 1) which = "right";
      if (!which) continue;
      el.setAttribute(MARK_SAFE, which);
      safeHit++;
      edgeHit[which] = (edgeHit[which] || 0) + 1;
    }
  }

  try {
    markSafeBars();
  } catch (e) {
    /* 同上：失败只是少了让位，不会造成空白 */
  }

  window.__dshFullBleedState = "on";
  return "on freed=" + freed + " safe=" + safeHit + " vw=" + vw +
    " top=" + edgeHit.top + " bottom=" + edgeHit.bottom +
    " left=" + edgeHit.left + " right=" + edgeHit.right;
})();
