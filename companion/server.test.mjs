import assert from 'node:assert/strict';
import {afterEach,beforeEach,test} from 'node:test';
import {once} from 'node:events';
import {request as httpRequest} from 'node:http';
import {openStore} from './store.mjs';
import {createCompanion} from './server.mjs';
import {providerClient} from './provider.mjs';

const did='4165550100', second='4165550101', peer='6475550110', other='6475550111';
const site='https://inbox.example.test';
let db,server,address,provider,pc,viewer,teammate,count;
const base={did,peer};
async function request(path,cookie,values,post=false,origin=site) {
  const url=address+'/api/'+path+(!post && values?'?'+new URLSearchParams(values):'');
  const headers={Host:'inbox.example.test'};
  if(cookie)headers.Cookie=cookie;
  if(post){headers.Origin=origin;headers['Content-Type']='application/json';}
  const response=await new Promise((resolve,reject)=>{
    const req=httpRequest(url,{headers,method:post?'POST':'GET'},resolve);
    req.on('error',reject);
    req.end(post?JSON.stringify(values??{}):undefined);
  });
  const chunks=[];
  for await(const chunk of response) chunks.push(chunk);
  return {status:response.statusCode,body:JSON.parse(Buffer.concat(chunks)),cookie:response.headers['set-cookie']?.[0]?.split(';')[0]};
}
async function login(user) {
  const resp=await request('pair',null,{code:db.invite(user),label:'browser'},true);
  assert.equal(resp.status,200); return resp.cookie;
}
beforeEach(async()=>{
  db=openStore(':memory:','fixture@example.test');
  db.grant('Owner',did,'manage');db.grant('Guest',did,'read');db.grant('Colleague',did,'send');
  db.share(did,peer,true);count=0;
  provider={
    history:async()=>({capped:false,messages:[peer,other].map((contact,i)=>({peer:contact,id:String(i+1),incoming:true,date:'2026-11-01 09:00:00',body:'Message to '+contact}))}),
    send:async()=>{count++;return {state:'accepted',providerId:'123'};}
  };
  server=createCompanion(db,provider,site);server.listen(0,'127.0.0.1');await once(server,'listening');
  address='http://127.0.0.1:'+server.address().port;
  pc=await login('Owner');viewer=await login('Guest');teammate=await login('Colleague');
});
afterEach(async()=>{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));db.db.close();});

