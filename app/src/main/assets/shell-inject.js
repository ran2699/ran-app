(function () {
  "use strict";

  // __CSS_B64__ 由 Kotlin 侧替换为 base64 后的 CSS 全文
  var CSS_B64 = "__CSS_B64__";
  var CLS = "ran-shell-android";
  var STYLE_ID = "ran-shell-style";

  function decodeCss() {
    try {
      var bin = atob(CSS_B64);
      var bytes = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      return new TextDecoder("utf-8").decode(bytes);
    } catch (e) {
      return "";
    }
  }

  function pageIsDark() {
    var h = document.documentElement;
    var b = document.body;
    if (h && h.classList.contains("dark")) return true;
    if (b && b.classList.contains("dark")) return true;
    try {
      return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
    } catch (e) {
      return false;
    }
  }

  function syncDark() {
    var h = document.documentElement;
    if (!h) return;
    if (pageIsDark()) h.classList.add("dark");
    else h.classList.remove("dark");
  }

  function injectStyle() {
    if (document.getElementById(STYLE_ID)) return;
    var h = document.documentElement;
    if (!h) return;
    var s = document.createElement("style");
    s.id = STYLE_ID;
    s.textContent = decodeCss();
    (document.head || h).appendChild(s);
  }

  var cssInjected = false;

  function boot() {
    var h = document.documentElement;
    if (!h) return false;

    if (!h.classList.contains(CLS)) h.classList.add(CLS);
    syncDark();

    // CSS 只在 head 可用后插入，避免被浏览器丢弃
    if (!cssInjected && (document.head || h)) {
      injectStyle();
      cssInjected = !!document.getElementById(STYLE_ID);
    }
    return true;
  }

  // 尽早执行
  if (!boot()) {
    var wait = new MutationObserver(function () {
      if (boot()) wait.disconnect();
    });
    wait.observe(document, { childList: true, subtree: true });
  }

  // 守卫：页面自身若重写 className，把标记加回去
  try {
    var guard = new MutationObserver(function () {
      var h = document.documentElement;
      if (h && !h.classList.contains(CLS)) h.classList.add(CLS);
      if (!cssInjected) boot();
      syncDark();
    });
    guard.observe(document, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["class"]
    });
  } catch (e) {}

  // 暗色/亮色切换跟随
  try {
    if (window.matchMedia) {
      var mq = window.matchMedia("(prefers-color-scheme: dark)");
      if (mq.addEventListener) mq.addEventListener("change", syncDark);
      else if (mq.addListener) mq.addListener(syncDark);
    }
  } catch (e) {}

  // SPA 路由切换后补一次
  window.addEventListener("popstate", function () { setTimeout(boot, 0); });
})();