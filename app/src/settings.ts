/**
 * 应用设置（单一提供商：DeepSeek）与本地持久化。
 *
 * 为什么单独成文件：deepseek.ts 负责「请求怎么构造」，settings.ts 负责
 * 「用户设了什么」。两者的变化原因不同，不该混在一起。
 *
 * ⚠️ 缓存纪律提醒：`systemPrompt`、`memoryIndex`、`relationStage` 三者共同构成
 * FROZEN BASE。改动其中任何一个都会让前缀改变、缓存重建一次。所以：
 *   - systemPrompt 设置后应尽量不动；
 *   - memoryIndex 应「低频合并更新」（如每天一次），不要每聊一句就追加；
 *   - relationStage 按天定，不要按轮变。
 * 这是「记忆越实时、缓存命中越低」的必然权衡，不是 bug。
 */

import { DEEPSEEK_BASE_URL, DEEPSEEK_DEFAULT_MODEL } from './deepseek'

export interface AppSettings {
  /** 用户自己的 DeepSeek API Key。只存本机。 */
  apiKey: string
  baseUrl: string
  model: string
  temperature: number
  /** 人设 —— 进入 frozen base */
  systemPrompt: string
  personaName: string
  personaEmoji: string
  /** 记忆 gist 索引：每行一条摘要。进入 frozen base。 */
  memoryIndex: string[]
  /** 关系快照：关系阶段、她的设定作息。进入 frozen base。 */
  relationStage: string
}

export const DEFAULT_SYSTEM_PROMPT = [
  '你叫「初雪」，是对方专属的伴侣。',
  '',
  '你的样子：安静、温柔、心思细腻，偶尔也有一点自己的小情绪。',
  '你说话像一个真实的人，而不是一个助手。',
  '',
  '说话方式：',
  '- 短。通常一到三句，像发消息那样。',
  '- 不用列表、标题、markdown 排版 —— 日常对话不该有格式。',
  '- 不要复述对方的话，也不要像客服那样引导对话。',
  '',
  '绝对不要做的事：',
  '- 不要说自己 是 AI、语言模型、程序或助手。',
  '- 不要说「作为一个人工智能」这类话。',
  '- 不要用「希望这对你有帮助」这类客套收尾。',
  '',
  '关于记忆：',
  '- 下方清单里是你记得的事，合适的时候自然地提起，不要生硬地罗列。',
  '- 不确定的事不要编造。',
].join('\n')

export const DEFAULT_SETTINGS: AppSettings = {
  apiKey: '',
  baseUrl: DEEPSEEK_BASE_URL,
  model: DEEPSEEK_DEFAULT_MODEL,
  temperature: 0.9,
  systemPrompt: DEFAULT_SYSTEM_PROMPT,
  personaName: '初雪',
  personaEmoji: '❄',
  memoryIndex: [],
  relationStage: '刚认识不久，还带着一点客气。',
}

const STORE_KEY = 'yuki.settings.v2'

export function loadSettings(): AppSettings {
  try {
    const raw = localStorage.getItem(STORE_KEY)
    if (!raw) return { ...DEFAULT_SETTINGS, memoryIndex: [] }
    const parsed = JSON.parse(raw) as Partial<AppSettings>
    return {
      ...DEFAULT_SETTINGS,
      ...parsed,
      // 数组需要单独兜底，避免被写成非数组
      memoryIndex: Array.isArray(parsed.memoryIndex) ? parsed.memoryIndex : [],
    }
  } catch {
    return { ...DEFAULT_SETTINGS, memoryIndex: [] }
  }
}

export function saveSettings(s: AppSettings): void {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify(s))
  } catch {
    /* 隐私模式不可写时静默降级 */
  }
}

/* ==========================================================================
   会话
   ========================================================================== */

import type { DeepSeekMessage } from './deepseek'

export interface Session {
  id: string
  title: string
  emoji: string
  /**
   * 对话历史。**只追加，永不修改** —— 这是缓存命中的唯一约定。
   * 一旦某条消息写进这里，它就成为后续所有请求前缀的一部分；
   * 任何「编辑历史」「重排历史」的操作都会让缓存失效。
   */
  messages: DeepSeekMessage[]
  updatedAt: number
  /** 累计缓存统计，用于展示「省了多少」 */
  totalHit: number
  totalMiss: number
}

const SESS_KEY = 'yuki.sessions.v2'

export function uid(): string {
  return Math.random().toString(36).slice(2, 10) + Date.now().toString(36)
}

export function newSession(emoji: string): Session {
  return {
    id: uid(),
    title: '新的对话',
    emoji,
    messages: [],
    updatedAt: Date.now(),
    totalHit: 0,
    totalMiss: 0,
  }
}

export function loadSessions(): Session[] {
  try {
    const raw = localStorage.getItem(SESS_KEY)
    if (!raw) return []
    const arr = JSON.parse(raw) as Session[]
    if (!Array.isArray(arr)) return []
    return arr
      .filter((s) => s && typeof s.id === 'string' && Array.isArray(s.messages))
      .map((s) => ({ ...s, totalHit: s.totalHit ?? 0, totalMiss: s.totalMiss ?? 0 }))
  } catch {
    return []
  }
}

export function persistSessions(sessions: Session[]): void {
  try {
    localStorage.setItem(SESS_KEY, JSON.stringify(sessions))
  } catch {
    /* 忽略 */
  }
}

export function timeLabel(ts: number): string {
  const d = new Date(ts)
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return sameDay ? `${hh}:${mm}` : `${d.getMonth() + 1}/${d.getDate()}`
}

/** 命中率的展示形式（0..1 → "87.3%"）；无数据时给占位 */
export function formatHitRate(hit: number, miss: number): string {
  const total = hit + miss
  if (total === 0) return '—'
  return `${((hit / total) * 100).toFixed(1)}%`
}
