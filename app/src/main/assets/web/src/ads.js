import {AD_CONFIG} from './ad-config.js';

/** SOFTM-ADFIT START 날짜:20261022 : 돌봄한눈의 접힘 UX를 가계부의 입력 보호·광고 수명주기에 맞춰 적용 */
const SCRIPT_ID = 'gagyebu-adfit-sdk';
const SDK_TIMEOUT = 15000;
const AD_TIMEOUT = 30000;
const ALLOWED_TABS = new Set(['home', 'transactions', 'calendar', 'analytics']);

export function validAdSlot(slot) {
  return Boolean(slot && /^DAN-[A-Za-z0-9]+$/.test(slot.unitId || '') &&
    ((slot.width === 320 && (slot.height === 50 || slot.height === 100)) ||
     (slot.width === 728 && slot.height === 90)));
}

export function isAdContextAllowed(context) {
  return ALLOWED_TABS.has(context.tab) && !context.modalOpen &&
    !(context.tab === 'transactions' && context.filter === 'pending');
}

export function selectBottomSlot(config, availableWidth) {
  const desktop = config.bottom?.desktop, mobile = config.bottom?.mobile;
  if (validAdSlot(desktop) && availableWidth >= desktop.width) return desktop;
  if (validAdSlot(mobile) && availableWidth >= mobile.width) return mobile;
  return null;
}

