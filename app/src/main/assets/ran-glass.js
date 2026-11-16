/* RAN 液态玻璃 · V1 运行时注入 */
(function () {
  if (window.__rgGlassBooted) { window.__rgGlassRetag && window.__rgGlassRetag(); return; }
  window.__rgGlassBooted = true;

  var D = document, W = window;
  var debug = [];

  /* ---------- 1. 雾层 DOM ---------- */
  function buildFog() {
    if (D.getElementById('rg-fog-top')) return;
    var host = D.getElementById('main') || D.body;
    ['top', 'bot'].forEach(function (side) {
      var el = D.createElement('div');
      el.id = 'rg-fog-' + side;
      el.className = 'rg-fog rg-fog-' + side;
      el.setAttribute('data-liquid-ignore', '');
      for (var i = 0; i < 4; i++) el.appendChild(D.createElement('i'));
      el.appendChild(D.createElement('b'));
      host.appendChild(el);
    });
  }

  /* ---------- 2. 实测高度 ---------- */
  var topEl, botEl, ro;
  function measure() {
    topEl = topEl || D.getElementById('mhead');
    botEl = botEl || D.getElementById('inputzone');
    var r = D.documentElement.style;
    if (topEl) {
      var th = Math.round(topEl.getBoundingClientRect().height);
      if (th > 20) r.setProperty('--rg-top-h', th + 'px');
    }
    if (botEl) {
      var bh = Math.round(botEl.getBoundingClientRect().height);
      if (bh > 20) r.setProperty('--rg-bot-h', bh + 'px');
    }
  }
  function watchHeights() {
    measure();
    if (typeof ResizeObserver === 'undefined' || ro) return;
    ro = new ResizeObserver(function () { measure(); });
    if (topEl) ro.observe(topEl);
    if (botEl) ro.observe(botEl);
  }

  /* ---------- 3. 滚动状态（折射层淡出） ---------- */
  function watchScroll() {
    var chat = D.getElementById('chat');
    if (!chat) return;
    var t = null;
    chat.addEventListener('scroll', function () {
      D.body.classList.add('rg-scrolling');
      clearTimeout(t);
      t = setTimeout(function () { D.body.classList.remove('rg-scrolling'); }, 220);
    }, { passive: true });
  }

  /* ---------- 4. 折射层（liquidGL，失败即忽略） ---------- */
  var lensOn = false;
  // 注意：#in 是可编辑输入框，liquidGL 用快照复制内容，打字的字不会实时进折射层，
  // 会导致输入时看不到自己打的字。输入框继续用 CSS 玻璃（视觉上仍是搜索栏质感）。
  var LENS_SEL = '#burger, #mnew, #dots, #dagentbtn, #inbig, #plusbtn';
  function tryLens() {
    if (lensOn) return;
    try {
      if (localStorage.getItem('ranGlassLens') === '0') { debug.push('lens off'); return; }
      if (typeof W.liquidGL !== 'function') { debug.push('no liquidGL'); return; }
      var canvasTest = D.createElement('canvas');
      if (!canvasTest.getContext('webgl2') && !canvasTest.getContext('webgl')) {
        debug.push('no webgl'); return;
      }
      var els = D.querySelectorAll(LENS_SEL);
      if (!els.length) { debug.push('no target'); return; }
      for (var i = 0; i < els.length; i++) els[i].classList.add('rg-lens');
      W.liquidGL({
        engine: 'auto',
        // 只快照主容器：#main 高度=视口，纹理小、光栅化快；
        // 用 'body' 会把整条聊天流画进纹理，超 maxTextureSize 会被降质甚至初始化失败
        snapshot: '#main',
        target: '.rg-lens',
        resolution: 1.25,
        // 透镜主体：整块轻微放大/位移，边缘再叠加深斜面 —— 折射主要发生在边缘
        refraction: 0.075,
        bevelWidth: 0.15,
        bevelDepth: 0.22,
        // 色散：红蓝分向偏移，是「液态玻璃」区别于磨砂的关键
        aberration: 0.6,
        frost: 0,
        specular: true,
        shadow: false,
        tint: 'rgba(255,255,255,0.04)',
        // Q 弹：手指按上去，表面像黏稠液体一样被推开再回弹
        interaction: 'fluid',
        interactionStrength: 0.45,
        interactionRadius: 0.4,
        interactionViscosity: 0.5,
        on: {
          init: function () {
            D.body.classList.add('rg-lens-on');
            var cs = D.querySelectorAll('canvas');
            for (var k = 0; k < cs.length; k++) {
              if (!cs[k].id || cs[k].id.indexOf('rg') !== 0) cs[k].classList.add('rg-lens-canvas');
            }
          }
        }
      });
      lensOn = true;
      D.body.setAttribute('data-rg-lens', 'on');
      debug.push('lens on');
    } catch (e) {
      var msg = 'lens err: ' + (e && e.message ? e.message : e);
      debug.push(msg);
      try { D.body.setAttribute('data-rg-lens-fail', msg); } catch (_) {}
    }
  }

  /* ---------- 5. 一致性维护（SPA 换页/重绘） ---------- */
  window.__rgGlassRetag = function () {
    buildFog();
    watchHeights();
  };
  function watchDom() {
    if (typeof MutationObserver === 'undefined') return;
    var t = null;
    new MutationObserver(function () {
      clearTimeout(t);
      t = setTimeout(function () {
        topEl = null; botEl = null;
        measure();
        watchScroll();
      }, 300);
    }).observe(D.getElementById('app') || D.body, { childList: true, subtree: true });
  }

  function boot() {
    buildFog();
    watchHeights();
    watchScroll();
    watchDom();
    setTimeout(tryLens, 900);
  }

  if (D.readyState === 'loading') D.addEventListener('DOMContentLoaded', boot);
  else boot();

  /* 调试出口：window.__rgGlassDebug() */
  window.__rgGlassDebug = function () { return debug.join(' | '); };
})();
