// Shared header account menu. Identity only; opening/logging in never backs up site data.
export const ACCOUNT_MENU_VERSION = '20261030-menu-1';
const USER_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><circle cx="12" cy="8" r="3.5"/><path d="M4.5 21v-2a7.5 7.5 0 0 1 15 0v2"/></svg>';
const TARGETS = {
  weatherhannun: '#siteAccountSlot, .topbar .header-actions, .topbar .top-inner, .topbar',
  matjiphannun: '.map-topbar, .panel-head .brand-title',
  hydrogenstation: '.topbar, .mobile-map-side-actions, .map-top',
  bizhannun: '.top-tools, header',
  homecare: '.care-header-actions, .head-row, .map-head, header',
  wirohaeyo: '[data-account-anchor], .topbar-actions, .header-actions, header'
};

export function safeProfileImage(value) {
  if (typeof value !== 'string' || !value.trim() || value.length > 4096) return '';
  try {
    const url = new URL(value.trim());
    // Legacy Kakao profiles sometimes return http URLs for their own CDN.
    if (url.protocol === 'http:' && /(^|\.)kakaocdn\.net$/i.test(url.hostname)) url.protocol = 'https:';
    if (url.protocol !== 'https:' || url.username || url.password) return '';
    return url.href;
  } catch { return ''; }
}

export function accountPresentation(user) {
  const signedIn = Boolean(user && user.uid && !user.isAnonymous);
  const providers = signedIn && Array.isArray(user.providerData) ? user.providerData : [];
  const displayName = signedIn ? String(user.displayName || providers.find(p => p.displayName)?.displayName || '내 계정').trim().slice(0, 120) || '내 계정' : '로그인하지 않았습니다';
  const photoURL = signedIn ? [user.photoURL, ...providers.map(p => p.photoURL)].map(safeProfileImage).find(Boolean) || '' : '';
  const provider = providers.map(p => ({'google.com':'Google',kakao:'카카오','oidc.kakao':'카카오',naver:'네이버','oidc.naver':'네이버'}[p.providerId])).find(Boolean);
  const initial = signedIn ? Array.from(displayName)[0].toLocaleUpperCase('ko-KR') : '';
  return {signedIn, displayName, photoURL, initial, subtitle: signedIn ? (provider ? provider + ' · 로그인됨' : '로그인됨') : '계정으로 로그인하거나 회원가입하세요.'};
}

function attachStyles(root) {
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = new URL('./account-menu.css?v=' + ACCOUNT_MENU_VERSION, import.meta.url).href;
  root.prepend(link);
}

