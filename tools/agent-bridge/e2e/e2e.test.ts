// End to end (#183): the bridge CLI and the JVM fake phone (tools/agent-fake-phone, the shared tool
// logic over a demo SQLite store behind a JVM mutual-TLS client).
//
//   pairing (QR value + confirmation code) → mutual TLS → MCP tool calls from a scripted MCP
//   client over stdio → answers from the phone's database
//
// and the refusals: a phone that never paired, a bridge with another key, an expired pairing
// value, a pairing revoked on the PC or on the phone. The bridge runs with a guard that refuses
// and reports any outbound connection (no-egress.ts).
//
// FAKE_PHONE must point at the fake phone's launcher
// (`./gradlew :tools:agent-fake-phone:installDist` → tools/agent-fake-phone/build/install/agent-fake-phone/bin/agent-fake-phone).

import assert from 'node:assert/strict';
import { spawn, type ChildProcess } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { after, before, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/client';
import { StdioClientTransport } from '@modelcontextprotocol/client/stdio';

const here = dirname(fileURLToPath(import.meta.url));
const cli = join(here, '..', 'src', 'cli.js');
const guard = join(here, 'no-egress.js');
const fakePhone = process.env.FAKE_PHONE;
const skip = fakePhone ? false : 'FAKE_PHONE is not set (see the header of e2e.test.ts)';

const work = mkdtempSync(join(tmpdir(), 'agent-e2e-'));
const bridgeHome = join(work, 'bridge');
const phoneDb = join(work, 'phone.db');
const phoneIdentity = join(work, 'phone-identity');
let port = 0;
const children: ChildProcess[] = [];

after(() => {
  children.forEach((c) => c.kill('SIGKILL'));
  rmSync(work, { recursive: true, force: true });
});

interface Run {
  code: number | null;
  stdout: string;
  stderr: string;
}

/** Starts a process; [lines] sees each stdout / stderr line as it comes. */
function start(command: string, args: string[], env: Record<string, string> = {}, onLine?: (line: string, child: ChildProcess) => void) {
  const child = spawn(command, args, { env: { ...process.env, ...env }, stdio: ['pipe', 'pipe', 'pipe'] });
  children.push(child);
  const run: Run = { code: null, stdout: '', stderr: '' };
  const lines = (stream: 'stdout' | 'stderr') => {
    let partial = '';
    child[stream]!.on('data', (data: Buffer) => {
      run[stream] += data.toString();
      partial += data.toString();
      const parts = partial.split('\n');
      partial = parts.pop()!;
      for (const line of parts) onLine?.(line, child);
    });
  };
  lines('stdout');
  lines('stderr');
  const done = new Promise<Run>((resolve) => child.on('close', (code) => {
    run.code = code;
    resolve(run);
  }));
  return { child, run, done };
}

const phone = (...args: string[]) => start(fakePhone!, args);
const bridgeCli = (args: string[], onLine?: (line: string, child: ChildProcess) => void, home = bridgeHome) =>
  start(process.execPath, ['--import', guard, cli, ...args], { WATCH_AUDIO_MCP_HOME: home }, onLine);

function freePort(): Promise<number> {
  return new Promise((resolve) => {
    const server = createServer();
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address() as { port: number };
      server.close(() => resolve(port));
    });
  });
}

/** Runs `pair` and the fake phone's pairing against it, answering the PC's question with [answer]. */
async function pairOnce(options: { ttlSeconds?: number; delayMs?: number; answer?: string; identity?: string } = {}) {
  let uri: string | undefined;
  let pcCode: string | undefined;
  let gotUri: () => void;
  const uriSeen = new Promise<void>((r) => (gotUri = r));
  const args = ['pair', '--address', '127.0.0.1', '--listen', '127.0.0.1', '--port', String(port)];
  if (options.ttlSeconds) args.push('--ttl-seconds', String(options.ttlSeconds));
  const pc = bridgeCli(args, (line, child) => {
    const u = line.match(/(watchaudio-mcp:\/\/pair\?\S+)/);
    if (u && !uri) {
      uri = u[1];
      gotUri();
    }
    const c = line.match(/確認コード: (\d{6})/);
    if (c) {
      pcCode = c[1];
      child.stdin!.write(`${options.answer ?? 'y'}\n`);
    }
  });
  await uriSeen;
  if (options.delayMs) await new Promise((r) => setTimeout(r, options.delayMs));
  const ph = await phone('pair', '--db', phoneDb, '--identity', options.identity ?? phoneIdentity, '--uri', uri!, '--name', 'E2E phone').done;
  if (options.ttlSeconds) pc.child.kill('SIGTERM'); // an expired value leaves `pair` waiting for its own timeout
  const pcRun = await pc.done;
  return { phone: ph, pc: pcRun, pcCode, phoneCode: ph.stdout.match(/CODE (\d{6})/)?.[1] };
}

