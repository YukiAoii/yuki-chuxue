/* Yuki 初雪 · 官网交互
   三件事：卡片滚动淡入、飘雪（呼应「初雪」）、从后端取真实版本号。
   全部原生 JS，不引任何库 —— 一个官网不值得多几个网络请求。 */

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

/* ── 3. 版本信息 ──
   ⚠️ 从后端拿真实值，不写死在页面里 ——
      写死的版本号会在下一次发版后变成撒谎。 */
(function () {
  var el = document.getElementById('stat');
  if (!el) return;
  fetch('api/site')
    .then(function (r) { return r.json(); })
    .then(function (d) {
      var mb = d.apk_size ? (d.apk_size / 1048576).toFixed(1) + ' MB' : '';
      el.textContent = '当前版本 ' + (d.version || '—') +
        (mb ? ' · ' + mb : '') +
        (d.apk_time ? ' · 更新于 ' + d.apk_time : '');
    })
    .catch(function () {
      // ⚠️ 拿不到就安静退回一句话，别把页脚变成一个报错框
      el.textContent = 'Yuki 初雪';
    });
})();

/* ── 4. 顶部栏：汉堡菜单 + 滚动后贴住 ──
   ⚠️ 这一段上一版**漏写了** —— HTML 里有按钮、CSS 里有展开态，
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