// The dependency parameters support deterministic, network-free lifecycle tests.
// The app calls initAds() with its own configuration; there is no query-string override.
export function createAdController({win, doc, config = AD_CONFIG}) {
  let context = {tab: null, filter: 'all', modalOpen: false};
  let collapsed = false, destroyed = false, sdkPromise;
  let focusTimer, resizeFrame, inlineObserver, layoutObserver, serial = 0;
  let inlineHost = null, inlineSeen = false, inlineShell = null;
  const active = new Map(), failedUnits = new Set(), listeners = [];
  const records = {bottom: null, analytics: null};

  try { collapsed = win.localStorage.getItem(config.storageKey) === '1'; } catch {}

  function element(tag, className, text) {
    const node = doc.createElement(tag);
    if (className) node.className = className;
    if (text) node.textContent = text;
    return node;
  }
  const zone = element('aside', 'gagyebu-bottom-ad');
  zone.id = 'gagyebuBottomAd';
  zone.hidden = true;
  zone.setAttribute('aria-label', '하단 광고');
  const controls = element('div', 'gagyebu-ad-controls');
  const label = element('span', 'gagyebu-ad-label', '광고');
  const toggle = element('button', 'gagyebu-ad-toggle');
  toggle.type = 'button';
  toggle.setAttribute('aria-controls', 'gagyebuBottomAdPanel');
  const panel = element('div', 'gagyebu-ad-panel');
  panel.id = 'gagyebuBottomAdPanel';
  controls.append(label, toggle);
  zone.append(controls, panel);
  doc.body.append(zone);

  function listen(target, name, handler, options) {
    target?.addEventListener(name, handler, options);
    listeners.push(() => target?.removeEventListener(name, handler, options));
  }
  function originAllowed() {
    if (!config.allowedOrigins?.includes(win.location.origin)) return false;
    const prefix = config.pathPrefix || '/';
    return win.location.pathname === prefix.replace(/\/$/, '') || win.location.pathname.startsWith(prefix);
  }
  function blocked() {
    const viewport = win.visualViewport;
    const keyboardOpen = viewport && win.innerHeight - viewport.height > 150 && viewport.scale === 1;
    const focused = doc.activeElement;
    // Removing a focused modal input does not reliably dispatch focusout in every browser.
    const inputFocused = Boolean(focused?.matches?.('input,textarea,select,[contenteditable="true"]') && !zone.contains(focused));
    return destroyed || config.enabled !== true || !originAllowed() || !isAdContextAllowed(context) ||
      doc.hidden || win.navigator.onLine === false || inputFocused || keyboardOpen ||
      doc.body.classList.contains('modal-open');
  }
  function availableBottomWidth() {
    const viewport = doc.documentElement.clientWidth || win.innerWidth;
    const sidebar = doc.querySelector('.sidebar');
    return Math.max(0, viewport - (sidebar?.getBoundingClientRect().width || 0));
  }
  function syncSpace() {
    const nav = doc.getElementById('mobileNav');
    const navHeight = nav?.getBoundingClientRect().height || 0;
    const height = zone.hidden ? 0 : Math.ceil(zone.getBoundingClientRect().height);
    doc.documentElement.style.setProperty('--gagyebu-nav-height', `${navHeight}px`);
    doc.documentElement.style.setProperty('--gagyebu-ad-height', `${height}px`);
  }
  function release(placement) {
    const record = records[placement];
    if (!record) return;
    records[placement] = null;
    win.clearTimeout(record.timer);
    if (record.requested && typeof win.adfit?.destroy === 'function') {
      try { win.adfit.destroy(record.ins); } catch {}
    }
    if (active.get(record.slot.unitId) === record) active.delete(record.slot.unitId);
    record.mount.remove();
  }
  function fail(placement, record) {
    if (records[placement] !== record) return;
    failedUnits.add(record.slot.unitId);
    release(placement);
    if (placement === 'bottom') {
      zone.hidden = true;
      zone.dataset.state = 'empty';
    } else if (inlineHost) {
      inlineHost.hidden = true;
      inlineHost.dataset.state = 'empty';
    }
    syncSpace();
  }
  function sdk() {
    if (sdkPromise) return sdkPromise;
    if (typeof win.adfit?.render === 'function' && typeof win.adfit?.destroy === 'function') return Promise.resolve(win.adfit);
    sdkPromise = new Promise((resolve, reject) => {
      let settled = false;
      const script = doc.getElementById(SCRIPT_ID) || element('script');
      const finish = (error) => {
        if (settled) return;
        settled = true;
        win.clearTimeout(timer);
        if (error || typeof win.adfit?.render !== 'function' || typeof win.adfit?.destroy !== 'function') reject(error || new Error('AdFit lifecycle unavailable'));
        else resolve(win.adfit);
      };
      const timer = win.setTimeout(() => finish(new Error('AdFit SDK timeout')), SDK_TIMEOUT);
      script.addEventListener('load', () => finish(), {once: true});
      script.addEventListener('error', () => finish(new Error('AdFit SDK unavailable')), {once: true});
      if (!script.isConnected) {
        script.id = SCRIPT_ID;
        script.async = true;
        script.charset = 'utf-8';
        // Keep the endpoint fixed; configuration must not become a script injection route.
        script.src = 'https://t1.kakaocdn.net/kas/static/ba.min.js';
        doc.head.append(script);
      }
    });
    return sdkPromise;
  }
  function receive(placement, status, ins) {
    const record = records[placement];
    if (!record || ins !== record.ins || !record.mount.isConnected) return;
    if (status === 'failed') { fail(placement, record); return; }
    win.clearTimeout(record.timer);
    record.status = 'ready';
    if (placement === 'bottom') zone.dataset.state = 'ready';
    else if (inlineHost) inlineHost.dataset.state = 'ready';
  }
  win.gagyebuBottomAdLoaded = ins => receive('bottom', 'loaded', ins);
  win.gagyebuBottomAdFailed = ins => receive('bottom', 'failed', ins);
  win.gagyebuAnalyticsAdLoaded = ins => receive('analytics', 'loaded', ins);
  win.gagyebuAnalyticsAdFailed = ins => receive('analytics', 'failed', ins);

  function mount(placement, host, slot) {
    const existing = records[placement];
    if (existing && existing.slot.unitId === slot.unitId &&
        existing.slot.width === slot.width && existing.slot.height === slot.height) {
      if (existing.mount.parentNode !== host) host.append(existing.mount);
      return;
    }
    release(placement);
    if (blocked() || failedUnits.has(slot.unitId) || active.has(slot.unitId) || active.size >= 2) return;
    const mountNode = element('div', 'gagyebu-ad-mount');
    mountNode.style.width = `${slot.width}px`;
    mountNode.style.height = `${slot.height}px`;
    host.append(mountNode);
    const ins = element('ins', 'kakao_ad_area');
    ins.style.display = 'none';
    ins.dataset.adUnit = slot.unitId;
    ins.dataset.adWidth = String(slot.width);
    ins.dataset.adHeight = String(slot.height);
    ins.dataset.adOnload = placement === 'bottom' ? 'gagyebuBottomAdLoaded' : 'gagyebuAnalyticsAdLoaded';
    ins.dataset.adOnfail = placement === 'bottom' ? 'gagyebuBottomAdFailed' : 'gagyebuAnalyticsAdFailed';
    const record = {token: ++serial, slot, ins, mount: mountNode, requested: false, status: 'loading', timer: null};
    records[placement] = record;
    active.set(slot.unitId, record);
    // The SDK loads before ins is attached, preventing its initial auto-scan from double requesting.
    sdk().then(api => {
      if (records[placement] !== record || blocked() || !mountNode.isConnected ||
          (placement === 'bottom' && collapsed)) return;
      mountNode.append(ins);
      record.requested = true;
      record.timer = win.setTimeout(() => fail(placement, record), AD_TIMEOUT);
      try { api.render(ins); } catch { fail(placement, record); }
    }).catch(() => fail(placement, record));
  }

  function inlineAllowed(slot) {
    const bottomIds = [config.bottom?.mobile?.unitId, config.bottom?.desktop?.unitId];
    return context.tab === 'analytics' && validAdSlot(slot) && !bottomIds.includes(slot.unitId) &&
      !failedUnits.has(slot.unitId) && inlineHost && inlineHost.getBoundingClientRect().width >= slot.width;
  }
  function attachInlineHost() {
    const next = doc.getElementById('analyticsAdSlot');
    if (next === inlineHost) return;
    inlineObserver?.disconnect();
    inlineObserver = null;
    // The normal host is a stable sibling of #page. Never move a live iframe between parents.
    release('analytics');
    inlineShell?.remove();
    inlineShell = null;
    inlineHost = next;
    inlineSeen = false;
    if (!next) {
      return;
    }
    if (!inlineShell) {
      inlineShell = element('section', 'gagyebu-inline-ad');
      inlineShell.setAttribute('aria-label', '분석 보고서 하단 광고');
      inlineShell.append(element('span', 'gagyebu-ad-label', '광고'));
    }
    next.append(inlineShell);
    if (win.IntersectionObserver) {
      inlineObserver = new win.IntersectionObserver(entries => {
        const entry = entries.find(item => item.target === inlineHost);
        if (!entry) return;
        inlineSeen = entry.isIntersecting;
        if (inlineSeen) sync();
      }, {threshold: 0.01});
      inlineObserver.observe(next);
    } else {
      const rect = next.getBoundingClientRect();
      inlineSeen = rect.bottom > 0 && rect.top < win.innerHeight;
    }
  }
  function syncInline(isBlocked) {
    attachInlineHost();
    const slot = config.analytics;
    // Measure the real width after returning from a hidden input/modal context.
    if (inlineHost && !isBlocked) inlineHost.hidden = false;
    if (isBlocked || !inlineAllowed(slot)) {
      release('analytics');
      if (inlineHost) inlineHost.hidden = true;
      return;
    }
    inlineHost.hidden = false;
    // Reserve only this known-size, noninteractive footer while its lazy request waits.
    inlineShell.style.minHeight = `${slot.height + 53}px`;
    if (!win.IntersectionObserver && !inlineSeen) {
      const rect = inlineHost.getBoundingClientRect();
      inlineSeen = rect.bottom > 0 && rect.top < win.innerHeight;
    }
    if (inlineSeen) {
      inlineHost.dataset.state = records.analytics?.status || 'loading';
      mount('analytics', inlineShell, slot);
    }
  }
  function sync() {
    if (destroyed) return;
    const isBlocked = blocked();
    const slot = selectBottomSlot(config, availableBottomWidth());
    const enabled = !isBlocked && slot && !failedUnits.has(slot.unitId);
    zone.hidden = !enabled;
    panel.hidden = collapsed;
    toggle.textContent = collapsed ? '광고 펼치기' : '광고 접기';
    toggle.setAttribute('aria-expanded', String(!collapsed));
    if (slot) zone.style.setProperty('--gagyebu-slot-width', `${slot.width}px`);
    if (!enabled || collapsed) {
      release('bottom');
      zone.dataset.state = enabled ? 'collapsed' : isBlocked ? 'blocked' : 'empty';
    } else {
      zone.dataset.state = records.bottom?.status || 'loading';
      mount('bottom', panel, slot);
    }
    syncInline(isBlocked);
    syncSpace();
  }
  function onFocusChange() {
    sync();
  }
  function scheduleSync() {
    win.cancelAnimationFrame(resizeFrame);
    resizeFrame = win.requestAnimationFrame(sync);
  }
  listen(toggle, 'click', () => {
    collapsed = !collapsed;
    try { win.localStorage.setItem(config.storageKey, collapsed ? '1' : '0'); } catch {}
    sync();
  });
  listen(doc, 'focusin', onFocusChange);
  listen(doc, 'focusout', () => {
    win.clearTimeout(focusTimer);
    focusTimer = win.setTimeout(onFocusChange, 0);
  });
  listen(doc, 'visibilitychange', sync);
  listen(win, 'online', sync);
  listen(win, 'offline', sync);
  listen(win, 'resize', scheduleSync, {passive: true});
  listen(win.visualViewport, 'resize', scheduleSync, {passive: true});
  listen(win, 'pageshow', sync);
  listen(win, 'pagehide', () => { release('bottom'); release('analytics'); });
  if (!win.IntersectionObserver) listen(win, 'scroll', scheduleSync, {passive: true});
  if (win.ResizeObserver) {
    layoutObserver = new win.ResizeObserver(syncSpace);
    layoutObserver.observe(zone);
    const nav = doc.getElementById('mobileNav');
    if (nav) layoutObserver.observe(nav);
  }
  sync();
  return {
    update(next) { context = {...context, ...next}; sync(); },
    destroy() {
      destroyed = true;
      release('bottom'); release('analytics');
      inlineObserver?.disconnect(); layoutObserver?.disconnect();
      listeners.forEach(unlisten => unlisten());
      win.clearTimeout(focusTimer); win.cancelAnimationFrame(resizeFrame);
      zone.remove(); inlineShell?.remove();
      doc.documentElement.style.setProperty('--gagyebu-ad-height', '0px');
      delete win.gagyebuBottomAdLoaded; delete win.gagyebuBottomAdFailed;
      delete win.gagyebuAnalyticsAdLoaded; delete win.gagyebuAnalyticsAdFailed;
    },
  };
}

let controller, latestContext = {tab: null};
export function initAds() {
  if (!controller && typeof window !== 'undefined') {
    controller = createAdController({win: window, doc: document});
    controller.update(latestContext);
  }
}
export function updateAdContext(next) {
  latestContext = {...latestContext, ...next};
  controller?.update(latestContext);
}
/** SOFTM-ADFIT END */
