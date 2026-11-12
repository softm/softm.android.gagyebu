// SOFTM-ADFIT 날짜:20261022 : 광고 모듈만 오프라인 캐시에 포함하며 외부 광고 응답은 저장하지 않음
const CACHE='gagyebu-static-avatar-icon-20261112';
const CORE=['./','./index.html','./src/app.js','./src/web-account.js','./src/web-account-legacy.js','./src/web-account-cloudflare.js','./src/account-ledger.js','./src/domain.js','./src/db.js','./src/cloud.js','./src/ledger-sync.js','./src/firebase-config.js','./src/styles.css','./src/ads.js','./src/ad-config.js','./src/ads.css','./manifest.webmanifest','./icons/icon.svg'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(CACHE).then(c=>c.addAll(CORE)).then(()=>self.skipWaiting()));});
self.addEventListener('activate',e=>{e.waitUntil(Promise.all([caches.keys().then(keys=>Promise.all(keys.filter(k=>k.startsWith('gagyebu-static-')&&k!==CACHE).map(k=>caches.delete(k)))),self.clients.claim()]));});
self.addEventListener('fetch',e=>{const req=e.request,url=new URL(req.url);if(req.method!=='GET'||url.origin!==self.location.origin||url.search||!url.pathname.startsWith(new URL(self.registration.scope).pathname))return;e.respondWith(fetch(req).then(response=>{if(response.ok){const copy=response.clone();caches.open(CACHE).then(cache=>cache.put(req,copy));}return response;}).catch(()=>caches.match(req).then(c=>c||caches.match('./index.html'))));});

