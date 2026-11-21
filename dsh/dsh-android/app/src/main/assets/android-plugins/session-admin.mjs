/**
 * [dsh-android] 会话管理热通道（v1.6.28）。
 *
 * ## 为什么需要这个插件
 *
 * 「删除对话」原本必须**停引擎 → 删文件 → 改 workspace.json → 重启引擎**。
 * 原因写在 `ChatWiper.kt` 的类注释里：`storages/workspace.json` 是引擎的**独占
 * 状态文件**，启动时读一次进内存，之后整份写回，既不监视文件也不比对时间戳。
 * 于是外部改完这个文件，引擎下一次任何变更都会用**旧内存状态**把改动抹掉。
 *
 * 结果就是用户每次删几个对话都要等引擎重启（十几秒），而且重启期间 WebUI 断连。
 *
 * ## 本插件的作用：让「删除」发生在引擎进程内部
 *
 * 插件跑在引擎自己的 Cordis 运行时里，因此它可以直接改**内存里那份注册表**，
 * 并触发引擎自己的工作区服务写回 —— 这样引擎内存与磁盘同时干净，**进程不用重启**，
 * WebUI 也就自然拿到新状态。
 *
 * ## 为什么用「文件通道」而不是注册 HTTP 路由
 *
 * 引擎的 webServer 路由注册 API 属于上游私有实现，版本间可能变。文件通道只用
 * `node:fs`，没有 API 依赖，插件加载失败也只是"通道不可用"，绝不会影响引擎启动
 * （Cordis 会隔离失败插件，见 plugin-health.mjs 的说明）。
 *
 * 协议（目录 `$DSH_HOME/.android-session-admin/`）：
 *   Android 写 `cmd-<id>.json`  →  本插件执行  →  插件写 `res-<id>.json`  →  Android 读
 *
 * ## 为什么是「反射 + 白名单 + 兜底教据」
 *
 * 我拿不到引擎源码，无法确定 `workspaceRegistry` 服务的方法签名。所以采取三段式：
 *   ① 白名单方法调用：只调名字明确表达「删除会话」的方法（带 try/catch）；
 *   ② 直接操作内存状态对象：在服务对象图里找 `{tables:{workspaces:…}}` 形状的
 *      对象，就地 splice 掉 id，再尝试调用名字像「保存/刷新」的方法；
 *   ③ 两者都失败 → 如实回报 `unsupported`，Android 侧回退到「停引擎」路径。
 *
 * 每次执行都回报用了哪条路、以及**执行后内存里还剩几条目标 id**（presentAfter），
 * 让"看起来成功其实没生效"这种情况能被看见，而不是让用户对着幽灵会话发呆。
 *
 * ## 安全边界
 * · 所有入口 try/catch，定时器里绝不抛异常（未捕获异常会带崩引擎进程）；
 * · 只处理 `.android-session-admin/` 下的文件，不碰别的目录；
 * · 只删/只改 id 引用，不改工作区条目、不改 defaultWorkspaceId；
 * · 定时器 `unref()`，插件被卸载或进程退出时不会挂住事件循环。
 *
 * @module dsh-android-session-admin
 */

import fs from "node:fs";
import path from "node:path";

const name = "android-session-admin";

/**
 * 不声明强依赖：任一服务缺失时插件仍应加载（只是通道能力降级）。
 * Cordis 的 `inject` 若写成必选，服务缺失会让本插件整条 entry 失败 —— 那会把
 * 「能不能免重启删对话」变成「插件加载成功与否」，不值得。
 */
const inject = [];

/** 轮询间隔：400ms。一次 tick 只有一次 `readdirSync`，可忽略不计。 */
const POLL_MS = 400;

/** 结果文件保留时长：超过就删，避免 android 侧异常退出后留一堆垃圾。 */
const RESULT_KEEP_MS = 5 * 60 * 1000;

/** 命令文件最大存活时长：超过且没有结果说明 Android 侧已放弃。 */
const CMD_STALE_MS = 60 * 1000;

/** 状态对象图搜索上限（防止在大对象图上跑太久）。 */
const MAX_NODES = 4000;
const MAX_DEPTH = 5;

/** 可能承载工作区注册表的服务名。按顺序尝试，全都可以缺失。 */
const SERVICE_NAMES = [
  "workspaceRegistry",
  "workspace",
  "workspaceController",
  "storageDomain",
  "storage",
  "sessionPersistence",
];

