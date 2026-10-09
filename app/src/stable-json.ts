/**
 * 确定性 JSON 序列化 —— 每一层都排序 key。
 *
 * 为什么这个文件必须存在：
 * DeepSeek 官方 Context Caching 文档原文——
 *   "A subsequent request can only hit the cache if it fully matches
 *    a cache prefix unit."
 * 而 JSON 的 key 顺序会改变字节。语义完全相同的对象，如果序列化出不同字节，
 * 前缀就不匹配，缓存直接失效。
 *
 * 这是最容易被忽略的一层：你以为"内容没变"，但字节变了。
 *
 * 思路参照天枢（Tianshu-harness, Apache-2.0）的 src/api/stable-json.ts，
 * 其注释原文："critical for DeepSeek's exact-prefix cache matching."
 */

export function stableStringify(value: unknown): string {
  if (value === null || value === undefined) return JSON.stringify(value) ?? 'null'
  if (typeof value !== 'object') return JSON.stringify(value) ?? 'null'

  if (Array.isArray(value)) {
    // 数组顺序有语义，保持原序
    return `[${value.map(stableStringify).join(',')}]`
  }

  const obj = value as Record<string, unknown>
  const keys = Object.keys(obj).sort()
  const pairs: string[] = []
  for (const k of keys) {
    const v = obj[k]
    if (v === undefined) continue
    pairs.push(`${JSON.stringify(k)}:${stableStringify(v)}`)
  }
  return `{${pairs.join(',')}}`
}
