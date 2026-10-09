/* ══════════════════════════════════════════════════════════════════════════
   Yuki 初雪 · 控制台　脚本 v3（彻底重写 · 2026-10-03）
   ──────────────────────────────────────────────────────────────────────────
   ## ⛔ 红线：API 层逐字冻结
   下面的 `API` 对象是**唯一**的请求出口，端点路径 / HTTP 方法 / 请求体字段 /
   返回字段的消费方式，与重写前**一字不差**。这次重写的只有：布局、渲染、
   交互与图表。要改接口请先改后端，不要在这里"顺手"改。
   ⛔ 后端 `main.py` 本次**一行未动**。

   ## 存储键也要保留
   `yuki_admin_token`（登录态）/ `yuki-admin-theme`（主题）—— 改了用户就得重新登录、
   主题也会丢。
   ══════════════════════════════════════════════════════════════════════════ */

const TOKEN_KEY = 'yuki_admin_token';
const THEME_KEY = 'yuki-admin-theme';

/* ─────────────────────────── 工具 ─────────────────────────── */

/** HTML 转义。任何拼进 innerHTML 的数据都必须过这一道。 */
function esc(v) {
  if (v === null || v === undefined) return '';
  return String(v)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

const $  = (sel, root) => (root || document).querySelector(sel);
const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));

function fmtNum(n) {
  return Number(n || 0).toLocaleString('zh-CN');
}

/** 大数字压缩：仪表盘上 38,620,927 不如 3862 万好读 */
function fmtCompact(n) {
  const v = Number(n || 0);
  if (v >= 1e8) return (v / 1e8).toFixed(2) + ' 亿';
  if (v >= 1e4) return (v / 1e4).toFixed(1) + ' 万';
  return fmtNum(v);
}

/** 服务端存 UTC ISO，浏览器自动换成本地时间 */
function fmtTime(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return String(iso);
  const p = (x) => String(x).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** 相对时间 —— 看"最近活跃"比绝对时间直观 */
function fmtAgo(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '—';
  const s = Math.floor((Date.now() - d.getTime()) / 1000);
  if (s < 60) return '刚刚';
  if (s < 3600) return `${Math.floor(s / 60)} 分钟前`;
  if (s < 86400) return `${Math.floor(s / 3600)} 小时前`;
  if (s < 86400 * 30) return `${Math.floor(s / 86400)} 天前`;
  return fmtTime(iso);
}

/** 只要日期（趋势图 x 轴） */
function fmtDay(iso) {
  if (!iso) return '';
  const parts = String(iso).split('T')[0].split('-');
  return parts.length === 3 ? `${+parts[1]}/${+parts[2]}` : String(iso);
}

function fmtBytes(n) {
  const v = Number(n || 0);
  if (v >= 1048576) return (v / 1048576).toFixed(1) + ' MB';
  if (v >= 1024) return (v / 1024).toFixed(1) + ' KB';
  return v + ' B';
}

function pct(part, whole) {
  const w = Number(whole || 0);
  if (!w) return '—';
  return (Number(part || 0) * 100 / w).toFixed(1) + '%';
}

/* ─────────────────────── 轻提示与确认框 ─────────────────────── */

let toastTimer = null;
function toast(message, kind = '') {
  const el = $('#toast');
  if (!el) return;
  el.className = 'toast show ' + kind;
  $('#toastText').textContent = message;
  clearTimeout(toastTimer);
  // 出错时说明通常更长，多留一会儿
  toastTimer = setTimeout(() => { el.className = 'toast ' + kind; }, kind === 'error' ? 6000 : 4000);
}

function confirmBox(title, bodyHtml, okText = '确定') {
  return new Promise((resolve) => {
    const modal = $('#modal');
    $('#modalTitle').textContent = title;
    $('#modalBody').innerHTML = bodyHtml;
    const ok = $('#modalOk');
    const cancel = $('#modalCancel');
    ok.textContent = okText;
    modal.classList.remove('hidden');

    const done = (v) => { modal.classList.add('hidden'); ok.onclick = null; cancel.onclick = null; resolve(v); };
    ok.onclick = () => done(true);
    cancel.onclick = () => done(false);
  });
}

/* ───────────────────────── 请求（唯一出口）───────────────────────── */

async function api(path, options = {}) {
  const headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers || {});
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) headers['Authorization'] = 'Bearer ' + token;

  const res = await fetch(path, Object.assign({}, options, { headers }));
  if (res.status === 401) {
    // 登录态失效：清掉并退回登录页（所有页面的 401 都走这里）
    localStorage.removeItem(TOKEN_KEY);
    showLogin();
    throw new Error('登录已失效，请重新登录');
  }

  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { data = { detail: text }; }
  if (!res.ok) {
    throw new Error((data && (data.detail || data.message)) || ('HTTP ' + res.status));
  }
  return data;
}

/* ══════════════════════════════════════════════════════════════════════════
   ⛔ API 层：端点与字段逐字冻结（本次重写**没有**改动其中任何一条）
   ══════════════════════════════════════════════════════════════════════════ */
const API = {
  login:    (u, p) => api('api/admin/login', { method: 'POST', body: JSON.stringify({ username: u, password: p }) }),
  me:       () => api('api/admin/me'),
  logout:   () => api('api/admin/logout', { method: 'POST' }),

  dash:     () => api('api/admin/dashboard'),

  devices:     (params) => api('api/admin/devices?' + new URLSearchParams(params)),
  patchDevice: (id, body) => api('api/admin/devices/' + encodeURIComponent(id), { method: 'PATCH', body: JSON.stringify(body) }),
  delDevice:   (id) => api('api/admin/devices/' + encodeURIComponent(id), { method: 'DELETE' }),

  users:     (params) => api('api/admin/users?' + new URLSearchParams(params)),
  patchUser: (id, body) => api('api/admin/users/' + encodeURIComponent(id), { method: 'PATCH', body: JSON.stringify(body) }),
  // v0.61.23 用户管理：详情（设备/命中率/会话/人设/记忆/消息）+ 重置密码
  userDetail:        (id) => api('api/admin/users/' + encodeURIComponent(id)),
  resetUserPassword: (id) => api('api/admin/users/' + encodeURIComponent(id) + '/password/reset', { method: 'POST', body: '{}' }),

  settings:    () => api('api/admin/settings'),
  putSettings: (values) => api('api/admin/settings', { method: 'PUT', body: JSON.stringify({ values }) }),

  testMail: (to) => api('api/admin/mail/test', { method: 'POST', body: JSON.stringify({ to }) }),
  codes:    () => api('api/admin/mail/pending-codes'),

  announcements: () => api('api/admin/announcements'),
  addAnn:   (body) => api('api/admin/announcements', { method: 'POST', body: JSON.stringify(body) }),
  editAnn:  (id, body) => api('api/admin/announcements/' + id, { method: 'PATCH', body: JSON.stringify(body) }),
  delAnn:   (id) => api('api/admin/announcements/' + id, { method: 'DELETE' }),

  audit:    (params) => api('api/admin/audit?' + new URLSearchParams(params)),

  // 免费分组：后端 GET/PUT /api/admin/free-group 一直都在（改完客户端下次启动生效）
  freeGroup:    () => api('api/admin/free-group'),
  putFreeGroup: (body) => api('api/admin/free-group', { method: 'PUT', body: JSON.stringify(body) }),
  // 由**服务器**去服务商拉真实模型清单（客户端永远不直连这个地址问模型）
  probeFreeGroupModels: (body) => api('api/admin/free-group/probe-models', { method: 'POST', body: JSON.stringify(body) }),

  feedback:     () => api('/api/admin/feedback?limit=100'),
  replyFeedback:(fid, body) => api('api/admin/feedback/' + fid + '/reply', { method: 'POST', body: JSON.stringify(body) }),

  // 密码找回：这两个接口**故意不要登录态**（忘了密码的人本来就登不进来）
  forgotPassword: (verifyEmail, toEmail) => api('api/admin/password/forgot', {
    method: 'POST', body: JSON.stringify({ verify_email: verifyEmail, to_email: toEmail }),
  }),
  resetPassword: (code, newPassword) => api('api/admin/password/reset', {
    method: 'POST', body: JSON.stringify({ code: code, new_password: newPassword }),
  }),
};

/* ══════════════════════════════════════════════════════════════════════════
   图表：手写 SVG / CSS
   ──────────────────────────────────────────────────────────────────────────
   ## 为什么不引图表库
   后台要能在**离线 / 内网**环境打开。引 CDN 的库，一旦拿不到就是整页白板；
   把库下到本地又要多出几百 KB 和一份升级负担。这里要的画法（环形、面积、
   条形）都很简单，手写反而是**更小、更可控、风格统一**的方案。
   ══════════════════════════════════════════════════════════════════════════ */

const PALETTE = ['#2F6BFF', '#22B8CF', '#2FA36B', '#E08700', '#8B5CF6', '#E5484D'];

/**
 * 环形图。
 * @param segments [{label, value}]（按值降序传入更自然）
 * @param opts {size, thickness, centerValue, centerLabel, colors}
 */
function donut(segments, opts = {}) {
  const size = opts.size || 148;
  const th = opts.thickness || 12;
  const r = (size - th) / 2;
  const C = 2 * Math.PI * r;
  const total = segments.reduce((s, x) => s + Number(x.value || 0), 0);

  let offset = 0;
  const arcs = segments.map((s, i) => {
    const frac = total ? Number(s.value || 0) / total : 0;
    const len = frac * C;
    const color = (opts.colors || PALETTE)[i % (opts.colors || PALETTE).length];
    const el = `<circle class="seg" cx="${size / 2}" cy="${size / 2}" r="${r}"
        stroke="${color}" stroke-width="${th}"
        stroke-dasharray="${len.toFixed(2)} ${(C - len).toFixed(2)}"
        stroke-dashoffset="${(-offset).toFixed(2)}"></circle>`;
    offset += len;
    return el;
  }).join('');

  const legend = segments.map((s, i) => `
    <div class="legend-item">
      <span class="legend-dot" style="background:${(opts.colors || PALETTE)[i % (opts.colors || PALETTE).length]}"></span>
      <span class="legend-label ellipsis">${esc(s.label)}</span>
      <span class="legend-val">${esc(s.text !== undefined ? s.text : fmtNum(s.value))}</span>
    </div>`).join('');

  return `
    <div class="donut-wrap">
      <div class="donut" style="width:${size}px;height:${size}px">
        <svg viewBox="0 0 ${size} ${size}">
          <circle class="track" cx="${size / 2}" cy="${size / 2}" r="${r}" stroke-width="${th}"></circle>
          ${arcs}
        </svg>
        <div class="donut-center">
          <span class="donut-value">${esc(opts.centerValue !== undefined ? opts.centerValue : fmtNum(total))}</span>
          <span class="donut-label">${esc(opts.centerLabel || '合计')}</span>
        </div>
      </div>
      <div class="donut-legend">${legend}</div>
    </div>`;
}

