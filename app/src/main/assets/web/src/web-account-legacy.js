const base='https://softm.github.io/shared/platform/v1/';
export async function mountWebAccount(changed){
 const {createAccountContext,mountSiteAccount}=await import(base+'site-account.mjs');
 const account=createAccountContext({siteId:'gagyebu',sdkLoader:async()=>{
  const {createSiteClient}=await import(base+'sdk.mjs?v=gagyebu-web-1');
  return {createSiteClient:options=>createSiteClient({...options,identityOnly:true})};
 }});
 const menu=mountSiteAccount({siteId:'gagyebu',name:'가계부',account,accountTarget:'#desktopAccountSlot,#mobileAccountSlot'});
 const size=document.createElement("style");size.textContent=".trigger{min-width:48px;min-height:48px}";menu.element.shadowRoot.append(size);
 account.subscribe(changed);return {account,menu};
}
let deleting=false;
export async function deleteVault(account,toast){
 if(deleting)return;
 const user=account?.currentUser;if(!user)throw Error('먼저 한눈 계정으로 로그인해 주세요.');
 if(!confirm((user.displayName||user.email||'현재 계정')+'의 가계부 원격 보관소를 삭제할까요? 저장된 백업·기록은 복구할 수 없습니다. 이 계정의 공유 장부와 해당 기기 사본이 삭제됩니다. 비로그인 장부, 다른 한눈 서비스와 SNS 계정은 유지됩니다.'))return;
 if(prompt('삭제하려면 “내 보관소 삭제”를 입력하세요.')!=='내 보관소 삭제')return;
 deleting=true;
 try{
  const [{getApps,initializeApp},{getAuth,signOut},{loadPlatformConfig}]=await Promise.all([
   import('https://www.gstatic.com/firebasejs/13.1.0/firebase-app.js'),import('https://www.gstatic.com/firebasejs/13.1.0/firebase-auth.js'),import(base+'sdk.mjs')]);
  const config=await loadPlatformConfig();
  const hub=getAuth(getApps().find(app=>app.name==='softm-account-hub')||initializeApp(config.firebase,'softm-account-hub'));
  await hub.authStateReady();
  if(account.currentUser?.uid!==user.uid||hub.currentUser?.uid!==user.uid)throw Error('계정 관리에서 같은 한눈 계정으로 다시 로그인한 뒤 시도해 주세요.');
  const token=await hub.currentUser.getIdToken(true);
  const res=await fetch(config.apiBase+'/gagyebu/vault/delete',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify({expectedUid:user.uid,confirm:'내 보관소 삭제'}),signal:AbortSignal.timeout(60000)});
  const result=await res.json();if(!res.ok||result.deleted!==true)throw Error(result.code==='recent-login-required'?'계정 관리에서 같은 SNS 계정으로 다시 로그인한 뒤 삭제해 주세요.':result.message||'삭제를 확인하지 못했습니다. 다시 시도해 주세요.');
  await (await import('./db.js')).clearSuiteLedger(user.uid);if(account.currentUser?.uid===user.uid)await account.logout();if(hub.currentUser?.uid===user.uid)await signOut(hub);
  toast('공유 장부를 삭제했습니다. 비로그인 장부는 유지됩니다.');
 }finally{deleting=false;}
}

export async function exchangeLedger(payload){
 const [{getApp},{getAuth}]=await Promise.all([import('https://www.gstatic.com/firebasejs/13.1.0/firebase-app.js'),import('https://www.gstatic.com/firebasejs/13.1.0/firebase-auth.js')]);
 const user=getAuth(getApp('softm-site-gagyebu')).currentUser;if(!user||user.uid!==payload.uid)throw Error('계정 변경');
 const token=await user.getIdToken(),res=await fetch('https://asia-northeast3-softm-hannun-api.cloudfunctions.net/suiteAuth/gagyebu/ledger',{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:JSON.stringify(payload),signal:AbortSignal.timeout(25000)});
 if(!res.ok)throw Error('장부 연결 실패');return res.json();
}
