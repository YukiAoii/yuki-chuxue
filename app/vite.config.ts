import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import type { Connect, Plugin } from 'vite'

/**
 * 开发期通用转发中间件。
 *
 * 为什么必须有它：BYOK 客户端要从浏览器直连各家模型 API，而浏览器受同源策略
 * 约束 —— 多数厂商不返回 Access-Control-Allow-Origin，请求会被 CORS 拦掉
 * （Anthropic 还额外要求 anthropic-dangerous-direct-browser-access 头）。
 * 这里起一个本地转发：前端 POST /__proxy 并带上 x-target-url 头指定真实端点，
 * 由 dev server 在 Node 侧发请求，绕开浏览器限制。
 *
 * 注意：仅开发期使用。打包成 App 后应改为原生网络层或自建后端转发。
 */
function byokProxy(): Connect.NextHandleFunction {
  return async (req, res, next) => {
    if (!req.url || !req.url.startsWith('/__proxy')) return next()

    const target = req.headers['x-target-url']
    if (typeof target !== 'string' || !/^https?:\/\//.test(target)) {
      res.statusCode = 400
      res.setHeader('content-type', 'application/json')
      res.end(JSON.stringify({ error: 'missing or invalid x-target-url header' }))
      return
    }

    // 收集请求体
    const chunks: Buffer[] = []
    for await (const chunk of req) chunks.push(chunk as Buffer)
    const body = Buffer.concat(chunks)

    // 转发请求头，剔除会干扰上游的字段
    const DROP = new Set(['host', 'content-length', 'x-target-url', 'origin', 'referer', 'connection'])
    const headers: Record<string, string> = {}
    for (const [k, v] of Object.entries(req.headers)) {
      if (DROP.has(k.toLowerCase())) continue
      if (typeof v === 'string') headers[k] = v
    }

    try {
      const upstream = await fetch(target, {
        method: req.method,
        headers,
        body: body.length > 0 ? body : undefined,
      })

      res.statusCode = upstream.status
      upstream.headers.forEach((value, key) => {
        // 这些头由我们自己控制，透传会导致长度/编码不一致
        if (['content-encoding', 'content-length', 'transfer-encoding'].includes(key.toLowerCase())) return
        res.setHeader(key, value)
      })
      const text = await upstream.text()
      res.end(text)
    } catch (err) {
      res.statusCode = 502
      res.setHeader('content-type', 'application/json')
      res.end(JSON.stringify({ error: `proxy failed: ${String(err)}` }))
    }
  }
}

const byokProxyPlugin: Plugin = {
  name: 'yuki-byok-proxy',
  configureServer(server) {
    server.middlewares.use(byokProxy())
  },
  configurePreviewServer(server) {
    server.middlewares.use(byokProxy())
  },
}

export default defineConfig({
  plugins: [react(), byokProxyPlugin],
  server: {
    port: 5173,
    host: '127.0.0.1',
    strictPort: false,
  },
})
