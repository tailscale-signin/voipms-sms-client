import { timingSafeEqual } from 'node:crypto';
import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';

const tokenEndpoint = 'http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token';

function canonicalDid(value) {
  return /^(?:\+?1)?([0-9]{10})$/.exec(value)?.[1];
}

export function readConfig(env) {
  const project = env.FIREBASE_PROJECT_ID ?? '';
  if (!/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(project)) {
    throw new Error('Set FIREBASE_PROJECT_ID to the Android app Firebase project ID.');
  }
  const numbers = (env.ALLOWED_DIDS ?? '').split(',').map(did => canonicalDid(did.trim()));
  if (numbers.some(did => !did)) {
    throw new Error('ALLOWED_DIDS must list one or more comma-separated NANP numbers.');
  }
  const token = env.CALLBACK_TOKEN ?? '';
  if (token && !/^[A-Za-z0-9_-]{32,128}$/.test(token)) {
    throw new Error('CALLBACK_TOKEN must contain 32 to 128 URL-safe characters.');
  }
  return { project, allowed: new Set(numbers), path: Buffer.from('/' + token) };
}

export function createSender(project, fetchRequest = fetch) {
  const messageEndpoint = `https://fcm.googleapis.com/v1/projects/${project}/messages:send`;
  let cached;
  let pending;

  async function fetchToken() {
    const response = await fetchRequest(tokenEndpoint, {
      headers: { 'Metadata-Flavor': 'Google' },
      redirect: 'error',
      signal: AbortSignal.timeout(5000),
    });
    if (!response.ok) throw new Error('Service identity unavailable');
    const { access_token, expires_in } = await response.json();
    if (typeof access_token !== 'string' || !access_token || !(Number(expires_in) > 0)) {
      throw new Error('Service identity token unavailable');
    }
    cached = { value: access_token, expires: Date.now() + Number(expires_in) * 1000 };
    return cached.value;
  }

  async function accessToken() {
    if (cached && Date.now() + 60_000 < cached.expires) return cached.value;
    pending ??= fetchToken().finally(() => { pending = undefined; });
    return pending;
  }

  return async function send(did) {
    const token = await accessToken();
    const response = await fetchRequest(messageEndpoint, {
      method: 'POST',
      redirect: 'error',
      signal: AbortSignal.timeout(8000),
      headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message: { topic: 'did-' + did, android: { priority: 'HIGH' }, data: { event: 'sync' } },
      }),
    });
    if (!response.ok) {
      if (response.status === 401 && cached?.value === token) cached = undefined;
      throw new Error('Push declined');
    }
    const { name } = await response.json();
    const prefix = 'projects/' + project + '/messages/';
    if (typeof name !== 'string' || !name.startsWith(prefix) || name.length === prefix.length) {
      throw new Error('Push acceptance unconfirmed');
    }
  };
}

export function createRelay(config, send = createSender(config.project)) {
  return createServer(async (request, response) => {
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('Content-Type', 'text/plain; charset=utf-8');
    const target = request.url ?? '';
    const separator = target.indexOf('?');
    const path = Buffer.from(separator === -1 ? target : target.slice(0, separator));
    if (path.length !== config.path.length || !timingSafeEqual(path, config.path)) {
      response.writeHead(404).end();
      return;
    }
    if (request.method !== 'GET') {
      response.writeHead(405, { Allow: 'GET' }).end();
      return;
    }
    // VoIP.ms may substitute a literal plus in its GET query. Form decoding would turn it into a space.
    const query = separator === -1 ? '' : target.slice(separator + 1);
    const value = /^did=((?:\+|%2[bB])?[0-9]+)$/.exec(query)?.[1];
    const did = value && canonicalDid(value.replace(/^%2b/i, '+'));
    if (!did) {
      response.writeHead(400).end();
      return;
    }
    if (!config.allowed.has(did)) {
      response.writeHead(404).end();
      return;
    }
    try {
      await send(did);
      response.writeHead(200).end('ok');
    } catch {
      response.writeHead(503).end();
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = Number(process.env.PORT ?? 8080);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid PORT');
  createRelay(readConfig(process.env)).listen(port, '0.0.0.0');
}
