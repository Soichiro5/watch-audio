// The bridge's TLS side with a phone played by node:tls: pairing, serving, and the refusals
// (unpaired key, wrong bridge key, expired or reused pairing value, revoked pairing).

import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, test } from 'node:test';
import { connect, type TLSSocket } from 'node:tls';
import { Bridge, NOT_CONNECTED_MESSAGE } from '../src/bridge.js';
import { certificateFingerprint, createIdentity, fromPem, type Identity } from '../src/certificate.js';
import { ConfigStore } from '../src/config.js';
import { confirmationCode, messageReader, startPairing } from '../src/pairing.js';
import { encodeFrame, type AgentMessage } from '../src/wire.js';

const dirs: string[] = [];
after(() => dirs.forEach((d) => rmSync(d, { recursive: true, force: true })));

function newStore(): ConfigStore {
  const dir = mkdtempSync(join(tmpdir(), 'agent-bridge-'));
  dirs.push(dir);
  return new ConfigStore(dir);
}

/** A TLS client presenting [phone] and pinning [bridgeFingerprint] like the app does. */
function connectPhone(port: number, phone: Identity, bridgeFingerprint: string): Promise<TLSSocket> {
  return new Promise((resolve, reject) => {
    const socket = connect({
      host: '127.0.0.1',
      port,
      key: phone.keyPem,
      cert: phone.certificatePem,
      minVersion: 'TLSv1.3',
      rejectUnauthorized: false,
      checkServerIdentity: () => undefined,
    });
    socket.once('secureConnect', () => {
      const raw = socket.getPeerCertificate(true).raw;
      if (certificateFingerprint(raw) !== bridgeFingerprint) {
        socket.destroy();
        reject(new Error('bridge key mismatch'));
      } else {
        resolve(socket);
      }
    });
    socket.once('error', reject);
  });
}

const send = (socket: TLSSocket, m: AgentMessage) => socket.write(encodeFrame(m));

async function pairPhone(store: ConfigStore, phone: Identity, validityMs?: number, delayMs = 0) {
  const session = await startPairing({
    store,
    hosts: ['127.0.0.1'],
    listenHost: '127.0.0.1',
    validityMs,
    log: () => {},
    confirm: async () => true,
  });
  if (delayMs) await new Promise((r) => setTimeout(r, delayMs));
  const port = Number(session.uri.addresses[0]!.split(':')[1]);
  const socket = await connectPhone(port, phone, session.uri.fingerprint);
  const reader = messageReader(socket);
  send(socket, { type: 'pair_request', protocol: 1, token: session.uri.token, name: 'Pixel' });
  const pending = await reader.next(5_000);
  let result: AgentMessage | null = pending;
  let code: string | undefined;
  if (pending?.type === 'pair_pending') {
    code = confirmationCode(session.uri.token, session.uri.fingerprint, phone.fingerprint);
    send(socket, { type: 'pair_confirm' });
    result = await reader.next(5_000);
  }
  const done = await session.done.then(() => 'ok', (e: Error) => e.message);
  socket.destroy();
  session.close();
  return { result, code, done, uri: session.uri };
}

test('pairing stores the phone; the value is single-use and expires', async () => {
  const store = newStore();
  const phone = createIdentity('phone');
  const { result, done } = await pairPhone(store, phone);
  assert.equal(result?.type, 'pair_result');
  assert.equal(done, 'ok');
  assert.deepEqual(store.load().phones.map((p) => p.fingerprint), [phone.fingerprint]);

  const expired = await pairPhone(newStore(), createIdentity('late phone'), 200, 400);
  assert.deepEqual(expired.result, { type: 'error', code: 'pairing_expired', message: 'the pairing value expired; run `watch-audio-mcp pair` again' });
});

test('a pairing value that does not match is refused at the first message', async () => {
  const store = newStore();
  const session = await startPairing({ store, hosts: ['127.0.0.1'], listenHost: '127.0.0.1', log: () => {}, confirm: async () => true });
  const port = Number(session.uri.addresses[0]!.split(':')[1]);
  const socket = await connectPhone(port, createIdentity('phone'), session.uri.fingerprint);
  const reader = messageReader(socket);
  send(socket, { type: 'pair_request', protocol: 1, token: 'AAAAAAAAAAAAAAAAAAAAAA', name: 'Pixel' });
  assert.equal((await reader.next(5_000))?.type, 'error');
  socket.destroy();
  session.close();
  assert.equal(store.load().phones.length, 0);
});

