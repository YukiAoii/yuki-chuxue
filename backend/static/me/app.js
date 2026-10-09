/* ═══════════════════════════════════════════════════════════════
   Yuki 初雪 · Ta 此刻 —— 用户侧网页逻辑
   登录（软件账号）→ 「我的角色」→ Ta 此刻的样子。
   数据只在登录本人账号下可见（服务端按归属校验）。
   文案纪律：不出现引擎/内部术语，全部口语化。
   ═══════════════════════════════════════════════════════════════ */
'use strict';

const $ = (sel) => document.querySelector(sel);
const TOKEN_KEY = 'yuki_me_token';

/* 驱动 → 用户口语词（与运营后台同一套） */
const DRIVE_WORDS = {
  possess: '想你', monitor: '牵挂', share: '想分享', libido: '心动', curiosity: '好奇',
  boredom: '无聊', duty: '上进心', reflection: '回味', grieve: '难过', anger: '生气', favored: '偏爱',
};

/* 意识状态 → 人话 */
const WAKE_WORDS = { awake: '醒着', sleeping: '睡着了', dreaming: '做梦中' };

const session = { token: localStorage.getItem(TOKEN_KEY) || '' };

/* ─── 工具 ─── */

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function clamp01(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return null;
  return Math.min(1, Math.max(0, n));
}

function relTime(iso) {
  if (!iso) return '';
  const t = Date.parse(iso);
  if (!Number.isFinite(t)) return '';
  const diff = Date.now() - t;
  if (diff < 60e3) return '刚刚';
  if (diff < 3600e3) return `${Math.floor(diff / 60e3)} 分钟前`;
  if (diff < 86400e3) return `${Math.floor(diff / 3600e3)} 小时前`;
  return `${Math.floor(diff / 86400e3)} 天前`;
}

async function api(path, opts = {}) {
  const headers = Object.assign({}, opts.headers);
  if (session.token) headers['Authorization'] = 'Bearer ' + session.token;
  let body = opts.body;
  if (body && typeof body === 'object') {
    body = JSON.stringify(body);
    headers['Content-Type'] = 'application/json';
  }
  const resp = await fetch(path, Object.assign({}, opts, { headers, body }));
  let data = null;
  try { data = await resp.json(); } catch { /* 非 JSON */ }
  if (!resp.ok) {
    const msg = (data && (data.detail || data.error)) || `请求失败（${resp.status}）`;
    const err = new Error(typeof msg === 'string' ? msg : JSON.stringify(msg));
    err.status = resp.status;
    throw err;
  }
  return data;
}

/* ─── 视图 ─── */

function showLogin() {
  $('#view-main').hidden = true;
  $('#view-login').hidden = false;
  $('#login-account').focus();
}

function showMain() {
  $('#view-login').hidden = true;
  $('#view-main').hidden = false;
}

function setError(el, msg) {
  if (!msg) { el.hidden = true; el.textContent = ''; return; }
  el.hidden = false;
  el.textContent = msg;
}

/* ─── 登录 / 登出 ─── */

async function doLogin(ev) {
  ev.preventDefault();
  const btn = $('#login-btn');
  const errBox = $('#login-error');
  setError(errBox, '');
  const account = $('#login-account').value.trim();
  const password = $('#login-password').value;
  if (!account || !password) { setError(errBox, '请填写账号和密码'); return; }
  btn.disabled = true; btn.textContent = '登录中…';
  try {
    const r = await api('/api/v1/auth/login', { method: 'POST', body: { account, password } });
    session.token = r.token;
    localStorage.setItem(TOKEN_KEY, r.token);
    $('#login-password').value = '';
    await enterMain();
  } catch (e) {
    setError(errBox, e.message || '登录失败');
  } finally {
    btn.disabled = false; btn.textContent = '登 录';
  }
}

async function doLogout() {
  try { await api('/api/v1/auth/logout', { method: 'POST' }); } catch { /* 忽略 */ }
  session.token = '';
  localStorage.removeItem(TOKEN_KEY);
  $('#persona-grid').innerHTML = '';
  showLogin();
}

/* ─── 主视图 ─── */

async function enterMain() {
  showMain();
  let user = null;
  try {
    const me = await api('/api/v1/me');
    user = me.user || {};
  } catch (e) {
    if (e.status === 401) { await doLogout(); return; }
  }
  $('#me-name').textContent = user ? (user.nickname || ('UID ' + user.uid)) : '';
  await loadPersonas();
}

