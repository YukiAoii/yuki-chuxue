/**
 * DeepSeek 专用请求构造 —— 为前缀缓存命中而设计。
 *
 * ══ 依据（两组均已核实）══
 * DeepSeek 官方 Context Caching 文档原文：
 *   "A subsequent request can only hit the cache if it fully matches
 *    a cache prefix unit."
 * 官方 Example 2 更直接：第一轮 A+B、第二轮 A+C —— 第二轮**不命中**，
 * 因为前缀差一个字符就不完全匹配。
 *
 * 天枢（Tianshu-harness）的工程实践（源码实证）：
 *   冻结基座 + 易变内容启动时快照 + 字节级稳定序列化 +
 *   边界只在真实用户消息处提交 + 压缩边界按固定步长推进。
 *
 * ══ 本文件遵循的四条纪律 ══
 *   1. FROZEN BASE（人设 + 记忆索引 + 关系快照）会话内逐字节不变；
 *   2. 历史消息**只追加、永不修改** —— 一旦写入，它就成为前缀的一部分；
 *   3. 动态内容（本轮召回的记忆正文、时间）拼在**最后一条用户消息**里 ——
 *      它位于前缀末端，不会破坏前面任何字节；
 *   4. 请求体用 stableStringify 序列化，消除 JSON key 顺序带来的字节漂移。
 */

import { stableStringify } from './stable-json'

export const DEEPSEEK_BASE_URL = 'https://api.deepseek.com/v1'

/** 默认模型。⚠️ 模型名会随厂商更新变化，调用报 404 时应按官方文档调整。 */
export const DEEPSEEK_DEFAULT_MODEL = 'deepseek-chat'

export interface DeepSeekMessage {
  role: 'system' | 'user' | 'assistant'
  content: string
}

/* ==========================================================================
   冻结基座
   ========================================================================== */

/** 会话内冻结不变的部分。**改动它 = 缓存重建一次**，所以更新频率要低。 */
export interface FrozenInputs {
  personaName: string
  /** 人设：她是什么样的人。禁止放入任何逐轮变化的内容。 */
  systemPrompt: string
  /** 记忆的 gist 索引 —— 每段记忆一行摘要，不是正文。 */
  memoryIndex: readonly string[]
  /** 关系快照：关系阶段、她的设定作息等（按天定，不按轮定）。 */
  relationStage: string
}

/**
 * 构造 FROZEN BASE。
 *
 * 铁律：**同样的输入必须产出同样的字节**。
 * 严禁在此插入时间戳、随机值、或任何逐轮变化的内容 —— 那会让每轮请求的
 * 前缀都不同，DeepSeek 的缓存将完全无法命中。
 *
 * 注意：记忆索引从「无」变「有」必然改变前缀，缓存会重建一次。
 * 这是可接受的代价，但意味着索引更新应当**低频**（如每天合并一次），
 * 而不是每聊一句就追加。
 */
export function buildFrozenBase(inputs: FrozenInputs): string {
  const lines: string[] = [
    `你是「${inputs.personaName}」。`,
    '',
    '## 你是什么样的人',
    inputs.systemPrompt.trim(),
    '',
    '## 你记得关于对方的事',
  ]

  if (inputs.memoryIndex.length === 0) {
    // 空态也输出固定占位行，保证结构稳定
    lines.push('（还没有值得记住的事）')
  } else {
    for (const m of inputs.memoryIndex) lines.push(`- ${m}`)
  }

  lines.push('', '## 你们的关系', inputs.relationStage.trim())
  return lines.join('\n')
}

/* ==========================================================================
   本轮动态区（附录）
   ========================================================================== */

export interface TurnAppendix {
  /** 本轮召回的记忆正文。放这里，不放 system —— 它每轮都可能不同。 */
  recalledMemories?: readonly string[]
  /** 当前时间。**必须放最末尾**，绝不能进 system。 */
  now?: string
}

/**
 * 把本轮动态内容拼到用户消息上。
 *
 * 为什么拼在这里而不是塞进 system：
 * 官方 Example 2 证明——在前缀中间插入任何变动，从该点往后全部失效。
 * 而拼在最后一条用户消息上时，它处于整个前缀的末端，
 * 前面所有内容（system + 全部历史）保持逐字节不变 → 命中。
 */
export function buildUserContent(userText: string, appendix: TurnAppendix = {}): string {
  const blocks: string[] = []

  if (appendix.recalledMemories && appendix.recalledMemories.length > 0) {
    blocks.push('【相关回忆】')
    for (const m of appendix.recalledMemories) blocks.push(m)
  }

  blocks.push(userText)

  if (appendix.now) blocks.push(`（现在：${appendix.now}）`)

  return blocks.join('\n')
}

