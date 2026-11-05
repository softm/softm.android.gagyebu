// Browser boundary tests use synthetic native identity and ledger data only.
const {chromium}=require(process.env.GAGYEBU_UI_RUNTIME?require('node:path').join(process.env.GAGYEBU_UI_RUNTIME,'node_modules/playwright'):'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs/promises');
const base=process.env.GAGYEBU_UI_URL||'http://127.0.0.1:8436',out=process.env.GAGYEBU_UI_OUTPUT||'/tmp/gagyebu-common-login-20261105';
(async()=>{
 const browser=await chromium.launch();
 try{
  const context=await browser.newContext({viewport:{width:393,height:852},serviceWorkers:'block'});
  const unexpected=[];
  await context.route('**/*',r=>{if(new URL(r.request().url()).origin===base)return r.continue();unexpected.push(r.request().url());return r.abort();});
  await context.addInitScript(()=>{
   window.fixture={actions:[],popups:0,state:JSON.parse(sessionStorage.getItem('fixture-account')||'{"status":"idle","error":"","user":null}')};
   window.open=()=>{fixture.popups++;return null;};
   window.finishLogin=(user,error='')=>{fixture.state={status:'idle',error,user};sessionStorage.setItem('fixture-account',JSON.stringify(fixture.state));window.dispatchEvent(new Event('gagyebu:account-changed'));};
   window.AndroidLedger={postMessage(raw){const r=JSON.parse(raw);fixture.actions.push({action:r.action,payload:r.payload});let result=true;
    if(r.action==='ledgerSync'){const old=JSON.parse(sessionStorage.getItem('fixture-ledger')||'null');if(r.payload.op==='read')result=old||{revision:null};else {result={revision:crypto.randomUUID()};sessionStorage.setItem('fixture-ledger',JSON.stringify({...result,ledger:r.payload.ledger}));}}
    if(r.action==='status')result={};
    if(r.action==='pending')result=[];
    if(r.action==='consumeLaunch')result=null;
    if(r.action==='accountStatus')result=fixture.state;
    if(r.action==='accountLogin')fixture.state={...fixture.state,status:'waiting',error:''};
    if(r.action==='accountLogout')finishLogin(null);
    if(r.action==='accountCancel')finishLogin(null,'로그인을 취소했습니다.');
    setTimeout(()=>AndroidLedger.onmessage({data:JSON.stringify({id:r.id,ok:true,result})}),0);
   }};
  });
  const page=await context.newPage(),errors=[];page.on('dialog',d=>d.accept());page.on('pageerror',e=>errors.push(e.message));
  await page.goto(base);await page.locator('softm-site-account[data-auth-state="guest"]').waitFor();
  await page.evaluate(async()=>{const {initialState,isoDate}=await import('./src/domain.js'),{saveState}=await import('./src/db.js');const s=initialState();s.transactions=[{id:'keep',title:'보존할 거래',amount:12300,kind:'expense',date:isoDate(),accountId:'bank',categoryId:'cafe',status:'pending',memo:'기존 기기 기록',source:'manual',createdAt:new Date().toISOString()}];await saveState(s);});
  await page.reload();await page.locator('softm-site-account[data-auth-state="guest"]').waitFor();
  const ledger=()=>page.evaluate(async()=>await(await import('./src/db.js')).loadState(null));const before=await ledger();
  const profile=page.locator('softm-site-account #profile'),menu=page.locator('softm-account-popover #menu');
  for(const width of [320,393,768,1280]){
   await page.setViewportSize({width,height:852});await profile.click();await menu.waitFor({state:'visible'});
   const box=await menu.boundingBox();assert.ok(box.x>=0&&box.x+box.width<=width+1);assert.ok(box.y>=0&&box.y+box.height<=852+1);
   if(width===393)await page.screenshot({path:out+'/account-guest-393.png'});await page.keyboard.press('Escape');
  }
  await page.setViewportSize({width:393,height:852});await profile.click();await page.locator('softm-account-popover #login').click();
  await page.waitForFunction(()=>fixture.actions.some(x=>x.action==='accountLogin'));assert.equal(await page.evaluate(()=>fixture.popups),0);
  await page.evaluate(()=>finishLogin({uid:'fixture-user',displayName:'검증 계정',email:'fixture@example.test',isAnonymous:false,photoURL:'javascript:alert(1)',providerData:[]}));
  await page.locator('softm-site-account[data-auth-state="authenticated"]').waitFor();assert.deepEqual(await ledger(),before);
  await page.waitForFunction(()=>fixture.actions.some(x=>x.action==='ledgerSync'&&x.payload.op==='write'));await profile.click();await page.screenshot({path:out+'/account-signed-in-393.png'});
  assert.equal(await page.locator('softm-account-popover #manage').isVisible(),true);
  assert.equal(await page.locator('softm-account-popover #withdraw-group').isVisible(),true);
  await page.reload();await page.locator('softm-site-account[data-auth-state="authenticated"]').waitFor();assert.deepEqual(await ledger(),before);
  await profile.click();await page.locator('softm-account-popover #logout').click();await page.locator('softm-site-account[data-auth-state="guest"]').waitFor();assert.deepEqual(await ledger(),before);
  await profile.click();await page.locator('softm-account-popover #signup').click();await page.waitForFunction(()=>fixture.actions.some(x=>x.action==='accountLogin'&&x.payload.mode==='signup'));
  await page.evaluate(()=>finishLogin(null,'로그인을 취소했습니다.'));
  await page.locator('softm-account-popover #feedback').filter({hasText:'취소'}).waitFor();assert.equal(await page.locator('softm-account-popover #login').isEnabled(),true);
  await page.locator('softm-account-popover #login').click();await page.evaluate(()=>finishLogin(null,'연결할 수 없습니다.'));
  await page.locator('softm-account-popover #feedback').filter({hasText:'연결할 수 없습니다'}).waitFor();assert.deepEqual(await ledger(),before);
  const actions=await page.evaluate(()=>fixture.actions.map(x=>x.action));assert.equal(actions.includes('clearHistory'),false);
  assert.deepEqual(unexpected,[]);assert.deepEqual(errors,[]);
  await fs.writeFile(out+'/browser-results.json',JSON.stringify({passed:['four viewport menu layouts','native external browser handoff','login/signup/logout','cancel and retry','profile URL safety','session restoration','ledger unchanged','guest preserved during account sync; no capture deletion'],errors,unexpected},null,2));
  console.log('PASS common account UI: guest ledger preserved; sync through native bridge; mobile/desktop layout.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
