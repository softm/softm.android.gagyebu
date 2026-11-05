import {initialState,validateBackup} from './domain.js';
import {getSuiteCache,setSuiteCache,selectSuiteLedger,loadState,saveState} from './db.js';
const tables=['accounts','categories','transactions','events'];
const canonical=x=>x&&typeof x==='object'?(Array.isArray(x)?x.map(canonical):Object.fromEntries(Object.keys(x).sort().map(k=>[k,canonical(x[k])]))):x;
const equal=(a,b)=>JSON.stringify(canonical(a))===JSON.stringify(canonical(b));
const privateKeys=['sourceText','sourceImportKey','attachment','attachmentPath','attachmentUploadId'];
const candidate=t=>t.status==='pending'&&String(t.source||'').startsWith('android-');
export function project(s){
 const v=structuredClone(s);const hidden=new Set(v.transactions.filter(candidate).map(t=>t.id));
 v.transactions=v.transactions.filter(t=>!hidden.has(t.id));for(const t of v.transactions)for(const k of privateKeys)delete t[k];
 v.events=(v.events||[]).filter(e=>!hidden.has(e.entityId)).map(e=>({id:e.id,entityId:e.entityId,action:e.action,createdAt:e.createdAt}));return JSON.parse(JSON.stringify({version:1,accounts:v.accounts,categories:v.categories,transactions:v.transactions,events:v.events,settings:v.settings,budgets:v.budgets}));
}
function restore(v,local){
 const out={...structuredClone(local),...structuredClone(v)},map=new Map(local.transactions.map(t=>[t.id,t]));
 for(const t of out.transactions){const old=map.get(t.id);if(old)for(const k of privateKeys)if(k in old)t[k]=old[k];}
 out.transactions.push(...local.transactions.filter(t=>candidate(t)&&!out.transactions.some(r=>r.id===t.id)));return out;
}
export function mergeLedger(base,mine,theirs,prefer){
 function value(b,l,r){
  if(equal(l,b))return r;if(equal(r,b)||equal(l,r))return l;
  if(b&&l&&r&&[b,l,r].every(o=>typeof o==='object'&&!Array.isArray(o)))return Object.fromEntries([...new Set([...Object.keys(b),...Object.keys(l),...Object.keys(r)])].map(k=>[k,value(b[k],l[k],r[k])]).filter(([,v])=>v!==undefined));
  if(prefer)return prefer==='mine'?l:r;throw Error('같은 항목을 두 기기에서 변경했습니다.');
 }
 const out={version:1,settings:value(base.settings,mine.settings,theirs.settings),budgets:value(base.budgets,mine.budgets,theirs.budgets)};
 for(const k of tables){const [b,l,r]=[base,mine,theirs].map(s=>new Map(s[k].map(x=>[x.id,x])));out[k]=[...new Set([...r.keys(),...l.keys()])].map(id=>value(b.get(id),l.get(id),r.get(id))).filter(Boolean);}
 validateBackup(out);return out;
}
export function startLedger({request,getState,setState,isBusy,status,onSwitch}){
 let uid=null,generation=0,ready=false,running=false,preference,choice=false,selected=Promise.resolve();
 let timer;const schedule=(ms=120)=>{clearTimeout(timer);timer=setTimeout(tick,ms);};
 async function select(next){
  next=next||null;if(next===uid)return selected;
  uid=next;const g=++generation;ready=false;choice=false;preference=null;onSwitch?.();selectSuiteLedger(next);setState(initialState());status(next?'계정 장부 여는 중':'이 기기의 장부');
  selected=(async()=>{let cache=next&&await getSuiteCache(next);if(g!==generation)return;
   let s=await loadState();if(g!==generation)return;
   if(next&&!cache){const guest=await loadState(null);if(g!==generation)return;
    if(!equal(project(guest),project(initialState()))&&confirm('현재 기기의 기존 장부를 이 한눈 계정에 합칠까요? 취소하면 계정 장부만 엽니다. 기기의 원래 장부는 보존됩니다.'))s=structuredClone(guest);
    await saveState(s,next);await setSuiteCache(next,{base:project(initialState()),revision:null});
   }
   if(g!==generation)return;setState(s);ready=true;if(next)schedule();
  })().catch(()=>status('장부를 열지 못했습니다. 새로고침해 주세요.'));return selected;
 }
 async function tick(){
  if(!uid||!ready||running||choice||document.hidden||isBusy()){if(uid)schedule(1500);return;}
  const user=uid,g=generation;running=true;
  const valid=()=>g===generation&&uid===user;
  try{
   const cache=await getSuiteCache(user),original=getState(),mine=project(original);
   const read=await request({op:'read',uid:user,known:cache.revision});if(!valid()||getState()!==original||isBusy())return;
   if(read.expired||(!read.revision&&cache.revision)||('epoch' in cache&&cache.epoch!==(read.epoch||null))){
    const clean=read.ledger||initialState();validateBackup(clean);await saveState(clean,user);
    await setSuiteCache(user,{base:project(clean),revision:read.revision||null,epoch:read.epoch||null});
    if(valid()){setState(clean);choice=!!read.expired;status(read.expired?'보관소가 삭제되었습니다. 다시 로그인해 주세요.':'삭제 이후의 계정 장부를 열었습니다.');}return;
   }
   const remote=read.ledger||cache.base;
   let merged;try{merged=mergeLedger(cache.base,mine,remote,preference);}catch(e){choice=true;status('충돌 · 설정에서 기준 장부를 선택하세요');return;}
   let rev=read.revision;if(!equal(merged,remote)||!rev){
    if(new TextEncoder().encode(JSON.stringify(merged)).length>3*1024*1024){status("동기화 용량 초과 · 장부는 이 기기에 저장됨 (3MB 한도)");return;}
    const result=await request({op:'write',uid:user,ledger:merged,expected:rev,epoch:read.epoch||null});if(!valid()||result.conflict||result.expired)return;rev=result.revision;
   }
   // New local edits during the network request are rebased on the next pass.
   if(!valid()||getState()!==original||isBusy())return;
   const state=restore(merged,original);if(!equal(state,original)){await saveState(state,user);if(!valid()||getState()!==original)return;setState(state);}
   await setSuiteCache(user,{base:merged,revision:rev,epoch:read.epoch||null});preference=null;status('동기화됨');
  }catch(e){if(valid())status('동기화 대기 · 이 기기에 저장됨');}finally{running=false;if(uid&&!choice)schedule(15000);}
 }
 window.addEventListener('online',()=>schedule());document.addEventListener('visibilitychange',()=>{if(!document.hidden)schedule();});
 return {select,changed:()=>{if(uid){if(!choice)status('동기화 대기');schedule();}},resolve(p){choice=false;preference=p;schedule();},get signed(){return !!uid;},get scope(){return generation;},get blocked(){return !ready&&!!uid;}};
}
