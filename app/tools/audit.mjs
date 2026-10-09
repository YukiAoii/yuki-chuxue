/**
 * 布局审计探针 —— 用 CDP 驱动 Edge/Chrome 做真实测量，代替肉眼看截图。
 *
 * 用法:
 *   node tools/audit.mjs <url> <outDir> <width> <height> [label]
 *
 * 输出:
 *   - <outDir>/<label>.png            该视口截图
 *   - <outDir>/<label>.audit.json     溢出元素清单 + 关键度量
 *   - stdout 打印人类可读摘要
 *
 * 检查项:
 *   1. 横向溢出：元素 right 超出视口宽度的（最常见的移动端破版）
 *   2. 文档整体滚动宽度 vs 视口宽度
 *   3. 过小的点击目标（< 40px 宽或高）
 *   4. 过小的正文字号（< 12px）
 */

import { writeFile, mkdir } from 'node:fs/promises'
import { setTimeout as sleep } from 'node:timers/promises'

const CDP_PORT = Number(process.env.CDP_PORT || 9222)
const [, , TARGET_URL = 'http://127.0.0.1:5173/', OUT_DIR = '.screenshots', W = '1280', H = '900', LABEL = 'view', CLICK_SELECTOR = ''] =
  process.argv
const width = Number(W)
const height = Number(H)

/** 极简 CDP 客户端 */
function makeClient(ws) {
  let seq = 0
  const pending = new Map()
  ws.addEventListener('message', (ev) => {
    let msg
    try {
      msg = JSON.parse(ev.data)
    } catch {
      return
    }
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id)
      pending.delete(msg.id)
      if (msg.error) reject(new Error(JSON.stringify(msg.error)))
      else resolve(msg.result)
    }
  })
  return (method, params = {}) =>
    new Promise((resolve, reject) => {
      const id = ++seq
      pending.set(id, { resolve, reject })
      ws.send(JSON.stringify({ id, method, params }))
    })
}

/** 在页面里跑的检测脚本（返回 JSON 字符串） */
const PROBE = `(() => {
  // 抽屉 / 滑出式菜单靠 transform 移出视口是预期行为，不能算破版。
  const hasTransformAncestor = (node) => {
    let p = node;
    while (p && p !== document.documentElement) {
      const t = getComputedStyle(p).transform;
      if (t && t !== 'none') return true;
      p = p.parentElement;
    }
    return false;
  };
  const vw = document.documentElement.clientWidth;
  const vh = document.documentElement.clientHeight;
  const overflow = [];
  const smallTargets = [];
  const smallText = [];

  for (const el of document.querySelectorAll('body *')) {
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity) === 0) continue;
    const r = el.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) continue;

    // 1. 横向溢出（允许 1px 亚像素误差）；左侧负值仅在非 transform 位移时才判为越界
    const leftOverflow = r.left < -1 && !hasTransformAncestor(el);
    if (r.right > vw + 1 || leftOverflow) {
      overflow.push({
        tag: el.tagName.toLowerCase(),
        cls: typeof el.className === 'string' ? el.className : '',
        left: Math.round(r.left),
        right: Math.round(r.right),
        width: Math.round(r.width),
        text: (el.textContent || '').trim().slice(0, 36),
      });
    }

    // 3. 可点击元素过小
    if (el.matches('button, a, input, textarea, select, [role="button"]')) {
      if (r.width > 0 && r.height > 0 && (r.width < 40 || r.height < 40)) {
        smallTargets.push({
          tag: el.tagName.toLowerCase(),
          cls: typeof el.className === 'string' ? el.className : '',
          w: Math.round(r.width),
          h: Math.round(r.height),
          text: (el.textContent || el.getAttribute('aria-label') || '').trim().slice(0, 24),
        });
      }
    }

    // 4. 正文文字过小（只看有直接文本节点、且非子元素承载文本的）
    const hasOwnText = Array.from(el.childNodes).some(
      (n) => n.nodeType === 3 && n.textContent.trim().length > 0
    );
    if (hasOwnText) {
      const fs = parseFloat(cs.fontSize);
      if (fs > 0 && fs < 12) {
        smallText.push({
          tag: el.tagName.toLowerCase(),
          cls: typeof el.className === 'string' ? el.className : '',
          fontSize: fs,
          text: (el.textContent || '').trim().slice(0, 28),
        });
      }
    }
  }

  return JSON.stringify({
    viewportWidth: vw,
    viewportHeight: vh,
    docScrollWidth: document.documentElement.scrollWidth,
    horizontalOverflow: document.documentElement.scrollWidth > vw,
    overflow: overflow.slice(0, 40),
    smallTargets: smallTargets.slice(0, 25),
    smallText: smallText.slice(0, 25),
  });
})()`

