import {createServer} from 'node:http';
import {mkdirSync, readFileSync} from 'node:fs';
import {homedir} from 'node:os';
import {dirname, join} from 'node:path';
import {pathToFileURL} from 'node:url';
import {check, HttpError, number, openStore, peerNumber} from './store.mjs';
import {providerClient} from './provider.mjs';

const assets = new Map([
  ['/', ['index.html','text/html; charset=utf-8']],
  ['/style.css',['style.css','text/css; charset=utf-8']],
  ['/app.js',['app.js','text/javascript; charset=utf-8']],
].map(([route,[file,mime]])=>[route,{mime,body:readFileSync(new URL('./public/'+file,import.meta.url))}]));
const controlHeaders = {
  'Cache-Control':'no-store', 'X-Content-Type-Options':'nosniff', 'Referrer-Policy':'no-referrer',
  'Content-Security-Policy':"default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
};
function field(value,max,empty=false) {
  check(typeof value==='string' && value.length<=max && (empty || value.trim()),400,'Enter valid text.');
  return value;
}
async function jsonBody(req) {
  check((req.headers['content-type']??'').split(';')[0]==='application/json',415,'Use a JSON request.');
  let size=0;
  const chunks=[];
  for await (const chunk of req) {
    size+=chunk.length;
    check(size<=12_000,413,'Request too large.');
    chunks.push(chunk);
  }
  try {
    const data=JSON.parse(Buffer.concat(chunks));
    check(data && typeof data==='object' && !Array.isArray(data),400,'Invalid request.');
    return data;
  } catch { throw new HttpError(400,'Invalid JSON.'); }
}