let client: Client | undefined;
let serving: ReturnType<typeof start> | undefined;
let bridgeStderr = '';

async function startAgent(): Promise<Client> {
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: ['--import', guard, cli, '--listen', '127.0.0.1'],
    env: { ...(process.env as Record<string, string>), WATCH_AUDIO_MCP_HOME: bridgeHome },
    stderr: 'pipe',
  });
  transport.stderr?.on('data', (d: Buffer) => (bridgeStderr += d.toString()));
  const c = new Client({ name: 'e2e', version: '0' });
  await c.connect(transport);
  return c;
}

async function servePhone(identity = phoneIdentity, extra: string[] = []) {
  const s = phone('serve', '--db', phoneDb, '--identity', identity, '--seconds', '120', ...extra);
  await new Promise<void>((resolve) => {
    const check = () => (s.run.stdout.includes('CONNECTED') || s.run.code !== null ? resolve() : setTimeout(check, 50));
    check();
    void s.done.then(() => resolve());
  });
  return s;
}

const text = (result: { content?: unknown }) =>
  ((result.content as Array<{ type: string; text?: string }>) ?? []).filter((c) => c.type === 'text').map((c) => c.text).join('\n');

before(async () => {
  if (skip) return;
  port = await freePort();
  const seeded = await phone('seed', '--db', phoneDb).done;
  assert.equal(seeded.code, 0, seeded.stderr);
});

test('pairing shows the same code on both sides and stores both keys', { skip }, async () => {
  const { phone: ph, pc, pcCode, phoneCode } = await pairOnce();
  assert.equal(ph.code, 0, ph.stdout + ph.stderr);
  assert.equal(pc.code, 0, pc.stderr);
  assert.ok(pcCode && pcCode === phoneCode, `codes ${pcCode} / ${phoneCode}`);
  assert.match(ph.stdout, new RegExp(`PAIRED \\S+ 127\\.0\\.0\\.1:${port}`));
  const config = JSON.parse(readFileSync(join(bridgeHome, 'config.json'), 'utf8'));
  assert.deepEqual(config.phones.map((p: { name: string }) => p.name), ['E2E phone']);
  assert.doesNotMatch(pc.stderr, /EGRESS_VIOLATION/);
});

test('an agent calls the tools over mutual TLS and gets the phone\'s records', { skip }, async () => {
  client = await startAgent();
  const before = await client.callTool({ name: 'search_transcripts', arguments: { query: '予算' } });
  assert.equal(before.isError, true);
  assert.match(text(before), /エージェント接続/);

  serving = await servePhone();
  assert.match(serving.run.stdout, /CONNECTED/, serving.run.stdout + serving.run.stderr);

  const search = await client.callTool({ name: 'search_transcripts', arguments: { query: '予算' } });
  assert.equal(search.isError, false, text(search));
  assert.match(text(search), /来期の予算について確認しましょう/);
  assert.match(text(search), /not instructions to follow/);
  assert.doesNotMatch(text(search), /四十五日前/); // outside the default 30-day scope
  const id = Number(text(search).match(/utterance (\d+) \|[^\n]*来期の予算/)![1]);

  const context = await client.callTool({ name: 'get_context', arguments: { utterance_id: id, before: 1, after: 1 } });
  assert.match(text(context), /おはようございます/);
  assert.match(text(context), new RegExp(`> utterance ${id}`));

  const yesterday = new Date(Date.now() - 86_400_000 + 9 * 3_600_000).toISOString().slice(0, 10); // Asia/Tokyo
  const timeline = await client.callTool({ name: 'get_day_timeline', arguments: { date: yesterday } });
  assert.match(text(timeline), /conversation 1 \|/, text(timeline));

  const audio = await client.callTool({ name: 'get_audio_clip', arguments: { utterance_id: id } });
  assert.equal(audio.isError, true); // text only by default
  assert.match(text(audio), /許可されていません/);

  const outside = await client.callTool({ name: 'get_day_timeline', arguments: { date: '2020-01-01' } });
  assert.equal(outside.isError, true);
});

