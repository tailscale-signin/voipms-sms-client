import { createHash, randomBytes } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';

export class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
export function check(ok, status, message) {
  if (!ok) throw new HttpError(status, message);
}
export function number(value) {
  return /^(?:\+?1)?(\d{10})$/.exec(String(value))?.[1];
}
export function peerNumber(value) {
  return number(value) ?? (/^\d{3,8}$/.test(String(value)) ? String(value) : undefined);
}
const token = () => randomBytes(24).toString('base64url');
const hash = value => createHash('sha256').update(String(value)).digest('hex');
const ranks = { read: 0, send: 1, manage: 2 };

export function openStore(filename, account) {
  const db = new DatabaseSync(filename);
  db.exec(`
    PRAGMA journal_mode=WAL;
    PRAGMA busy_timeout=5000;
    CREATE TABLE IF NOT EXISTS provider(account TEXT PRIMARY KEY);
    CREATE TABLE IF NOT EXISTS grants(user TEXT, did TEXT, role TEXT, PRIMARY KEY(user,did));
    CREATE TABLE IF NOT EXISTS invites(digest TEXT PRIMARY KEY,user TEXT,expires INTEGER,issuer TEXT);
    CREATE TABLE IF NOT EXISTS sessions(digest TEXT PRIMARY KEY,id TEXT UNIQUE,user TEXT,label TEXT,expires INTEGER);
    CREATE TABLE IF NOT EXISTS shared(did TEXT,peer TEXT,PRIMARY KEY(did,peer));
    CREATE TABLE IF NOT EXISTS wholelines(did TEXT PRIMARY KEY);
    CREATE TABLE IF NOT EXISTS excluded(did TEXT,peer TEXT,PRIMARY KEY(did,peer));
    CREATE TABLE IF NOT EXISTS userprivate(user TEXT,did TEXT,peer TEXT,PRIMARY KEY(user,did,peer));
    CREATE TABLE IF NOT EXISTS drafts(user TEXT,did TEXT,peer TEXT,body TEXT,version INTEGER DEFAULT 1,PRIMARY KEY(user,did,peer));
    CREATE TABLE IF NOT EXISTS claims(did TEXT,peer TEXT,user TEXT,PRIMARY KEY(did,peer));
    CREATE TABLE IF NOT EXISTS notes(id INTEGER PRIMARY KEY,did TEXT,peer TEXT,user TEXT,body TEXT,created INTEGER);
    CREATE TABLE IF NOT EXISTS sends(user TEXT,nonce TEXT,did TEXT,peer TEXT,body TEXT,state TEXT,providerId TEXT,created INTEGER,PRIMARY KEY(user,nonce));
  `);
  const run = (sql,...params) => db.prepare(sql).run(...params);
  const one = (sql,...params) => db.prepare(sql).get(...params);
  const all = (sql,...params) => db.prepare(sql).all(...params);
  const transaction = action => {
    db.exec('BEGIN IMMEDIATE');
    try { const result = action(); db.exec('COMMIT'); return result; }
    catch (err) { db.exec('ROLLBACK'); throw err; }
  };
  transaction(()=>{
    const bound = one('SELECT account FROM provider');
    if (bound && bound.account !== hash(account.trim().toLowerCase())) {
      throw new Error('Use a separate companion database for each VoIP.ms account.');
    }
    if (!bound) run('INSERT INTO provider VALUES (?)',hash(account.trim().toLowerCase()));
  });
  const grants = user => all('SELECT did,role FROM grants WHERE user=? ORDER BY did',user);
  const grant = (user,did,role) => {
    check(typeof user==='string' && /^[\w .@-]{1,64}$/.test(user) && number(did) && Object.hasOwn(ranks,role),400,'Invalid grant.');
    run('INSERT INTO grants VALUES (?,?,?) ON CONFLICT(user,did) DO UPDATE SET role=excluded.role',user,number(did),role);
  };
  const authorize = (user,did,role='read') => {
    const current = one('SELECT role FROM grants WHERE user=? AND did=?',user,did)?.role;
    check(current && ranks[current]>=ranks[role],403,'This account has no permission for this action.');
    return current;
  };
  const share = (did,peer,enabled) => transaction(()=>{
    check(number(did)===did && peerNumber(peer)===peer,400,'Enter a valid line and contact number.');
    if (enabled) {
      run('DELETE FROM excluded WHERE did=? AND peer=?',did,peer);
      run('INSERT OR IGNORE INTO shared VALUES (?,?)',did,peer);
      return;
    }
    run('INSERT OR IGNORE INTO excluded VALUES (?,?)',did,peer);
    run('DELETE FROM shared WHERE did=? AND peer=?',did,peer);
    for (const name of ['claims','notes','drafts']) run('DELETE FROM '+name+' WHERE did=? AND peer=?',did,peer);
    // Keep replay protection when a contact is shared again.
    run("UPDATE sends SET body='' WHERE did=? AND peer=?",did,peer);
  });
  const wholeLine = did => Boolean(one('SELECT 1 FROM wholelines WHERE did=?',did));
  const shared = (did,peer) => !one('SELECT 1 FROM excluded WHERE did=? AND peer=?',did,peer)
    && (wholeLine(did) || Boolean(one('SELECT 1 FROM shared WHERE did=? AND peer=?',did,peer)));
  const requireShared = (did,peer,user) => check(shared(did,peer) &&
    !one('SELECT 1 FROM userprivate WHERE user=? AND did=? AND peer=?',user,did,peer),404,'This contact has not been shared.');
  const invite = (user,issuer=null) => {
    check(grants(user).length,400,'Grant access to a line first.');
    run('DELETE FROM invites WHERE expires<=?',Date.now());
    const code=token();
    run('INSERT INTO invites VALUES(?,?,?,?)',hash(code),user,Date.now()+10*60_000,issuer);
    return code;
  };
  const pair = (code,label) => transaction(()=>{
    const person=one('DELETE FROM invites WHERE digest=? AND expires>? RETURNING user,issuer',hash(code),Date.now());
    check(person && grants(person.user).length,401,'Pairing code expired or invalid.');
    if (person.issuer) check(one('SELECT 1 FROM sessions WHERE id=? AND user=? AND expires>?',person.issuer,person.user,Date.now()),401,'Pairing device revoked.');
    run('DELETE FROM sessions WHERE expires<=?',Date.now());
    const credential=token();
    run('INSERT INTO sessions VALUES(?,?,?,?,?)',hash(credential),token(),person.user,label,Date.now()+7*86_400_000);
    return credential;
  });
  const session = credential => {
    const result=one('SELECT id,user,label FROM sessions WHERE digest=? AND expires>?',hash(credential),Date.now());
    check(result && grants(result.user).length,401,'Pair this device to continue.');
    return result;
  };
  const claim = (user,did,peer,wanted) => {
    const owner=one('SELECT user FROM claims WHERE did=? AND peer=?',did,peer)?.user;
    check(!owner || owner===user,409,'Claimed by '+owner+'.');
    if(wanted) run('INSERT OR IGNORE INTO claims VALUES(?,?,?)',did,peer,user);
    else run('DELETE FROM claims WHERE did=? AND peer=? AND user=?',did,peer,user);
  };
  return {db,run,one,all,transaction,accountTag:hash(account.trim().toLowerCase()),grants,grant,authorize,share,shared,wholeLine,requireShared,invite,pair,session,claim};
}
