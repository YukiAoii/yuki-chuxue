/* Yuki 初雪 · 官网交互
   五件事：卡片滚动淡入、飘雪（呼应「初雪」）、版本信息（页脚 + 下载区）、
   反馈表单、顶部栏（汉堡菜单 + 滚动贴住）。
   全部原生 JS，不引任何库 —— 一个官网不值得多几个网络请求。

   v0.57.0 的信息结构重排带出两处新逻辑：
   · **下载区**要显示"当前是哪一版、多大、什么时候更新的"。这些值只有后端知道
     （它去网盘取），所以**不能写死在 HTML 里** —— 写死就是下一次发版后开始撒谎。
   · **反馈表单**：不登录也能发，所以提交失败时要**说清是为什么**
     （限流 / 太长 / 网络），而不是只把按钮变灰。 */

/* ── 1. 卡片滚动淡入 ──
   用 IntersectionObserver 而不是监听 scroll：后者每帧都要算位置，
   手机上又费电又容易掉帧。 */
(function () {
  var io = new IntersectionObserver(function (entries) {
    entries.forEach(function (e) {
      if (e.isIntersecting) {
        e.target.classList.add('in');
        io.unobserve(e.target);   // 出现过就不再观察，没必要反复触发
      }
    });
  }, { threshold: .15, rootMargin: '0px 0px -8% 0px' });

  document.querySelectorAll('.reveal').forEach(function (el, i) {
    el.style.transitionDelay = (i % 3) * 70 + 'ms';   // 同一行的几张错开一点，像依次浮现
    io.observe(el);
  });
})();

/* ── 2. 飘雪 ── */
(function () {
  var reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  var c = document.getElementById('snow');
  if (!c || reduce) return;

  var ctx = c.getContext('2d');
  var flakes = [], W, H;

  function size() {
    W = c.width = innerWidth;
    H = c.height = innerHeight;
    // 数量跟着屏宽走：小屏给少一点，省电
    var n = Math.min(70, Math.round(innerWidth / 14));
    flakes = Array.from({ length: n }, function () {
      return {
        x: Math.random() * W, y: Math.random() * H,
        r: Math.random() * 2.1 + .5,
        s: Math.random() * .5 + .25,
        d: Math.random() * Math.PI * 2
      };
    });
  }

  function tick() {
    ctx.clearRect(0, 0, W, H);
    ctx.fillStyle = 'rgba(255,255,255,.85)';
    ctx.beginPath();
    flakes.forEach(function (f) {
      f.y += f.s;
      f.d += .01;
      f.x += Math.sin(f.d) * .55;          // 左右微摆，直上直下太机械
      if (f.y > H + 6) { f.y = -6; f.x = Math.random() * W; }
      ctx.moveTo(f.x, f.y);
      ctx.arc(f.x, f.y, f.r, 0, 6.283);
    });
    ctx.fill();
    requestAnimationFrame(tick);
  }

  size();
  tick();
  addEventListener('resize', size);
})();

/* ── 3. 版本信息（页脚 + 下载区）──
   ⚠️ 全部从后端拿真实值，不写死在页面里 ——
      写死的版本号会在下一次发版后变成撒谎。
   ⚠️ 优先用**网盘那份**的大小/时间：下载按钮给的就是网盘里的最新包，
      拿本地旧包的数字去描述它，用户会觉得对不上。 */
(function () {
  var stat = document.getElementById('stat');
  var vEl = document.getElementById('dlVersion');
  var sEl = document.getElementById('dlSize');
  var tEl = document.getElementById('dlTime');

  function mb(n) {
    if (!n) return '—';
    // 小于 1MB 时按 KB 显示：否则一个 5KB 的占位包会渲染成「0.0 MB」，
    // 看起来像"大小读取失败"，而不是"这个文件很小"。
    return n >= 1048576
      ? (n / 1048576).toFixed(1) + ' MB'
      : Math.max(1, Math.round(n / 1024)) + ' KB';
  }

  fetch('api/site')
    .then(function (r) { return r.json(); })
    .then(function (d) {
      if (vEl) vEl.textContent = d.version || '—';
      if (sEl) sEl.textContent = mb(d.apk_size);
      if (tEl) tEl.textContent = d.apk_time || '—';
      if (stat) {
        stat.textContent = '当前版本 ' + (d.version || '—') +
          (d.apk_size ? ' · ' + mb(d.apk_size) : '') +
          (d.apk_time ? ' · 更新于 ' + d.apk_time : '');
      }
    })
    .catch(function () {
      // ⚠️ 拿不到就**安静**退回一句话，别把页面变成一个报错框；
      //    但下载按钮本身仍然可用（后端那条路与这段脚本无关）。
      if (stat) stat.textContent = 'Yuki 初雪';
      if (vEl) vEl.textContent = '—';
      if (sEl) sEl.textContent = '—';
      if (tEl) tEl.textContent = '—';
    });
})();

