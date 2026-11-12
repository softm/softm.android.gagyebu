// PC gagyebu: Cloudflare-only account and encrypted D1 ledger.
// Legacy Firebase data remains stored for deliberate migration, never as a
// silent login fallback.
const WORKER='https://softm-hannun-account.softm.workers.dev';
let impl=null,loading=null;
async function backend(){
 if(impl)return impl;
 if(!loading)loading=(async()=>{
  let health;
  try{
   const response=await fetch(WORKER+'/health',{cache:'no-store',signal:AbortSignal.timeout(8000)});
   if(!response.ok)throw Error('서버 오류');
   health=await response.json();
  }catch{
   throw Error('Cloudflare 계정 서버에 연결하지 못했습니다. 기기에 저장한 기존 장부는 유지됩니다.');
  }
  if(health?.ok!==true||health.features?.gagyebuLedger!==true)
   throw Error('Cloudflare 암호화 장부 서비스가 준비되지 않았습니다. 기기 장부를 유지하고 나중에 다시 연결하세요.');
  return import('./web-account-cloudflare.js?v=cloudflare-only-20261112');
 })().then(v=>(impl=v,v)).finally(()=>loading=null);
 return loading;
}
export async function mountWebAccount(changed){
 return (await backend()).mountCloudflareWebAccount(changed);
}
export async function exchangeLedger(payload){
 const module=await backend();
 const mounted=await module.mountCloudflareWebAccount(()=>{});
 return mounted.exchangeLedger(payload);
}
export async function deleteVault(){
 throw Error('Cloudflare 보관소 삭제는 데이터 이전 검증이 끝날 때까지 지원하지 않습니다. JSON 백업을 이용하세요.');
}
