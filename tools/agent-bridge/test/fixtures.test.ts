// The wire format, pairing values, tool list and certificates against shared/agent/testdata, which
// the Kotlin tests read too (reference values computed independently, in Python).

import assert from 'node:assert/strict';
import { generateKeyPairSync, X509Certificate } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { certificateFingerprint, createIdentity, fromPem, keyFingerprint, selfSignedCertificate } from '../src/certificate.js';
import { advertisedKeyHash, advertisement, asksFor, records, SERVICE_TYPE } from '../src/mdns.js';
import { buildPairingUri, confirmationCode, parsePairingUri } from '../src/pairing.js';
import { TOOLS } from '../src/tools.js';
import { encodeFrame, FrameDecoder, FrameError, parseMessage, type AgentMessage } from '../src/wire.js';

// dist/test/ -> tools/agent-bridge -> repository root
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
const fixture = (name: string) => JSON.parse(readFileSync(join(root, 'shared', 'agent', 'testdata', name), 'utf8'));

test('every message of messages.json parses and encodes back to the same JSON', () => {
  const messages = fixture('messages.json') as Record<string, unknown>;
  for (const [name, json] of Object.entries(messages)) {
    const parsed = parseMessage(json);
    assert.deepEqual(JSON.parse(JSON.stringify(parsed)), json, name);
  }
  assert.throws(() => parseMessage({ type: 'hello', protocol: 1 }), FrameError);
  assert.throws(() => parseMessage({ type: 'nope' }), FrameError);
});

test('frames match the vector and survive any split', () => {
  const frames = fixture('frames.json');
  const vector = Buffer.from(frames.bye_shutdown.hex, 'hex');
  assert.deepEqual(encodeFrame({ type: 'bye', reason: 'shutdown' }), vector);
  const decoder = new FrameDecoder();
  const out: AgentMessage[] = [];
  for (const byte of vector) out.push(...decoder.feed(Buffer.from([byte])));
  assert.deepEqual(out, [{ type: 'bye', reason: 'shutdown' }]);
  assert.throws(() => new FrameDecoder().feed(Buffer.from(frames.oversize_header_hex, 'hex')), FrameError);
});

test('pairing URI, confirmation code and mDNS hash', () => {
  const pairing = fixture('pairing.json');
  assert.deepEqual(parsePairingUri(pairing.uri), pairing.parsed);
  assert.equal(buildPairingUri(pairing.parsed), pairing.uri);
  for (const bad of pairing.invalid as string[]) assert.throws(() => parsePairingUri(bad), Error, bad);
  const c = pairing.confirmation;
  assert.equal(confirmationCode(c.token, c.bridgeFingerprint, c.phoneFingerprint), c.code);
  assert.equal(advertisedKeyHash(pairing.mdns.nonce, pairing.mdns.fingerprint), pairing.mdns.h);
});

test('the tool list equals tools.json', () => {
  assert.deepEqual(JSON.parse(JSON.stringify(TOOLS)), fixture('tools.json').tools);
  for (const tool of TOOLS) {
    assert.equal(tool.annotations.readOnlyHint, true, tool.name);
  }
});

test('self-signed certificate parses, verifies and carries the key', () => {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const der = selfSignedCertificate(privateKey, publicKey, 'watch-audio-mcp');
  const cert = new X509Certificate(der);
  assert.equal(cert.verify(publicKey), true);
  assert.equal(cert.subject, 'CN=watch-audio-mcp');
  assert.equal(cert.validTo.includes('9999'), true);
  assert.equal(certificateFingerprint(der), keyFingerprint(publicKey.export({ type: 'spki', format: 'der' })));

  const identity = createIdentity('x');
  assert.equal(certificateFingerprint(fromPem(identity.certificatePem)), identity.fingerprint);
});

test('mDNS records answer a browse and carry no stable identifier', () => {
  const identity = createIdentity('x');
  const a = advertisement(identity.fingerprint, 47823, ['192.168.1.20']);
  const b = advertisement(identity.fingerprint, 47823, ['192.168.1.20']);
  assert.notEqual(a.txt.join(), b.txt.join()); // new nonce and instance name per run
  assert.equal(asksFor({ questions: [{ name: SERVICE_TYPE, type: 'PTR' }] }, a), true);
  assert.equal(asksFor({ questions: [{ name: '_other._tcp.local', type: 'PTR' }] }, a), false);
  const { answers, additionals } = records(a);
  assert.equal(answers[0]!.data, a.instance);
  const srv = additionals.find((r) => r.type === 'SRV')!;
  assert.deepEqual((srv.data as { port: number }).port, 47823);
  const txt = (additionals.find((r) => r.type === 'TXT')!.data as Buffer[]).map((t) => t.toString());
  const nonce = txt.find((t) => t.startsWith('n='))!.slice(2);
  assert.ok(txt.includes(`h=${advertisedKeyHash(nonce, identity.fingerprint)}`));
  assert.ok(!txt.some((t) => t.includes(identity.fingerprint)));
});
