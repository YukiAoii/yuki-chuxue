"""后台加「下发的 API」编辑页（v0.58.0）。

## 用户要求
「后台增加反馈回复和编辑那个下发的 api」——
反馈回复页上一轮加了；这一半是**改下发给客户端的那份免费分组配置**
（`GET /api/app/free-group` 的内容，改完所有客户端下次启动就会拿到新的）。

⚠️ 接口 `GET/PUT /api/admin/free-group` 早就有了，但后台页里没有能改的地方 ——
   和上一轮的反馈一样，"接口存在而没有能点的界面，等于没有"。

⚠️ 界面里要把**后果**写清楚：这个地址/密钥是发给**所有**用户的，
   改错等于把所有人的连接设置一起改坏。所以：
   · 保存前要求确认一次（尤其改 base_url / api_key）；
   · 密钥输入框默认可切换显示（管理员要核对，但别默认摊在屏幕上）。
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


html, nl = read(HTML)
if "#/freegroup" not in html:
    anchor = '<a class="nav-item" href="#/feedback" data-page="feedback">'
    i = html.index(anchor)
    j = html.index("</a>", i) + len("</a>")
    item = '''
      <a class="nav-item" href="#/freegroup" data-page="freegroup">
        <span class="nav-ico" aria-hidden="true">⇄</span>
        <span class="nav-label">下发的 API</span>
      </a>'''
    html = html[:j] + item + html[j:]
    io.open(HTML, "wb").write(html.replace("\n", nl).encode("utf-8"))
    print("① admin.html 已加导航项")
else:
    print("① 导航项已存在")

js, nl = read(JS)
if "render: renderFreeGroup" not in js:
    a = "  feedback:      { title: '反馈', desc: '用户从官网和 App 发来的反馈，可以直接在这里回复', render: renderFeedback },"
    js = js.replace(
        a,
        a + "\n  freegroup:     { title: '下发的 API', desc: '所有客户端都会拿到这份连接配置，改完下次启动生效', render: renderFreeGroup },",
        1,
    )

RENDER = '''
/**
 * 「下发的 API」编辑页（v0.58.0）。
 *
 * 这是 `GET /api/app/free-group` 的内容 —— 也就是 App 里那条「Yuki初雪Pro」分组。
 *
 * ⚠️ 它发给**所有**用户。改错一次，所有人的连接设置一起坏，
 *    而且用户端只会看到"发不出去消息"，看不出是后台配置的问题。
 *    所以保存前必须先确认，并且把「影响所有人」写在页面上，不是写在文档里。
 *
 * ⚠️ 密钥默认遮住：管理员要核对时点「显示」即可，但别默认摊在屏幕上
 *    （后台也可能被别人瞄到）。
 */
async function renderFreeGroup(view) {
  const cfg = await api('/api/admin/free-group');

  view.innerHTML = `
    <div class="grid grid-2-1">
      <div class="card">
        <div class="card-head"><h3>下发给客户端的连接配置</h3></div>
        <div class="alert alert-warn" style="margin:0 16px 12px">
          这份配置会发给<b>所有用户</b>。改错了，所有人的 App 都会连不上 ——
          客户端只会显示"发不出去消息"，看不出是这里的配置问题。
        </div>
        <form id="fgForm">
          <label class="field">
            <span>是否下发</span>
            <select name="enabled">
              <option value="1" ${cfg.enabled ? 'selected' : ''}>开启（客户端会出现这条分组）</option>
              <option value="0" ${!cfg.enabled ? 'selected' : ''}>关闭（客户端不再显示它）</option>
            </select>
          </label>
          <label class="field"><span>分组名称</span>
            <input name="name" value="${esc(cfg.name || '')}" placeholder="Yuki初雪Pro">
          </label>
          <label class="field"><span>API 地址（base_url）</span>
            <input name="base_url" value="${esc(cfg.base_url || '')}" placeholder="https://api.example.com:18443/v1">
          </label>
          <label class="field"><span>密钥（api_key）</span>
            <input name="api_key" type="password" value="${esc(cfg.api_key || '')}" placeholder="sk-...">
            <button type="button" class="btn btn-soft btn-sm" id="fgEye" style="margin-top:6px">显示</button>
          </label>
          <label class="field"><span>客户端看到的一句说明</span>
            <input name="notice" value="${esc(cfg.notice || '')}">
          </label>
          <button class="btn" type="submit">保存并下发</button>
        </form>
      </div>

      <div class="card">
        <div class="card-head"><h3>客户端实际拿到的</h3></div>
        <pre id="fgPreview" class="muted" style="white-space:pre-wrap;word-break:break-all;padding:0 16px 16px"></pre>
        <p class="muted" style="padding:0 16px 16px">
          这份就是 <code>GET /api/app/free-group</code> 的返回。改完保存，用户下次启动 App 就会拿到新的。
        </p>
      </div>
    </div>`;

  const form = view.querySelector('#fgForm');
  const eye = view.querySelector('#fgEye');
  const keyBox = form.elements.api_key;
  eye.addEventListener('click', () => {
    const hidden = keyBox.type === 'password';
    keyBox.type = hidden ? 'text' : 'password';
    eye.textContent = hidden ? '隐藏' : '显示';
  });

  const preview = view.querySelector('#fgPreview');
  const paint = (c) => {
    preview.textContent = JSON.stringify(
      { enabled: c.enabled, name: c.name, base_url: c.base_url, api_key: '（已隐藏）', notice: c.notice },
      null, 2,
    );
  };
  paint(cfg);

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const next = {
      enabled: form.elements.enabled.value === '1',
      name: form.elements.name.value.trim(),
      base_url: form.elements.base_url.value.trim(),
      api_key: form.elements.api_key.value.trim(),
      notice: form.elements.notice.value.trim(),
    };
    const danger = next.base_url !== cfg.base_url || next.api_key !== cfg.api_key;
    if (danger && !confirm('你改动了地址或密钥。\\n\\n这会影响**所有**用户 —— 确认要下发吗？')) return;
    if (!confirm('确认保存并下发给所有客户端？')) return;
    try {
      const r = await api('/api/admin/free-group', { method: 'PUT', body: JSON.stringify(next) });
      paint(r.config || next);
      alert('已保存。客户端下次启动就会拿到新的配置。');
    } catch (err) {
      alert('保存失败：' + err.message);
    }
  });
}
'''

if "function renderFreeGroup" not in js:
    js = js.rstrip("\n") + "\n" + RENDER
    print("② admin.js 已加 renderFreeGroup")

io.open(JS, "wb").write(js.replace("\n", nl).encode("utf-8"))
print("完成")
