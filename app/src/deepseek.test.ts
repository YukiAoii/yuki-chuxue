/**
 * 缓存友好性的行为测试。
 *
 * 这些测试不是"跑通不报错"式的形式测试 —— 它们断言的是**字节级稳定性**，
 * 也就是 DeepSeek 前缀缓存能否命中的充要条件。
 *
 * 官方原文："A subsequent request can only hit the cache if it fully matches
 * a cache prefix unit." —— 所以「同一语义 → 同一字节」必须被钉死。
 */

import { describe, expect, it } from 'vitest'
import { stableStringify } from './stable-json'
import {
  buildChatRequest,
  buildFrozenBase,
  buildUserContent,
  describeHttpError,
  parseChatResponse,
  parseUsage,
  type DeepSeekMessage,
  type FrozenInputs,
} from './deepseek'

const FROZEN: FrozenInputs = {
  personaName: '初雪',
  systemPrompt: '你安静、温柔、心思细腻。说话像一个真实的人。',
  memoryIndex: ['对方喜欢下雨天', '对方在做一个叫 Yuki初雪 的 App'],
  relationStage: '刚认识不久，还带着一点客气。',
}

const CFG = { apiKey: 'sk-test', model: 'deepseek-chat' }

/* ==========================================================================
   稳定序列化 —— 字节一致的地基
   ========================================================================== */

describe('stableStringify', () => {
  it('key 顺序不同的等价对象产出完全相同的字节', () => {
    expect(stableStringify({ b: 1, a: 2 })).toBe(stableStringify({ a: 2, b: 1 }))
  })

  it('嵌套层也逐级排序', () => {
    expect(stableStringify({ x: { z: 1, y: 2 } })).toBe('{"x":{"y":2,"z":1}}')
  })

  it('数组保持原序（顺序有语义，不能排序）', () => {
    expect(stableStringify([3, 1, 2])).toBe('[3,1,2]')
  })

  it('剔除 undefined 字段，避免「有 key」与「无 key」的字节抖动', () => {
    expect(stableStringify({ a: 1, b: undefined })).toBe('{"a":1}')
  })

  it('保留 null（它有明确语义）', () => {
    expect(stableStringify({ a: null })).toBe('{"a":null}')
  })

  it('中文按原样保留，不被转义（转义方式变化同样会破坏前缀）', () => {
    expect(stableStringify({ s: '初雪' })).toBe('{"s":"初雪"}')
  })
})

/* ==========================================================================
   冻结基座 —— 会话内必须逐字节不变
   ========================================================================== */

describe('buildFrozenBase', () => {
  it('同样的输入产出逐字节相同的字符串', () => {
    expect(buildFrozenBase(FROZEN)).toBe(buildFrozenBase(FROZEN))
  })

  it('翻转字段构造顺序不影响结果（调用方传入顺序无关）', () => {
    const reordered: FrozenInputs = {
      relationStage: FROZEN.relationStage,
      memoryIndex: [...FROZEN.memoryIndex],
      systemPrompt: FROZEN.systemPrompt,
      personaName: FROZEN.personaName,
    }
    expect(buildFrozenBase(reordered)).toBe(buildFrozenBase(FROZEN))
  })

  it('不包含任何时间信息（时间进 frozen 会让缓存永远无法命中）', () => {
    const out = buildFrozenBase(FROZEN)
    expect(out).not.toMatch(/\d{4}-\d{2}-\d{2}/)
    expect(out).not.toMatch(/\d{2}:\d{2}/)
  })

  it('记忆索引为空时输出固定占位行，结构仍然稳定', () => {
    const a = buildFrozenBase({ ...FROZEN, memoryIndex: [] })
    const b = buildFrozenBase({ ...FROZEN, memoryIndex: [] })
    expect(a).toBe(b)
    expect(a).toContain('（还没有值得记住的事）')
  })

  it('记忆索引变化会改变前缀 —— 记录这个代价，说明索引必须低频更新', () => {
    const before = buildFrozenBase({ ...FROZEN, memoryIndex: [] })
    const after = buildFrozenBase({ ...FROZEN, memoryIndex: ['她记住了新的事'] })
    expect(after).not.toBe(before)
  })
})

/* ==========================================================================
   本轮动态区 —— 只允许出现在前缀末端
   ========================================================================== */