/* ── 4. 反馈表单 ──
   ⚠️ 失败必须说清**为什么**：限流（"发得太快了"）、太长、网络不通 ——
      三种情况用户要做的事完全不同，只把按钮变灰等于让他干瞪眼。 */
(function () {
  var form = document.getElementById('fbForm');
  if (!form) return;

  var kind = document.getElementById('fbKind');
  var content = document.getElementById('fbContent');
  var contact = document.getElementById('fbContact');
  var count = document.getElementById('fbCount');
  var msg = document.getElementById('fbMsg');
  var btn = document.getElementById('fbSubmit');

  function say(text, cls) {
    if (!msg) return;
    msg.textContent = text;
    msg.className = 'form-msg' + (cls ? ' ' + cls : '');
  }

  if (content && count) {
    content.addEventListener('input', function () {
      count.textContent = String(content.value.length);
    });
  }

  form.addEventListener('submit', function (e) {
    e.preventDefault();
    var text = (content && content.value || '').trim();
    if (!text) {
      say('写点什么再发吧。', 'bad');
      if (content) content.focus();
      return;
    }
    if (btn) btn.disabled = true;
    say('正在发送…');

    fetch('api/feedback', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        kind: kind ? kind.value : 'other',
        content: text,
        contact: (contact && contact.value || '').trim()
      })
    })
      .then(function (r) {
        return r.json()
          .catch(function () { return {}; })
          .then(function (d) { return { ok: r.ok, d: d }; });
      })
      .then(function (res) {
        if (btn) btn.disabled = false;
        if (res.ok) {
          form.reset();
          if (count) count.textContent = '0';
          say('收到了，谢谢你。', 'ok');
        } else {
          var dt = res.d && res.d.detail;
          // FastAPI 的参数校验错误是数组，那种情况给一句人能读的
          say(typeof dt === 'string' ? dt : '没发出去，过一会儿再试。', 'bad');
        }
      })
      .catch(function () {
        if (btn) btn.disabled = false;
        say('网络不通，过一会儿再试。', 'bad');
      });
  });
})();

/* ── 5. 顶部栏：汉堡菜单 + 滚动后贴住 ──
   ⚠️ 这一段上一版**漏写过** —— HTML 里有按钮、CSS 里有展开态，
      但没有任何东西去切换它，所以手机上点三条横杠毫无反应。 */
(function () {
  var nav = document.getElementById('nav');
  var burger = document.getElementById('burger');
  var links = document.getElementById('navLinks');
  if (!nav || !burger || !links) return;

  function setOpen(open) {
    links.classList.toggle('open', open);
    burger.setAttribute('aria-expanded', open ? 'true' : 'false');
  }

  burger.addEventListener('click', function (e) {
    e.stopPropagation();                       // 别让这次点击立刻被下面的"点外部关闭"收掉
    setOpen(!links.classList.contains('open'));
  });

  // 点任意一项后收起：不收起的话，跳过去之后菜单还盖在内容上
  links.querySelectorAll('a').forEach(function (a) {
    a.addEventListener('click', function () { setOpen(false); });
  });

  // 点空白处收起（手机上比"再点一次汉堡"更符合直觉）
  document.addEventListener('click', function (e) {
    if (!links.classList.contains('open')) return;
    if (!links.contains(e.target) && !burger.contains(e.target)) setOpen(false);
  });

  // 滚动后顶栏贴住：一开始让它浮在画上，不抢注意力
  function onScroll() { nav.classList.toggle('solid', window.scrollY > 20); }
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();
})();