async function main() {
  // 新建一个页面 target（新版 Chrome 要求 PUT）
  const newTarget = await fetch(
    `http://127.0.0.1:${CDP_PORT}/json/new?${encodeURIComponent(TARGET_URL)}`,
    { method: 'PUT' },
  ).then((r) => r.json())

  const wsUrl = newTarget.webSocketDebuggerUrl
  if (!wsUrl) throw new Error(`未能取得调试端点: ${JSON.stringify(newTarget)}`)

  const ws = new WebSocket(wsUrl)
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true })
    ws.addEventListener('error', () => reject(new Error('CDP WebSocket 连接失败')), { once: true })
  })

  const send = makeClient(ws)
  const targetId = newTarget.id

  try {
    await send('Page.enable')
    await send('Runtime.enable')
    await send('Emulation.setDeviceMetricsOverride', {
      width,
      height,
      deviceScaleFactor: 1,
      mobile: width < 600,
    })
    await sleep(1200) // 让布局与动画稳定

    // 可选：先真实点击某个元素，再测量 + 截图（用于验证交互路径）
    if (CLICK_SELECTOR) {
      const clickRes = await send('Runtime.evaluate', {
        expression: `(() => {
          const el = document.querySelector(${JSON.stringify(CLICK_SELECTOR)});
          if (!el) return 'NOT_FOUND';
          el.click();
          return 'CLICKED';
        })()`,
        returnByValue: true,
      })
      console.log(`[${LABEL}] 点击 ${CLICK_SELECTOR} -> ${clickRes.result.value}`)
      await sleep(800) // 等面板/抽屉动画走完
    }

    const probeRes = await send('Runtime.evaluate', {
      expression: PROBE,
      returnByValue: true,
    })
    const report = JSON.parse(probeRes.result.value)

    const shot = await send('Page.captureScreenshot', { format: 'png' })
    await mkdir(OUT_DIR, { recursive: true })
    await writeFile(`${OUT_DIR}/${LABEL}.png`, Buffer.from(shot.data, 'base64'))
    await writeFile(`${OUT_DIR}/${LABEL}.audit.json`, JSON.stringify(report, null, 2))

    /* ---- 人类可读摘要 ---- */
    const lines = []
    lines.push(`[${LABEL}] 视口 ${report.viewportWidth}×${report.viewportHeight}`)
    lines.push(
      `  横向溢出: ${report.horizontalOverflow ? '❌ 是' : '✅ 无'} (doc scrollWidth=${report.docScrollWidth})`,
    )
    if (report.overflow.length) {
      lines.push(`  越界元素 ${report.overflow.length} 个:`)
      for (const o of report.overflow.slice(0, 8)) {
        lines.push(`    · <${o.tag} class="${o.cls}"> left=${o.left} right=${o.right} "${o.text}"`)
      }
    }
    lines.push(`  过小点击目标: ${report.smallTargets.length ? `⚠ ${report.smallTargets.length} 个` : '✅ 无'}`)
    for (const t of report.smallTargets.slice(0, 6)) {
      lines.push(`    · <${t.tag} class="${t.cls}"> ${t.w}×${t.h} "${t.text}"`)
    }
    lines.push(`  过小字号: ${report.smallText.length ? `⚠ ${report.smallText.length} 处` : '✅ 无'}`)
    for (const t of report.smallText.slice(0, 6)) {
      lines.push(`    · <${t.tag} class="${t.cls}"> ${t.fontSize}px "${t.text}"`)
    }
    console.log(lines.join('\n'))
  } finally {
    ws.close()
    await fetch(`http://127.0.0.1:${CDP_PORT}/json/close/${targetId}`).catch(() => {})
  }
}

main().catch((err) => {
  console.error('审计失败:', err.message)
  process.exit(1)
})
