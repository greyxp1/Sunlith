import { Agent, request } from 'node:https';
import { connect } from 'node:tls';
import { createHash, timingSafeEqual } from 'node:crypto';
import { readFile, realpath, stat } from 'node:fs/promises';
import { isAbsolute, relative, resolve, sep, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isIP } from 'node:net';

const REQUEST_LIMIT = 512 * 1024;
const RESPONSE_LIMIT = 2 * 1024 * 1024;
const repo = fileURLToPath(new URL('../../../', import.meta.url));
const tooLarge = 'The request or result is too large. Use a smaller file or date range.';

function outsideRepository(path, root) {
  const part = relative(root, path);
  return part !== '' && (part === '..' || part.startsWith(`..${sep}`) || isAbsolute(part));
}
function validateDeviceUrl(value) {
  let url;
  try { url = new URL(value); } catch { throw new Error('Device URL must be HTTPS with a private IPv4 address.'); }
  const host = url.hostname;
  const octets = host.split('.').map(Number);
  const privateIp = isIP(host) === 4 && (octets[0] === 10 || octets[0] === 127 ||
    (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) ||
    (octets[0] === 192 && octets[1] === 168) ||
    (octets[0] === 100 && octets[1] >= 64 && octets[1] <= 127));
  if (value !== value.trim() || url.protocol !== 'https:' || !privateIp || url.username || url.password || url.pathname !== '/' || url.search || url.hash ||
      !/^https:\/\/\d+\.\d+\.\d+\.\d+(?::[1-9][0-9]{0,4})?\/?$/.test(value) ||
      value.match(/^https:\/\/([^:/]+)/)?.[1] !== host || Number(url.port || 443) > 65535) {
    throw new Error('Device URL must be HTTPS with a private literal IPv4 address, optional port, and no credentials, path, query or fragment.');
  }
  return url;
}
async function privateFile(path, maximum, label) {
  if (typeof path !== 'string' || !isAbsolute(path) || !outsideRepository(resolve(path), repo)) throw new Error(`${label} must be an absolute path outside the repository.`);
  try {
    const [actual, actualRepo] = await Promise.all([realpath(path), realpath(repo)]);
    if (!outsideRepository(actual, actualRepo)) throw new Error('private-path');
    if (label === 'Calendar file' && extname(actual).toLowerCase() !== '.ics') throw new Error('private-extension');
    const info = await stat(actual);
    if (!info.isFile() || info.size > maximum || (label === 'Device configuration' && process.platform !== 'win32' && (info.mode & 0o077) !== 0)) throw new Error('private-file');
    const bytes = await readFile(actual);
    if (bytes.length > maximum) throw new Error('private-limit');
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch { throw new Error(`Cannot read ${label.toLowerCase()}. Use a private UTF-8 file outside the repository within the size limit.`); }
}
export async function loadDeviceConfig(path) {
  const source = await privateFile(path, 4096, 'Device configuration');
  let parsed;
  try { parsed = JSON.parse(source); } catch {}
  const { version, baseUrl, certificateSha256, token } = parsed ?? {};
  if (Object.keys(parsed ?? {}).length !== 4 || version !== 1 || typeof baseUrl !== 'string' || baseUrl.length > 100 ||
      !/^[a-f0-9]{64}$/.test(certificateSha256) || !/^[A-Za-z0-9_-]{43}$/.test(token)) throw new Error('Invalid device configuration. Pair the phone again.');
  validateDeviceUrl(baseUrl);
  return parsed;
}

export async function callDevice(config, tool, args) {
  const url = new URL(config.baseUrl);
  if (tool === 'import_calendar_file') {
    if (extname(args.filePath).toLowerCase() !== '.ics') throw new Error('Choose an ICS file.');
    args = { calendarId: args.calendarId, ics: await privateFile(args.filePath, 256 * 1024, 'Calendar file'), confirm: args.confirm };
    tool = 'import_calendar';
  }
  const timeoutMs = tool === 'import_calendar' ? 180_000 : 10_000;
  const body = Buffer.from(JSON.stringify({ tool, arguments: args }), 'utf8');
  if (body.length > REQUEST_LIMIT) throw new Error(tooLarge);
  const expectedPin = Buffer.from(config.certificateSha256, 'hex');
  const agent = new Agent({ keepAlive: false, maxSockets: 1 });
  let connectingSocket;
  agent.createConnection = (options, callback) => {
    const socket = connect({ host: url.hostname, port: Number(url.port || 443), rejectUnauthorized: false, minVersion: 'TLSv1.2' });
    connectingSocket = socket;
    let completed = false;
    const finish = (error) => {
      if (completed) return;
      completed = true;
      callback(error, error ? undefined : socket);
    };
    socket.once('error', () => finish(new Error('Cannot connect securely to the paired Sunlith phone.')));
    socket.once('secureConnect', () => {
      const certificate = socket.getPeerCertificate();
      const pin = certificate.raw ? createHash('sha256').update(certificate.raw).digest() : Buffer.alloc(0);
      if (pin.length !== expectedPin.length || !timingSafeEqual(pin, expectedPin)) {
        finish(new Error('Phone certificate does not match the pairing. No calendar request was sent.'));
        socket.destroy();
        return;
      }
      finish(null);
    });
  };
  return new Promise((resolveResult, reject) => {
    let settled = false;
    let req;
    const finish = (error, result) => {
      if (settled) return;
      settled = true;
      clearTimeout(deadline);
      req?.destroy();
      connectingSocket?.destroy();
      agent.destroy();
      if (error) reject(error); else resolveResult(result);
    };
    const deadline = setTimeout(() => finish(new Error('Sunlith phone request timed out. Open the app and check the private connection.')), timeoutMs);
    try {
      req = request({ hostname: url.hostname, port: Number(url.port || 443), path: '/v1/tools', method: 'POST', agent,
        headers: { authorization: `Bearer ${config.token}`, 'content-type': 'application/json; charset=utf-8', 'content-length': body.length, accept: 'application/json' }, maxHeaderSize: 16 * 1024 }, response => {
        let size = 0;
        const chunks = [];
        if (Number(response.headers['content-length']) > RESPONSE_LIMIT) { finish(new Error(tooLarge)); return; }
        response.on('data', chunk => {
          size += chunk.length;
          if (size > RESPONSE_LIMIT) { finish(new Error(tooLarge)); return; }
          chunks.push(chunk);
        });
        response.once('error', () => finish(new Error('Sunlith phone response was interrupted.')));
        response.once('end', () => {
          if (settled) return;
          let payload;
          try { payload = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks))); }
          catch { finish(new Error(response.statusCode === 413 ? tooLarge : 'Sunlith returned an invalid response.')); return; }
          if (response.statusCode !== 200) {
            const message = payload?.error?.message;
            const fallback = response.statusCode === 413 ? tooLarge : 'Sunlith refused the request. Redirects are not followed.';
            finish(new Error(typeof message === 'string' && message ? message.slice(0, 500) : fallback));
            return;
          }
          if (!payload || typeof payload !== 'object' || Array.isArray(payload) || !Object.hasOwn(payload, 'result') || Object.hasOwn(payload, 'error') ||
              !payload.result || typeof payload.result !== 'object' || Array.isArray(payload.result)) {
            finish(new Error('Sunlith returned an invalid response.')); return;
          }
          finish(null, payload.result);
        });
      });
      req.once('error', error => finish(new Error(error.message.startsWith('Phone certificate does not match') ? error.message : 'Cannot connect securely to the paired Sunlith phone.')));
      req.end(body);
    } catch { finish(new Error('Cannot connect securely to the paired Sunlith phone.')); }
  });
}