export function mountSiteAccount({siteId, name = '이 서비스', target = 'header', accountTarget, account, vault} = {}) {
  if (!account || typeof account.subscribe !== 'function') throw new TypeError('계정 연결이 필요합니다.');
  const host = document.createElement('softm-site-account');
  host.dataset.site = siteId;
  host.dataset.accountUi = 'icon-menu-v2';
  host.style.cssText = 'display:inline-flex;flex:0 0 auto;align-items:center;vertical-align:middle;min-width:0;max-width:100%;margin-inline-start:6px;pointer-events:auto;position:relative';
  const root = host.attachShadow({mode:'open'});
  root.innerHTML = `<button type="button" id="profile" class="trigger" aria-haspopup="dialog" aria-expanded="false"><span class="avatar" aria-hidden="true"></span><span class="dot" hidden aria-hidden="true"></span></button>`;
  attachStyles(root);
  // A body portal avoids clipping by transformed/overflow-hidden map and header containers.
  const portal = document.createElement('softm-account-popover');
  portal.dataset.site = siteId;
  portal.style.cssText = 'position:fixed;inset:0;z-index:2147483000;pointer-events:none;display:block';
  const ui = portal.attachShadow({mode:'open'});
  ui.innerHTML = `<section class="menu" id="menu" role="dialog" aria-labelledby="account-name" hidden><div class="identity"><span class="avatar" aria-hidden="true"></span><div class="identity-text"><h2 class="name" id="account-name"></h2><p class="subtitle"></p></div></div><button type="button" id="login" class="action primary">로그인</button><button type="button" id="signup" class="action">회원가입</button><a id="manage" class="action" href="https://softm.github.io/account/" target="_blank" rel="noopener noreferrer" hidden>계정 관리</a><button type="button" id="logout" class="action" hidden>로그아웃</button><div class="optional" id="vault-group"><button type="button" id="vault" class="action">내 보관소</button><p class="hint">백업·복원은 별도 관리 기능입니다.</p></div><div class="optional" id="withdraw-group" hidden><a class="action danger" href="https://softm.github.io/account/#withdrawal" target="_blank" rel="noopener noreferrer">회원탈퇴</a></div><p class="hint" id="feedback" role="status" aria-live="polite"></p></section><p class="status" role="status" aria-live="polite" aria-atomic="true"></p>`;
  attachStyles(ui);
  const $ = id => ui.getElementById(id);
  const trigger = root.getElementById('profile'), menu = $('menu'), toast = ui.querySelector('.status');
  let destroyed = false, busy = false, restoring = true, opened = false, currentUser = account.currentUser || null;
  let noticeTimer = 0, frame = 0, generation = 0, avatarKey = '', lastUid = currentUser?.uid;
  const cleanups = [];
  function listen(node, type, fn, options) { node.addEventListener(type,fn,options); cleanups.push(() => node.removeEventListener(type,fn,options)); }
  function notice(message, persistent = false) {
    if (destroyed) return;
    clearTimeout(noticeTimer); toast.textContent = message; $('feedback').textContent = message;
    if (!persistent) noticeTimer = setTimeout(() => { if (!destroyed) {toast.textContent=''; $('feedback').textContent='';} }, 6000);
  }
  function paintAvatar(container, view, version) {
    container.replaceChildren();
    const fallback = document.createElement('span');
    if (view.signedIn) fallback.textContent = view.initial; else fallback.innerHTML = USER_ICON;
    container.append(fallback);
    if (!view.photoURL) return;
    const image = document.createElement('img');
    image.alt = ''; image.referrerPolicy = 'no-referrer'; image.decoding = 'async'; image.hidden = true;
    image.onload = () => { if (!destroyed && version === generation) { fallback.hidden=true; image.hidden=false; } };
    image.onerror = () => { if (!destroyed && version === generation) { image.remove(); fallback.hidden=false; } };
    container.append(image); image.src = view.photoURL;
  }
  function render(user = currentUser) {
    if (destroyed) return;
    currentUser = user;
    const view = accountPresentation(user);
    host.dataset.authState = restoring ? 'checking' : view.signedIn ? 'authenticated' : 'guest';
    trigger.setAttribute('aria-label', restoring ? '로그인 상태 확인 중, 계정 메뉴 열기' : view.signedIn ? view.displayName + '님의 계정 메뉴 열기' : '로그인 및 회원가입 메뉴 열기');
    trigger.title = restoring ? '로그인 상태 확인 중' : view.signedIn ? view.displayName + ' · 로그인됨' : '로그인';
    trigger.setAttribute('aria-busy', String(restoring || busy));
    root.querySelector('.dot').hidden = !view.signedIn;
    $('account-name').textContent = view.displayName;
    ui.querySelector('.subtitle').textContent = restoring ? '로그인 상태를 확인하고 있습니다.' : view.subtitle;
    $('login').hidden = $('signup').hidden = view.signedIn;
    $('manage').hidden = $('logout').hidden = $('withdraw-group').hidden = !view.signedIn;
    $('vault-group').hidden = typeof vault?.open !== 'function';
    for (const id of ['login','signup','logout']) $(id).disabled = busy || restoring;
    const key = JSON.stringify([user?.uid, view.displayName, view.photoURL, view.signedIn]);
    if (key !== avatarKey) { avatarKey=key; generation++; paintAvatar(root.querySelector('.avatar'),view,generation); paintAvatar(ui.querySelector('.avatar'),view,generation); }
    if (opened) position();
  }
  function viewport() { const v=window.visualViewport; return {left:v?.offsetLeft||0,top:v?.offsetTop||0,width:v?.width||innerWidth,height:v?.height||innerHeight}; }
  function position() {
    if (!opened || destroyed) return;
    const v=viewport(), rect=trigger.getBoundingClientRect(), gap=12;
    const width=Math.max(0,Math.min(304,v.width-gap*2));
    menu.style.width=width+'px'; menu.style.maxWidth='none'; menu.style.maxHeight=Math.max(0,v.height-gap*2)+'px';
    const height=menu.getBoundingClientRect().height;
    const left=Math.max(v.left+gap,Math.min(rect.right-width,v.left+v.width-width-gap));
    let top=rect.bottom+8;
    if (top+height>v.top+v.height-gap && rect.top-height-8>=v.top+gap) top=rect.top-height-8;
    top=Math.max(v.top+gap,Math.min(top,v.top+v.height-height-gap));
    menu.style.left=left+'px'; menu.style.top=top+'px';
  }
  function items() { return [...menu.querySelectorAll('button:not(:disabled),a[href]')].filter(el=>!el.closest('[hidden]')); }
  function setOpen(value, focus = false) {
    if (destroyed) return;
    opened=Boolean(value); menu.hidden=!opened; trigger.setAttribute('aria-expanded',String(opened));
    if (opened) { position(); if (focus) (items()[focus==='last'?items().length-1:0]||menu).focus(); }
    else if (focus && host.isConnected) trigger.focus({preventScroll:true});
  }
  async function login(mode) {
    if (busy || restoring || destroyed) return;
    // Must precede all awaits so mobile browsers retain the user gesture.
    const popup=account.externalBrowser ? null : window.open('about:blank','_blank','popup,width=520,height=760');
    if (!account.externalBrowser && !popup) { notice('팝업을 허용한 뒤 다시 눌러 주세요.'); return; }
    busy=true; render(); notice(mode==='signup'?'회원가입 화면을 열고 있습니다.':'로그인 화면을 열고 있습니다.',true);
    try { await account.login({popup,mode}); if (!destroyed) {render(account.currentUser);setOpen(false,true);notice(name+'에 로그인했습니다.');} }
    catch (error) {try{popup?.close();}catch{} notice(error?.message||'로그인을 완료하지 못했습니다. 다시 시도해 주세요.');}
    finally {busy=false;render();}
  }
  async function logout() {
    if (busy || restoring || destroyed) return;
    busy=true;render();notice('로그아웃하고 있습니다.',true);
    try {await account.logout();if (!destroyed){render(account.currentUser);setOpen(false,true);notice('로그아웃되었습니다.');}}
    catch {notice('로그아웃하지 못했습니다. 다시 시도해 주세요.');}
    finally {busy=false;render();}
  }
  listen(trigger,'click',()=>setOpen(!opened));
  listen(trigger,'keydown',event=>{if(event.key==='ArrowDown'||event.key==='ArrowUp'){event.preventDefault();setOpen(true,event.key==='ArrowUp'?'last':true);}});
  listen($('login'),'click',()=>login('login'));listen($('signup'),'click',()=>login('signup'));listen($('logout'),'click',logout);
  listen($('vault'),'click',()=>{setOpen(false,true);try{vault?.open();}catch{notice('내 보관소를 열지 못했습니다. 다시 시도해 주세요.');}});
  listen(menu,'click',event=>{if(event.target.closest('a'))setOpen(false,true);});
  listen(document,'pointerdown',event=>{const path=event.composedPath();if(opened&&!path.includes(host)&&!path.includes(portal))setOpen(false);},true);
  listen(document,'focusin',event=>{const path=event.composedPath();if(opened&&!path.includes(host)&&!path.includes(portal))setOpen(false);},true);
  listen(document,'keydown',event=>{if(opened&&event.key==='Escape'){event.preventDefault();event.stopPropagation();setOpen(false,true);}},true);
  listen(menu,'keydown',event=>{
    if(!['ArrowDown','ArrowUp','Home','End'].includes(event.key))return;
    const choices=items();if(!choices.length)return;event.preventDefault();let index=choices.indexOf(ui.activeElement);
    index=event.key==='Home'?0:event.key==='End'?choices.length-1:(index+(event.key==='ArrowDown'?1:-1)+choices.length)%choices.length;choices[index].focus();
  });
  function visible(element) {
    if(!element || !element.getClientRects().length || getComputedStyle(element).visibility==='hidden')return false;
    const r=element.getBoundingClientRect();
    const emptyAnchor=r.width===0 && r.height===0 && element.children.length===0;
    return (emptyAnchor || r.width>0 && r.height>0) && r.bottom>=0 && r.right>=0 && r.top<innerHeight && r.left<innerWidth;
  }
  function place() {
    if (destroyed) return;
    const selectors=(accountTarget || TARGETS[siteId] || target).split(',').map(s=>s.trim()).filter(Boolean);
    let anchor;
    for (const selector of selectors) {anchor=[...document.querySelectorAll(selector)].find(visible);if(anchor)break;}
    if (!anchor) anchor=[...document.querySelectorAll('header .header-actions,header .topbar-actions,header')].find(visible);
    host.dataset.floating=String(!anchor); anchor ||= document.body;
    if (host.parentElement!==anchor) {
      if(siteId==='hydrogenstation' && anchor.classList.contains('mobile-map-side-actions'))anchor.prepend(host);
      else anchor.append(host);
    }
    // Legacy workspace identity remains reachable from the app's Account
    // navigation; avoid showing a second login button beside the common menu.
    if(siteId==='bizhannun'){const legacy=anchor.querySelector('.account-trigger[data-nav="account"]');if(legacy){legacy.hidden=true;legacy.style.display='none';}}
    const overlayParent=document.fullscreenElement||document.body;
    if (portal.parentElement!==overlayParent) overlayParent.append(portal);
    position();
  }
  function schedulePlace() {if(!frame&&!destroyed)frame=requestAnimationFrame(()=>{frame=0;place();});}
  listen(window,'resize',schedulePlace);listen(window,'scroll',position,true);listen(document,'fullscreenchange',schedulePlace);
  if(window.visualViewport){listen(window.visualViewport,'resize',schedulePlace);listen(window.visualViewport,'scroll',position);}
  const observer=new MutationObserver(()=>{
    if(!host.isConnected || !portal.isConnected)place();
    else if(host.dataset.floating==='true' || !visible(host.parentElement))schedulePlace();
  });
  const layoutObserver=new ResizeObserver(schedulePlace);
  const modeObserver=new MutationObserver(schedulePlace);
  place();observer.observe(document.body,{childList:true,subtree:true});
  layoutObserver.observe(host);
  modeObserver.observe(document.body,{attributes:true,attributeFilter:['class']});
  modeObserver.observe(document.documentElement,{attributes:true,attributeFilter:['class']});
  const off=account.subscribe(user=>{if(lastUid!==user?.uid){setOpen(false);lastUid=user?.uid;}render(user);});
  Promise.resolve().then(()=>account.ready()).then(()=>{if(!destroyed){restoring=false;render(account.currentUser);}}).catch(()=>{if(!destroyed){restoring=false;render();notice('로그인 연결을 확인해 주세요. 로그인 버튼을 누르면 재시도합니다.');}});
  return {element:host,open:()=>setOpen(true),close:()=>setOpen(false),destroy(){if(destroyed)return;destroyed=true;generation++;off?.();observer.disconnect();layoutObserver.disconnect();modeObserver.disconnect();cleanups.forEach(fn=>fn());cancelAnimationFrame(frame);clearTimeout(noticeTimer);host.remove();portal.remove();}};
}
