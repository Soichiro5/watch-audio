// Pairing (docs/research/agent-access.md §4.3): `watch-audio-mcp pair` prints a QR code with the
// bridge's key fingerprint, its addresses and a one-time value valid for 5 minutes. The phone
// connects over TLS pinning that fingerprint, sends the value and its own certificate; both screens
// show the same 6-digit code and both users confirm. Only then is the phone's key stored.

import { createHash, randomBytes, timingSafeEqual, X509Certificate } from 'node:crypto';
import { createServer, type TLSSocket } from 'node:tls';
import { certificateFingerprint, toPem } from './certificate.js';
import type { BridgeConfig, ConfigStore } from './config.js';
import { encodeFrame, ErrorCode, FrameDecoder, PROTOCOL_VERSION, type AgentMessage } from './wire.js';

export const PAIRING_VALIDITY_MS = 5 * 60_000;
const TOKEN_BYTES = 16;
const SCHEME = 'watchaudio-mcp://pair';
const UNRESERVED = /[A-Za-z0-9\-._~:[\]]/;

export interface PairingUri {
  version: number;
  name: string;
  fingerprint: string;
  addresses: string[];
  token: string;
  expiresAt: number;
}

function percentEncode(text: string): string {
  let out = '';
  for (const byte of Buffer.from(text, 'utf8')) {
    const c = String.fromCharCode(byte);
    out += byte < 0x80 && UNRESERVED.test(c) ? c : '%' + byte.toString(16).toUpperCase().padStart(2, '0');
  }
  return out;
}

export function buildPairingUri(uri: PairingUri): string {
  return `${SCHEME}?v=${uri.version}&n=${percentEncode(uri.name)}&fp=${uri.fingerprint}` +
    `&a=${uri.addresses.map(percentEncode).join(',')}&t=${uri.token}&e=${uri.expiresAt}`;
}

/** @throws Error if [text] is not a pairing URI of this version. */
export function parsePairingUri(text: string): PairingUri {
  if (!text.startsWith(`${SCHEME}?`)) throw new Error('not a watch-audio-mcp pairing URI');
  const params = new Map<string, string>();
  for (const part of text.slice(SCHEME.length + 1).split('&')) {
    if (!part) continue;
    const eq = part.indexOf('=');
    params.set(eq < 0 ? part : part.slice(0, eq), eq < 0 ? '' : part.slice(eq + 1));
  }
  const get = (key: string) => {
    const value = params.get(key);
    if (value === undefined) throw new Error(`pairing URI lacks '${key}'`);
    return value;
  };
  if (get('v') !== String(PROTOCOL_VERSION)) throw new Error(`unsupported pairing URI version ${get('v')}`);
  const fingerprint = get('fp');
  if (!isBase64Url(fingerprint, 32)) throw new Error('bad fingerprint');
  const token = get('t');
  if (!isBase64Url(token, TOKEN_BYTES)) throw new Error('bad pairing value');
  const expiresAt = Number(get('e'));
  if (!Number.isSafeInteger(expiresAt)) throw new Error('bad expiry');
  return {
    version: PROTOCOL_VERSION,
    name: decodeURIComponent(get('n')),
    fingerprint,
    addresses: get('a').split(',').filter(Boolean).map((a) => decodeURIComponent(a)),
    token,
    expiresAt,
  };
}

function isBase64Url(text: string, bytes: number): boolean {
  return /^[A-Za-z0-9_-]+$/.test(text) && Buffer.from(text, 'base64url').length === bytes;
}

/**
 * The 6-digit code both screens show: the first 4 bytes (big-endian) of
 * SHA-256("watch-audio-mcp pairing v1" ‖ 0x00 ‖ token ‖ bridge fingerprint ‖ phone fingerprint)
 * modulo 1 000 000, zero-padded (shared/agent Pairing.kt).
 */