/* ==========================================================================
   请求构造
   ========================================================================== */

export interface DeepSeekConfig {
  apiKey: string
  model?: string
  baseUrl?: string
  temperature?: number
  maxTokens?: number
}

export interface BuiltRequest {
  url: string
  headers: Record<string, string>
  /** 已稳定序列化的请求体 —— 直接用作 fetch body */
  body: string
  /** 便于测试与排查：本次实际发送的消息数组 */
  messages: DeepSeekMessage[]
}

/**
 * 构造一次对话请求。
 *
 * @param history 历史消息，**只追加**。调用方必须保证已写入的消息不被修改，
 *                否则前缀改变、缓存失效（这是本设计唯一需要外部遵守的约定）。
 */
export function buildChatRequest(
  cfg: DeepSeekConfig,
  frozenBase: string,
  history: readonly DeepSeekMessage[],
  userText: string,
  appendix: TurnAppendix = {},
): BuiltRequest {
  const base = (cfg.baseUrl || DEEPSEEK_BASE_URL).replace(/\/+$/, '')

  const messages: DeepSeekMessage[] = [
    { role: 'system', content: frozenBase },
    ...history,
    { role: 'user', content: buildUserContent(userText, appendix) },
  ]

  const payload = {
    model: cfg.model || DEEPSEEK_DEFAULT_MODEL,
    messages,
    temperature: cfg.temperature ?? 0.9,
    max_tokens: cfg.maxTokens ?? 2048,
    stream: false,
  }

  return {
    url: `${base}/chat/completions`,
    headers: {
      'content-type': 'application/json',
      authorization: `Bearer ${cfg.apiKey}`,
    },
    body: stableStringify(payload),
    messages,
  }
}

/* ==========================================================================
   响应解析（含缓存命中统计）
   ========================================================================== */

export interface CacheStats {
  hitTokens: number
  missTokens: number
  inputTokens: number
  outputTokens: number
  /** 命中率 0..1；无输入时为 0 */
  hitRate: number
}

interface RawUsage {
  prompt_tokens?: number
  completion_tokens?: number
  prompt_cache_hit_tokens?: number
  prompt_cache_miss_tokens?: number
}

/** 解析 usage 里的缓存命中字段（官方文档：Checking Cache Hit Status） */
export function parseUsage(raw: unknown): CacheStats {
  const u = (raw ?? {}) as RawUsage
  const hit = u.prompt_cache_hit_tokens ?? 0
  const miss = u.prompt_cache_miss_tokens ?? 0
  const total = hit + miss
  return {
    hitTokens: hit,
    missTokens: miss,
    inputTokens: u.prompt_tokens ?? total,
    outputTokens: u.completion_tokens ?? 0,
    hitRate: total > 0 ? hit / total : 0,
  }
}

export interface ChatOutcome {
  text: string
  cache: CacheStats
}

/** 解析一次对话响应 */
export function parseChatResponse(raw: unknown): ChatOutcome {
  const d = raw as {
    choices?: { message?: { content?: string } }[]
    usage?: unknown
    error?: { message?: string }
  }

  if (d.error) throw new Error(d.error.message ?? 'DeepSeek 返回了错误')

  const text = d.choices?.[0]?.message?.content
  if (typeof text !== 'string' || text.length === 0) {
    throw new Error('模型返回了空内容（可能是内容策略拦截或模型名有误）')
  }

  return { text, cache: parseUsage(d.usage) }
}

/** 把 HTTP 错误翻译成人能看懂的一句话 */
export function describeHttpError(status: number, raw: string): string {
  let detail = raw.slice(0, 300)
  try {
    const j = JSON.parse(raw) as { error?: { message?: string } | string }
    if (typeof j.error === 'string') detail = j.error
    else if (j.error?.message) detail = j.error.message
  } catch {
    /* 保持原文 */
  }

  if (status === 401 || status === 403) return `认证失败（${status}）：API Key 无效。${detail}`
  if (status === 402) return `余额不足（402）：请为 DeepSeek 账户充值。${detail}`
  if (status === 404) return `接口或模型不存在（404）：检查 API 地址与模型名。${detail}`
  if (status === 429) return `请求过于频繁（429）：${detail}`
  if (status >= 500) return `DeepSeek 服务异常（${status}）：${detail}`
  return `请求失败（${status}）：${detail}`
}
