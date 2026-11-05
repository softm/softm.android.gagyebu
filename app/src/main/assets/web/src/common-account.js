import {mountSiteAccount} from '../shared/platform/v1/account-menu.mjs';

// This adapter handles identity only; it deliberately has no ledger/database API.
export function mountCommonAccount({request,onChange=()=>{}}) {
  let user=null, snapshot={status:'idle',error:''}, pending=null, refreshing=null;
  const listeners=new Set();
  const emit=()=>{listeners.forEach(fn=>fn(user));onChange({...snapshot,user});};
  async function refresh(){
    if(refreshing)return refreshing;
    refreshing=request('accountStatus').then(value=>{
      snapshot=value;user=value.user||null;emit();
      if(pending&&value.status==='idle'){
        const current=pending;pending=null;clearInterval(current.poll);clearTimeout(current.timer);
        if(user&&!value.error)current.resolve(user);else current.reject(Error(value.error||'로그인을 취소했습니다.'));
      }
      return account;
    }).finally(()=>{refreshing=null;});
    return refreshing;
  }
  const account={
    externalBrowser:true,
    get currentUser(){return user;},
    subscribe(fn){listeners.add(fn);fn(user);return ()=>listeners.delete(fn);},
    ready:refresh,
    async login({mode='login'}={}){
      if(pending)throw Error('진행 중인 로그인을 완료하거나 취소해 주세요.');
      await request('accountLogin',{mode});
      return new Promise((resolve,reject)=>{
        const finish=message=>{if(!pending)return;const current=pending;pending=null;clearInterval(current.poll);clearTimeout(current.timer);current.reject(Error(message));};
        pending={resolve,reject,poll:setInterval(()=>refresh().catch(()=>{}),900),timer:setTimeout(()=>{
          request('accountCancel').catch(()=>{});finish('로그인 시간이 만료됐습니다. 다시 시도해 주세요.');
        },330000)};
        refresh().catch(()=>finish('로그인 상태를 확인하지 못했습니다. 다시 시도해 주세요.'));
      });
    },
    async logout(){await request('accountLogout');await refresh();}
  };
  const menu=mountSiteAccount({siteId:'gagyebu',name:'가계부',account,accountTarget:'#mobileAccountSlot, #desktopAccountSlot'});
  window.addEventListener('gagyebu:account-changed',()=>refresh().catch(()=>{}));
  window.addEventListener('gagyebu:native-status',()=>refresh().catch(()=>{}));
  return {account,open:menu.open,close:menu.close,async cancel(){await request('accountCancel');await refresh();},
    get snapshot(){return {...snapshot,user};}};
}