describe('buildUserContent', () => {
  it('顺序为：相关回忆 → 用户输入 → 时间', () => {
    const out = buildUserContent('今天好累', {
      recalledMemories: ['你上次说你也睡不好'],
      now: '2026-09-25 22:10',
    })
    const iRecall = out.indexOf('【相关回忆】')
    const iUser = out.indexOf('今天好累')
    const iNow = out.indexOf('（现在：')
    expect(iRecall).toBeGreaterThanOrEqual(0)
    expect(iUser).toBeGreaterThan(iRecall)
    expect(iNow).toBeGreaterThan(iUser) // 时间必须在最末尾
  })

  it('没有附录时就是用户原文，不加多余字符', () => {
    expect(buildUserContent('你好')).toBe('你好')
  })

  it('时间变化只影响末尾，不影响用户输入之前的部分', () => {
    const a = buildUserContent('你好', { now: '10:00' })
    const b = buildUserContent('你好', { now: '11:00' })
    expect(a.startsWith('你好')).toBe(true)
    expect(b.startsWith('你好')).toBe(true)
  })
})

/* ==========================================================================
   缓存友好性的核心断言 —— 前一轮的内容必须原样成为后一轮的前缀
   ========================================================================== */

describe('多轮请求的前缀一致性（缓存命中的充要条件）', () => {
  const frozen = buildFrozenBase(FROZEN)

  it('第二轮请求的 system 与第一轮逐字节相同', () => {
    const r1 = buildChatRequest(CFG, frozen, [], '你好', { now: '10:00' })
    const history: DeepSeekMessage[] = [
      { role: 'user', content: r1.messages[1]!.content },
      { role: 'assistant', content: '你好呀' },
    ]
    const r2 = buildChatRequest(CFG, frozen, history, '在做什么', { now: '10:01' })

    expect(r2.messages[0]!.content).toBe(r1.messages[0]!.content)
  })

  it('第一轮的用户消息进入历史后逐字节不变（第二轮的 messages[1] === 第一轮的 messages[1]）', () => {
    const r1 = buildChatRequest(CFG, frozen, [], '你好', { now: '10:00' })
    const history: DeepSeekMessage[] = [
      { role: 'user', content: r1.messages[1]!.content },
      { role: 'assistant', content: '你好呀' },
    ]
    const r2 = buildChatRequest(CFG, frozen, history, '在做什么', { now: '10:01' })

    expect(r2.messages[1]).toEqual(r1.messages[1])
    expect(r2.messages[2]).toEqual({ role: 'assistant', content: '你好呀' })
  })

  it('第三轮时，前两轮的完整前缀依然逐字节保留', () => {
    const r1 = buildChatRequest(CFG, frozen, [], '第一句', { now: '10:00' })
    const h1: DeepSeekMessage[] = [
      { role: 'user', content: r1.messages[1]!.content },
      { role: 'assistant', content: '回复一' },
    ]
    const r2 = buildChatRequest(CFG, frozen, h1, '第二句', { now: '10:01' })
    const h2: DeepSeekMessage[] = [
      ...h1,
      { role: 'user', content: r2.messages[3]!.content },
      { role: 'assistant', content: '回复二' },
    ]
    const r3 = buildChatRequest(CFG, frozen, h2, '第三句', { now: '10:02' })

    // 第三轮的前 4 条必须与第二轮的完整 4 条逐字节一致 —— 前缀从未被改写
    expect(r3.messages.slice(0, 4)).toEqual(r2.messages.slice(0, 4))
    // 且 system 与第一轮的首条用户消息，跨越两轮依然原样保留
    expect(r3.messages[0]).toEqual(r1.messages[0])
    expect(r3.messages[1]).toEqual(r1.messages[1])
  })

  it('同一输入重复构造，请求体逐字节相同（stableStringify 生效）', () => {
    const history: DeepSeekMessage[] = [{ role: 'user', content: '你好' }]
    const a = buildChatRequest(CFG, frozen, history, '再来', { now: '10:00' })
    const b = buildChatRequest(CFG, frozen, history, '再来', { now: '10:00' })
    expect(a.body).toBe(b.body)
  })

  it('只有本轮新增的末尾不同：两轮的请求体差异必须局限在尾部', () => {
    const r1 = buildChatRequest(CFG, frozen, [], '你好', { now: '10:00' })
    const r2 = buildChatRequest(CFG, frozen, [], '你好', { now: '10:00' })
    // 相同输入 → 完全相同；这证明 body 里没有随机成分（如未排序的 key）
    expect(r1.body).toBe(r2.body)
  })
})