async function loadPersonas() {
  const grid = $('#persona-grid');
  const errBox = $('#main-error');
  setError(errBox, '');
  $('#empty-hint').hidden = true;
  if (!grid.children.length) {
    grid.innerHTML = '<div class="card pcard skeleton"></div><div class="card pcard skeleton"></div>';
  }
  try {
    const r = await api('/xinchao/personas');
    renderPersonas(Array.isArray(r.personas) ? r.personas : []);
  } catch (e) {
    if (e.status === 401) { await doLogout(); return; }
    grid.innerHTML = '';
    setError(errBox, '读取角色状态失败：' + (e.message || '未知错误'));
  }
}

function renderPersonas(list) {
  const grid = $('#persona-grid');
  if (!list.length) {
    grid.innerHTML = '';
    $('#empty-hint').hidden = false;
    return;
  }
  grid.innerHTML = list.map(renderCard).join('');
}

function renderCard(p) {
  const v = p.view || {};
  const emo = v.emotion || {};
  const name = p.name || ('角色 · ' + String(p.personaId || '').slice(0, 8));
  const online = !!p.online;
  const wake = WAKE_WORDS[v.consciousness] || '';
  const asleep = wake && wake !== '醒着';

  /* 心情罗盘：valence→横向（左低落右愉悦），arousal→纵向（下平静上起伏）——纯视觉，不放数字 */
  const vx = clamp01(emo.valence);
  const vy = clamp01(emo.arousal);
  const dot = (vx === null || vy === null)
    ? ''
    : `<div class="compass-dot" style="left:${(vx * 100).toFixed(1)}%;top:${((1 - vy) * 100).toFixed(1)}%"></div>`;

  /* 惦记：按强度取前 5 个词（纯词，不露数字） */
  const pills = Object.entries(v.drives || {})
    .map(([k, val]) => ({ word: DRIVE_WORDS[k] || k, val: Number(val) || 0 }))
    .sort((a, b) => b.val - a.val)
    .slice(0, 5)
    .map((d) => `<span class="pill">${esc(d.word)}</span>`)
    .join('');

  const dream = (v.recentDreams || [])[0];
  const dreamBlock = dream
    ? `<div class="dream"><span class="dtitle">最近的梦</span><p>${esc(dream.dream)}</p></div>`
    : '';

  const nowText = v.text
    ? `<div class="now-text">${esc(v.text)}</div>`
    : `<div class="now-text empty">还没有新的消息——和 Ta 说说话吧</div>`;

  const wakeBadge = wake
    ? `<span class="wake-badge ${asleep ? 'asleep' : ''}">${esc(wake)}</span>`
    : '';

  return `
  <article class="card pcard">
    <div class="pcard-head">
      <span class="avatar-dot ${online ? '' : 'off'}" title="${online ? '在线' : '暂未连接'}"></span>
      <span class="pname" title="${esc(name)}">${esc(name)}</span>
      ${wakeBadge}
    </div>
    <div>
      <div class="section-title">Ta 此刻</div>
      ${nowText}
    </div>
    <div class="mood-row">
      <div class="compass">
        ${dot}
        <span class="compass-axis x">愉悦 →</span>
        <span class="compass-axis y">↑ 起伏</span>
      </div>
      <div class="mood-side">
        <div class="mood-label">Ta 现在心里最惦记的：</div>
        <div class="pills">${pills || '<span class="mood-label">（还在慢慢认识你）</span>'}</div>
      </div>
    </div>
    ${dreamBlock}
    <div class="pcard-foot">
      <span>最近有动静</span>
      <span>${esc(relTime(p.lastSeenAt)) || '—'}</span>
    </div>
  </article>`;
}

/* ─── 细雪 ─── */

function makeSnow() {
  const host = $('#snow');
  if (!host || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  const n = window.innerWidth < 640 ? 12 : 22;
  const frag = document.createDocumentFragment();
  for (let i = 0; i < n; i++) {
    const s = document.createElement('span');
    s.className = 'snowflake';
    const size = 2.5 + Math.random() * 3.5;
    s.style.width = size + 'px';
    s.style.height = size + 'px';
    s.style.left = (Math.random() * 100).toFixed(1) + 'vw';
    s.style.animationDuration = (12 + Math.random() * 16).toFixed(1) + 's';
    s.style.animationDelay = (-Math.random() * 24).toFixed(1) + 's';
    frag.appendChild(s);
  }
  host.appendChild(frag);
}

/* ─── 启动 ─── */

function boot() {
  makeSnow();
  $('#login-form').addEventListener('submit', doLogin);
  $('#logout-btn').addEventListener('click', doLogout);
  $('#refresh-btn').addEventListener('click', async () => {
    const btn = $('#refresh-btn');
    btn.disabled = true; btn.textContent = '刷新中…';
    try { await loadPersonas(); } finally { btn.disabled = false; btn.textContent = '刷新'; }
  });
  if (session.token) {
    enterMain();
  } else {
    showLogin();
  }
}

document.addEventListener('DOMContentLoaded', boot);