export function createCompanion(store,provider,origin) {
  const site=new URL(origin);
  return createServer(async (req,res)=>{
    for (const [key,value] of Object.entries(controlHeaders)) res.setHeader(key,value);
    const json=(status,data)=>res.writeHead(status,{'Content-Type':'application/json; charset=utf-8'}).end(JSON.stringify(data));
    try {
      // The canonical trusted origin is configured on the host, independent of any forwarded headers.
      check(req.headers.host===site.host,403,'Unrecognized host.');
      const url=new URL(req.url,origin), route=url.pathname;
      if (req.method==='GET' && assets.has(route)) {
        const asset=assets.get(route);
        res.writeHead(200,{'Content-Type':asset.mime}).end(asset.body);
        return;
      }
      check(route.startsWith('/api/'),404,'Unknown route.');
      check(req.method==='GET' || req.method==='POST',405,'Unsupported method.');
      const post=req.method==='POST';
      if (post) check(req.headers.origin===origin,403,'Open your configured companion address.');
      const data=post?await jsonBody(req):Object.fromEntries(url.searchParams);
      const cookieName=site.protocol==='https:'?'__Host-sms_session':'sms_session';
      const attributes='; Path=/; HttpOnly; SameSite=Strict'+(site.protocol==='https:'?'; Secure':'');
      if (post && route==='/api/pair') {
        const credential=store.pair(field(data.code,64),field(data.label,64));
        res.setHeader('Set-Cookie',cookieName+'='+credential+attributes+'; Max-Age=604800');
        json(200,{ok:true});
        return;
      }
      const cookie=(req.headers.cookie??'').split(';').map(v=>v.trim()).find(v=>v.startsWith(cookieName+'='))?.slice(cookieName.length+1);
      const session=()=>store.session(cookie);
      const {user,id}=session();
      if (data.accountTag) check(data.accountTag===store.accountTag,403,'Different VoIP.ms account.');
      if (!post && route==='/api/me') {
        json(200,{user,id,accountTag:store.accountTag,lines:store.grants(user).map(line=>({...line,wholeLine:store.wholeLine(line.did)})),
          devices:store.all('SELECT id,label,expires FROM sessions WHERE user=? AND expires>? ORDER BY expires DESC',user,Date.now())}); return;
      }
      if (post && route==='/api/pair-code') { json(200,{code:store.invite(user,id)}); return; }
      if (post && route==='/api/revoke') {
        const target=field(data.id,64);
        store.run('DELETE FROM sessions WHERE user=? AND id=?',user,target);
        if (target===id) res.setHeader('Set-Cookie',cookieName+'='+attributes+'; Max-Age=0');
        json(200,{ok:true}); return;
      }
      const did=number(data.did);
      check(did,400,'Choose a valid phone line.');
      store.authorize(user,did);
      if (!post && route==='/api/threads') {
        const {messages,capped}=await provider.history(did);
        session(); store.authorize(user,did);
        const threads=new Map();
        const wide=store.wholeLine(did);
        const excluded=new Set(store.all('SELECT peer FROM excluded WHERE did=? UNION SELECT peer FROM userprivate WHERE did=? AND user=?',did,did,user).map(row=>row.peer));
        const included=new Set(store.all('SELECT peer FROM shared WHERE did=?',did).map(row=>row.peer));
        const owners=new Map(store.all('SELECT peer,user FROM claims WHERE did=?',did).map(v=>[v.peer,v.user]));
        for (const contact of included) {
          if (!excluded.has(contact)) threads.set(contact,{peer:contact,body:'',date:'',owner:owners.get(contact)??null});
        }
        for (const m of messages) if (!excluded.has(m.peer) && (wide || included.has(m.peer))) {
          threads.set(m.peer,{peer:m.peer,body:m.body || 'Media attachment',date:m.date,owner:owners.get(m.peer)??null});
        }
        json(200,{threads:[...threads.values()].sort((a,b)=>b.date.localeCompare(a.date)),capped}); return;
      }
      const peer=peerNumber(data.peer);
      check(peer,400,'Enter a valid contact number.');
      if (post && route==='/api/private') {
        // A read-only teammate can suppress their own devices without changing another person's grant.
        if (store.authorize(user,did)==='manage') store.share(did,peer,false);
        store.transaction(()=>{
          store.run('INSERT OR IGNORE INTO userprivate VALUES(?,?,?)',user,did,peer);
          store.run('DELETE FROM drafts WHERE user=? AND did=? AND peer=?',user,did,peer);
          store.run('DELETE FROM draftsends WHERE user=? AND did=? AND peer=?',user,did,peer);
          store.run('DELETE FROM claims WHERE user=? AND did=? AND peer=?',user,did,peer);
        });
        json(200,{ok:true}); return;
      }
      if (post && route==='/api/share') {
        store.authorize(user,did,'manage');
        check(typeof data.enabled==='boolean',400,'Choose a sharing setting.');
        store.share(did,peer,data.enabled);
        if(data.enabled)store.run('DELETE FROM userprivate WHERE user=? AND did=? AND peer=?',user,did,peer);
        json(200,{ok:true}); return;
      }
      store.requireShared(did,peer,user);
      if (post && route==='/api/native-draft') {
        json(200,store.one('SELECT body,version FROM drafts WHERE user=? AND did=? AND peer=?',user,did,peer)??{body:'',version:0});return;
      }
      if (!post && route==='/api/thread') {
        const {messages,capped}=await provider.history(did);
        session(); store.authorize(user,did); store.requireShared(did,peer,user);
        const sends=store.all("SELECT user,providerId,state,created FROM sends WHERE did=? AND peer=? AND body<>'' ORDER BY created DESC LIMIT 50",did,peer);
        const authors=new Map(sends.filter(v=>v.providerId).map(v=>[v.providerId,v.user]));
        const thread=messages.filter(m=>m.peer===peer);
        const outgoing=new Set(thread.filter(m=>!m.incoming).map(m=>m.id));
        json(200,{messages:thread.map(m=>({...m,author:m.incoming?null:authors.get(m.id)})),capped,
          owner:store.one('SELECT user FROM claims WHERE did=? AND peer=?',did,peer)?.user??null,
          draft:store.one('SELECT body,version FROM drafts WHERE user=? AND did=? AND peer=?',user,did,peer)??{body:'',version:0},
          notes:store.all('SELECT user,body,created FROM notes WHERE did=? AND peer=? ORDER BY id',did,peer),
          attempts:sends.filter(v=>!v.providerId || !outgoing.has(v.providerId))}); return;
      }
      check(post,404,'Unknown route.');
      store.authorize(user,did,'send');
      if (route==='/api/draft') {
        const text=field(data.body,2048,true);
        const version=store.transaction(()=>{
          const current=store.one('SELECT version FROM drafts WHERE user=? AND did=? AND peer=?',user,did,peer)?.version??0;
          check(current===data.version,409,'This draft changed on another device. Reload before editing.');
          store.run('INSERT INTO drafts VALUES(?,?,?,?,?) ON CONFLICT(user,did,peer) DO UPDATE SET body=excluded.body,version=excluded.version',user,did,peer,text,current+1);
          return current+1;
        });
        json(200,{version}); return;
      }
      if (route==='/api/claim') {
        check(typeof data.wanted==='boolean',400,'Choose a claim action.');
        store.transaction(()=>store.claim(user,did,peer,data.wanted)); json(200,{ok:true}); return;
      }
      if (route==='/api/note') {
        store.run('INSERT INTO notes(did,peer,user,body,created) VALUES(?,?,?,?,?)',did,peer,user,field(data.body,2048),Date.now());
        json(201,{ok:true}); return;
      }
      check(route==='/api/send',404,'Unknown route.');
      const body=field(data.body,160), nonce=field(data.nonce,64);
      check(/^[\w-]{20,64}$/.test(nonce) && number(peer),400,'Enter a 10-digit recipient for SMS.');
      // Conservative single-SMS limits. Do not silently split or retry a provider write.
      const gsm=/^[@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !"#¤%&'()*+,./0-9:;<=>?¡A-ZÄÖÑÜ§¿a-zäöñüà\^{}\\\[\]~|€\f-]*$/u;
      const extended=/[\^{}\\\[\]~|€\f]/gu;
      check(gsm.test(body)?body.length+(body.match(extended)?.length??0)<=160:body.length<=70,400,'Shorten this message to one SMS.');
      const previous=store.transaction(()=>{
        const prior=store.one('SELECT did,peer,body,state,providerId FROM sends WHERE user=? AND nonce=?',user,nonce);
        if (prior) { check(prior.did===did && prior.peer===peer && prior.body===body,409,'This send key was already used.'); return prior; }
        if (Number.isInteger(data.version)) {
          const earlier=store.one('SELECT s.body,s.state,s.providerId FROM draftsends d JOIN sends s ON s.user=d.user AND s.nonce=d.nonce WHERE d.user=? AND d.did=? AND d.peer=? AND d.version=?',user,did,peer,data.version);
          if (earlier && earlier.body===body) return earlier;
          const draft=store.one('SELECT version,body FROM drafts WHERE user=? AND did=? AND peer=?',user,did,peer);
          check(data.version===(draft?.version??0),409,'The draft changed on another device. Refresh before sending.');
          if (draft?.body===body) store.run('INSERT INTO draftsends VALUES(?,?,?,?,?)',user,did,peer,data.version,nonce);
        }
        store.claim(user,did,peer,true);
        store.run("INSERT INTO sends VALUES(?,?,?,?,?,'unconfirmed',NULL,?)",user,nonce,did,peer,body,Date.now()); return null;
      });
      if (previous) { json(200,{state:previous.state,providerId:previous.providerId}); return; }
      let result;
      try { result=await provider.send(did,peer,body); } catch { result={state:'unconfirmed',providerId:null}; }
      store.transaction(()=>{
        store.run('UPDATE sends SET state=?,providerId=? WHERE user=? AND nonce=?',result.state,result.providerId,user,nonce);
        if (result.state==='accepted') store.run("UPDATE drafts SET body='',version=version+1 WHERE user=? AND did=? AND peer=? AND body=? AND version=?",user,did,peer,body,Number.isInteger(data.version)?data.version:-1);
      });
      session(); store.authorize(user,did); store.requireShared(did,peer,user);
      json(200,result);
    } catch (err) {
      json(err instanceof HttpError?err.status:502,{error:err instanceof HttpError?err.message:'The service could not complete this request.'});
    }
  });
}

function main() {
  const [command='serve',...args]=process.argv.slice(2);
  const email=process.env.VOIPMS_EMAIL;
  if (!email) throw new Error('Set VOIPMS_EMAIL.');
  const filename=process.env.SMS_COMPANION_DB || join(homedir(),'.voipms-sms-companion','inbox.sqlite');
  process.umask(0o077);
  mkdirSync(dirname(filename),{recursive:true,mode:0o700});
  const store=openStore(filename,email);
  if(command==='grant') {
    const [user,role,...lines]=args;
    check(lines.length>0,400,'Use grant NAME read|send|manage DID...');
    store.transaction(()=>lines.forEach(line=>store.grant(user,line,role))); return;
  }
  if(command==='revoke-user') {
    const [user]=args;
    check(user,400,'Specify an identity.');
    store.transaction(()=>{
      for(const table of ['sessions','invites','grants','claims','drafts','draftsends']) store.run('DELETE FROM '+table+' WHERE user=?',user);
    }); return;
  }
  if(command==='ungrant') {
    const [user,line]=args;
    const did=number(line);
    check(user && did,400,'Use ungrant NAME DID.');
    store.transaction(()=>{
      for(const table of ['grants','claims','drafts','draftsends']) store.run('DELETE FROM '+table+' WHERE user=? AND did=?',user,did);
    }); return;
  }
  if(command==='restore-contact') {
    const [user,line,contact]=args, did=number(line), peer=peerNumber(contact);
    check(user && did && peer,400,'Use restore-contact NAME DID PEER.');
    store.authorize(user,did);
    store.run('DELETE FROM userprivate WHERE user=? AND did=? AND peer=?',user,did,peer);return;
  }
  if(command==='pair') { console.log(store.invite(args[0]??'')); return; }
  if(command==='share' || command==='unshare') { store.share(number(args[0])??'',peerNumber(args[1])??'',command==='share'); return; }
  if(command==='line-wide') {
    const did=number(args[0]);
    check(did && ['on','off'].includes(args[1]),400,'Use line-wide DID on|off.');
    store.run(args[1]==='on'?'INSERT OR IGNORE INTO wholelines VALUES(?)':'DELETE FROM wholelines WHERE did=?',did); return;
  }
  check(command==='serve',400,'Unknown command.');
  const password=process.env.VOIPMS_PASSWORD;
  check(password,400,'Set VOIPMS_PASSWORD.');
  const port=Number(process.env.SMS_COMPANION_PORT??8765);
  check(Number.isInteger(port) && port>0 && port<=65535,400,'Invalid port.');
  const site=new URL(process.env.SMS_COMPANION_ORIGIN??('http://127.0.0.1:'+port));
  check(!site.username && !site.password && site.href===site.origin+'/' &&
    (site.protocol==='https:' || (site.protocol==='http:' && ['127.0.0.1','localhost','[::1]'].includes(site.hostname))),400,'Use HTTPS or a loopback HTTP origin.');
  createCompanion(store,providerClient(email,password),site.origin).listen(port,'127.0.0.1',()=>console.log('Companion available at '+site.origin));
}
if (process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href) {
  try { main(); } catch (err) { console.error(err.message); process.exitCode=1; }
}
