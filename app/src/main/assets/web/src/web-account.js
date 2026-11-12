// Account authentication is owned by the shared account Worker.
// Ledger storage is owned exclusively by the gagyebu-api Worker.
// Never hide the login UI because a service health probe fails.
let impl=null,loading=null;
async function backend(){
 if(impl)return impl;
 if(!loading)loading=import('./web-account-cloudflare.js?v=gagyebu-sdk-syntax-fixed-20261113')
  .then(module=>(impl=module,module))
  .finally(()=>{loading=null;});
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