test('one-time pairing, origin checks and revocation apply to every request',async()=>{
  assert.equal((await request('me')).status,401);
  const invite=db.invite('Owner');
  assert.equal((await request('pair',null,{code:invite,label:'Phone'},true,'https://other.test')).status,403);
  const inviteArgs={code:invite,label:'Phone'};
  const phone=await request('pair',null,inviteArgs,true);
  assert.equal((await request('pair',null,inviteArgs,true)).status,401);
  const id=(await request('me',phone.cookie)).body.id;
  const issued=(await request('pair-code',phone.cookie,{},true)).body.code;
  await request('revoke',viewer,{id},true);
  assert.equal((await request('me',phone.cookie)).status,200);
  await request('revoke',pc,{id},true);
  assert.equal((await request('me',phone.cookie)).status,401);
  assert.equal((await request('pair',null,{code:issued,label:'Late'},true)).status,401);
});
test('DID role, private contact and line-wide exclusion cover every read and write',async()=>{
  assert.equal((await request('threads',pc,{did:second})).status,403);
  assert.deepEqual((await request('threads',viewer,{did})).body.threads.map(t=>t.peer),[peer]);
  assert.equal((await request('thread',pc,{did,peer:other})).status,404);
  for (const route of ['draft','claim','note','send','share']) {
    assert.equal((await request(route,viewer,{...base},true)).status,403,route);
  }
  assert.equal((await request('share',teammate,{did,peer:other,enabled:true},true)).status,403);
  db.run('INSERT INTO wholelines VALUES(?)',did);
  assert.equal((await request('threads',viewer,{did})).body.threads.length,2);
  await request('share',pc,{...base,enabled:false},true);
  assert.deepEqual((await request('threads',viewer,{did})).body.threads.map(t=>t.peer),[other]);
  assert.equal((await request('thread',pc,base)).status,404);
  assert.equal((await request('send',pc,{...base,body:'hello',nonce:'a'.repeat(24)},true)).status,404);
});
test('an exclusion during a provider fetch takes effect before returning messages',async()=>{
  const getHistory=provider.history;let resume,resolveStarted;
  const started=new Promise(resolve=>{resolveStarted=resolve;});
  provider.history=async()=>{resolveStarted();await new Promise(r=>{resume=r;});return getHistory();};
  const pending=request('thread',viewer,base);await started;
  db.share(did,peer,false);resume();
  assert.equal((await pending).status,404);
});
test('drafts survive handoff, reject stale overwrites and stay private to their author',async()=>{
  const phone=await login('Owner');
  assert.equal((await request('draft',pc,{...base,version:0,body:'Draft one'},true)).body.version,1);
  assert.equal((await request('thread',phone,base)).body.draft.body,'Draft one');
  assert.equal((await request('thread',teammate,base)).body.draft.body,'');
  assert.equal((await request('draft',phone,{...base,version:0,body:'Outdated'},true)).status,409);
  assert.equal((await request('thread',pc,base)).body.draft.body,'Draft one');
});
test('native paired draft requests bind to account, line and peer with no provider history call',async()=>{
  const identity=(await request('me',pc)).body;
  assert.match(identity.accountTag,/^[a-f0-9]{64}$/);
  provider.history=async()=>{throw new Error('Draft handoff must work without a provider history query');};
  const args={...base,accountTag:identity.accountTag};
  assert.equal((await request('draft',pc,{...args,version:0,body:'Draft from Android'},true)).status,200);
  assert.deepEqual((await request('native-draft',pc,args,true)).body,{body:'Draft from Android',version:1});
  assert.equal((await request('native-draft',pc,{...args,accountTag:'another'},true)).status,403);
  assert.equal((await request('native-draft',pc,{...args,did:second},true)).status,403);
  assert.equal((await request('private',viewer,args,true)).status,200);
  assert.equal((await request('native-draft',viewer,args,true)).status,404);
  assert.equal((await request('native-draft',pc,args,true)).status,200);
  assert.equal((await request('private',pc,args,true)).status,200);
  assert.equal((await request('native-draft',teammate,args,true)).status,404);
});
test('claims block competing sends; notes never reach the provider',async()=>{
  assert.equal((await request('claim',pc,{...base,wanted:true},true)).status,200);
  assert.equal((await request('claim',teammate,{...base,wanted:false},true)).status,409);
  assert.equal((await request('send',teammate,{...base,nonce:'q'.repeat(24),body:'Hi'},true)).status,409);
  await request('note',teammate,{...base,body:'Internal follow-up'},true);
  assert.equal((await request('thread',viewer,base)).body.notes[0].body,'Internal follow-up');
  assert.equal(count,0);
  await request('claim',pc,{...base,wanted:false},true);
  assert.equal((await request('claim',teammate,{...base,wanted:true},true)).status,200);
});
test('an ambiguous send is never replayed, including concurrent requests',async()=>{
  const attempt={...base,body:'Once',nonce:'x'.repeat(24)};
  provider.send=async()=>{count++;throw Error('Lost response');};
  const pending=await Promise.all([request('send',pc,attempt,true),request('send',pc,attempt,true)]);
  assert.ok(pending.every(r=>r.body.state==='unconfirmed'));assert.equal(count,1);
  assert.equal((await request('send',pc,{...attempt,body:'Different'},true)).status,409);
  assert.equal((await request('send',pc,{...attempt,body:'ä'.repeat(90)},true)).status,409);
  assert.equal((await request('send',pc,{...attempt,body:'你'.repeat(71)},true)).status,400);
  assert.equal((await request('send',pc,{...attempt,body:'^'.repeat(81)},true)).status,400);
});
test('two devices submitting the same saved draft create a single outbound message',async()=>{
  const phone=await login('Owner');
  await request('draft',pc,{...base,body:'Finish on desktop',version:0},true);
  const draft={...base,body:'Finish on desktop',version:1};
  const results=await Promise.all([
    request('send',pc,{...draft,nonce:'a'.repeat(24)},true),
    request('send',phone,{...draft,nonce:'b'.repeat(24)},true),
  ]);
  assert.ok(results.every(v=>v.status===200));
  assert.equal(count,1);
  assert.equal((await request('thread',pc,base)).body.draft.body,'');
});
test('VoIP.ms adapter matches Android field shapes, omits other DIDs and masks code messages',async()=>{
  const captured=[];
  const api=providerClient('me','secret',async(url,options)=>{
    assert.equal(url,'https://voip.ms/api/v1/rest.php');assert.equal(options.redirect,'error');
    const form=Object.fromEntries(options.body);captured.push(form);
    return Response.json(form.method==='sendSMS'?{status:'success',sms:73}:{status:'success',sms:[
      {did:second,contact:peer,id:'1',type:'1',date:'2026-11-02 09:00:00',message:'PRIVATE'},
      {did:'+1'+did,contact:peer,id:'2',type:'1',date:'2026-11-02 09:00:00',message:'Your login code is 128456',col_media1:'url',media:['url','url2']},
    ]});
  });
  const {messages}=await api.history(did);assert.equal(messages.length,1);
  assert.equal(messages[0].attachments,2);assert.ok(!messages[0].body.includes('128456'));
  assert.equal((await api.send(did,peer,'hello')).providerId,'73');
  assert.deepEqual(captured.map(c=>[c.method,c.did]),[['getMMS',did],['sendSMS',did]]);
  assert.equal(captured[0].all_messages,'1');assert.equal(captured[1].dst,peer);
});
