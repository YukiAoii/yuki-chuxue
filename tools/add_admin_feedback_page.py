"""给后台页加「反馈」分区（v0.58.0）。

## 为什么必须有
用户要求「后端必须有能回复反馈的功能」。后端接口上一轮加好了，
但**后台页里压根没有这一页** —— 反馈进了库，除了直接开 sqlite 谁也看不到。
接口存在而没有一个能点的界面，等于没有。

三处改动：
1. `admin.html`：左侧导航加一项 `#/feedback`
2. `admin.js`：`PAGES` 表加一条
3. `admin.js`：加 `renderFeedback(view)` —— 列表 + 就地回复框
"""
import io
import os

D = r"C:\Users\<用户名>\Desktop\项目1\Yuki初雪\backend\static"
HTML = os.path.join(D, "admin.html")
JS = os.path.join(D, "admin.js")


def read(p):
    raw = io.open(p, "rb").read().decode("utf-8")
    nl = "\r\n" if "\r\n" in raw else "\n"
    return raw.replace("\r\n", "\n"), nl


# ── ① 导航项：加在「版本发布」之后 ──
html, nl = read(HTML)
anchor = '<a class="nav-item" href="#/audit" data-page="audit">'
if "#/feedback" not in html:
    i = html.index(anchor)
    # 找到这一整个 <a> 的结尾
    j = html.index("</a>", i) + len("</a>")
    item = '''
      <a class="nav-item" href="#/feedback" data-page="feedback">
        <span class="nav-ico" aria-hidden="true">✉</span>
        <span class="nav-label">反馈<span id="fbDot" class="nav-dot" hidden></span></span>
      </a>'''
    html = html[:j] + item + html[j:]
    io.open(HTML, "wb").write(html.replace("\n", nl).encode("utf-8"))
    print("① admin.html 已加导航项")
else:
    print("① 导航项已存在")

# ── ② PAGES 表 + 渲染函数 ──
js, nl = read(JS)
if "render: renderFeedback" not in js:
    a = "  audit:         { title: '操作日志', desc: '后台的每一次写操作都记在这里', render: renderAudit },"
    js = js.replace(
        a,
        a + "\n  feedback:      { title: '反馈', desc: '用户从官网和 App 发来的反馈，可以直接在这里回复', render: renderFeedback },",
        1,
    )

RENDER = '''
/**
 * 反馈页（v0.58.0）。
 *
 * ⚠️ 在这之前反馈是**只写不读**的：进库了，但后台没有这一页 ——
 *    除了直接开 sqlite 谁也看不到，更没法回。回不了 = 没有反馈功能。
 *
 * ⚠️ 回复框留空再点「回复」= **撤销回复**（后端把状态退回未回复）。
 *    发错了得能收回来，否则那条永远显示"已回复"而内容是错的。
 */
async function renderFeedback(view) {
  const data = await api('/api/admin/feedback?limit=100');
  const items = data.items || [];

  const rows = items.length
    ? items.map((f) => `<tr>
        <td>#${f.id}</td>
        <td><span class="badge">${esc(f.kind)}</span></td>
        <td>
          <b>${esc(f.content)}</b>
          ${f.contact ? `<div class="muted">联系方式：${esc(f.contact)}</div>` : ''}
          ${f.mine ? '<div class="muted">来自登录用户（Ta 能在 App 里看到回复）</div>' : '<div class="muted">匿名 / 官网表单</div>'}
        </td>
        <td class="nowrap">${esc(fmtTime(f.createdAt))}</td>
        <td class="nowrap">${f.reply ? '<span class="badge badge-ok">已回复</span>' : '<span class="badge badge-warn">待回复</span>'}</td>
        <td style="min-width:300px">
          <textarea data-reply-for="${f.id}" rows="2" placeholder="写回复…（留空再点=撤销）">${esc(f.reply || '')}</textarea>
          <div style="margin-top:6px">
            <button class="btn btn-sm" data-act="reply" data-id="${f.id}">保存回复</button>
          </div>
        </td>
      </tr>`).join('')
    : '<tr class="empty-row"><td colspan="6">还没有人反馈</td></tr>';

  view.innerHTML = `
    <div class="card">
      <div class="card-head">
        <h3>反馈（共 ${data.total} 条 · 待回复 ${data.unreplied} 条）</h3>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>ID</th><th>类型</th><th>内容</th><th>时间</th><th>状态</th><th>回复</th></tr></thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
    </div>`;

  view.querySelectorAll('button[data-act="reply"]').forEach((b) => {
    b.addEventListener('click', async () => {
      const id = b.dataset.id;
      const box = view.querySelector(`textarea[data-reply-for="${id}"]`);
      const old = b.textContent;
      b.disabled = true;
      b.textContent = '保存中…';
      try {
        await api(`/api/admin/feedback/${id}/reply`, {
          method: 'POST',
          body: JSON.stringify({ reply: box.value }),
        });
        await route();   // 重画这一页：状态徽标/待回复计数要跟着变
      } catch (e) {
        alert('回复失败：' + e.message);
        b.disabled = false;
        b.textContent = old;
      }
    });
  });
}
'''

if "function renderFeedback" not in js:
    js = js.rstrip("\n") + "\n" + RENDER
    print("② admin.js 已加 renderFeedback")

io.open(JS, "wb").write(js.replace("\n", nl).encode("utf-8"))
print("完成")