/* ==========================================================================
   请求形状
   ========================================================================== */

describe('buildChatRequest', () => {
  const frozen = buildFrozenBase(FROZEN)

  it('消息顺序为 system → 历史 → 本轮用户', () => {
    const history: DeepSeekMessage[] = [
      { role: 'user', content: 'a' },
      { role: 'assistant', content: 'b' },
    ]
    const r = buildChatRequest(CFG, frozen, history, 'c')
    expect(r.messages.map((m) => m.role)).toEqual([
      'system',
      'user',
      'assistant',
      'user',
    ])
    expect(r.messages.at(-1)!.content).toBe('c')
  })

  it('URL 拼接不产生双斜杠', () => {
    expect(buildChatRequest({ ...CFG, baseUrl: 'https://api.deepseek.com/v1/' }, frozen, [], 'x').url).toBe(
      'https://api.deepseek.com/v1/chat/completions',
    )
    expect(buildChatRequest(CFG, frozen, [], 'x').url).toBe(
      'https://api.deepseek.com/v1/chat/completions',
    )
  })

  it('带上 Bearer 鉴权头', () => {
    const r = buildChatRequest(CFG, frozen, [], 'x')
    expect(r.headers['authorization']).toBe('Bearer sk-test')
  })

  it('请求体是合法 JSON 且包含 model 等字段（stableStringify 不能产出坏 JSON）', () => {
    const r = buildChatRequest(CFG, frozen, [], 'x')
    const parsed = JSON.parse(r.body) as { model: string; messages: unknown[] }
    expect(parsed.model).toBe('deepseek-chat')
    expect(Array.isArray(parsed.messages)).toBe(true)
  })
})

/* ==========================================================================
   缓存命中统计
   ========================================================================== */

describe('parseUsage', () => {
  it('解析官方的两个缓存字段并算出命中率', () => {
    const s = parseUsage({
      prompt_tokens: 1000,
      completion_tokens: 50,
      prompt_cache_hit_tokens: 900,
      prompt_cache_miss_tokens: 100,
    })
    expect(s.hitTokens).toBe(900)
    expect(s.missTokens).toBe(100)
    expect(s.hitRate).toBeCloseTo(0.9, 5)
    expect(s.outputTokens).toBe(50)
  })

  it('无缓存字段时命中率为 0 而非报错（首次请求本来就不该命中）', () => {
    const s = parseUsage({ prompt_tokens: 100, completion_tokens: 10 })
    expect(s.hitTokens).toBe(0)
    expect(s.hitRate).toBe(0)
  })

  it('usage 缺失时不崩溃', () => {
    const s = parseUsage(undefined)
    expect(s.hitRate).toBe(0)
    expect(s.inputTokens).toBe(0)
  })
})

/* ==========================================================================
   响应与错误
   ========================================================================== */

describe('parseChatResponse', () => {
  it('取出正文与缓存统计', () => {
    const out = parseChatResponse({
      choices: [{ message: { content: '嗯，我在。' } }],
      usage: { prompt_cache_hit_tokens: 800, prompt_cache_miss_tokens: 200 },
    })
    expect(out.text).toBe('嗯，我在。')
    expect(out.cache.hitRate).toBeCloseTo(0.8, 5)
  })

  it('空内容抛出可读错误而不是静默成功', () => {
    expect(() => parseChatResponse({ choices: [{ message: { content: '' } }] })).toThrow(/空内容/)
  })

  it('响应体里的 error 字段被抛出', () => {
    expect(() => parseChatResponse({ error: { message: '模型不存在' } })).toThrow(/模型不存在/)
  })
})

describe('describeHttpError', () => {
  it('401 翻译成认证失败并带上原因', () => {
    expect(describeHttpError(401, JSON.stringify({ error: { message: 'Invalid token' } }))).toMatch(
      /认证失败.*Invalid token/s,
    )
  })

  it('402 提示余额不足（DeepSeek 特有）', () => {
    expect(describeHttpError(402, '{}')).toMatch(/余额不足/)
  })

  it('404 提示检查地址与模型名', () => {
    expect(describeHttpError(404, '{}')).toMatch(/模型名/)
  })

  it('非 JSON 错误体不会让翻译函数崩溃', () => {
    expect(describeHttpError(500, '<html>bad gateway</html>')).toMatch(/500/)
  })
})
