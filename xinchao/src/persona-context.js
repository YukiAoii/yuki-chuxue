// 【多状态（C-2）】persona 上下文：同一进程内按 personaId 分数据桶。
// 设计见仓库 memory/心潮接入-Wave3设计.md（2026-10-05）。
//
// 背景：心潮原是「单实例单状态」——所有状态文件指到 data/xinchao/default/。
// 现在每个 persona 一套 { store, blackBox, journal, bridgeQueue, cabin, personality }，
// 路径为 data/xinchao/<personaId>/（懒创建、进程内缓存）。
//
// 接入方式（server.js）：
//   1. 启动时 initPersonaContext(config)；
//   2. 请求入口 runInPersona(resolvePersonaId(request), async () => { ...原逻辑... })；
//   3. handler 内联引用处以 `const { store, ... } = currentBundle();` 遮蔽模块级；
//   4. 模块级函数顶部同样取 currentBundle()（不在请求上下文时回退 default）。
//
// ⚠️ 本波未切桶的组件：attention / relevance（默认关闭的功能，statePath 仍指 default；
//    需要时再补，见设计文档「2.2 心潮侧」）。oauth / model / bark 是服务级——不切。

import { AsyncLocalStorage } from 'node:async_hooks';
import { readdir } from 'node:fs/promises';
import { dirname as pathDirname, join as pathJoin, basename as pathBasename } from 'node:path';
import { StateStore } from './state-store.js';
import { newState } from './engine.js';
import { BlackBox } from './black-box.js';
import { TransitionJournal } from './transition-journal.js';
import { BridgeQueue } from './bridge-queue.js';
import { CabinStore } from './cabin-store.js';
import { PersonalityStore } from './personality-store.js';

/** personaId 白名单：字母数字-_，1..64。防路径穿越（`../`、绝对路径）。 */
const PERSONA_ID_RE = /^[0-9a-zA-Z_-]{1,64}$/;

const als = new AsyncLocalStorage();

/** personaId -> bundle（进程内缓存；懒创建）。 */
const bundles = new Map();

let sharedConfig = null;

/** 启动时由 server.js 注入 config（bundle 的路径都从它推导）。 */
export function initPersonaContext(config) {
  sharedConfig = config;
}

/**
 * 从请求解析 personaId：`X-Persona-Id` 头优先，`?persona=` 次之；都没给 → 'default'。
 * 非法值（不满足白名单）→ 抛 400（由 handler 的顶层 catch 转为响应）。
 */
export function resolvePersonaId(request, url = null) {
  const raw = String(
    request?.headers?.['x-persona-id'] ?? url?.searchParams?.get('persona') ?? '',
  ).trim();
  if (!raw) return 'default';
  if (!PERSONA_ID_RE.test(raw)) {
    const error = new Error(`非法 persona id：${raw.slice(0, 80)}`);
    error.status = 400;
    throw error;
  }
  return raw;
}

/**
 * 把 default 桶里的某个文件路径映射到指定 persona 桶：
 * `.../data/xinchao/default/state.json` → `.../data/xinchao/<pid>/state.json`
 * （依赖「配置文件位于桶目录下」这一现行部署结构；default 原样返回。）
 */
function pathForPersona(configuredPath, personaId) {
  if (personaId === 'default') return configuredPath;
  const dir = pathDirname(configuredPath);          // .../default
  return pathJoin(pathDirname(dir), personaId, pathBasename(configuredPath));
}

function createBundle(config, personaId) {
  return {
    personaId,
    store: new StateStore(pathForPersona(config.statePath, personaId), () => newState()),
    blackBox: new BlackBox(pathForPersona(config.box.statePath, personaId)),
    journal: new TransitionJournal(pathForPersona(config.journalPath, personaId)),
    bridgeQueue: new BridgeQueue(pathForPersona(config.bridge.statePath, personaId), config.bridge),
    cabin: new CabinStore(pathForPersona(config.cabin.statePath, personaId), config.cabin),
    personality: new PersonalityStore(pathForPersona(config.personalityPath, personaId)),
  };
}

/** 取（或懒创建）某个 persona 的组件集。数据目录由各 store 写盘时自建（StateStore.write 已 mkdir）。 */
export function bundleFor(personaId = 'default') {
  const pid = personaId || 'default';
  let bundle = bundles.get(pid);
  if (!bundle) {
    if (!sharedConfig) throw new Error('persona-context 未初始化：先调用 initPersonaContext(config)');
    bundle = createBundle(sharedConfig, pid);
    bundles.set(pid, bundle);
  }
  return bundle;
}

/** 当前请求的组件集（非请求上下文——启动/定时器——回退 default）。 */
export function currentBundle() {
  return als.getStore()?.bundle ?? bundleFor('default');
}

/** 当前请求的 personaId（默认 'default'）。 */
export function currentPersonaId() {
  return als.getStore()?.personaId ?? 'default';
}

/** 在一个 persona 上下文中执行 fn（请求入口用；异步穿透由 AsyncLocalStorage 保证）。 */
export function runInPersona(personaId, fn) {
  return als.run({ personaId, bundle: bundleFor(personaId) }, fn);
}

/**
 * 在「当前执行流」进入 persona 上下文——http 回调入口用的 enterWith 变体。
 *
 * ⚠️ 与 [runInPersona] 的分工：run 创建包裹作用域（适合「包住一段逻辑」）；
 * enterWith 就地设置当前异步上下文（适合「回调即请求」的 handler——回调体不再包一层）。
 * 并发隔离已用探针实测（fast/slow 交错请求各自读到自己的 store，见 _dl/probe-als.mjs）。
 */
export function enterPersonaContext(personaId) {
  als.enterWith({ personaId, bundle: bundleFor(personaId) });
}

/** 已知 persona 集合（进程内已创建 + 'default'）——结算遍历等用。 */
export function knownPersonaIds() {
  return [...new Set(['default', ...bundles.keys()])];
}

/**
 * 启动时预加载磁盘上已有的桶（重启后定时结算不漏——未预加载的桶要等首个请求才创建，
 * 那段空窗里会错过定时结算与主动行为）。返回预加载的桶数。
 */
export async function preloadExistingPersonas() {
  try {
    const baseDir = pathDirname(pathDirname(sharedConfig.statePath));   // .../data/xinchao
    const entries = await readdir(baseDir, { withFileTypes: true });
    let count = 0;
    for (const entry of entries) {
      if (!entry.isDirectory() || entry.name === 'default') continue;
      if (!PERSONA_ID_RE.test(entry.name)) continue;                    // 只认合法桶名
      bundleFor(entry.name);
      count += 1;
    }
    return count;
  } catch {
    return 0;   // 目录不存在等——首次部署时没有桶，属正常
  }
}
