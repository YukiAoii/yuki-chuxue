import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  buildChatRequest,
  buildFrozenBase,
  describeHttpError,
  parseChatResponse,
  type CacheStats,
} from './deepseek'
import {
  formatHitRate,
  loadSessions,
  loadSettings,
  newSession,
  persistSessions,
  saveSettings,
  timeLabel,
  type AppSettings,
  type Session,
} from './settings'

/**
 * 开发期走本地转发以绕开 CORS（浏览器直连 api.deepseek.com 会被同源策略拦）。
 * 打包成 App 后应替换为原生网络层。
 */
const PROXY_PATH = '/__proxy'

/** 生成送给模型的时间戳 —— 它只会出现在请求的最后一行，绝不进 frozen base */
function nowLabel(): string {
  const d = new Date()
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${d.getMonth() + 1}月${d.getDate()}日 ${hh}:${mm}`
}

export default function App() {
  const [cfg, setCfg] = useState<AppSettings>(() => loadSettings())
  const [sessions, setSessions] = useState<Session[]>(() => {
    const loaded = loadSessions()
    return loaded.length > 0 ? loaded : [newSession('❄')]
  })
  const [activeId, setActiveId] = useState('')
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [lastCache, setLastCache] = useState<CacheStats | null>(null)
  const [panelOpen, setPanelOpen] = useState(false)
  const [navOpen, setNavOpen] = useState(false)

  const threadRef = useRef<HTMLDivElement>(null)
  const taRef = useRef<HTMLTextAreaElement>(null)

  const active = useMemo(
    () => sessions.find((s) => s.id === activeId) ?? sessions[0],
    [sessions, activeId],
  )

  /** frozen base 只在配置变化时重算 —— 它必须逐字节稳定 */
  const frozenBase = useMemo(
    () =>
      buildFrozenBase({
        personaName: cfg.personaName,
        systemPrompt: cfg.systemPrompt,
        memoryIndex: cfg.memoryIndex,
        relationStage: cfg.relationStage,
      }),
    [cfg.personaName, cfg.systemPrompt, cfg.memoryIndex, cfg.relationStage],
  )

  const configured = Boolean(cfg.apiKey.trim())

  const patchSession = useCallback((id: string, patch: Partial<Session>) => {
    setSessions((prev) => {
      const next = prev.map((s) => (s.id === id ? { ...s, ...patch, updatedAt: Date.now() } : s))
      persistSessions(next)
      return next
    })
  }, [])

  const patchConfig = useCallback((patch: Partial<AppSettings>) => {
    setCfg((prev) => {
      const next = { ...prev, ...patch }
      saveSettings(next)
      return next
    })
  }, [])

  useEffect(() => {
    const el = threadRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [active?.messages, busy, activeId])

  useEffect(() => {
    const ta = taRef.current
    if (!ta) return
    ta.style.height = 'auto'
    ta.style.height = `${Math.min(ta.scrollHeight, 168)}px`
  }, [input])

  const handleSend = useCallback(async () => {
    const text = input.trim()
    if (!text || busy || !active) return
    if (!configured) {
      setError('请先在「设置」里填入你的 DeepSeek API Key')
      return
    }

    const req = buildChatRequest(
      {
        apiKey: cfg.apiKey,
        model: cfg.model,
        baseUrl: cfg.baseUrl,
        temperature: cfg.temperature,
      },
      frozenBase,
      active.messages, // 只追加的历史
      text,
      { now: nowLabel() },
    )

    setInput('')
    setError('')
    setBusy(true)

    try {
      const res = await fetch(PROXY_PATH, {
        method: 'POST',
        headers: { ...req.headers, 'x-target-url': req.url },
        body: req.body,
      })
      const raw = await res.text()
      if (!res.ok) throw new Error(describeHttpError(res.status, raw))

      const out = parseChatResponse(JSON.parse(raw))

      /**
       * 关键：写进历史的是 req.messages 的最后一条（含附录内容），
       * 而不是用户原始输入。这样下一轮它作为前缀时逐字节一致 → 缓存命中。
       * 任何「只存原文」的做法都会让两者产生偏移。
       */
      const committed = req.messages[req.messages.length - 1]!

      patchSession(active.id, {
        messages: [...active.messages, committed, { role: 'assistant', content: out.text }],
        title: active.messages.length === 0 ? text.slice(0, 16) : active.title,
        totalHit: (active.totalHit ?? 0) + out.cache.hitTokens,
        totalMiss: (active.totalMiss ?? 0) + out.cache.missTokens,
      })
      setLastCache(out.cache)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }, [input, busy, active, cfg, frozenBase, configured, patchSession])

  const onComposerKey = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      void handleSend()
    }
  }

  const createSession = () => {
    const s = newSession(cfg.personaEmoji || '❄')
    setSessions((prev) => {
      const next = [s, ...prev]
      persistSessions(next)
      return next
    })
    setActiveId(s.id)
    setNavOpen(false)
    setLastCache(null)
  }

  const sessionHitRate = active ? formatHitRate(active.totalHit ?? 0, active.totalMiss ?? 0) : '—'

  return (
    <>
      <div className="aurora-field" aria-hidden="true" />
      <div className="snow-dust" aria-hidden="true" />

      <div className="app-shell">
        {navOpen && <div className="scrim" onClick={() => setNavOpen(false)} />}

        {/* ---------------- 侧栏 ---------------- */}
        <aside className={`sidebar${navOpen ? ' open' : ''}`}>
          <div className="sidebar-head">
            <div className="brand">
              <div className="brand-mark">{cfg.personaEmoji || '❄'}</div>
              <div className="brand-text">
                <div className="brand-name">Yuki · 初雪</div>
                <div className="brand-sub">DeepSeek 专用 · 缓存优先</div>
              </div>
            </div>
          </div>

          <div className="sidebar-body">
            <button className="btn block" onClick={createSession}>
              ＋ 新的对话
            </button>

            <div className="section-label">对话</div>
            {sessions.map((s) => {
              const last = s.messages[s.messages.length - 1]
              return (
                <button
                  key={s.id}
                  className="session-item"
                  aria-current={s.id === active?.id}
                  onClick={() => {
                    setActiveId(s.id)
                    setNavOpen(false)
                    setLastCache(null)
                  }}
                >
                  <span className="session-avatar">{s.emoji}</span>
                  <span className="session-meta">
                    <span className="session-title">{s.title}</span>
                    <span className="session-preview">
                      {last ? last.content.replace(/^【相关回忆】[\s\S]*?\n\n/, '').slice(0, 22) : '还没有开始说话'}
                    </span>
                  </span>
                  <span className="session-time">{timeLabel(s.updatedAt)}</span>
                </button>
              )
            })}
          </div>

          <div className="sidebar-foot">
            <button className="session-item" onClick={() => setPanelOpen(true)}>
              <span className="session-avatar">⚙</span>
              <span className="session-meta">
                <span className="session-title">设置</span>
                <span className="session-preview">
                  {configured ? `${cfg.model} · 命中 ${sessionHitRate}` : '尚未填入 API Key'}
                </span>
              </span>
            </button>
          </div>
        </aside>

        {/* ---------------- 主区 ---------------- */}
        <main className="main">
          <header className="topbar">
            <button
              className="icon-btn only-mobile"
              aria-label="打开会话列表"
              onClick={() => setNavOpen(true)}
            >
              ☰
            </button>
            <div className="topbar-title">
              <div className="topbar-name">{cfg.personaName || '初雪'}</div>
              <div className="topbar-status">
                <span className={`status-dot${configured ? '' : ' off'}`} />
                {configured
                  ? lastCache
                    ? `缓存命中 ${(lastCache.hitRate * 100).toFixed(1)}% · ${lastCache.hitTokens.toLocaleString()}/${(lastCache.hitTokens + lastCache.missTokens).toLocaleString()} tokens`
                    : '准备就绪'
                  : '未配置 API Key'}
              </div>
            </div>
            <button className="icon-btn" aria-label="设置" onClick={() => setPanelOpen(true)}>
              ⚙
            </button>
          </header>

          <div className="thread" ref={threadRef}>
            {!active || active.messages.length === 0 ? (
              <div className="empty-state">
                <div className="empty-orb">{cfg.personaEmoji || '❄'}</div>
                <h2 className="empty-title">这里只有你们两个人</h2>
                <p className="empty-desc">
                  {configured
                    ? `说点什么吧。${cfg.personaName || '初雪'}会记得你说过的话。`
                    : '先填入你自己的 DeepSeek API Key —— 密钥只留在本机，不会上传。'}
                </p>
              </div>
            ) : (
              <div className="thread-inner">
                {active.messages.map((m, i) => (
                  <div
                    key={i}
                    className={`msg-row ${m.role === 'user' ? 'from-user' : 'from-her'}`}
                  >
                    <div className={`msg-avatar ${m.role === 'user' ? 'me' : 'her'}`}>
                      {m.role === 'user' ? '你' : cfg.personaEmoji || '❄'}
                    </div>
                    <div className="bubble-col">
                      <div className={`bubble ${m.role === 'user' ? 'me' : 'her'}`}>
                        {m.content}
                      </div>
                    </div>
                  </div>
                ))}

                {busy && (
                  <div className="msg-row from-her">
                    <div className="msg-avatar her">{cfg.personaEmoji || '❄'}</div>
                    <div className="bubble-col">
                      <div className="bubble her">
                        <span className="typing" aria-label="正在输入">
                          <i />
                          <i />
                          <i />
                        </span>
                      </div>
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>

          <div className="composer-wrap">
            {error && (
              <div
                className="notice danger"
                role="alert"
                style={{ maxWidth: 720, margin: '0 auto 12px' }}
              >
                <span>⚠</span>
                <span>{error}</span>
              </div>
            )}
            <form
              className="composer"
              onSubmit={(e) => {
                e.preventDefault()
                void handleSend()
              }}
            >
              <label className="sr-only" htmlFor="composer-input">
                输入消息
              </label>
              <textarea
                id="composer-input"
                ref={taRef}
                value={input}
                rows={1}
                placeholder={configured ? '说点什么…（Enter 发送）' : '先填入 API Key'}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={onComposerKey}
                disabled={busy}
              />
              <button
                type="submit"
                className="icon-btn primary"
                aria-label="发送"
                disabled={busy || !input.trim()}
              >
                ↑
              </button>
            </form>
          </div>
        </main>
      </div>

      {panelOpen && (
        <SettingsPanel cfg={cfg} onChange={patchConfig} onClose={() => setPanelOpen(false)} />
      )}
    </>
  )
}

/* ==========================================================================
   设置面板（DeepSeek 专用）
   ========================================================================== */

function SettingsPanel({
  cfg,
  onChange,
  onClose,
}: {
  cfg: AppSettings
  onChange: (patch: Partial<AppSettings>) => void
  onClose: () => void
}) {
  const [memDraft, setMemDraft] = useState(cfg.memoryIndex.join('\n'))

  const commitMemories = (text: string) => {
    const list = text
      .split('\n')
      .map((s) => s.trim())
      .filter((s) => s.length > 0)
    onChange({ memoryIndex: list })
  }

  return (
    <>
      <div className="scrim" onClick={onClose} />
      <aside className="panel glass-sheen" role="dialog" aria-modal="true" aria-label="设置">
        <div className="panel-head">
          <div className="panel-title">设置</div>
          <button className="icon-btn" onClick={onClose} aria-label="关闭">
            ✕
          </button>
        </div>

        <div className="panel-body">
          <div className="notice">
            <span>🔒</span>
            <span>
              API Key 只保存在<b>这台设备</b>，不会上传到任何服务器。
              请求经由本地开发服务转发 —— 这是绕过浏览器跨域限制所必需的中转，
              打包成 App 后会换成原生网络层。
            </span>
          </div>

          <div className="field">
            <label className="field-label" htmlFor="cfg-key">
              DeepSeek API Key
            </label>
            <input
              id="cfg-key"
              className="input mono"
              type="password"
              value={cfg.apiKey}
              spellCheck={false}
              autoComplete="off"
              placeholder="sk- 开头"
              onChange={(e) => onChange({ apiKey: e.target.value })}
            />
            <span className="field-hint">获取地址：https://platform.deepseek.com/api_keys</span>
          </div>

          <div className="field">
            <label className="field-label" htmlFor="cfg-model">
              模型
            </label>
            <input
              id="cfg-model"
              className="input mono"
              value={cfg.model}
              spellCheck={false}
              onChange={(e) => onChange({ model: e.target.value })}
            />
            <div className="choice-grid">
              {['deepseek-chat', 'deepseek-reasoner'].map((m) => (
                <button
                  key={m}
                  type="button"
                  className="choice"
                  aria-pressed={m === cfg.model}
                  onClick={() => onChange({ model: m })}
                >
                  <span className="choice-main">
                    <span className="choice-name">{m}</span>
                  </span>
                </button>
              ))}
            </div>
            <span className="field-hint">
              模型名随官方更新变化，如报 404 请按文档改成当前可用的名字。
            </span>
          </div>

          <hr className="divider" />

          <div className="field">
            <label className="field-label" htmlFor="cfg-name">
              她的名字
            </label>
            <input
              id="cfg-name"
              className="input"
              value={cfg.personaName}
              onChange={(e) => onChange({ personaName: e.target.value })}
            />
          </div>

          <div className="field">
            <label className="field-label" htmlFor="cfg-sys">
              人设
            </label>
            <textarea
              id="cfg-sys"
              className="input"
              rows={10}
              value={cfg.systemPrompt}
              style={{ lineHeight: 1.65, resize: 'vertical' }}
              onChange={(e) => onChange({ systemPrompt: e.target.value })}
            />
            <span className="field-hint">
              这是「不崩人设」的关键，也进入冻结前缀 —— 改一次会让缓存重建一次。
            </span>
          </div>

          <div className="field">
            <label className="field-label" htmlFor="cfg-mem">
              她记得的事（每行一条）
            </label>
            <textarea
              id="cfg-mem"
              className="input"
              rows={5}
              value={memDraft}
              placeholder={'对方喜欢下雨天\n对方在做一个 App'}
              style={{ lineHeight: 1.65, resize: 'vertical' }}
              onChange={(e) => {
                setMemDraft(e.target.value)
                commitMemories(e.target.value)
              }}
            />
            <span className="field-hint">
              ⚡ 这里进的是<b>摘要索引</b>，不是记忆正文。改它会让缓存重建一次，
              所以建议低频更新（例如每天整理一次），而不是聊一句加一条。
            </span>
          </div>

          <div className="field">
            <label className="field-label" htmlFor="cfg-rel">
              你们的关系
            </label>
            <textarea
              id="cfg-rel"
              className="input"
              rows={3}
              value={cfg.relationStage}
              style={{ lineHeight: 1.65, resize: 'vertical' }}
              onChange={(e) => onChange({ relationStage: e.target.value })}
            />
            <span className="field-hint">按天定，不建议按轮次改动。</span>
          </div>

          <div className="field">
            <span className="field-label">语气随机度（{cfg.temperature}）</span>
            <div className="choice-grid">
              {[
                { label: '稳定', value: 0.4, desc: '更克制、更一致' },
                { label: '平衡', value: 0.75, desc: '日常对话' },
                { label: '活泼', value: 1.0, desc: '更跳脱、更有情绪' },
              ].map((t) => (
                <button
                  key={t.value}
                  type="button"
                  className="choice"
                  aria-pressed={Math.abs(t.value - cfg.temperature) < 0.04}
                  onClick={() => onChange({ temperature: t.value })}
                >
                  <span className="choice-main">
                    <span className="choice-name">{t.label}</span>
                    <span className="choice-sub">{t.desc}</span>
                  </span>
                </button>
              ))}
            </div>
          </div>
        </div>

        <div className="panel-foot">
          <button className="btn block gradient" onClick={onClose}>
            完成
          </button>
        </div>
      </aside>
    </>
  )
}