/** 白名单：只调用名字明确表示「删除/摘掉会话」的方法。 */
const DELETE_METHOD = /^(delete|remove|drop|detach|forget|archive)(Session|Sessions|Conversation|Conversations)$/;

/** 写回/广播：名字像这些的无参方法，在内存改完后调一下让引擎自己落盘。 */
const FLUSH_METHOD = /^(save|saveNow|flush|flushNow|persist|commit|write|writeNow|sync|update|touch|notify|emit|emitChange|broadcast|refresh|invalidate|markDirty)$/i;

function safeGet(ctx, service) {
  try {
    return ctx.get(service);
  } catch {
    return void 0;
  }
}

function homeDir() {
  const home = process.env.DSH_HOME || "";
  return home ? path.join(home, ".android-session-admin") : "";
}

function ensureDir(dir) {
  try {
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
    return true;
  } catch {
    return false;
  }
}

/** 原子写：临时文件 + rename，读侧永远看不到半截 JSON。 */
function writeJson(file, value) {
  try {
    const temp = `${file}.tmp-${process.pid}`;
    fs.writeFileSync(temp, JSON.stringify(value) + "\n", { mode: 0o644 });
    try { fs.chmodSync(temp, 0o644); } catch {}
    fs.renameSync(temp, file);
    return true;
  } catch (error) {
    console.error(`[android-session-admin] write ${path.basename(file)} failed: ${error?.message || error}`);
    return false;
  }
}

// ── 状态对象图搜索 ──────────────────────────────────────────────────────

/**
 * 判断一个对象是否是「工作区注册表」的内存形态。
 *
 * 判据来自 `workspace.json` 的结构（Android 侧 ChatWiper/WorkspaceRegistryHealer
 * 都按这个结构读写）：`{ tables: { workspaces: { <wid>: { sessionIds: [] } } }, global: {...} }`。
 */
function isRegistry(node) {
  return !!(
    node &&
    typeof node === "object" &&
    node.tables &&
    typeof node.tables === "object" &&
    node.tables.workspaces &&
    typeof node.tables.workspaces === "object"
  );
}

/**
 * 在若干服务对象里做有界 BFS，找出所有「注册表形状」的对象。
 *
 * @returns {{nodes: object[], methods: Record<string, string[]>, visited: number}}
 */
function findRegistries(roots) {
  const nodes = [];
  const methods = {};
  const seen = new Set();
  let visited = 0;
  const queue = roots.map(({ service, node }) => ({ node, depth: 0, from: service }));
  while (queue.length > 0 && visited < MAX_NODES) {
    const { node, depth, from } = queue.shift();
    if (!node || (typeof node !== "object" && typeof node !== "function")) continue;
    if (seen.has(node)) continue;
    seen.add(node);
    visited++;
    if (isRegistry(node)) nodes.push(node);
    if (depth >= MAX_DEPTH) continue;

    const keys = (() => {
      try { return Object.keys(node); } catch { return []; }
    })();
    // 记录服务对象上的方法名，供诊断（"引擎到底提供了什么"）。
    if (depth === 0) {
      methods[from] = keys.filter((key) => {
        try { return typeof node[key] === "function"; } catch { return false; }
      });
    }
    for (const key of keys) {
      if (key === "parent" || key === "ctx" || key === "root" || key === "scope") continue;
      if (key.startsWith("_")) continue;
      let value;
      try { value = node[key]; } catch { continue; }
      if (!value || typeof value !== "object") continue;
      if (Array.isArray(value)) {
        // 数组只走一层（sessionIds 之类本身就是字符串数组，不需要深入）
        if (depth + 1 <= MAX_DEPTH) queue.push({ node: value, depth: depth + 1, from: key });
        continue;
      }
      queue.push({ node: value, depth: depth + 1, from: key });
    }
  }
  return { nodes, methods, visited };
}

/**
 * 遍历 workspaces 容器，兼容「普通对象」与「Map」两种形态。
 *
 * 引擎内部很可能用 Map 存工作区（`storages` 类服务常见）。只按 `Object.keys`
 * 遍历会把 Map 当成空对象 —— 于是"看不见 id"被误判成"没有 id"，删除会假成功。
 */