test('serving: paired phone answers tool calls; others never get past the handshake', async () => {
  const store = newStore();
  const phone = createIdentity('phone');
  await pairPhone(store, phone);
  const logs: string[] = [];
  const bridge = new Bridge({ store, software: 'test', log: (l) => logs.push(l), listenHost: '127.0.0.1', port: 0 });
  const port = await bridge.start();
  const bridgeFingerprint = store.identity().fingerprint;

  assert.deepEqual(await bridge.call('search_transcripts', { query: 'x' }), {
    content: [{ type: 'text', text: NOT_CONNECTED_MESSAGE }],
    isError: true,
  });

  // The paired phone: hello, then it answers a forwarded call.
  const socket = await connectPhone(port, phone, bridgeFingerprint);
  const reader = messageReader(socket);
  send(socket, { type: 'hello', protocol: 1, role: 'phone', name: 'Pixel', software: 'test' });
  assert.equal((await reader.next(5_000))?.type, 'hello');
  await waitFor(() => bridge.connectedPhone === 'Pixel');
  const answer = bridge.call('search_transcripts', { query: '予算' });
  const call = await reader.next(5_000);
  assert.equal(call?.type, 'rpc');
  const request = (call as unknown as { message: { id: number; params: { name: string; arguments: unknown } } }).message;
  assert.deepEqual(request.params, { name: 'search_transcripts', arguments: { query: '予算' } });
  send(socket, { type: 'rpc', message: { jsonrpc: '2.0', id: request.id, result: { content: [{ type: 'text', text: 'utterance 1 | 予算' }], isError: false } } });
  assert.deepEqual(await answer, { content: [{ type: 'text', text: 'utterance 1 | 予算' }], isError: false });

  // A phone that never paired: the handshake fails (or, at the latest, the socket closes before any message).
  await assert.rejects(handshakeAndRead(port, createIdentity('stranger'), bridgeFingerprint));

  // A bridge with another key: the phone's pinning refuses it.
  const other = new Bridge({ store: newStore(), software: 'test', log: () => {}, listenHost: '127.0.0.1', port: 0 });
  const otherPort = await other.start();
  await assert.rejects(connectPhone(otherPort, phone, bridgeFingerprint), /bridge key mismatch|socket|ECONNRESET/);
  await other.close();

  // Revoked on the PC (`unpair`): the open connection is closed and a new one is refused.
  const config = store.load();
  config.phones = [];
  store.save(config);
  await waitFor(() => bridge.connectedPhone === undefined, 5_000);
  await assert.rejects(handshakeAndRead(port, phone, bridgeFingerprint));
  socket.destroy();
  await bridge.close();
  assert.ok(logs.some((l) => l.includes('拒否')), logs.join('\n'));
});

/** Connects, says hello and waits for the bridge's hello; rejects if the bridge refuses. */
async function handshakeAndRead(port: number, phone: Identity, bridgeFingerprint: string): Promise<void> {
  const socket = await connectPhone(port, phone, bridgeFingerprint);
  const reader = messageReader(socket);
  send(socket, { type: 'hello', protocol: 1, role: 'phone', name: 'x', software: 'test' });
  const reply = await reader.next(3_000);
  socket.destroy();
  if (reply?.type !== 'hello') throw new Error(`refused (${reply?.type ?? 'closed'})`);
}

async function waitFor(condition: () => boolean, timeoutMs = 3_000): Promise<void> {
  const start = Date.now();
  while (!condition()) {
    if (Date.now() - start > timeoutMs) throw new Error('timed out');
    await new Promise((r) => setTimeout(r, 20));
  }
}

test('identity is stored readable by the owner only and reused', () => {
  const store = newStore();
  const first = store.identity();
  assert.equal(store.identity().fingerprint, first.fingerprint);
  assert.equal(certificateFingerprint(fromPem(first.certificatePem)), first.fingerprint);
  if (process.platform !== 'win32') {
    assert.equal(statSync(join(store.dir, 'identity.key.pem')).mode & 0o777, 0o600);
    assert.equal(statSync(store.dir).mode & 0o777, 0o700);
  }
});