/**
 * 面积折线图。
 * @param points [{label, v}]
 */
function areaChart(points, opts = {}) {
  const w = 720, h = 200, padL = 36, padR = 14, padT = 16, padB = 26;
  const n = points.length;
  if (!n) return '<div class="empty">还没有数据</div>';

  const max = Math.max(1, ...points.map((p) => Number(p.v || 0)));
  const stepX = n > 1 ? (w - padL - padR) / (n - 1) : 0;
  const x = (i) => padL + stepX * i;
  const y = (v) => h - padB - (Number(v || 0) / max) * (h - padT - padB);

  const line = points.map((p, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(p.v).toFixed(1)}`).join(' ');
  const area = `${line} L${x(n - 1).toFixed(1)},${h - padB} L${x(0).toFixed(1)},${h - padB} Z`;

  // 横向网格 4 条 + 左侧刻度
  const grid = [0, .25, .5, .75, 1].map((f) => {
    const yy = padT + (h - padT - padB) * f;
    const val = Math.round(max * (1 - f));
    return `<line class="chart-grid" x1="${padL}" y1="${yy.toFixed(1)}" x2="${w - padR}" y2="${yy.toFixed(1)}"></line>
            <text class="chart-axis" x="${padL - 6}" y="${(yy + 3.5).toFixed(1)}" text-anchor="end">${val}</text>`;
  }).join('');

  // x 轴标签最多 7 个，避免挤成一团
  const every = Math.max(1, Math.ceil(n / 7));
  const xlab = points.map((p, i) => (i % every === 0 || i === n - 1)
    ? `<text class="chart-axis" x="${x(i).toFixed(1)}" y="${h - 8}" text-anchor="middle">${esc(p.label)}</text>` : '').join('');

  const dots = points.map((p, i) => `<circle class="chart-dot" cx="${x(i).toFixed(1)}" cy="${y(p.v).toFixed(1)}" r="2.6"></circle>`).join('');

  return `
    <div class="chart-wrap">
      <svg viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" role="img" aria-label="${esc(opts.aria || '趋势图')}">
        <defs>
          <linearGradient id="areaFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#2F6BFF" stop-opacity="0.32"></stop>
            <stop offset="100%" stop-color="#2F6BFF" stop-opacity="0.02"></stop>
          </linearGradient>
        </defs>
        ${grid}
        <path class="chart-area" d="${area}"></path>
        <path class="chart-line" d="${line}"></path>
        ${dots}
        ${xlab}
      </svg>
    </div>`;
}

/** 条形组：{label, value, text?} */
function bars(items, opts = {}) {
  const max = Math.max(1, ...items.map((x) => Number(x.value || 0)));
  return `<div class="bars">${items.map((x, i) => `
    <div class="bar-row">
      <span class="bar-label" title="${esc(x.label)}">${esc(x.label)}</span>
      <span class="bar-track"><i class="bar-fill" style="width:${(Number(x.value || 0) * 100 / max).toFixed(1)}%;background:${(opts.colors || PALETTE)[i % (opts.colors || PALETTE).length]}"></i></span>
      <span class="bar-val">${esc(x.text !== undefined ? x.text : fmtNum(x.value))}</span>
    </div>`).join('')}</div>`;
}

/* ══════════════════════════════════════════════════════════════════════════
   导航 / 路由 / 主题 / 登录态
   ══════════════════════════════════════════════════════════════════════════ */

/** 导航项：图标用内联 path（免外部图标库，离线也能画） */
const NAV = [
  { key: 'dashboard', label: '仪表盘', title: '仪表盘', desc: '整体运行状况与使用趋势',
    icon: '<path d="M3 13h8V3H3v10Zm10 8h8V11h-8v10ZM3 21h8v-6H3v6Zm10-12h8V3h-8v6Z"/>' },
  { key: 'users', label: '用户', title: '用户账号', desc: '账号是可选的 —— 不注册也能用 App；设备已并入这一页',
    icon: '<path d="M12 12a5 5 0 1 0 0-10 5 5 0 0 0 0 10Zm0 2c-4.4 0-8 2.2-8 5v1h16v-1c0-2.8-3.6-5-8-5Z"/>' },
  { key: 'free-api', label: '免费 API', title: '免费 API', desc: '面向所有用户的免费分组（客户端下次启动生效）',
    icon: '<path d="M12 2 3 7v10l9 5 9-5V7l-9-5Zm0 2.3 6.5 3.6L12 11.5 5.5 7.9 12 4.3ZM5 9.6l6 3.3v5.8l-6-3.3V9.6Zm8 9.1v-5.8l6-3.3v5.8l-6 3.3Z"/>' },
  { key: 'mail', label: '邮件', title: '邮件服务', desc: '填好 SMTP，注册验证码就能自动发信',
    icon: '<path d="M3 5h18a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1Zm1 2.2V17h16V7.2l-8 5.1-8-5.1ZM5.6 7l6.4 4.1L18.4 7H5.6Z"/>' },
  { key: 'releases', label: '版本', title: '版本发布', desc: '发布新版本，App 会提示用户更新',
    icon: '<path d="M12 2 5 9h4v7h6V9h4l-7-7ZM5 19h14v2H5v-2Z"/>' },
  { key: 'announcements', label: '公告', title: '公告', desc: 'App 启动时会拉取这里的公告',
    icon: '<path d="M3 10v4a1 1 0 0 0 1 1h2l4 4V5L6 9H4a1 1 0 0 0-1 1Zm12.5-1.3a5 5 0 0 1 0 6.6l1.4 1.4a7 7 0 0 0 0-9.4l-1.4 1.4Zm-2.8-2.8a9 9 0 0 1 0 13.2l1.4 1.4a11 11 0 0 0 0-16l-1.4 1.4Z"/>' },
  { key: 'feedback', label: '反馈', title: '反馈', desc: '官网与 App 收到的反馈，可直接回复',
    icon: '<path d="M4 3h16a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H8l-4 4V4a1 1 0 0 1 1-1Zm2 3v2h12V6H6Zm0 4v2h8v-2H6Z"/>' },
  { key: 'audit', label: '日志', title: '操作日志', desc: '后台的每一次写操作都记在这里',
    icon: '<path d="M5 3h11l4 4v14H5V3Zm2 2v14h11V8h-3V5H7Zm2 5h8v2H9v-2Zm0 4h8v2H9v-2Z"/>' },
  { key: 'xinchao', label: 'Ta 的状态', title: 'Ta 的状态', desc: 'Ta 此刻的天气与状态快照（只读）',
    icon: '<path d="M12 21s-7-4.4-9.3-8.9A5.4 5.4 0 0 1 12 6.7a5.4 5.4 0 0 1 9.3 5.4C19 16.6 12 21 12 21Z"/>' },
];

const PAGES = {
  dashboard:     renderDashboard,
  users:         renderUsers,
  'free-api':    renderFreeGroup,
  mail:          renderMail,
  releases:      renderReleases,
  announcements: renderAnnouncements,
  feedback:      renderFeedback,
  audit:         renderAudit,
  xinchao:       renderXinchao,
};

/* ─────────────────────── Ta 的状态（引擎接入 2026-10-05 · 只读）─────────────────────── */
// 数据源：本后端 /api/admin/xinchao/state → 本机引擎 18110（部署说明见 <心潮部署目录>\README-部署.md）
// 展示用语表：面向人类的口语词（与用户侧网页 /me/ 用同一套；内部 key 与引擎 dimensions 对应）。
const XINCHAO_DRIVE_NAMES = {
  possess: '想你', monitor: '牵挂', share: '想分享', libido: '心动', curiosity: '好奇',
  boredom: '无聊', duty: '上进心', reflection: '回味', grieve: '难过', anger: '生气',
  favored: '偏爱',
};

async function renderXinchao(view) {
  let data;
  try {
    data = await api('api/admin/xinchao/state');
  } catch (e) {
    view.innerHTML = `<div class="alert alert-error">读取状态失败：${esc(e.message)}</div>`;
    return;
  }
  const health = data.health;
  if (!health || !health.ok) {
    view.innerHTML = `<div class="alert alert-error">
      引擎服务不可达。请确认本机 <b>C:\\Xinchao</b> 下的两个服务在运行
      （启动方式见 C:\\Xinchao\\README-部署.md）。</div>`;
    return;
  }

  const now = data.now || {};
  const state = data.state || {};
  const drives = Object.entries(state.drives || {})
    .map(([k, v]) => ({ label: XINCHAO_DRIVE_NAMES[k] || k, value: Number(v) || 0 }))
    .sort((a, b) => b.value - a.value);
  const dreams = Array.isArray(state.recentDreams)
    ? state.recentDreams.slice(-8).reverse().filter((d) => d && d.dream)
    : [];

  view.innerHTML = `
    <div class="card">
      <p class="muted" style="margin:0">
        服务状态：<b style="color:#2FA36B">在线</b> ·
        引擎 v${esc(String(health.version || '?'))} ·
        模式 ${esc(String(health.mode || '?'))}
      </p>
      <div style="margin-top:14px;display:flex;gap:10px;flex-wrap:wrap;align-items:center">
        <button class="btn" id="xcSettle">立即结算一次</button>
        <button class="btn btn-danger" id="xcRestart">重启引擎服务</button>
        <span id="xcOpNote" class="muted"></span>
      </div>
    </div>

    <div class="card">
      <h3 style="margin-top:0">Ta 此刻</h3>
      <div style="white-space:pre-wrap;line-height:1.9">${esc(now.text || '（暂无「此刻」文本）')}</div>
    </div>

    <div class="card">
      <h3 style="margin-top:0">驱力</h3>
      ${drives.length ? drives.map((d) => `
        <div style="display:flex;align-items:center;gap:10px;margin:7px 0">
          <span class="muted" style="width:64px;flex:none">${esc(d.label)}</span>
          <div style="flex:1;height:8px;border-radius:4px;background:rgba(128,128,128,.18)">
            <div style="height:8px;border-radius:4px;background:#2F6BFF;width:${Math.max(1, Math.min(100, d.value * 100)).toFixed(1)}%"></div>
          </div>
          <span class="mono" style="width:46px;text-align:right">${d.value.toFixed(2)}</span>
        </div>`).join('') : '<p class="muted">（暂无驱力数据）</p>'}
    </div>

    <div class="card">
      <h3 style="margin-top:0">快照</h3>
      <p class="muted" style="margin:0">
        意识：${esc(String(state.consciousness ?? '?'))} ·
        版本号 revision ${esc(String(state.revision ?? '?'))}
      </p>
      <h3 style="margin:18px 0 8px">最近的梦（${dreams.length}）</h3>
      ${dreams.length ? dreams.map((d) => `
        <div style="padding:10px 0;border-top:1px dashed rgba(128,128,128,.25)">
          <span class="muted" style="font-size:12px">${esc(String(d.createdAt || '').slice(0, 16).replace('T', ' '))}</span>
          <div style="margin-top:4px;line-height:1.8">${esc(String(d.dream || '').slice(0, 140))}</div>
        </div>`).join('') : '<p class="muted">还没有梦。</p>'}
      <details style="margin-top:10px">
        <summary class="muted" style="cursor:pointer">原始数据（排查用）</summary>
        <pre class="mono" style="overflow:auto;max-height:360px;background:rgba(128,128,128,.06);padding:10px;border-radius:8px">${esc(JSON.stringify(data, null, 2))}</pre>
      </details>
    </div>`;

  const note = view.querySelector('#xcOpNote');
  view.querySelector('#xcSettle').addEventListener('click', async () => {
    note.textContent = '结算中…';
    try {
      const r = await api('api/admin/xinchao/settle', { method: 'POST', body: '{}' });
      note.textContent = `已结算（revision ${r.revision ?? '?'}）`;
    } catch (e) {
      note.textContent = '结算失败：' + e.message;
    }
    setTimeout(() => route(), 1500);
  });
  view.querySelector('#xcRestart').addEventListener('click', async () => {
    const ok = await confirmBox('重启引擎服务',
      '<p>将重启「情绪引擎 + 记忆大脑」两个服务，约 10~30 秒内相关功能不可用。</p>', '重启');
    if (!ok) return;
    note.textContent = '正在重启（最多等 45 秒）…';
    try {
      const r = await api('api/admin/xinchao/restart', { method: 'POST', body: '{}' });
      note.textContent = r.message || '已重启';
    } catch (e) {
      note.textContent = '重启失败：' + e.message;
    }
    setTimeout(() => route(), 1800);
  });
}

/** 侧栏导航由 JS 生成 —— 加一个页签只需改 NAV 一处 */
function buildNav() {
  $('#nav').innerHTML = NAV.map((n) => `
    <a class="nav-item" href="#/${n.key}" data-page="${n.key}">
      <svg class="nav-ico" viewBox="0 0 24 24" fill="currentColor">${n.icon}</svg>
      <span class="nav-label">${esc(n.label)}</span>
    </a>`).join('');
}

function currentPage() {
  // ⚠️ 字符类含 `-`：`free-api` 用 `[a-z]+` 只会截到 "free"，查不到就**静默回退仪表盘**
  const m = (location.hash || '').match(/^#\/([a-z][a-z-]*)/);
  const key = m ? m[1] : 'dashboard';
  return NAV.some((n) => n.key === key) ? key : 'dashboard';
}

async function route() {
  const key = currentPage();
  const meta = NAV.find((n) => n.key === key);
  const render = PAGES[key];

  $$('#nav .nav-item').forEach((a) => a.classList.toggle('active', a.dataset.page === key));
  $('#pageTitle').textContent = meta.title;
  $('#pageDesc').textContent = meta.desc;

  const view = $('#view');
  view.innerHTML = '<div class="card"><p class="muted" style="margin:0">加载中…</p></div>';
  try {
    await render(view);
  } catch (e) {
    view.innerHTML = `<div class="alert alert-error">加载失败：${esc(e.message)}</div>`;
  }
}

/* ─────────────────────────── 主题 ─────────────────────────── */

const ICON_SUN = '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="4.2" fill="none" stroke="currentColor" stroke-width="1.6"></circle>'
  + '<path d="M12 2v2.4M12 19.6V22M2 12h2.4M19.6 12H22M4.9 4.9l1.7 1.7M17.4 17.4l1.7 1.7M19.1 4.9l-1.7 1.7M6.6 17.4l-1.7 1.7" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"></path></svg>';
const ICON_MOON = '<svg viewBox="0 0 24 24"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" fill="none" stroke="currentColor" stroke-width="1.6"></path></svg>';

/** 主题一律读 DOM 属性，不另存 JS 变量 —— 少一个会漂的状态 */
function applyTheme(t) {
  document.documentElement.setAttribute('data-theme', t);
  try { localStorage.setItem(THEME_KEY, t); } catch (e) { /* 隐私模式：记不住也无妨 */ }
  const btn = $('#themeToggle');
  if (btn) {
    // 图标表示"按下去会切到哪"
    btn.innerHTML = t === 'dark' ? ICON_SUN : ICON_MOON;
    btn.title = t === 'dark' ? '切到日间' : '切到夜间';
  }
}

/* ─────────────────────── 登录 / 忘记密码 ─────────────────────── */

function showLogin() {
  $('#login').classList.remove('hidden');
  $('#forgot').classList.add('hidden');
  $('#app').classList.add('hidden');
}

function showApp() {
  $('#login').classList.add('hidden');
  $('#forgot').classList.add('hidden');
  $('#app').classList.remove('hidden');
}

function showForgot() {
  $('#login').classList.add('hidden');
  $('#app').classList.add('hidden');
  $('#forgot').classList.remove('hidden');
}

function alertBox(sel, msg, kind = 'error') {
  const el = $(sel);
  if (!el) return;
  el.className = 'alert alert-' + kind;
  el.textContent = msg;
  el.classList.remove('hidden');
}

/* ─────────────────────── 窄屏抽屉 ─────────────────────── */

function setNavOpen(open) {
  const sb = $('#sidebar');
  const sc = $('#scrim');
  if (!sb || !sc) return;
  sb.classList.toggle('open', open);
  sc.classList.toggle('show', open);
}

/* ─────────────────────────── 启动 ─────────────────────────── */

async function boot() {
  try {
    const me = await API.me();
    $('#whoami').textContent = '已登录：' + me.username;
    $('#sideStatus').innerHTML =
      `<span class="${me.mail_ready ? 'dot-ok' : 'dot-warn'}"></span>` +
      (me.mail_ready ? '邮件服务已配置' : '邮件服务未配置');
  } catch (e) {
    return;   // 401 已在 api() 里处理
  }
  await route();
}

(async function start() {
  buildNav();

  // 顶栏：刷新 / 主题 / 侧栏
  $('#refreshBtn').addEventListener('click', route);

  const themeBtn = $('#themeToggle');
  applyTheme(document.documentElement.getAttribute('data-theme') || 'dark');
  themeBtn.addEventListener('click', () => {
    applyTheme(document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark');
  });

  $('#navToggle').addEventListener('click', () => setNavOpen(true));
  $('#sideClose').addEventListener('click', () => setNavOpen(false));
  $('#scrim').addEventListener('click', () => setNavOpen(false));
  $$('#nav .nav-item').forEach((a) => a.addEventListener('click', () => setNavOpen(false)));
  window.addEventListener('hashchange', () => { setNavOpen(false); route(); });

  // 退出登录
  $('#logoutBtn').addEventListener('click', async () => {
    const ok = await confirmBox('退出登录？', '退出后需要重新输入管理员密码。', '退出');
    if (!ok) return;
    try { await API.logout(); } catch (e) { /* 令牌已经失效也无所谓 */ }
    localStorage.removeItem(TOKEN_KEY);
    showLogin();
  });

  // 登录
  $('#loginForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = $('#loginBtn');
    btn.disabled = true;
    try {
      const r = await API.login($('#loginUser').value.trim(), $('#loginPass').value);
      localStorage.setItem(TOKEN_KEY, r.token);
      showApp();
      await boot();
    } catch (err) {
      alertBox('#loginError', err.message);
    } finally {
      btn.disabled = false;
    }
  });

  // 忘记密码（两步）
  $('#forgotLink').addEventListener('click', (e) => { e.preventDefault(); showForgot(); });
  $('#backToLogin').addEventListener('click', (e) => { e.preventDefault(); showLogin(); });

  $('#forgotForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
      await API.forgotPassword($('#fgVerifyEmail').value.trim(), $('#fgToEmail').value.trim());
      alertBox('#forgotMsg', '重置码已发出，请查收邮件。', 'ok');
      alertBox('#forgotError', '', 'error');
      $('#forgotError').classList.add('hidden');
    } catch (err) {
      alertBox('#forgotError', err.message);
    }
  });

  $('#forgotReset').addEventListener('click', async () => {
    try {
      await API.resetPassword($('#fgCode').value.trim(), $('#fgNewPass').value);
      alertBox('#forgotMsg', '密码已重置，请用新密码登录。', 'ok');
      setTimeout(showLogin, 1200);
    } catch (err) {
      alertBox('#forgotError', err.message);
    }
  });

  // 有令牌就直接进控制台，否则登录页
  const token = localStorage.getItem(TOKEN_KEY);
  if (!token) { showLogin(); return; }
  showApp();
  await boot();
})();

/* ══════════════════════════════════════════════════════════════════════════
   ⛔ 补进 `API` 的发布相关方法
   —— **方法名与重写前逐字一致**（`releases` / `upsertRelease` / `patchRelease` /
      `delRelease`），只把定义从字面量里挪到这里，端点与请求体格式完全没动。
      GET   api/admin/releases
      POST  api/admin/releases            （新增或更新，以 version_code 为键 upsert）
      PATCH api/admin/releases/{code}
      DEL   api/admin/releases/{code}
      POST  api/admin/releases/apk        （multipart 上传安装包）
      POST  api/admin/upload              （图床上传）
   ══════════════════════════════════════════════════════════════════════════ */
API.releases       = () => api('api/admin/releases');
API.upsertRelease  = (body) => api('api/admin/releases', { method: 'POST', body: JSON.stringify(body) });
API.patchRelease   = (code, body) => api('api/admin/releases/' + code, { method: 'PATCH', body: JSON.stringify(body) });
API.delRelease     = (code) => api('api/admin/releases/' + code, { method: 'DELETE' });
API.uploadReleaseApk = (fd) => api('api/admin/releases/apk', { method: 'POST', body: fd, headers: {} });
API.upload         = (fd) => api('api/admin/upload', { method: 'POST', body: fd, headers: {} });

/* ══════════════════════════════════════════════════════════════════════════
   仪表盘 —— 这一页是"精美"的主场
   ──────────────────────────────────────────────────────────────────────────
   信息层次：① 8 个 KPI（一眼看规模）→ ② 两个环形（版本构成 / 缓存命中构成）
   → ③ 14 天趋势面积图 → ④ 最近操作时间线。
   环形的数据**全部来自后端已有字段**（`versions` 与 `hit_tokens/miss_tokens`），
   没有为此新增任何接口或字段。
   ══════════════════════════════════════════════════════════════════════════ */

const SVG_USERS = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 12a5 5 0 1 0 0-10 5 5 0 0 0 0 10Zm0 2c-4.4 0-8 2.2-8 5v1h16v-1c0-2.8-3.6-5-8-5Z"/></svg>';
const SVG_DEVICE = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2Zm0 2v16h10V4H7Z"/></svg>';
const SVG_CHAT = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M4 3h16a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H8l-4 4V4a1 1 0 0 1 1-1Z"/></svg>';
const SVG_PULSE = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M3 12h4l2-5 3 10 3-7 2 2h4v2h-5l-1-1-3 7-3-10-1 4H3v-2Z"/></svg>';

function kpiCard(label, value, foot, icon, cls = '', id = '') {
  return `<div class="kpi ${cls}"${id ? ` id="${esc(id)}"` : ''}>
    <span class="kpi-ico">${icon}</span>
    <span class="kpi-label">${esc(label)}</span>
    <span class="kpi-value num">${esc(value)}</span>
    <span class="kpi-foot">${esc(foot)}</span>
  </div>`;
}

async function renderDashboard(view) {
  const d = await API.dash();
  const c = d.cards || {};
  const trend = d.trend || [];
  const versions = d.versions || [];
  const recent = d.recent || [];

  const hit = Number(c.hit_tokens || 0);
  const miss = Number(c.miss_tokens || 0);
  const billed = hit + miss;
  const hitRate = billed ? (hit * 100 / billed).toFixed(1) + '%' : '—';

  const trendPoints = trend.map((t) => ({ label: fmtDay(t.day), v: Number(t.active_devices || 0) }));
  const reportPoints = trend.map((t) => ({ label: fmtDay(t.day), v: Number(t.reports || 0) }));

  const versionSegs = versions.map((v) => ({ label: v.app_version || '未知', value: Number(v.n || 0) }));

  view.innerHTML = `
    <div class="kpi-row">
      ${kpiCard('设备总数', fmtNum(c.devices), '累计上报过的设备', SVG_DEVICE)}
      ${kpiCard('7 日活跃', fmtNum(c.active_7d), '最近一周还开过的', SVG_PULSE, 'kpi-ok')}
      ${kpiCard('注册用户', fmtNum(c.users), '账号是可选功能', SVG_USERS)}
      ${kpiCard('会话总数', fmtNum(c.sessions), '所有设备累计', SVG_CHAT)}
    </div>

    <div class="kpi-row mt12">
      ${kpiCard('消息总数', fmtNum(c.messages), '所有设备累计', SVG_CHAT)}
      ${kpiCard('记忆条目', fmtNum(c.memories), '她记得的事', SVG_USERS)}
      ${kpiCard('人设总数', fmtNum(c.personas), '所有设备累计', SVG_USERS)}
      ${kpiCard('缓存命中率', hitRate, `命中 ${fmtCompact(hit)} / 未命中 ${fmtCompact(miss)}`, SVG_PULSE, 'kpi-warn')}
    </div>

    <div class="grid grid-2-1 mt12">
      <div class="card">
        <div class="card-head">
          <span class="card-title">活跃设备趋势</span>
          <span class="card-sub">近 ${trendPoints.length} 天 · 每天上报过数据的设备数</span>
        </div>
        ${areaChart(trendPoints, { aria: '活跃设备趋势' })}
      </div>
      <div class="card">
        <div class="card-head">
          <span class="card-title">版本构成</span>
          <span class="card-sub">${versionSegs.length} 个版本</span>
        </div>
        ${versionSegs.length
          ? donut(versionSegs, { centerValue: fmtNum(trend[trend.length - 1] ? c.devices : c.devices), centerLabel: '设备总数' })
          : '<div class="empty">还没有版本数据</div>'}
      </div>
    </div>

    <div class="grid grid-1-2 mt12">
      <div class="card">
        <div class="card-head">
          <span class="card-title">缓存命中构成</span>
          <span class="card-sub">token 计费口径</span>
        </div>
        ${billed
          ? donut(
              [
                { label: '命中（省钱）', value: hit, text: fmtCompact(hit) },
                { label: '未命中', value: miss, text: fmtCompact(miss) },
              ],
              { centerValue: hitRate, centerLabel: '命中率', colors: ['#2FA36B', '#E5484D'], size: 132, thickness: 11 })
          : '<div class="empty">还没有计费数据</div>'}
      </div>

      <div class="card">
        <div class="card-head">
          <span class="card-title">每天上报次数</span>
          <span class="card-sub">上报开关开启的设备才有</span>
        </div>
        ${areaChart(reportPoints, { aria: '上报趋势' })}
      </div>
    </div>

    <div class="grid grid-2-1 mt12">
      <div class="card">
        <div class="card-head">
          <span class="card-title">最近操作</span>
          <span class="card-sub">只记后台的写操作</span>
        </div>
        ${recent.length ? `<div class="timeline">${recent.map((r, i) => `
          <div class="tl-item ${i === 0 ? 'accent' : ''}">
            <span class="tl-time">${esc(fmtAgo(r.created_at))}</span>
            <span class="tl-dot"><i></i></span>
            <span class="tl-text"><b>${esc(r.action)}</b> ${esc(String(r.detail || '').slice(0, 46))}
              <span class="muted mono"> ${esc(r.ip || '')}</span></span>
          </div>`).join('')}</div>` : '<div class="empty">还没有操作记录</div>'}
      </div>
      <div class="card">
        <div class="card-head">
          <span class="card-title">数据结构</span>
          <span class="card-sub">各维度累计占比</span>
        </div>
        ${bars([
          { label: '会话', value: Number(c.sessions || 0) },
          { label: '消息', value: Number(c.messages || 0) },
          { label: '记忆', value: Number(c.memories || 0) },
          { label: '人设', value: Number(c.personas || 0) },
        ])}
      </div>
    </div>
  `;
}

/* ═══════════════════ 设备 ═══════════════════ */

const devicesState = { q: '', page: 1, size: 20 };

async function renderDevices(view) {
  // ⚠️ 「账号关联」列已按用户要求**移除**（那一版是把设备手动挂到账号上，用户不要了）。
  //    接口层没动：`patchDevice` 仍按原样支持 alias，只是界面不再提供 user_id 的入口。
  const data = await API.devices({ q: devicesState.q, page: devicesState.page, size: devicesState.size });
  const rows = data.items || [];

  const body = rows.length
    ? rows.map((r) => {
        const billed = (r.hit_tokens || 0) + (r.miss_tokens || 0);
        const rate = billed ? ((r.hit_tokens || 0) * 100 / billed).toFixed(1) + '%' : '—';
        const fresh = (Date.now() - new Date(r.last_seen).getTime()) < 86400 * 1000;
        return `<tr>
          <td>
            <div class="mono">${esc(r.device_id)}</div>
            ${r.alias ? `<div class="muted">别名：${esc(r.alias)}</div>` : ''}
          </td>
          <td>${esc(r.model || '—')}<div class="muted">Android ${esc(r.android_version || '?')}</div></td>
          <td><span class="badge badge-brand">${esc(r.app_version || '—')}</span></td>
          <td class="num">${fmtNum(r.session_count)}</td>
          <td class="num">${fmtNum(r.message_count)}</td>
          <td class="num">${fmtNum(r.memory_count)}</td>
          <td class="num">${esc(rate)}</td>
          <td class="nowrap">
            ${fresh ? '<span class="badge badge-ok">活跃</span>' : '<span class="badge">沉默</span>'}
            <div class="muted">${esc(fmtAgo(r.last_seen))}</div>
          </td>
          <td class="nowrap">
            <button class="btn btn-soft btn-sm" data-act="alias" data-id="${esc(r.device_id)}">改名</button>
            <button class="btn btn-danger btn-sm" data-act="del" data-id="${esc(r.device_id)}">删除</button>
          </td>
        </tr>`;
      }).join('')
    : '<tr class="empty-row"><td colspan="9">还没有设备上报。App 端开启上报后，这里会出现。</td></tr>';

  view.innerHTML = `
    <div class="toolbar">
      <label class="search">
        <svg viewBox="0 0 24 24" fill="currentColor"><path d="M10 2a8 8 0 1 0 4.9 14.3l5.4 5.4 1.4-1.4-5.4-5.4A8 8 0 0 0 10 2Zm0 2a6 6 0 1 1 0 12 6 6 0 0 1 0-12Z"/></svg>
        <input id="devQ" type="search" placeholder="搜索设备 ID / 别名 / 型号" value="${esc(devicesState.q)}">
      </label>
      <span class="grow"></span>
      <span class="muted">共 ${fmtNum(data.total)} 台</span>
    </div>

    <div class="table-wrap">
      <table>
        <thead><tr>
          <th>设备</th><th>机型</th><th>App 版本</th>
          <th class="num">会话</th><th class="num">消息</th><th class="num">记忆</th><th class="num">命中率</th>
          <th>最近活跃</th><th>操作</th>
        </tr></thead>
        <tbody>${body}</tbody>
      </table>
    </div>
    ${pagerHtml(data)}
  `;

  $('#devQ').addEventListener('change', (e) => {
    devicesState.q = e.target.value.trim();
    devicesState.page = 1;
    route();
  });

  $$('[data-act]', view).forEach((btn) => {
    btn.addEventListener('click', async () => {
      const id = btn.dataset.id;
      if (btn.dataset.act === 'alias') {
        const val = prompt('给这台设备起个名字（留空则清除）：', '');
        if (val === null) return;
        await API.patchDevice(id, { alias: val });
        toast('已更新', 'ok');
        route();
      } else {
        const ok = await confirmBox('删除这台设备？',
          `设备 <span class="mono">${esc(id)}</span> 的统计数据会一并删除。<br>
           <b>只影响后台统计</b>，不会影响那台手机上的任何内容。`, '删除');
        if (!ok) return;
        await API.delDevice(id);
        toast('已删除', 'ok');
        route();
      }
    });
  });

  bindPager(view, (p) => { devicesState.page = p; route(); });
}

/* ═══════════════════ 用户 ═══════════════════ */

const usersState = { q: '', page: 1, size: 20 };

/** 头像：接口本来就返回 `avatar_url`（`SELECT *`），没图就首字母占位 */
function avatarHtml(u) {
  const name = String(u.nickname || u.email || u.uid || '?').trim();
  const url = String(u.avatar_url || '').trim();
  if (!url) {
    return `<span class="avatar avatar-fallback" title="${esc(name)}">${esc(name.slice(0, 1))}</span>`;
  }
  return `<img class="avatar" src="${esc(url)}" alt="" title="${esc(name)}"
               onerror="this.style.visibility='hidden'">`;
}

async function renderUsers(view) {
  const data = await API.users({ q: usersState.q, page: usersState.page, size: usersState.size });
  const rows = data.items || [];

  // 未关联设备总数（v0.61.24.1：设备页并入用户页后，匿名设备从这张 KPI 卡点进去看）
  const orphan = await API.devices({ orphan: true, size: 1 });
  const orphanTotal = orphan.total || 0;

  // 小统计：有头像 / 绑邮箱 —— 都用列表里已有字段算，不额外请求
  const withAvatar = rows.filter((r) => String(r.avatar_url || '').trim()).length;
  const withMail = rows.filter((r) => r.email).length;

  // 行内命中率：直接用列表接口带回来的 hit/miss（v0.61.24.1 —— 免得"一人一次详情请求"）
  const rateOf = (r) => {
    const h = Number(r.hit_tokens || 0);
    const m = Number(r.miss_tokens || 0);
    return (h + m) > 0 ? (h * 100 / (h + m)).toFixed(1) + '%' : '—';
  };
  const body = rows.length
    ? rows.map((r) => `<tr>
        <td>${avatarHtml(r)}</td>
        <td class="mono">${esc(r.uid)}</td>
        <td>${esc(r.nickname || '—')}</td>
        <td class="nowrap">${fmtNum(r.device_count)}</td>
        <td class="nowrap">${esc(r.last_active ? fmtAgo(r.last_active) : '—')}</td>
        <td class="nowrap">${rateOf(r)}</td>
        <td class="nowrap">${esc(r.last_login_at ? fmtAgo(r.last_login_at) : '—')}</td>
        <td class="nowrap">
          <button class="btn btn-soft btn-sm" data-act="detail" data-id="${esc(r.id)}">详情</button>
        </td>
      </tr>`).join('')
    : '<tr class="empty-row"><td colspan="8">还没有注册用户。App 首次启动会要求注册（昵称 + 密码）。</td></tr>';

  view.innerHTML = `
    <div class="kpi-row">
      ${kpiCard('注册用户', fmtNum(data.total), '全部账号', SVG_USERS)}
      ${kpiCard('未关联设备', fmtNum(orphanTotal), '点击查看 —— 没登录过账号的', SVG_DEVICE, 'kpi-ok', 'orphanKpi')}
      ${kpiCard('本页有头像', fmtNum(withAvatar), '上传过头像的', SVG_USERS)}
      ${kpiCard('本页绑邮箱', fmtNum(withMail), '可用于找回密码', SVG_CHAT)}
    </div>

    <div class="toolbar" style="margin-top:12px">
      <label class="search">
        <svg viewBox="0 0 24 24" fill="currentColor"><path d="M10 2a8 8 0 1 0 4.9 14.3l5.4 5.4 1.4-1.4-5.4-5.4A8 8 0 0 0 10 2Zm0 2a6 6 0 1 1 0 12 6 6 0 0 1 0-12Z"/></svg>
        <input id="uQ" type="search" placeholder="搜索邮箱 / 昵称 / ID" value="${esc(usersState.q)}">
      </label>
      <span class="grow"></span>
      <span class="muted">共 ${fmtNum(data.total)} 人</span>
    </div>

    <div class="table-wrap">
      <table>
        <thead><tr>
          <th></th><th>UID</th><th>昵称</th><th>设备</th><th>最近活跃</th><th>命中率</th><th>最近登录</th><th>操作</th>
        </tr></thead>
        <tbody>${body}</tbody>
      </table>
    </div>
    ${pagerHtml(data)}
  `;

  $('#uQ').addEventListener('change', (e) => {
    usersState.q = e.target.value.trim();
    usersState.page = 1;
    route();
  });

  // 点「详情」→ 弹窗：设备 / 版本 / 缓存命中 / 会话 / 人设 / 记忆 / 消息 + 重置密码
  Array.from(view.querySelectorAll('[data-act="detail"]')).forEach((btn) => {
    btn.addEventListener('click', () => openUserDetail(btn.dataset.id));
  });

  // 「未关联设备」KPI 卡 → 列出还没归属账号的设备（v0.61.24.1：原「设备」页并入本页）
  const orphanCard = document.getElementById('orphanKpi');
  if (orphanCard) {
    orphanCard.style.cursor = 'pointer';
    orphanCard.addEventListener('click', openOrphanDevices);
  }

  bindPager(view, (p) => { usersState.page = p; route(); });
}

/* ── 用户详情弹窗（v0.61.23 用户管理系统）──
 *
 * 数据来自 `GET api/admin/users/{id}`：设备列表是**两路的并集** ——
 * 「自动关联」（客户端带身份上报写入 user_devices）与「手工指认」（devices.user_id）
 * 都会显示，老设备不会因为来源不同而漏掉。
 * 汇总里的命中率由服务端算好；没有请求时它是 null → 显示 "—"（不是 0，两者含义不同）。
 */
function showUserModal(title, bodyHtml) {
  $('#modalTitle').textContent = title;
  $('#modalBody').innerHTML = bodyHtml;
  // 详情是"看"的：确定键收起来，只留一个关闭
  $('#modalOk').style.display = 'none';
  const cancel = $('#modalCancel');
  cancel.textContent = '关闭';
  $('#modal').classList.remove('hidden');
  cancel.onclick = () => {
    $('#modal').classList.add('hidden');
    cancel.textContent = '取消';
    $('#modalOk').style.display = '';
  };
}

function deviceRowHtml(d) {
  const hit = Number(d.hit_tokens || 0);
  const miss = Number(d.miss_tokens || 0);
  const rate = (hit + miss) > 0 ? (hit * 100 / (hit + miss)).toFixed(1) + '%' : '—';
  return `<tr>
    <td class="mono">${esc(d.device_id)}</td>
    <td>${esc(d.model || '—')}</td>
    <td class="nowrap">${esc(d.app_version || '—')}</td>
    <td class="nowrap">${esc(fmtAgo(d.last_seen))}</td>
    <td class="nowrap">${fmtNum(d.persona_count)} / ${fmtNum(d.session_count)} / ${fmtNum(d.message_count)} / ${fmtNum(d.memory_count)}</td>
    <td class="nowrap">${fmtNum(hit)} / ${fmtNum(miss)} <span class="muted">(${rate})</span></td>
  </tr>`;
}

async function openUserDetail(id) {
  let d;
  try {
    d = await API.userDetail(id);
  } catch (e) {
    toast('读取用户详情失败：' + (e.message || e), 'err');
    return;
  }
  const u = d.user || {};
  const s = d.summary || {};
  const devs = d.devices || [];
  const rate = (s.hit_rate === null || s.hit_rate === undefined) ? '—' : s.hit_rate + '%';

  showUserModal('用户详情 · ' + (u.nickname || u.uid || ''), `
    <div class="kpi-row">
      ${kpiCard('设备数', fmtNum(s.device_count), '用过的设备', SVG_USERS)}
      ${kpiCard('缓存命中率', rate, `命中 ${fmtNum(s.hit_tokens)} / 未命中 ${fmtNum(s.miss_tokens)}`, SVG_CHAT, 'kpi-ok')}
      ${kpiCard('消息', fmtNum(s.message_count), `会话 ${fmtNum(s.session_count)}`, SVG_CHAT)}
      ${kpiCard('人设 / 记忆', fmtNum(s.persona_count) + ' / ' + fmtNum(s.memory_count), '全部人设合计', SVG_USERS)}
    </div>

    <p class="muted" style="margin:10px 0 6px;font-size:12px;line-height:1.8">
      UID ${esc(u.uid || '—')} · ${esc(u.nickname || '—')} · ${esc(u.email || '未绑定邮箱')}<br>
      注册 ${esc(fmtTime(u.created_at))} · 最近登录 ${esc(u.last_login_at ? fmtAgo(u.last_login_at) : '—')}
        · 最近活跃 ${esc(s.last_active ? fmtAgo(s.last_active) : '—')}
      </p>

    <div class="table-wrap">
      <table>
        <thead><tr><th>设备</th><th>机型</th><th>版本</th><th>最近上报</th><th>人设/会话/消息/记忆</th><th>命中/未命中</th></tr></thead>
        <tbody>${devs.length
          ? devs.map(deviceRowHtml).join('')
          : '<tr class="empty-row"><td colspan="6">该用户还没有关联设备 —— 等 Ta 下次带登录态启动 App 上报后自动出现。</td></tr>'}</tbody>
      </table>
    </div>

    <div class="modal-actions" style="justify-content:flex-start;gap:10px">
      <button class="btn btn-danger" id="uResetPw">重置密码</button>
      <span class="muted" style="font-size:12px">生成一个随机新密码，只显示这一次</span>
    </div>
  `);

  $('#uResetPw').addEventListener('click', async () => {
    if (!confirm('确定给「' + (u.nickname || u.uid) + '」重置密码？旧密码将立即失效。')) return;
    try {
      const r = await API.resetUserPassword(id);
      const box = document.createElement('div');
      box.className = 'card';
      box.style.marginTop = '10px';
      box.innerHTML = `<div class="card-head"><span class="card-title">新密码（只显示这一次）</span></div>
        <p class="mono" style="font-size:16px;margin:8px 0 0;user-select:all">${esc(r.password)}</p>
        <p class="muted" style="margin:6px 0 0;font-size:12px">请当场抄给用户；关掉这个窗后服务端也拿不回来。</p>`;
      $('#modalBody').appendChild(box);
      toast('密码已重置', 'ok');
    } catch (e) {
      toast('重置失败：' + (e.message || e), 'err');
    }
  });
}

/* ── 未关联设备（v0.61.24.1：原「设备」页并入用户页后，匿名设备从这里看）──
 *
 * 为什么单独给它一个入口：绝大多数用户**不注册账号**，他们的设备永远不会关联到
 * 任何 user。页签合并之后如果没有这个入口，这些设备就"看不见了"。
 */
async function openOrphanDevices() {
  let d;
  try {
    d = await API.devices({ orphan: true, page: 1, size: 50 });
  } catch (e) {
    toast('读取设备失败：' + (e.message || e), 'err');
    return;
  }
  const devs = d.items || [];
  showUserModal('未关联设备 · ' + fmtNum(d.total) + ' 台', `
    <p class="muted" style="margin:0 0 8px;font-size:12px;line-height:1.7">
      这些设备还没有归属任何账号（没登录过账号，或用的是老版本 App）。
      Ta 下次带登录态启动 App 并上报后，设备会自动出现在该用户的详情里。
    </p>
    <div class="table-wrap">
      <table>
        <thead><tr><th>设备</th><th>机型</th><th>版本</th><th>最近上报</th><th>人设/会话/消息/记忆</th><th>命中/未命中</th></tr></thead>
        <tbody>${devs.length
          ? devs.map(deviceRowHtml).join('')
          : '<tr class="empty-row"><td colspan="6">没有未关联的设备 —— 上报过的设备都归到了账号下。</td></tr>'}</tbody>
      </table>
    </div>
  `);
}

/* ── 模型卡片：一张卡 = 一个模型（真名 + 显示名）──
 *
 * 为什么不用一个「id=显示名」的文本框：那种写法要用户先看懂约定，
 * 而且"等号"这个符号本身在输入框里几乎看不见 —— 用户反馈「我看不到等号」。
 * 卡片把两件事摊开成两个框：左边发给服务商、右边给用户看，各自有 placeholder。
 */
const MODEL_INDEX_GLYPH = ['①', '②', '③', '④', '⑤', '⑥', '⑦', '⑧', '⑨', '⑩'];

function modelRowHtml(id, label) {
  return `<div class="card" data-model-row style="padding:10px 12px">
    <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
      <span class="muted" data-idx style="font-size:13px;min-width:18px"></span>
      <input class="fg-id" type="text" value="${esc(id || '')}"
             placeholder="真名：发给服务商的，如 deepseek-flash" style="flex:1 1 190px">
      <input class="fg-label" type="text" value="${esc(label || '')}"
             placeholder="显示名：用户看到的，可留空" style="flex:1 1 150px">
      <button type="button" class="btn btn-danger btn-sm" data-del>删</button>
    </div>
  </div>`;
}

/** 重排①②③ —— 序号就是顺序，而**第一张是客户端默认用的那个**。 */
function renumberModelRows() {
  const rows = Array.from(document.querySelectorAll('#fgModelList [data-model-row]'));
  rows.forEach((row, i) => {
    const n = row.querySelector('[data-idx]');
    if (n) n.textContent = MODEL_INDEX_GLYPH[i] || String(i + 1) + '.';
  });
}

/** 加一张卡；**真名重复就不再加**（同一个模型两张卡只会让人分不清哪个算）。 */
function addModelRow(id, label) {
  const box = document.querySelector('#fgModelList');
  const mid = String(id || '').trim();
  if (!box || !mid) return false;
  const exists = Array.from(box.querySelectorAll('.fg-id'))
    .some((el) => el.value.trim() === mid);
  if (!exists) {
    box.insertAdjacentHTML('beforeend', modelRowHtml(mid, label));
    renumberModelRows();
    // 新加的卡滚进视野 —— 一次点十几下时，不加这个用户根本看不到加没加上
    const last = box.lastElementChild;
    if (last && last.scrollIntoView) last.scrollIntoView({ block: 'nearest' });
  }
  return true;
}

/** 从卡片里读回模型表（保存时用）。**真名为空的那张直接丢掉**，不报错。 */
function readModelRows() {
  const rows = Array.from(document.querySelectorAll('#fgModelList [data-model-row]'));
  const out = [];
  rows.forEach((row) => {
    const idEl = row.querySelector('.fg-id');
    const labelEl = row.querySelector('.fg-label');
    const id = idEl ? idEl.value.trim() : '';
    if (!id) return;
    out.push({ id: id, label: labelEl ? labelEl.value.trim() : '' });
  });
  return out;
}

/* ═══════════════════ 免费 API ═══════════════════ */

async function renderFreeGroup(view) {
  const res = await API.freeGroup();
  const c = res.config || res;

  view.innerHTML = `
    <div class="grid grid-2-1">
      <div class="card">
        <div class="card-head">
          <span class="card-title">免费分组</span>
          <span class="card-sub">所有用户共享的一组连接设置</span>
        </div>

        <label class="field">
          <span>启用</span>
          <span class="switch"><input type="checkbox" id="fgEnabled" ${c.enabled ? 'checked' : ''}><span class="track"></span></span>
        </label>
        <label class="field"><span>显示名</span>
          <input id="fgName" type="text" value="${esc(c.name || '')}" placeholder="例：免费体验">
        </label>
        <label class="field"><span>接口地址（base_url）</span>
          <input id="fgBase" type="text" value="${esc(c.base_url || '')}" placeholder="https://…/v1">
        </label>
        <label class="field"><span>API Key</span>
          <input id="fgKey" type="text" value="${esc(c.api_key || '')}" placeholder="sk-…">
        </label>
        <label class="field"><span>提示文案（notice）</span>
          <textarea id="fgNotice" rows="3">${esc(c.notice || '')}</textarea>
        </label>
        <div class="field"><span>模型清单</span>
          <div class="muted" style="font-size:12px;line-height:1.7;margin:-2px 0 10px">
            每个模型一张卡：左边是<b>真正发给服务商的名字</b>（必须是真实模型，写错会请求失败），
            右边是<b>用户看到的名字</b>（随便改，不影响请求；留空就显示左边那个）。
            <b>第一张卡是客户端默认用的模型</b>，上下顺序就是这里的顺序。
          </div>
          <div id="fgModelList" style="display:flex;flex-direction:column;gap:8px"></div>
          <div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-top:10px">
            <button type="button" class="btn" id="fgProbe">拉取真实模型列表</button>
            <button type="button" class="btn btn-ghost" id="fgAddModel">+ 手动加一个</button>
            <span class="muted" id="fgProbeMsg" style="font-size:12px"></span>
          </div>
          <div id="fgProbeList" style="display:none;flex-wrap:wrap;gap:8px;margin:12px 0 0"></div>
          <p class="muted" style="margin:10px 0 0;font-size:12px;line-height:1.7">
            点「拉取」是拿<b>上面那个地址</b>去问服务商要真实清单（服务器去问，不用你填两遍）；
            拉到的每一项点一下就会加一张卡。以后换了服务地址，重拉一次、挑好、保存，客户端下次启动就跟上，不用发新版。
          </p>
        </div>

        <div class="modal-actions">
          <button class="btn btn-primary" id="fgSave">保存</button>
        </div>
      </div>

      <div class="card">
        <div class="card-head">
          <span class="card-title">当前状态</span>
        </div>
        <div class="bars">
          ${bars([
            { label: '启用', value: c.enabled ? 1 : 0, text: c.enabled ? '已开启' : '已关闭' },
            { label: 'Key', value: c.api_key ? c.api_key.length : 0, text: c.api_key ? '已配置' : '未配置' },
            { label: '地址', value: c.base_url ? 1 : 0, text: c.base_url ? '已配置' : '未配置' },
          ], { colors: ['#2FA36B', '#2F6BFF', '#22B8CF'] })}
        </div>
        <p class="muted" style="margin:14px 0 0;font-size:12px;line-height:1.7">
          保存后 <b>客户端下次启动</b>生效（它们启动时拉一次，服务端不推送）。<br>
          Key 只留在服务器上 —— 既不进源码包，也不下发到客户端的配置文件里。
        </p>
      </div>
    </div>
  `;

  // 已有的模型 → 渲染成卡片；没有就空着（下面有「+ 手动加一个」）
  (c.models || []).forEach((m) => addModelRow(m.id, m.label));
  renumberModelRows();

  // 删：事件委托，卡片是动态加的
  $('#fgModelList').addEventListener('click', (e) => {
    const del = e.target.closest('[data-del]');
    if (del) {
      const row = del.closest('[data-model-row]');
      if (row) row.remove();
      renumberModelRows();
    }
  });

  // 手动加一个空卡
  $('#fgAddModel').addEventListener('click', () => {
    const box = $('#fgModelList');
    box.insertAdjacentHTML('beforeend', modelRowHtml('', ''));
    renumberModelRows();
    const idEl = box.lastElementChild && box.lastElementChild.querySelector('.fg-id');
    if (idEl) idEl.focus();
  });

  $('#fgSave').addEventListener('click', async () => {
    await API.putFreeGroup({
      enabled: $('#fgEnabled').checked,
      name: $('#fgName').value,
      base_url: $('#fgBase').value,
      api_key: $('#fgKey').value,
      notice: $('#fgNotice').value,
      // 卡片列表 → 对象数组（后端本来就吃 {id,label}）
      models: readModelRows(),
    });
    toast('已保存（客户端下次启动生效）', 'ok');
    route();
  });

  // 「拉取真实模型列表」：拿**上面那个地址**去问服务商（这一步在服务器上做）。
  // ⚠️ 客户端永远不会自己去问 —— 这正是用户要的"我这边调整，他们不会有感觉"。
  $('#fgProbe').addEventListener('click', async () => {
    const msg = $('#fgProbeMsg');
    const box = $('#fgProbeList');
    msg.textContent = '正在拉取…';
    box.style.display = 'none';
    box.innerHTML = '';
    let r;
    try {
      r = await API.probeFreeGroupModels({
        base_url: $('#fgBase').value,
        api_key: $('#fgKey').value,
      });
    } catch (e) {
      msg.textContent = '拉取失败：' + ((e && e.message) || e);
      return;
    }
    if (!r || !r.ok) {
      msg.textContent = (r && r.error) || '拉取失败';
      return;
    }
    msg.textContent = `拉到 ${r.models.length} 个 —— 点一下加一张卡（已经加过的会标出来）`;
    box.style.display = 'flex';
    box.innerHTML = r.models.map((m) =>
      `<button type="button" class="btn" data-m="${esc(m)}" style="margin:0">${esc(m)}</button>`,
    ).join('');
    box.querySelectorAll('button[data-m]').forEach((b) => {
      b.addEventListener('click', () => {
        const m = b.getAttribute('data-m');
        // 已经在卡片里的就标出来，而不是默默无事发生 —— 点了没反应最容易被当成坏了
        addModelRow(m);
        b.disabled = true;
        b.textContent = m + ' ✓';
      });
    });
  });
}

/* ────────────────── 分页（多个页共用）────────────────── */

function pagerHtml(data) {
  const total = Number(data.total || 0);
  const size = Number(data.size || 20);
  const page = Number(data.page || 1);
  const pages = Math.max(1, Math.ceil(total / size));
  if (pages <= 1) return '';
  return `<div class="pager">
    <button data-p="1" ${page <= 1 ? 'disabled' : ''}>«</button>
    <button data-p="${page - 1}" ${page <= 1 ? 'disabled' : ''}>‹</button>
    <span>第 ${page} / ${pages} 页</span>
    <button data-p="${page + 1}" ${page >= pages ? 'disabled' : ''}>›</button>
    <button data-p="${pages}" ${page >= pages ? 'disabled' : ''}>»</button>
  </div>`;
}

function bindPager(view, go) {
  $$('.pager button', view).forEach((b) => {
    b.addEventListener('click', () => { if (!b.disabled) go(Number(b.dataset.p)); });
  });
}

/* ═══════════════════ 邮件服务 ═══════════════════ */

async function renderMail(view) {
  const [settingsRes, codesRes] = await Promise.all([API.settings(), API.codes()]);
  const v = settingsRes.values || {};
  const codes = codesRes.items || [];

  const codeRows = codes.length
    ? codes.map((c) => `<tr>
        <td>${esc(c.email)}</td>
        <td class="mono" style="letter-spacing:2px">${esc(c.code)}</td>
        <td>${c.expired ? '<span class="badge badge-danger">已过期</span>' : '<span class="badge badge-ok">可用</span>'}</td>
        <td class="nowrap">${esc(fmtTime(c.sent_at))}</td>
      </tr>`).join('')
    : '<tr class="empty-row"><td colspan="4">暂无待用验证码</td></tr>';

  view.innerHTML = `
    <div class="grid grid-2">
      <div class="card">
        <div class="card-head">
          <span class="card-title">SMTP 配置</span>
          <span class="badge ${settingsRes.mail_ready ? 'badge-ok' : 'badge-warn'}">
            ${settingsRes.mail_ready ? '已配置' : '未配置'}
          </span>
        </div>

        <div class="alert alert-info" style="margin-bottom:14px">
          配置保存在服务端数据库里，改完<b>立即生效</b>，不需要重启后端。
          密码类字段不会回显（显示 <span class="mono">__SET__</span> 表示已保存）—— 不改就别清空它。
        </div>

        <form id="mailForm">
          <div class="grid grid-2">
            <label class="field"><span>SMTP 服务器</span>
              <input name="mail_host" placeholder="smtp.qq.com" value="${esc(v.mail_host || '')}">
            </label>
            <label class="field"><span>端口</span>
              <input name="mail_port" placeholder="465" value="${esc(v.mail_port || '')}">
            </label>
          </div>
          <div class="grid grid-2">
            <label class="field"><span>加密方式</span>
              <select name="mail_security">
                <option value="ssl">SSL（465，推荐）</option>
                <option value="starttls">STARTTLS（587）</option>
                <option value="none">不加密（25，不推荐）</option>
              </select>
            </label>
            <label class="field"><span>账号</span>
              <input name="mail_user" placeholder="you@qq.com" value="${esc(v.mail_user || '')}">
            </label>
          </div>
          <label class="field"><span>密码 / 授权码</span>
            <input name="mail_password" type="password" placeholder="留空即不修改" value="${esc(v.mail_password || '')}">
          </label>
          <div class="grid grid-2">
            <label class="field"><span>发件地址</span>
              <input name="mail_from" placeholder="you@qq.com" value="${esc(v.mail_from || '')}">
            </label>
            <label class="field"><span>发件人显示名</span>
              <input name="mail_from_name" placeholder="Yuki 初雪" value="${esc(v.mail_from_name || '')}">
            </label>
          </div>
          <label class="field"><span>密码找回验证邮箱</span>
            <input name="admin_recovery_email" placeholder="用于确认「忘记密码」的人是你" value="${esc(v.admin_recovery_email || '')}">
          </label>

          <div class="modal-actions" style="justify-content:flex-start">
            <button class="btn btn-primary" type="submit">保存</button>
            <input id="mailTestTo" class="btn" style="width:190px;height:32px;font-weight:400"
                   placeholder="发一封测试邮件到…" type="email">
            <button class="btn" type="button" id="mailTestBtn">测试发信</button>
          </div>
        </form>
      </div>

      <div class="card">
        <div class="card-head">
          <span class="card-title">待用验证码</span>
          <span class="card-sub">注册 / 找回密码时下发</span>
        </div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>邮箱</th><th>验证码</th><th>状态</th><th>发送时间</th></tr></thead>
            <tbody>${codeRows}</tbody>
          </table>
        </div>
      </div>
    </div>
  `;

  // 加密方式回填（下拉不能像 input 那样直接写 value）
  const sec = $('select[name="mail_security"]');
  if (sec && v.mail_security) sec.value = v.mail_security;

  $('#mailForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const fd = new FormData(e.target);
    const values = {};
    fd.forEach((val, key) => { values[key] = val; });
    await API.putSettings(values);
    toast('已保存，立即生效', 'ok');
    route();
  });

  $('#mailTestBtn').addEventListener('click', async () => {
    const to = $('#mailTestTo').value.trim();
    if (!to) { toast('先填一个收件地址', 'error'); return; }
    try {
      const r = await API.testMail(to);
      toast(r && r.message ? r.message : '测试邮件已发送', r && r.ok === false ? 'error' : 'ok');
    } catch (err) {
      toast(err.message, 'error');
    }
  });
}

/* ═══════════════════ 版本发布 ═══════════════════ */

async function renderReleases(view) {
  const data = await API.releases();
  const items = data.items || [];

  // ⚠️ v0.61.21 重排（用户：「重新排版我看不太懂」）。
  // 原来：表单在右、列表在左，而「上传安装包」藏在**每一张卡片的条件下**
  //（只有"缺安装包"时才渲染）—— 于是已发包的版本永远没有上传入口，
  // 而用户还得自己把"左侧那张卡"和"右侧那个表单"对上号。
  // 现在：**从上到下 ①填信息 → ②传包 → ③已发布**，每一步一块、各自说清做什么。
  const rows = items.length ? items.map((r) => `
    <div class="release">
      <div class="release-head">
        <div class="release-main">
          <h4>${esc(r.version_name || '')} <span class="muted mono">vc${esc(r.version_code)}</span></h4>
          <div class="muted" style="font-size:12px">
            ${esc(fmtTime(r.published_at))}
            ${r.force ? ' · <span class="badge badge-warn">强制更新</span>' : ''}
            ${r.min_supported_code ? ` · 最低 vc${esc(r.min_supported_code)}` : ''}
          </div>
        </div>
        <div class="release-actions">
          <button class="btn btn-soft btn-sm" data-act="edit" data-code="${esc(r.version_code)}">编辑</button>
          <button class="btn btn-danger btn-sm" data-act="del" data-code="${esc(r.version_code)}">删除</button>
        </div>
      </div>
      <div style="margin-top:8px">
        ${r.apk_ready
          ? `<span class="badge badge-ok">✓ 已有安装包 ${esc(fmtBytes(r.apk_size || r.size))}</span>`
          : '<span class="badge badge-danger">缺安装包 —— 这一版用户收不到</span>'}
      </div>
      ${r.apk_issue ? `<div class="alert alert-warn" style="margin-top:10px">${esc(r.apk_issue)}</div>` : ''}
      ${r.notes ? `<div class="notes-block">${esc(r.notes)}</div>` : ''}
    </div>`).join('') : '<div class="empty">还没有发布记录</div>';

  // ② 的目标版本下拉：把每一版的"有没有包"直接写进选项文字 ——
  //    选错版本是这一步最容易犯的错，而它的代价是"用户收不到更新"。
  const targetOptions = items.map((r) =>
    `<option value="${esc(r.version_code)}">vc${esc(r.version_code)} · ${esc(r.version_name || '')}${r.apk_ready ? '（已有包，会上传替换）' : '（缺包）'}</option>`,
  ).join('');

  view.innerHTML = `
    <div class="grid" style="grid-template-columns:1fr;gap:14px">

      <!-- ① 填信息 -->
      <div class="card">
        <div class="card-head">
          <span class="card-title">① 填这一版的信息</span>
          <span class="card-sub">版本号已存在 = 改它；填新的 = 建它</span>
        </div>
        <form id="relForm">
          <div class="grid grid-2">
            <label class="field"><span>版本号（code，整数）</span>
              <input name="version_code" type="number" placeholder="107" required>
            </label>
            <label class="field"><span>版本名</span>
              <input name="version_name" placeholder="0.61.21" required>
            </label>
          </div>
          <label class="field"><span>更新说明（一行一条，用户看的就是这几行）</span>
            <textarea name="notes" rows="7" placeholder="［修复］…&#10;［新增］…"></textarea>
          </label>
          <div class="grid grid-2">
            <label class="field"><span>最低支持 code（低于它的一律不可用）</span>
              <input name="min_supported_code" type="number" placeholder="0">
            </label>
            <label class="field"><span>强制更新</span>
              <span class="switch"><input type="checkbox" name="force"><span class="track"></span></span>
            </label>
          </div>
          <button class="btn btn-primary btn-block" type="submit">保存版本</button>
        </form>
      </div>

      <!-- ② 传包 —— 独立一块，永远在场（v0.61.21：以前它只在"缺包"时才出现） -->
      <div class="card">
        <div class="card-head">
          <span class="card-title">② 上传安装包</span>
          <span class="card-sub">少了这一步，客户端收不到更新</span>
        </div>
        <div class="grid grid-2">
          <label class="field"><span>给哪一版</span>
            <select id="apkTarget">${targetOptions}</select>
          </label>
          <label class="field"><span>安装包（.apk）</span>
            <input id="apkFile" type="file" accept=".apk">
          </label>
        </div>
        <button class="btn btn-primary btn-block" id="apkUpload">上传安装包</button>
        <p class="muted" style="font-size:12px;margin:10px 0 0;line-height:1.7">
          上传后服务器会把实际大小与指纹写进那一版；客户端拿它们校验下载完整性。
        </p>
      </div>

      <!-- ③ 已发布 -->
      <div class="card">
        <div class="card-head">
          <span class="card-title">③ 已发布版本</span>
          <span class="card-sub">共 ${fmtNum(items.length)} 条</span>
        </div>
        ${rows}
      </div>

    </div>
  `;

  // ② 的上传：绑在自己那块上（不再依赖每张卡片的 data-act 委托）
  const upBtn = $('#apkUpload');
  if (upBtn) {
    upBtn.addEventListener('click', async () => {
      const file = $('#apkFile')?.files?.[0];
      const target = $('#apkTarget')?.value || '';
      if (!file) { toast('先选一个 .apk 文件', 'error'); return; }
      if (!target) { toast('没有可选的版本 —— 先在①里保存一个', 'error'); return; }
      const fd = new FormData();
      fd.append('file', file);
      fd.append('version_code', target);
      upBtn.disabled = true;
      upBtn.textContent = '上传中…';
      try {
        await API.uploadReleaseApk(fd);
        toast('安装包已上传', 'ok');
        route();
      } catch (err) {
        toast('上传失败：' + err.message, 'error');
        upBtn.disabled = false;
        upBtn.textContent = '上传安装包';
      }
    });
  }

  $('#relForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const fd = new FormData(e.target);
    await API.upsertRelease({
      version_code: Number(fd.get('version_code')),
      version_name: String(fd.get('version_name') || ''),
      notes: String(fd.get('notes') || ''),
      min_supported_code: Number(fd.get('min_supported_code') || 0),
      force: fd.get('force') === 'on',
    });
    toast('已保存', 'ok');
    route();
  });

  $$('[data-act]', view).forEach((btn) => {
    btn.addEventListener('click', async () => {
      const code = btn.dataset.code;
      const act = btn.dataset.act;

      if (act === 'del') {
        const ok = await confirmBox('删除这个版本？',
          `版本 <span class="mono">vc${esc(code)}</span> 的记录会被删除。<br>
           已经装了新版的用户不受影响；但<b>还没更新的人会看不到这次更新</b>。`, '删除');
        if (!ok) return;
        await API.delRelease(code);
        toast('已删除', 'ok');
        route();
        return;
      }

      if (act === 'edit') {
        const r = items.find((x) => String(x.version_code) === String(code)) || {};
        view.innerHTML = `
          <div class="card" style="max-width:560px">
            <div class="card-head"><span class="card-title">编辑 vc${esc(code)}</span></div>
            <label class="field"><span>版本名</span>
              <input id="eName" value="${esc(r.version_name || '')}">
            </label>
            <label class="field"><span>更新说明</span>
              <textarea id="eNotes" rows="7">${esc(r.notes || '')}</textarea>
            </label>
            <div class="grid grid-2">
              <label class="field"><span>最低支持 code</span>
                <input id="eMin" type="number" value="${esc(r.min_supported_code || 0)}">
              </label>
              <label class="field"><span>强制更新</span>
                <span class="switch"><input id="eForce" type="checkbox" ${r.force ? 'checked' : ''}><span class="track"></span></span>
              </label>
            </div>
            <div class="modal-actions">
              <button class="btn" id="eCancel">返回</button>
              <button class="btn btn-primary" id="eSave">保存</button>
            </div>
          </div>`;
        $('#eCancel').addEventListener('click', route);
        $('#eSave').addEventListener('click', async () => {
          await API.patchRelease(code, {
            version_name: $('#eName').value,
            notes: $('#eNotes').value,
            min_supported_code: Number($('#eMin').value || 0),
            force: $('#eForce').checked,
          });
          toast('已保存', 'ok');
          route();
        });
        return;
      }

      // ⚠️ v0.61.21：原来这里有一个 `act === 'upload'` 分支，读的是**卡片里**那个
      //    `input[data-apk]`。重排之后上传归 ② 那块卡片自己绑（见 renderReleases），
      //    这里的分支就成了永远进不来的死代码 —— 删掉，免得下一个人以为它还在生效。
    });
  });
}

/* ═══════════════════ 公告 ═══════════════════ */

async function renderAnnouncements(view) {
  const data = await API.announcements();
  const items = data.items || [];

  const list = items.length ? items.map((a) => `
    <div class="release">
      <div class="release-head">
        <div class="release-main">
          <h4>${esc(a.title || '(无标题)')}</h4>
          <div class="muted" style="font-size:12px">${esc(fmtTime(a.created_at))}</div>
        </div>
        <div class="release-actions">
          <button class="btn btn-danger btn-sm" data-act="del" data-id="${esc(a.id)}">删除</button>
        </div>
      </div>
      <div class="notes-block">${esc(a.body || '')}</div>
    </div>`).join('') : '<div class="empty">还没有公告</div>';

  view.innerHTML = `
    <div class="grid grid-2-1">
      <div>
        <div class="card-head" style="margin-bottom:10px">
          <span class="card-title">已发布公告</span>
          <span class="card-sub">App 启动时会拉取</span>
        </div>
        ${list}
      </div>
      <div class="card" style="align-self:start">
        <div class="card-head"><span class="card-title">发一条公告</span></div>
        <form id="annForm">
          <label class="field"><span>标题</span><input name="title" required></label>
          <label class="field"><span>正文</span><textarea name="body" rows="7" required></textarea></label>
          <button class="btn btn-primary btn-block" type="submit">发布</button>
        </form>
      </div>
    </div>
  `;

  $('#annForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const fd = new FormData(e.target);
    await API.addAnn({ title: fd.get('title'), body: fd.get('body') });
    toast('已发布', 'ok');
    route();
  });

  $$('[data-act="del"]', view).forEach((btn) => {
    btn.addEventListener('click', async () => {
      const ok = await confirmBox('删除这条公告？', '删除后 App 就不再显示它了。', '删除');
      if (!ok) return;
      await API.delAnn(btn.dataset.id);
      toast('已删除', 'ok');
      route();
    });
  });
}

/* ═══════════════════ 反馈 ═══════════════════ */

async function renderFeedback(view) {
  const data = await API.feedback();
  const items = data.items || [];

  const rows = items.length ? items.map((f) => `
    <div class="release">
      <div class="release-head">
        <div class="release-main">
          <h4>${esc(f.contact || f.email || '匿名')} <span class="muted" style="font-size:12px;font-weight:400">${esc(fmtAgo(f.created_at))}</span></h4>
        </div>
        <div class="release-actions">
          ${f.reply ? '<span class="badge badge-ok">已回复</span>' : '<span class="badge badge-warn">待回复</span>'}
        </div>
      </div>
      <div class="notes-block">${esc(f.content || '')}</div>
      ${f.reply ? `<div class="notes-block" style="border-color:var(--brand-dim)">我的回复：${esc(f.reply)}</div>` : ''}
      <div class="upload-row" style="margin-top:10px">
        <input class="btn" style="flex:1;min-width:0;height:32px;font-weight:400"
               placeholder="回复（留空再提交 = 撤销回复）" data-reply="${esc(f.id)}" value="${esc(f.reply || '')}">
        <button class="btn btn-soft btn-sm" data-act="reply" data-id="${esc(f.id)}">提交</button>
      </div>
    </div>`).join('') : '<div class="empty">还没有反馈</div>';

  view.innerHTML = `
    <div class="kpi-row">
      ${kpiCard('反馈总数', fmtNum(data.total !== undefined ? data.total : items.length), '累计收到', SVG_CHAT)}
      ${kpiCard('待回复', fmtNum(data.unreplied !== undefined ? data.unreplied : items.filter((x) => !x.reply).length), '还需要处理', SVG_CHAT, 'kpi-warn')}
      ${kpiCard('本页已回复', fmtNum(items.filter((x) => x.reply).length), '', SVG_CHAT, 'kpi-ok')}
      ${kpiCard('本页条数', fmtNum(items.length), '一次最多 100 条', SVG_PULSE)}
    </div>
    <div class="mt12">${rows}</div>
  `;

  $$('[data-act="reply"]', view).forEach((btn) => {
    btn.addEventListener('click', async () => {
      const input = $(`input[data-reply="${btn.dataset.id}"]`);
      await API.replyFeedback(btn.dataset.id, { reply: input ? input.value : '' });
      toast(input && input.value.trim() ? '已回复' : '已撤销回复', 'ok');
      route();
    });
  });
}

/* ═══════════════════ 操作日志 ═══════════════════ */

const auditState = { page: 1, size: 30 };

async function renderAudit(view) {
  const data = await API.audit({ page: auditState.page, size: auditState.size });
  const rows = data.items || [];

  const body = rows.length ? rows.map((r) => `<tr>
      <td class="nowrap">${esc(fmtTime(r.created_at))}</td>
      <td><b>${esc(r.action)}</b></td>
      <td>${esc(r.actor || '—')}</td>
      <td class="mono">${esc(r.ip || '')}</td>
      <td class="ellipsis" style="max-width:520px" title="${esc(r.detail || '')}">${esc(r.detail || '')}</td>
    </tr>`).join('')
    : '<tr class="empty-row"><td colspan="5">还没有操作记录</td></tr>';

  view.innerHTML = `
    <div class="table-wrap">
      <table>
        <thead><tr><th>时间</th><th>操作</th><th>操作者</th><th>IP</th><th>详情</th></tr></thead>
        <tbody>${body}</tbody>
      </table>
    </div>
    ${pagerHtml(data)}
  `;

  bindPager(view, (p) => { auditState.page = p; route(); });
}