function forEachWorkspace(tables, visit) {
  if (!tables || typeof tables !== "object") return;
  if (tables instanceof Map) {
    for (const [wid, ws] of tables) visit(String(wid), ws);
    return;
  }
  for (const wid of Object.keys(tables)) visit(wid, tables[wid]);
}

/** 统计目标 id 还出现在多少个 sessionIds / 归档表里（结构遍历）。 */
function countPresence(nodes, ids) {
  const wanted = new Set(ids);
  let hit = 0;
  for (const node of nodes) {
    forEachWorkspace(node?.tables?.workspaces, (_wid, ws) => {
      const list = ws?.sessionIds;
      if (!Array.isArray(list)) return;
      for (const id of list) if (wanted.has(String(id))) hit++;
    });
    const global = node?.global;
    if (global && typeof global === "object") {
      for (const key of ["archivedSessionIds", "pinnedSessionIds"]) {
        const list = global[key];
        if (!Array.isArray(list)) continue;
        for (const id of list) if (wanted.has(String(id))) hit++;
      }
    }
  }
  return hit;
}

/**
 * 「原始串」复核：把注册表对象序列化后直接找 id 字面量。
 *
 * 为什么需要这层：结构遍历只认我猜到的形状。若引擎换了容器（Map / 自定义类 /
 * getter），结构遍历会看不见 id，于是"看不见"被误读成"已删除" —— 用户会以为删
 * 成功了，实际一条没删。这一层不依赖形状，只看 id 字面量还在不在序列化结果里，
 * 是"确实没了"最硬的证据。
 *
 * @returns {number} 命中次数；-1 表示无法判定（序列化失败 / 过大）
 */
function rawPresence(nodes, ids) {
  if (ids.length === 0) return 0;
  let hit = 0;
  for (const node of nodes) {
    let text;
    try {
      text = JSON.stringify(node);
    } catch {
      return -1;
    }
    if (typeof text !== "string") return -1;
    // 大注册表 × 多 id 的扫描可能拖慢引擎事件循环，超限就放弃这层判定
    if (text.length > 2_000_000 || ids.length > 500) return -1;
    for (const id of ids) {
      // 必须是 JSON 字符串 token（带引号），否则 "b" 会命中 "tables"、"p" 会命中
      // "pinnedSessionIds" —— 假失败比假成功好，但假失败会让用户被无谓地重启引擎。
      if (text.includes(`"${id}"`)) hit++;
    }
  }
  return hit;
}

/** 就地摘掉 id，返回改动条数。**只动 sessionId 引用，不动工作区条目本身。** */
function pruneNode(node, ids) {
  const wanted = new Set(ids);
  let changed = 0;
  forEachWorkspace(node?.tables?.workspaces, (_wid, ws) => {
    if (!ws || !Array.isArray(ws.sessionIds)) return;
    const next = ws.sessionIds.filter((id) => !wanted.has(String(id)));
    if (next.length !== ws.sessionIds.length) {
      changed += ws.sessionIds.length - next.length;
      ws.sessionIds = next;
    }
  });
  const global = node?.global;
  if (global && typeof global === "object") {
    for (const key of ["archivedSessionIds", "pinnedSessionIds"]) {
      const list = global[key];
      if (!Array.isArray(list)) continue;
      const next = list.filter((id) => !wanted.has(String(id)));
      if (next.length !== list.length) {
        changed += list.length - next.length;
        global[key] = next;
      }
    }
  }
  return changed;
}

/** 在给定对象上调用名字匹配 FLUSH_METHOD 的无参方法（全 try/catch）。 */
function tryFlush(targets) {
  const tried = [];
  for (const { label, object } of targets) {
    if (!object || (typeof object !== "object" && typeof object !== "function")) continue;
    let keys = [];
    try { keys = Object.keys(object); } catch { continue; }
    for (const key of keys) {
      if (!FLUSH_METHOD.test(key)) continue;
      let fn;
      try { fn = object[key]; } catch { continue; }
      if (typeof fn !== "function") continue;
      try {
        fn.call(object);
        tried.push(`${label}.${key}`);
      } catch (error) {
        tried.push(`${label}.${key}!${error?.message || "error"}`);
      }
    }
  }
  return tried;
}

// ── 命令执行 ────────────────────────────────────────────────────────────