export function confirmationCode(token: string, bridgeFingerprint: string, phoneFingerprint: string): string {
  const digest = createHash('sha256')
    .update(Buffer.from('watch-audio-mcp pairing v1', 'utf8'))
    .update(Buffer.from([0]))
    .update(Buffer.from(token, 'base64url'))
    .update(Buffer.from(bridgeFingerprint, 'base64url'))
    .update(Buffer.from(phoneFingerprint, 'base64url'))
    .digest();
  return String(digest.readUInt32BE(0) % 1_000_000).padStart(6, '0');
}

export function newToken(): string {
  return randomBytes(TOKEN_BYTES).toString('base64url');
}

/** `host:port` with IPv6 hosts in brackets. */
export function hostPort(host: string, port: number): string {
  return host.includes(':') ? `[${host}]:${port}` : `${host}:${port}`;
}

export interface PairingSession {
  uri: PairingUri;
  /** Resolves with the paired phone's name, or rejects when pairing failed or expired. */
  done: Promise<string>;
  close(): void;
}

export interface PairingOptions {
  store: ConfigStore;
  /** Addresses (hosts) put in the QR code. */
  hosts: string[];
  /** Interface to listen on (all by default). */
  listenHost?: string;
  /** Validity of the one-time value (at most 5 minutes; shorter for tests). */
  validityMs?: number;
  /**
   * Shows the code and asks the user to compare it with the phone's; true = same. [cancelled]
   * fires when the phone gave up; the question should then resolve false.
   */
  confirm: (code: string, phoneName: string, cancelled: AbortSignal) => Promise<boolean>;
  log: (line: string) => void;
  now?: () => number;
}

/**
 * Listens for one pairing attempt. The TLS handshake accepts any client certificate (the phone
 * is not known yet); the first message must carry the one-time value, which is single-use.
 */
