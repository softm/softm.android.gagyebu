/* Pure domain functions. KRW is stored as integer won; never use floating point for ledger totals. */
export const KINDS = { expense: '지출', income: '수입', transfer: '이체', repayment: '카드상환', refund: '환불' };
export const COLORS = ['#2d8578','#e6a357','#7187d5','#e07f91','#92a86e','#9278ae','#5faaa4','#c49764'];
export const DEFAULT_CATEGORIES = [
  ['food','식비','expense','🍚'],['cafe','카페·간식','expense','☕'],['grocery','장보기','expense','🛒'],
  ['transport','교통','expense','🚇'],['living','생활','expense','🏠'],['shopping','쇼핑','expense','🛍️'],
  ['health','의료·건강','expense','💊'],['leisure','문화·여가','expense','🎬'],['bill','공과금·통신','expense','🧾'],
  ['other-expense','기타지출','expense','📦'],['salary','급여','income','💼'],
  ['side','부수입','income','✨'],['other-income','기타수입','income','💰']
].map(([id,name,type,icon])=>({id,name,type,icon}));
export const DEFAULT_ACCOUNTS = [
  {id:'cash', name:'현금', kind:'cash', openingBalance:0},
  {id:'bank', name:'입출금 통장', kind:'bank', openingBalance:0},
  {id:'card', name:'신용카드', kind:'card', openingBalance:0}
];
export function initialState(){return {
 version:1, accounts: structuredClone(DEFAULT_ACCOUNTS), categories: structuredClone(DEFAULT_CATEGORIES), transactions:[],
 settings:{monthlyBudget:1200000,periodStartDay:1,theme:'light',hasOnboarded:false}, budgets:{}, events:[]
};}
export const money = (n) => `${Math.round(Number(n)||0).toLocaleString('ko-KR')}원`;
export function isoDate(date=new Date()){const d=new Date(date);return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;}
export const monthKey=(date=new Date())=> isoDate(date).slice(0,7);
export const asDate=(value)=> /^\d{4}-\d{2}-\d{2}$/.test(String(value||'')) ? new Date(`${value}T12:00:00`) : new Date(value);
export function shiftMonth(month, offset){const [year,m]=month.split('-').map(Number);const d=new Date(year,m-1+offset,1);return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}`;}
export function getPeriod(month,day=1){const [year,m]=month.split('-').map(Number);const safe=Math.min(28,Math.max(1,Number(day)||1));const start=isoDate(new Date(year,m-1,safe));const end=isoDate(new Date(year,m,safe));return {start,end,label:safe===1?`${year}년 ${m}월`:`${start.replaceAll('-','.')} ~ ${isoDate(new Date(year,m,safe-1)).replaceAll('-','.')}`};}
export function currentPeriodMonth(day=1,now=new Date()){const d=Math.min(28,Math.max(1,+day||1));return now.getDate()<d?shiftMonth(monthKey(now),-1):monthKey(now);}
export const isConfirmed=(t)=>t.status==='confirmed';
export function inPeriod(t,p){return t.date>=p.start && t.date<p.end;}
export function getSummary(state,month){
 const p=getPeriod(month,state.settings.periodStartDay);
 let income=0,expense=0,count=0,refunds=0;
 const byCategory={};const filtered=[];
 for(const t of state.transactions){if(!isConfirmed(t)||!inPeriod(t,p))continue;filtered.push(t);count++;
 if(t.kind==='income')income+=t.amount;
 if(t.kind==='expense'){expense+=t.amount;byCategory[t.categoryId]=(byCategory[t.categoryId]||0)+t.amount;}
 if(t.kind==='refund'){refunds+=t.amount;expense-=t.amount;
  const related=state.transactions.find(x=>x.id===t.refundOf); const cat=related?.categoryId||t.categoryId||'other-expense';
  byCategory[cat]=(byCategory[cat]||0)-t.amount;
 }
 }
 return {income,expense,count,refunds,net:income-expense,byCategory,period:p,budget:Number(state.settings.monthlyBudget)||0,remaining:(Number(state.settings.monthlyBudget)||0)-expense,transactions:filtered};
}
export function getAccountBalances(state){
 const balances=Object.fromEntries(state.accounts.map(a=>[a.id,Number(a.openingBalance)||0]));
 for(const t of state.transactions){if(!isConfirmed(t))continue;const amount=Number(t.amount)||0;
  if(!(t.accountId in balances))continue;
  const acct=state.accounts.find(a=>a.id===t.accountId);
  if(t.kind==='expense')balances[t.accountId]+=(acct?.kind==='card'?amount:-amount);
  else if(t.kind==='refund')balances[t.accountId]+=(acct?.kind==='card'?-amount:amount);
  else if(t.kind==='income')balances[t.accountId]+=amount;
  else if(t.kind==='transfer'||t.kind==='repayment'){
    balances[t.accountId]-=amount;
    if(t.toAccountId in balances)balances[t.toAccountId]+=(t.kind==='repayment'?-amount:amount);
  }
 }
 return balances;
}
export function calculateExpression(raw){
 const expr=String(raw||'').replace(/,/g,'').replace(/×/g,'*').replace(/÷/g,'/').trim();
 if(!expr||!/^[-+*/().\d\s]+$/.test(expr))throw Error('숫자와 + − × ÷ ( )만 입력할 수 있습니다.');
 const tokens=expr.match(/(?:\d+(?:\.\d*)?|\.\d+)|[()+*/-]/g)||[];
 if(tokens.join('')!==expr.replace(/\s/g,''))throw Error('계산식을 확인해주세요.');let pos=0;
 const atom=()=>{const tok=tokens[pos++];if(tok==='+'||tok==='-'){const v=atom();return tok==='-'?-v:v;}if(tok==='('){const n=add();if(tokens[pos++]!==')')throw Error('괄호가 올바르지 않습니다.');return n;}if(tok&&/^(\d|\.)/.test(tok)){const n=Number(tok);if(!Number.isFinite(n))throw Error('잘못된 숫자입니다.');return n;}throw Error('계산식이 완성되지 않았습니다.');};
 const mul=()=>{let a=atom();while(tokens[pos]==='*'||tokens[pos]==='/'){const op=tokens[pos++];const b=atom();if(op==='/'&&b===0)throw Error('0으로 나눌 수 없습니다.');a=op==='*'?a*b:a/b;}return a;};
 const add=()=>{let a=mul();while(tokens[pos]==='+'||tokens[pos]==='-'){const op=tokens[pos++];const b=mul();a=op==='+'?a+b:a-b;}return a;};
 const value=add();if(pos!==tokens.length||!Number.isFinite(value))throw Error('계산식을 확인해주세요.');
 const result=Math.round(value);if(!Number.isSafeInteger(result)||result<=0)throw Error('금액은 1원 이상의 올바른 숫자여야 합니다.');return result;
}
export function duplicateOf(candidate,transactions){
 const key=(v)=>String(v||'').toLowerCase().replace(/\s+/g,'').replace(/[^가-힣a-z0-9]/g,'');
 const norm=key(candidate.title);
 return transactions.find(t=>t.id!==candidate.id && t.kind===candidate.kind && t.amount===candidate.amount && t.date===candidate.date && t.accountId===candidate.accountId && (candidate.sourceKey&&candidate.sourceKey===t.sourceKey || norm && norm===key(t.title)))?.id||null;
}
export function suggestCategory(title,kind='expense'){
 const v=String(title||'').toLowerCase();if(kind==='income')return /급여|월급|봉급|salary/.test(v)?'salary':'other-income';
 for(const [cat,pattern] of [['cafe',/카페|커피|스타벅스|이디야|투썸|빽다방/],['grocery',/마트|농협|식자재|장보기|슈퍼|쿠팡프레시/],['transport',/버스|지하철|주유|택시|k-패스|주차|톨비|교통/],['food',/점심|저녁|아침|식당|김밥|피자|치킨|분식|배달/],['shopping',/쇼핑|옷|올리브영|쿠팡|11번가/],['bill',/전기|가스요금|관리비|수도|통신|인터넷|보험료/],['health',/의원|병원|약국|치과|진료/],['leisure',/영화|여행|숙박|공연|티켓/]]){if(pattern.test(v))return cat;}
 return 'other-expense';
}
export function parsePastedLines(text,accountId='bank',today=isoDate()){
 const out=[];for(const raw of text.split(/\r?\n/).map(x=>x.trim()).filter(Boolean)){
 const match=raw.match(/(\d{1,3}(?:,\d{3})+|\d+)\s*원/);if(!match)continue;
 const amount=Number(match[1].replaceAll(',',''));if(!Number.isSafeInteger(amount)||amount<=0)continue;
 const dt=raw.match(/(20\d{2})[.\/-](\d{1,2})[.\/-](\d{1,2})/);
 const date=dt?`${dt[1]}-${dt[2].padStart(2,'0')}-${dt[3].padStart(2,'0')}`:today;
 if(!Number.isFinite(asDate(date).getTime()))continue;
 const kind=/취소|환불/.test(raw)?'refund':/입금|급여|월급/.test(raw)?'income':'expense';
 const title=raw.replace(dt?.[0]||'','').replace(match[0],'').replace(/승인|출금|입금|결제|체크카드|신용카드|원/g,'').trim().slice(0,90)||'가져온 거래';
 out.push({id:crypto.randomUUID(),kind,date,amount,title,accountId,categoryId:suggestCategory(title,kind),status:'pending',memo:'',source:'paste',sourceText:raw,createdAt:new Date().toISOString()});
 }return out;
}
export function parseCsv(text){
 const rows=[];let row=[],field='',quoted=false;const s=text.replace(/^\uFEFF/,'');
 for(let i=0;i<s.length;i++){const c=s[i];if(c==='"'){if(quoted&&s[i+1]==='"'){field+='"';i++;}else quoted=!quoted;}
 else if(c===','&&!quoted){row.push(field);field='';}else if((c==='\n'||c==='\r')&&!quoted){if(c==='\r'&&s[i+1]==='\n')i++;row.push(field);if(row.some(x=>x.trim()))rows.push(row);row=[];field='';}
 else field+=c;}
 if(quoted)throw Error('CSV 따옴표가 닫히지 않았습니다.');row.push(field);if(row.some(x=>x.trim()))rows.push(row);
 if(rows.length<2)throw Error('CSV에는 제목 행과 거래 1건 이상이 필요합니다.');
 const head=rows.shift().map(h=>h.trim().toLowerCase());const idx=(...keys)=>head.findIndex(h=>keys.some(k=>h===k));
 const di=idx('날짜','거래일','date'),ai=idx('금액','amount'),ti=idx('내역','사용처','내용','title','merchant');
 if(di<0||ai<0)throw Error('CSV에 날짜, 금액 열이 필요합니다.');
 return rows.map((r,n)=>{
 const d=(r[di]||'').trim().replace(/[./]/g,'-');const date=/^\d{4}-\d{2}-\d{2}$/.test(d)?d:'';if(!date||!Number.isFinite(asDate(date).getTime()))throw Error(`${n+2}행 날짜 형식 오류 (YYYY-MM-DD)`);
 const raw=(r[ai]||'').replace(/[₩,원\s]/g,'');const amount=Math.round(Math.abs(Number(raw)));if(!Number.isSafeInteger(amount)||amount<=0)throw Error(`${n+2}행 금액 오류`);
 const ki=idx('구분','유형','type'),ci=idx('분류','카테고리','category'),ac=idx('계좌','자산','account'),mi=idx('메모','memo');
 const kindRaw=ki>=0?r[ki]?.trim().toLowerCase():'';
 const kind=/수입|income|입금/.test(kindRaw)?'income':/환불|refund/.test(kindRaw)?'refund':/이체|transfer/.test(kindRaw)?'transfer':/상환|repayment/.test(kindRaw)?'repayment':raw.startsWith('-')?'expense':'expense';
 const title=(ti>=0?r[ti]:null)||'가져온 거래';
 return {id:crypto.randomUUID(),date,kind,amount,title:title.slice(0,90),categoryName:ci>=0?r[ci]?.trim():'',accountName:ac>=0?r[ac]?.trim():'',memo:mi>=0?(r[mi]||'').slice(0,300):'',status:'pending',source:'csv',sourceText:`CSV ${n+2}행`,createdAt:new Date().toISOString()};
 });
}
export function validateBackup(o){
 if(!o||o.version!==1||!Array.isArray(o.transactions)||!Array.isArray(o.accounts)||!Array.isArray(o.categories)||typeof o.settings!=='object'||!o.settings)throw Error('지원하지 않는 백업 파일입니다.');
 if(o.transactions.length>100000||o.accounts.length>500||o.categories.length>500)throw Error('백업 크기가 허용 범위를 초과했습니다.');
 const ids=new Set();for(const a of o.accounts){if(typeof a.id!=='string'||typeof a.name!=='string'||!['cash','bank','card'].includes(a.kind)||!Number.isSafeInteger(+a.openingBalance))throw Error('백업의 자산 정보가 올바르지 않습니다.');}
 for(const t of o.transactions){if(!t||typeof t.id!=='string'||ids.has(t.id)||!KINDS[t.kind]||!/^\d{4}-\d{2}-\d{2}$/.test(t.date)||!Number.isSafeInteger(t.amount)||t.amount<=0||typeof t.title!=='string'||!['pending','confirmed'].includes(t.status))throw Error('백업의 거래 정보가 올바르지 않습니다.');ids.add(t.id);}
 return true;
}
export function createDemoState(){const s=initialState();s.settings.hasOnboarded=true;s.settings.monthlyBudget=800000;s.accounts[1].openingBalance=2500000;const now=new Date();const y=now.getFullYear(),m=now.getMonth();
 const items=[[-12,'income',2650000,'월급','salary','bank'],[-8,'expense',184000,'주간 장보기','grocery','card'],[-6,'expense',62000,'저녁 외식','food','card'],[-4,'expense',48000,'주유','transport','card'],[-3,'expense',12000,'점심 식사','food','bank'],[-2,'expense',5500,'카페 라떼','cafe','card'],[-1,'expense',35000,'생활용품','living','bank'],[0,'expense',9300,'편의점','food','cash']];
 for(const [off,kind,amount,title,categoryId,accountId] of items){const date=isoDate(new Date(y,m,Math.max(1,now.getDate()+off)));s.transactions.push({id:crypto.randomUUID(),date,kind,amount,title,categoryId,accountId,status:'confirmed',memo:'',createdAt:new Date().toISOString()});}
 s.transactions.push({id:crypto.randomUUID(),date:isoDate(now),kind:'expense',amount:16500,title:'새 결제 알림',categoryId:'other-expense',accountId:'card',status:'pending',memo:'확인 후 분류하세요',createdAt:new Date().toISOString()});return s;
}