function execute(ctx, cmd) {
  const started = Date.now();
  const op = String(cmd?.op || "");
  const ids = Array.isArray(cmd?.ids) ? cmd.ids.map(String).filter(Boolean) : [];
  const roots = [];
  const found = {};
  for (const serviceName of SERVICE_NAMES) {
    const service = safeGet(ctx, serviceName);
    if (service === void 0) continue;
    roots.push({ service: serviceName, node: service });
    found[serviceName] = true;
  }
  const scan = findRegistries(roots);
  const base = {
    id: String(cmd?.id || ""),
    op,
    requested: ids.length,
    ms: 0,
    services: Object.keys(found),
    methods: scan.methods,
    registryNodes: scan.nodes.length,
    visited: scan.visited,
    strategy: "",
    removed: 0,
    presentBefore: countPresence(scan.nodes, ids),
    presentAfter: 0,
    rawBefore: rawPresence(scan.nodes, ids),
    rawAfter: 0,
    flush: [],
    note: "",
  };

  if (op === "probe") {
    return {
      ...base,
      ok: scan.nodes.length > 0,
      presentAfter: base.presentBefore,
      strategy: "probe",
      ms: Date.now() - started,
    };
  }
  if (op !== "delete") {
    return { ...base, ok: false, note: `unknown op ${op}`, ms: Date.now() - started };
  }
  if (ids.length === 0) {
    return { ...base, ok: true, strategy: "noop", ms: Date.now() - started };
  }

  // ① 白名单方法：让引擎自己的服务去改（最安全，语义最准）
  let removedByMethod = 0;
  const methodNames = [];
  for (const serviceName of Object.keys(found)) {
    const service = safeGet(ctx, serviceName);
    if (!service) continue;
    let keys = [];
    try { keys = Object.keys(service); } catch { continue; }
    for (const key of keys) {
      if (!DELETE_METHOD.test(key)) continue;
      let fn;
      try { fn = service[key]; } catch { continue; }
      if (typeof fn !== "function") continue;
      methodNames.push(`${serviceName}.${key}`);
      for (const id of ids) {
        for (const args of [[id], [[id]], [{ sessionId: id }]]) {
          try {
            const r = fn.apply(service, args);
            // 异步方法无法在同步流程里等结果，交给后面的 presentAfter 复核
            if (r && typeof r.then === "function") r.catch(() => {});
            removedByMethod++;
            break;
          } catch { /* 换一种入参形态再试 */ }
        }
      }
    }
  }
  let afterMethod = countPresence(findRegistries(roots).nodes, ids);
  let rawAfterMethod = rawPresence(findRegistries(roots).nodes, ids);
  // 只有「结构遍历看不见」**且**「原始串也找不到」才敢认定删掉了。
  // rawAfterMethod === -1 表示无法判定，此时退回结构遍历的判断。
  if (afterMethod === 0 && rawAfterMethod <= 0) {
    return {
      ...base,
      ok: scan.nodes.length > 0,
      strategy: methodNames.length > 0
        ? `registry-method(${methodNames.join(",")})`
        : "already-absent",
      removed: ids.length,
      presentAfter: 0,
      rawAfter: rawAfterMethod,
      note: scan.nodes.length === 0 ? "no workspace-registry-shaped object reachable" : "",
      ms: Date.now() - started,
    };
  }

  // ② 直接改内存状态对象，然后让引擎自己落盘
  let pruned = 0;
  for (const node of scan.nodes) pruned += pruneNode(node, ids);
  const flushTargets = [];
  for (const serviceName of Object.keys(found)) {
    flushTargets.push({ label: serviceName, object: safeGet(ctx, serviceName) });
  }
  for (let i = 0; i < scan.nodes.length; i++) {
    flushTargets.push({ label: `registry#${i}`, object: scan.nodes[i] });
    flushTargets.push({ label: `registry#${i}.tables`, object: scan.nodes[i].tables });
  }
  const flushed = tryFlush(flushTargets);
  const recheck = findRegistries(roots);
  const presentAfter = countPresence(recheck.nodes, ids);
  const rawAfter = rawPresence(recheck.nodes, ids);

  return {
    ...base,
    ok: recheck.nodes.length > 0 && presentAfter === 0 && rawAfter <= 0,
    strategy: scan.nodes.length > 0 ? "state-prune" : "unsupported",
    removed: Math.max(pruned, removedByMethod),
    presentAfter,
    rawAfter,
    flush: flushed,
    note: scan.nodes.length === 0
      ? "no workspace-registry-shaped object reachable from services"
      : (rawAfter > 0 ? "ids still present in serialized registry (shape not understood)" : ""),
    ms: Date.now() - started,
  };
}