export async function startPairing(options: PairingOptions): Promise<PairingSession> {
  const now = options.now ?? Date.now;
  const identity = options.store.identity();
  const validity = Math.min(options.validityMs ?? PAIRING_VALIDITY_MS, PAIRING_VALIDITY_MS);
  const token = newToken();
  const expiresAt = now() + validity;
  let used = false;
  let settle: { resolve: (name: string) => void; reject: (e: Error) => void } | undefined;
  const done = new Promise<string>((resolve, reject) => (settle = { resolve, reject }));
  done.catch(() => {}); // the caller may await it later

  const server = createServer({
    key: identity.keyPem,
    cert: identity.certificatePem,
    minVersion: 'TLSv1.3',
    requestCert: true,
    rejectUnauthorized: false, // the phone's key is what we are about to learn; checked by the code
  });
  const sockets = new Set<TLSSocket>();
  server.on('secureConnection', (socket: TLSSocket) => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
    void handle(socket).catch((e: Error) => {
      options.log(`ペアリングの接続でエラー: ${e.message}`);
      socket.destroy();
    });
  });
  server.on('tlsClientError', () => {
    // A peer that does not finish the handshake (e.g. it pinned another key): nothing to do.
  });

  async function handle(socket: TLSSocket): Promise<void> {
    const peer = socket.getPeerCertificate(true);
    if (!peer || !peer.raw) {
      socket.destroy();
      return;
    }
    const phoneFingerprint = certificateFingerprint(peer.raw);
    const reader = messageReader(socket);
    const send = (m: AgentMessage) => socket.write(encodeFrame(m));
    const first = await reader.next(10_000);
    if (first?.type !== 'pair_request') {
      send({ type: 'error', code: ErrorCode.BAD_MESSAGE, message: 'expected pair_request' });
      socket.end();
      return;
    }
    if (first.protocol !== PROTOCOL_VERSION) {
      send({ type: 'error', code: ErrorCode.UNSUPPORTED_PROTOCOL, message: `protocol ${PROTOCOL_VERSION} only` });
      socket.end();
      return;
    }
    const valid = !used && first.token.length === token.length &&
      timingSafeEqual(Buffer.from(first.token), Buffer.from(token));
    if (!valid) {
      send({ type: 'error', code: ErrorCode.PAIRING_INVALID, message: 'unknown or used pairing value' });
      socket.end();
      return;
    }
    used = true; // one attempt per value, whatever happens next
    if (now() > expiresAt) {
      send({ type: 'error', code: ErrorCode.PAIRING_EXPIRED, message: 'the pairing value expired; run `watch-audio-mcp pair` again' });
      socket.end();
      settle?.reject(new Error('ペアリング用の値の有効期限が切れていました。もう一度 pair を実行してください。'));
      return;
    }
    send({ type: 'pair_pending' });
    const phoneName = first.name.slice(0, 64) || 'phone';
    const code = confirmationCode(token, identity.fingerprint, phoneFingerprint);
    // The users confirm in any order; a refusal on the phone cancels the question on the PC.
    const phoneDeclined = new AbortController();
    const remoteReply = reader.next(Math.max(1_000, expiresAt - now() + 60_000)).then((m) => {
      if (m?.type !== 'pair_confirm') phoneDeclined.abort();
      return m;
    });
    const local = await options.confirm(code, phoneName, phoneDeclined.signal);
    const remote = local ? await remoteReply : null;
    if (!local || remote?.type !== 'pair_confirm') {
      if (local) send({ type: 'error', code: ErrorCode.PAIRING_REJECTED, message: 'not confirmed on the phone' });
      else send({ type: 'error', code: ErrorCode.PAIRING_REJECTED, message: 'declined on the PC' });
      socket.end();
      settle?.reject(new Error(local ? 'スマホ側で確定されませんでした。' : 'PC 側で確認コードが一致しないと答えました。'));
      return;
    }
    const config: BridgeConfig = options.store.load();
    config.phones = config.phones.filter((p) => p.fingerprint !== phoneFingerprint);
    config.phones.push({
      name: phoneName,
      fingerprint: phoneFingerprint,
      certificatePem: toPem(Buffer.from(new X509Certificate(peer.raw).raw), 'CERTIFICATE'),
      pairedAt: now(),
    });
    options.store.save(config);
    send({ type: 'pair_result', name: config.name, port: config.port });
    socket.end();
    settle?.resolve(phoneName);
  }

  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, options.listenHost, () => resolve());
  });
  const port = (server.address() as { port: number }).port;
  // Keep listening a little past the expiry, so a late phone is told why instead of finding no one.
  const timer = setTimeout(() => {
    if (!used) settle?.reject(new Error('5 分以内にスマホから読み取られませんでした。もう一度 pair を実行してください。'));
  }, validity + 30_000);
  timer.unref();
  const config = options.store.load();
  const uri: PairingUri = {
    version: PROTOCOL_VERSION,
    name: config.name,
    fingerprint: identity.fingerprint,
    addresses: options.hosts.map((h) => hostPort(h, port)),
    token,
    expiresAt,
  };
  return {
    uri,
    done,
    close() {
      clearTimeout(timer);
      for (const s of sockets) s.destroy();
      server.close();
    },
  };
}

/** Reads messages from a socket one at a time. */
export function messageReader(socket: TLSSocket) {
  const decoder = new FrameDecoder();
  const queue: AgentMessage[] = [];
  const waiters: Array<(m: AgentMessage | null) => void> = [];
  let closed = false;
  socket.on('data', (chunk: Buffer) => {
    try {
      for (const message of decoder.feed(chunk)) {
        const waiter = waiters.shift();
        if (waiter) waiter(message);
        else queue.push(message);
      }
    } catch {
      socket.destroy();
    }
  });
  const end = () => {
    closed = true;
    while (waiters.length) waiters.shift()!(null);
  };
  socket.on('close', end);
  socket.on('error', end);
  return {
    /** The next message, or null when the socket closed or [timeoutMs] passed. */
    next(timeoutMs: number): Promise<AgentMessage | null> {
      const queued = queue.shift();
      if (queued) return Promise.resolve(queued);
      if (closed) return Promise.resolve(null);
      return new Promise((resolve) => {
        const timer = setTimeout(() => {
          const i = waiters.indexOf(done);
          if (i >= 0) waiters.splice(i, 1);
          resolve(null);
        }, timeoutMs);
        const done = (m: AgentMessage | null) => {
          clearTimeout(timer);
          resolve(m);
        };
        waiters.push(done);
      });
    },
  };
}
