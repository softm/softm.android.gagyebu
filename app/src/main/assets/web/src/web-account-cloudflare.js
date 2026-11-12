
const WORKER='https://softm-hannun-account.softm.workers.dev';
const CLIENT='./account-client.mjs?v=gagyebu-local-sdk-syntax-fixed-20261113';

let mounted=null;
let providerStatus={google:true,kakao:false,naver:false};
async function checkProviders(){
 try{
  const res=await fetch(WORKER+'/health',{cache:'no-store',signal:AbortSignal.timeout(7000)});
  if(!res.ok)return;
  const data=await res.json();
  if(data?.providers)providerStatus={google:!!data.providers.google,kakao:!!data.providers.kakao,naver:!!data.providers.naver};
 }catch{/* Keep last known provider state; never enable unverified SNS. */}
}

function el(tag,className,text){
 const node=document.createElement(tag);if(className)node.className=className;
 if(text!==undefined)node.textContent=text;return node;
}
export async function mountCloudflareWebAccount(changed){
 if(mounted)return mounted;
 const {createCloudflareAccountClient}=await import(CLIENT);
 const client=createCloudflareAccountClient({baseUrl:WORKER,siteId:'gagyebu'});
 let busy=false;
 const account={backend:'cloudflare',get currentUser(){const user=client.currentUser;return user?{uid:user.uid,displayName:user.displayName||'한눈 계정',photoURL:user.photoURL||null}:null;},
  login:provider=>client.login(provider),logout:()=>client.logout(),exchangeLedger:payload=>client.exchangeLedger(payload)};
 const style=el('style');style.textContent=`
 .cf-gagyebu-trigger{position:relative;display:inline-flex;align-items:center;justify-content:center;flex:none;width:44px;min-width:44px;max-width:44px;height:44px;min-height:44px;padding:4px;border:1px solid #d8e1e7;border-radius:50%;background:#fff;color:#243d54;cursor:pointer;overflow:visible}
 .cf-gagyebu-trigger[aria-busy=true]::after{content:'';position:absolute;inset:-2px;border:2px solid transparent;border-top-color:#f35c80;border-right-color:#f35c8055;border-radius:50%;animation:cf-gagyebu-spin .8s linear infinite;pointer-events:none}
 @keyframes cf-gagyebu-spin{to{transform:rotate(360deg)}}
 @media(prefers-reduced-motion:reduce){.cf-gagyebu-trigger[aria-busy=true]::after{animation:none;border-color:#f35c80}}
 .cf-gagyebu-trigger .cf-trigger-avatar{width:34px;height:34px;flex:none;display:grid;place-items:center;border-radius:50%;background:#eaf2fb;color:#1d507a;font-weight:800;overflow:hidden}
 .cf-gagyebu-trigger .cf-trigger-avatar img{width:100%;height:100%;object-fit:cover}
 .cf-gagyebu-trigger .cf-trigger-guest{width:24px;height:24px;color:#4f6278;stroke:currentColor;fill:none;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}
 .cf-gagyebu-trigger:focus-visible{outline:3px solid #315eaa;outline-offset:2px}
 @media(max-width:480px){.cf-gagyebu-trigger{width:42px;min-width:42px;max-width:42px;height:42px;min-height:42px;padding:3px}.cf-gagyebu-trigger .cf-trigger-avatar{width:32px;height:32px}}

 .cf-gagyebu-overlay{position:fixed;inset:0;background:#0e192c85;z-index:999999;display:grid;place-items:center;padding:16px}
 .cf-gagyebu-dialog{background:#fff;color:#172635;border-radius:18px;width:min(100%,420px);padding:22px;box-shadow:0 14px 50px #0003}
 .cf-gagyebu-dialog h2{margin:0 0 8px;font-size:22px}
 .cf-gagyebu-dialog p{font-size:14px;line-height:1.65;color:#5b6a7b}
 .cf-gagyebu-dialog button{display:block;width:100%;padding:13px;margin-top:10px;border:1px solid #d9e1eb;border-radius:10px;background:#f6f9fb;color:#192c43;cursor:pointer}
 .cf-gagyebu-dialog button.primary{background:#213f5e;color:#fff;border:0}
 .cf-gagyebu-dialog button:disabled{cursor:not-allowed;opacity:.65;filter:saturate(.65)}
 .cf-gagyebu-dialog .cf-provider{position:relative;display:flex;align-items:center;justify-content:center;gap:10px;min-height:50px;font-weight:700;font-size:15px;border-radius:12px;transition:filter .15s ease}
 .cf-gagyebu-dialog .cf-provider:not(:disabled):hover{filter:brightness(.97)}
 .cf-gagyebu-dialog .cf-provider-google{background:#fff;color:#202124;border:1px solid #dadce0}
 .cf-gagyebu-dialog .cf-provider-kakao{background:#fee500;color:#191919;border-color:#fee500}
 .cf-gagyebu-dialog .cf-provider-naver{background:#03c75a;color:#fff;border-color:#03c75a}
 .cf-provider .cf-provider-mark{display:inline-flex;align-items:center;justify-content:center;width:26px;height:26px;flex:none;font-size:19px;font-weight:900}
 .cf-provider-google .cf-provider-mark{font-family:Arial,sans-serif;background:conic-gradient(#4285f4 0 28%,#34a853 28% 48%,#fbbc05 48% 68%,#ea4335 68% 85%,#4285f4 85%);background-clip:text;-webkit-background-clip:text;color:transparent}
 .cf-provider-kakao .cf-provider-mark{position:relative}
 .cf-provider-kakao .cf-provider-mark::before{content:"";display:block;background:#191919;width:22px;height:17px;border-radius:55%}
 .cf-provider-kakao .cf-provider-mark::after{content:"";position:absolute;background:#191919;width:8px;height:8px;bottom:3px;left:4px;transform:rotate(28deg)}
 .cf-provider-naver .cf-provider-mark{font-family:Arial,sans-serif;color:#fff}
 .cf-gagyebu-dialog .cf-provider[disabled]{opacity:.62}
 .cf-gagyebu-dialog .cf-account-actions{display:flex;align-items:center;gap:10px;padding:12px 0}
 .cf-gagyebu-dialog .cf-account-actions .cf-account-avatar{display:grid;place-items:center;background:#edf4fa;border-radius:50%;width:44px;height:44px;color:#28445f;font-weight:800}
 .cf-gagyebu-dialog .cf-account-actions p{margin:0}
 .cf-gagyebu-dialog .cf-account-actions strong{color:#192c43}
 .cf-gagyebu-dialog .cf-manage-link{display:block;text-align:center;margin-top:14px;color:#315f86;font-size:14px}
 .cf-gagyebu-dialog button:focus-visible,.cf-gagyebu-dialog a:focus-visible{outline:3px solid #315eaa;outline-offset:3px}
 `;
 document.head.append(style);
 const overlay=el('div','cf-gagyebu-overlay');overlay.hidden=true;
 // The overlay must not consume input when closed.
 overlay.style.display='none';
 const dialog=el('div','cf-gagyebu-dialog');dialog.setAttribute('role','dialog');
 dialog.setAttribute('aria-modal','true');dialog.setAttribute('aria-label','한눈 계정');
 overlay.append(dialog);document.body.append(overlay);
 function close(){overlay.hidden=true;overlay.style.display='none';}
 function show(){overlay.hidden=false;overlay.style.display='grid';render();void checkProviders().then(()=>{if(!overlay.hidden)render();});}
 overlay.addEventListener('click',e=>{if(e.target===overlay)close();});
 function action(text,callback,primary=false){
  const b=el('button',primary?'primary':'',text);
  b.type='button';b.addEventListener('click',async()=>{
   b.disabled=true;busy=true;renderTriggers();
   try{await callback();}catch(e){message.textContent=e.message||'계정 연결에 실패했습니다.';}
   finally{b.disabled=false;busy=false;renderTriggers();}
  });
  dialog.append(b);return b;
 }
 const message=el('p');message.setAttribute('role','status');
 function render(){
  dialog.replaceChildren();
  dialog.append(el('h2','', '한눈 공통 계정'));
  const user=account.currentUser;
  dialog.append(el('p','',user?'가계부 계정이 연결되어 있습니다.':'원하는 SNS로 로그인하면 가계부에 자동으로 연결됩니다.'));
  if(user){
   const line=el('div','cf-account-actions');
   const mark=el('span','cf-account-avatar',(account.currentUser?.displayName||'한')[0]);
   const details=el('div');
   details.append(el('strong','',account.currentUser?.displayName||'한눈 계정'));
   details.append(el('p','', '현재 로그인 · '+({google:'Google',kakao:'카카오',naver:'네이버'}[client.currentUser?.provider]||'한눈 계정')));
   line.append(mark,details);
   dialog.append(line);
   dialog.append(el('p','', '계정별 장부는 Cloudflare에서 암호화해 보관합니다.'));
   const manage=el('a','cf-manage-link', '계정관리');
   manage.href=WORKER+'/account';manage.target='_blank';manage.rel='noopener noreferrer';

   dialog.append(manage);
   action('로그아웃',async()=>{
     await account.logout();await changed(null);close();renderTriggers();
   },true);
  }else{
   for(const [id,label] of [['google','Google'],['kakao','카카오'],['naver','네이버']]){
    const enabled=!!providerStatus[id];
    const caption=enabled?label+'로 계속하기':label+(id==='naver'?' · 승인 대기':' · 연결 준비 중');
    // No inert login choices: disabled providers remain visually identifiable.
    const b=action(caption,async()=>{
      const next=await account.login(id);
      await changed(next);close();renderTriggers();
    });
    b.classList.add('cf-provider','cf-provider-'+id);
    b.dataset.provider=id;
    const icon=el('span','cf-provider-mark',id==='google'?'G':id==='naver'?'N':'');
    icon.setAttribute('aria-hidden','true');
    b.prepend(icon);
    b.disabled=!enabled;
   }
  }
  action('닫기',async()=>close());
  dialog.append(message);message.textContent='';
 }
 const buttons=[];
 function renderTriggers(){
  const user=account.currentUser;
  for(const b of buttons){
   b.replaceChildren();
   b.classList.toggle('cf-trigger-signed-in',!!user);
   b.setAttribute('aria-busy',busy?'true':'false');
   if(!user){
    // Icon-only trigger: preserve descriptive accessible name and tooltip.
    const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');
    svg.setAttribute('class','cf-trigger-guest');
    svg.setAttribute('viewBox','0 0 24 24');
    svg.setAttribute('aria-hidden','true');
    const head=document.createElementNS('http://www.w3.org/2000/svg','circle');
    head.setAttribute('cx','12');head.setAttribute('cy','8');head.setAttribute('r','4');
    const body=document.createElementNS('http://www.w3.org/2000/svg','path');
    body.setAttribute('d','M4 21v-2a8 8 0 0 1 16 0v2');
    svg.append(head,body);
    b.append(svg);
    b.setAttribute('aria-label','로그인');
    b.title='로그인';
    continue;
   }
   const name=String(user.displayName||'한눈 계정').trim()||'한눈 계정';
   const avatar=el('span','cf-trigger-avatar',name.slice(0,1));
   const photo=user.photoURL;
   if(typeof photo==='string'&&photo.length<=2048){
    try{
     const url=new URL(photo);
     if(url.protocol==='https:'&&!url.username&&!url.password){
      const img=document.createElement('img');
      img.src=url.href;img.alt='';img.referrerPolicy='no-referrer';img.loading='lazy';
      img.addEventListener('error',()=>{img.remove();avatar.textContent=name.slice(0,1);},{once:true});
      avatar.replaceChildren(img);
     }
    }catch{}
   }
   // Header stays fixed-size on desktop and mobile; full name remains in the tooltip.
   b.append(avatar);
   b.setAttribute('aria-label',name+' 계정 관리');
   b.title=name+' · 계정 관리';
  }
 }
 for(const id of ['desktopAccountSlot','mobileAccountSlot']){
  const target=document.getElementById(id);if(!target)continue;
  const b=el('button','cf-gagyebu-trigger');b.type='button';b.addEventListener('click',show);
  target.replaceChildren(b);buttons.push(b);
 }
 renderTriggers();
 const initial=account.currentUser;await changed(initial);
 mounted={menu:{open:show,element:buttons[0]||null},account,exchangeLedger:account.exchangeLedger};
 return mounted;
}