/** 处理一条命令：执行 → 写结果 → 删命令文件。全程 try/catch。 */
function handle(ctx, dir, file) {
  const full = path.join(dir, file);
  let cmd;
  try {
    cmd = JSON.parse(fs.readFileSync(full, "utf8"));
  } catch (error) {
    console.error(`[android-session-admin] bad cmd ${file}: ${error?.message || error}`);
    try { fs.unlinkSync(full); } catch {}
    return;
  }
  let result;
  try {
    result = execute(ctx, cmd);
  } catch (error) {
    result = {
      id: String(cmd?.id || ""),
      op: String(cmd?.op || ""),
      ok: false,
      strategy: "threw",
      note: String(error?.message || error).slice(0, 300),
      presentAfter: -1,
      removed: 0,
    };
  }
  const id = String(cmd?.id || file.replace(/^cmd-/, "").replace(/\.json$/, ""));
  writeJson(path.join(dir, `res-${id}.json`), { ...result, pid: process.pid, at: Date.now() });
  try { fs.unlinkSync(full); } catch {}
  if (result.ok) {
    console.log(
      `[android-session-admin] ${result.op} ok strategy=${result.strategy} ` +
        `removed=${result.removed} presentAfter=${result.presentAfter} ms=${result.ms}`,
    );
  } else {
    console.error(
      `[android-session-admin] ${result.op} failed strategy=${result.strategy} ` +
        `presentAfter=${result.presentAfter} note=${result.note}`,
    );
  }
}

/** 一次 tick：收集命令、清理过期文件。 */
function tick(ctx, dir) {
  let entries;
  try {
    entries = fs.readdirSync(dir);
  } catch {
    return;
  }
  const now = Date.now();
  for (const entry of entries) {
    const full = path.join(dir, entry);
    let stat;
    try { stat = fs.statSync(full); } catch { continue; }
    const age = now - stat.mtimeMs;
    if (entry.startsWith("res-")) {
      if (age > RESULT_KEEP_MS) try { fs.unlinkSync(full); } catch {}
      continue;
    }
    if (!entry.startsWith("cmd-") || !entry.endsWith(".json")) continue;
    if (age > CMD_STALE_MS) {
      // Android 侧早已放弃等待：直接丢弃，别执行过期指令
      try { fs.unlinkSync(full); } catch {}
      continue;
    }
    handle(ctx, dir, entry);
  }
}

function apply(ctx) {
  // ⚠️ 整段包 try/catch：插件 apply 抛错会被 plugin-health 记成 failedEntry，
  // 进而让 profile 判为 degraded（触发隔离/安全模式）。热通道只是便利功能，
  // 它不可用时正确的结果是「回退到停引擎删除」，而不是让引擎看起来病了。
  try {
    return applyChannel(ctx);
  } catch (error) {
    console.error(`[android-session-admin] disabled: ${error?.message || error}`);
  }
}

function applyChannel(ctx) {
  const dir = homeDir();
  if (!dir) {
    console.error("[android-session-admin] DSH_HOME missing, hot channel disabled");
    return;
  }
  if (!ensureDir(dir)) {
    console.error(`[android-session-admin] cannot create ${dir}, hot channel disabled`);
    return;
  }
  let running = false;
  const timer = setInterval(() => {
    // 串行化：一次 tick 没跑完就不再进（大列表删除可能超过 400ms）
    if (running) return;
    running = true;
    try {
      tick(ctx, dir);
    } catch (error) {
      console.error(`[android-session-admin] tick failed: ${error?.message || error}`);
    } finally {
      running = false;
    }
  }, POLL_MS);
  // 不挂住事件循环：引擎退出时本定时器不该成为阻碍
  try { timer.unref?.(); } catch {}
  console.log(`[android-session-admin] hot channel ready at ${dir}`);
  return () => {
    try { clearInterval(timer); } catch {}
  };
}

export { apply, inject, name };