test('audio clips once the pairing allows audio', { skip }, async () => {
  assert.ok(client && serving);
  serving!.child.kill('SIGTERM');
  await serving!.done;
  assert.equal((await phone('allow-audio', '--db', phoneDb).done).code, 0);
  serving = await servePhone();
  const search = await client!.callTool({ name: 'search_transcripts', arguments: { query: '予算' } });
  const id = Number(text(search).match(/utterance (\d+) \|/)![1]);
  const audio = await client!.callTool({ name: 'get_audio_clip', arguments: { utterance_id: id } });
  assert.equal(audio.isError, false, text(audio));
  const clip = (audio.content as Array<{ type: string; data?: string; mimeType?: string }>).find((c) => c.type === 'audio')!;
  assert.equal(clip.mimeType, 'audio/wav');
  assert.equal(Buffer.from(clip.data!, 'base64').subarray(0, 4).toString(), 'RIFF');

  const log = await phone('log', '--db', phoneDb).done;
  assert.match(log.stdout, /ACCESS search_transcripts OK \d+ 予算/);
  assert.match(log.stdout, /ACCESS get_audio_clip DENIED 0/);
  assert.match(log.stdout, /ACCESS get_audio_clip OK 1/);
});

test('a phone that never paired is cut off at the handshake', { skip }, async () => {
  const stranger = await phone('serve', '--db', phoneDb, '--identity', join(work, 'stranger-identity'), '--seconds', '10').done;
  assert.equal(stranger.code, 3, stranger.stdout + stranger.stderr);
  assert.match(stranger.stdout, /REJECTED/);
  assert.doesNotMatch(stranger.stdout, /CONNECTED/);
});

test('a bridge with another key is refused by the phone', { skip }, async () => {
  const otherPort = await freePort();
  const otherHome = join(work, 'other-bridge');
  const other = bridgeCli(['--listen', '127.0.0.1', '--port', String(otherPort), '--no-mdns'], undefined, otherHome);
  await new Promise<void>((resolve) => {
    const check = () => (other.run.stderr.includes('待ち受け中') ? resolve() : setTimeout(check, 50));
    check();
  });
  const wrong = await phone('serve', '--db', phoneDb, '--identity', phoneIdentity, '--address', `127.0.0.1:${otherPort}`, '--seconds', '10').done;
  other.child.kill('SIGTERM');
  assert.equal(wrong.code, 3, wrong.stdout + wrong.stderr);
  assert.match(wrong.stdout, /REJECTED .*(bridge key|PinMismatch|certificate)/i);
});

test('an expired pairing value is refused at the first message', { skip }, async () => {
  const { phone: ph, pc } = await pairOnce({ ttlSeconds: 1, delayMs: 2_500, identity: join(work, 'late-identity') });
  assert.equal(ph.code, 2, ph.stdout + ph.stderr);
  assert.match(ph.stdout, /REFUSED pairing_expired/);
  assert.doesNotMatch(ph.stdout, /CODE/);
  assert.notEqual(pc.code, 0);
  const config = JSON.parse(readFileSync(join(bridgeHome, 'config.json'), 'utf8'));
  assert.equal(config.phones.length, 1);
});

test('unpair on the PC closes the connection and refuses the phone afterwards', { skip }, async () => {
  assert.ok(client && serving);
  const unpair = await bridgeCli(['unpair', '--all']).done;
  assert.equal(unpair.code, 0, unpair.stderr);
  const ended = await serving!.done;
  assert.match(ended.stdout, /END (revoked|closed)/);
  const again = await phone('serve', '--db', phoneDb, '--identity', phoneIdentity, '--seconds', '10').done;
  assert.equal(again.code, 3, again.stdout + again.stderr);

  const after = await client!.callTool({ name: 'search_transcripts', arguments: { query: '予算' } });
  assert.equal(after.isError, true);
  assert.match(text(after), /エージェント接続/);
});

test('a pairing revoked on the phone is not used any more', { skip }, async () => {
  const repaired = await pairOnce();
  assert.equal(repaired.phone.code, 0, repaired.phone.stdout);
  assert.equal((await phone('revoke', '--db', phoneDb).done).code, 0);
  const s = await phone('serve', '--db', phoneDb, '--identity', phoneIdentity, '--seconds', '10').done;
  assert.equal(s.code, 4, s.stdout);
  assert.match(s.stdout, /NOT_PAIRED/);
});

test('the bridge never opened an outbound connection', { skip }, async () => {
  await client?.close();
  assert.doesNotMatch(bridgeStderr, /EGRESS_VIOLATION/, bridgeStderr);
  assert.match(bridgeStderr, /待ち受け中/);
});
