import assert from 'node:assert/strict';
import { once } from 'node:events';
import test from 'node:test';
import { createRelay, createSender, readConfig } from './server.mjs';

const env = { FIREBASE_PROJECT_ID: 'sms-demo', ALLOWED_DIDS: '+14165550100,14165550101' };

async function startRelay(t, config, sender) {
  const server = createRelay(config, sender);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => new Promise(resolve => server.close(resolve)));
  return 'http://127.0.0.1:' + server.address().port;
}

test('operator configuration rejects empty or malformed routes', () => {
  for (const invalid of [
    { ALLOWED_DIDS: '' }, { ALLOWED_DIDS: '4165550100,x' }, { FIREBASE_PROJECT_ID: 'bad/id' },
    { CALLBACK_TOKEN: 'short' }, { CALLBACK_TOKEN: '/'.repeat(35) },
  ]) {
    assert.throws(() => readConfig({ ...env, ...invalid }));
  }
});

test('exact VoIP.ms acknowledgement for each accepted DID format, including raw plus', async t => {
  const received = [];
  const base = await startRelay(t, readConfig(env), async did => received.push(did));
  for (const form of ['4165550100', '14165550100', '+14165550100', '%2B14165550100']) {
    const response = await fetch(base + '/?did=' + form);
    assert.equal(response.status, 200);
    assert.equal(await response.text(), 'ok');
  }
  assert.deepEqual(received, Array(4).fill('4165550100'));
});

test('incorrect paths, ambiguous requests, payload parameters and unlisted DIDs are rejected', async t => {
  const path = 'a'.repeat(40);
  let calls = 0;
  const base = await startRelay(t, readConfig({ ...env, CALLBACK_TOKEN: path }), async () => { calls++; });
  for (const [target, method, status] of [
    ['/?did=4165550100', 'GET', 404], ['/' + path + '?did=4165550199', 'GET', 404],
    ['/' + path + '?did=4165550100', 'POST', 405], ['/' + path, 'GET', 400],
    ['/' + path + '?did=4165550100&message=example', 'GET', 400],
    ['/' + path + '?did=4165550100&did=4165550101', 'GET', 400],
    ['/' + path + '?did=%204165550100', 'GET', 400],
  ]) {
    const response = await fetch(base + target, { method });
    assert.equal(response.status, status);
    assert.equal(await response.text(), '');
  }
  assert.equal(calls, 0);
  assert.equal((await fetch(base + '/' + path + '?did=4165550100')).status, 200);
  assert.equal(calls, 1);
});

test('managed identity can send two distinct data-only high-priority messages with one access token', async t => {
  let metadata = 0;
  const messages = [];
  const sender = createSender(env.FIREBASE_PROJECT_ID, async (url, request) => {
    if (url.startsWith('http://metadata.google.internal/')) {
      assert.equal(request.headers['Metadata-Flavor'], 'Google');
      metadata++;
      return Response.json({ access_token: 'test-token', expires_in: 3600 });
    }
    assert.equal(url, 'https://fcm.googleapis.com/v1/projects/sms-demo/messages:send');
    assert.equal(request.headers.Authorization, 'Bearer test-token');
    messages.push(JSON.parse(request.body).message);
    return Response.json({ name: 'projects/sms-demo/messages/test-message' });
  });
  const base = await startRelay(t, readConfig(env), sender);
  const dids = ['4165550100', '4165550101'];
  const results = await Promise.all(dids.map(did => fetch(base + '/?did=' + did)));
  for (const response of results) assert.equal(await response.text(), 'ok');
  assert.equal(metadata, 1);
  for (const message of messages) {
    assert.ok(Number(message.data.callbackTime) > 0);
    delete message.data.callbackTime;
  }
  assert.deepEqual(messages.sort((a, b) => a.topic.localeCompare(b.topic)), dids.map(did => ({
    topic: 'did-' + did, android: { priority: 'HIGH' }, data: { event: 'sync' },
  })));
});

test('credential failure, rejection, malformed upstream success and network loss permit provider retry', async t => {
  let reply;
  const sender = createSender(env.FIREBASE_PROJECT_ID, async (url) => {
    if (reply === 'metadata error') throw new Error('unavailable');
    if (url.startsWith('http://metadata.google.internal/')) {
      return Response.json({ access_token: 'test', expires_in: 3600 });
    }
    if (reply === 'FCM error') throw new Error('network unavailable');
    return reply;
  });
  const base = await startRelay(t, readConfig(env), sender);
  for (const upstream of ['metadata error', new Response('', { status: 429 }), Response.json({}), 'FCM error']) {
    reply = upstream;
    const response = await fetch(base + '/?did=4165550100');
    assert.equal(response.status, 503);
    assert.equal(await response.text(), '');
  }
  reply = Response.json({ name: 'projects/sms-demo/messages/retried' });
  assert.equal(await (await fetch(base + '/?did=4165550100')).text(), 'ok');
});

test('Google-free callbacks route only to subscribed devices for their own line', async () => {
  const { createRouter } = await import('./server.mjs');
  const subscription = {endpoint:'https://push.example/u/token', keys:{p256dh:'A'.repeat(87),auth:'B'.repeat(22)}};
  const config = readConfig({ALLOWED_DIDS:'4165550100', UNIFIEDPUSH_SUBSCRIPTIONS:JSON.stringify({'4165550100':[subscription]})});
  const sent = [];
  const route = createRouter(config, () => assert.fail('unexpected Google push'), async (sub, time) => sent.push([sub,time]));
  await route('4165550100',100);
  assert.deepEqual(sent, [[subscription,100]]);
  await assert.rejects(route('4165550199',101));
  assert.throws(() => readConfig({ ALLOWED_DIDS:'4165550100' }));
});
