// The PC's side of the Android emulator check of agent access (#184, .github/scripts/agent-access.sh):
// the real bridge CLI on the CI host, which the emulator reaches at 10.0.2.2. Not a test file
// (`npm run test:e2e` does not run it); the script runs it in two phases:
//
//   node dist/e2e/emulator-host.js pair <home>
//       runs `watch-audio-mcp pair --address 10.0.2.2`, prints `URI <pairing uri>` for the phone,
//       answers y when the code appears and prints `PC_CODE <code>`; exits with pair's status.
//   node dist/e2e/emulator-host.js serve <home>
//       starts the CLI as an MCP server (as an agent would), calls search_transcripts every second
//       and prints one line per change: `PHASE connected <text>` once the phone answers,
//       `PHASE disconnected` once it no longer does, four times (on, off, on again, revoked);
//       exits 0 after the last one, 1 on a timeout.
//
// Both run under the no-egress guard: the CLI must not open any outbound connection.

import { spawn } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/client';
import { StdioClientTransport } from '@modelcontextprotocol/client/stdio';

const here = dirname(fileURLToPath(import.meta.url));
const cli = join(here, '..', 'src', 'cli.js');
const guard = join(here, 'no-egress.js');
const [phase, home] = process.argv.slice(2);
if (!home || (phase !== 'pair' && phase !== 'serve')) {
  process.stderr.write('usage: emulator-host.js pair|serve <WATCH_AUDIO_MCP_HOME>\n');
  process.exit(2);
}
const env = { ...(process.env as Record<string, string>), WATCH_AUDIO_MCP_HOME: home };
const out = (line: string) => process.stdout.write(`${line}\n`);

async function pair(): Promise<number> {
  // The emulator reaches the host's loopback at 10.0.2.2; the bridge listens on all interfaces.
  const address = process.env.AGENT_HOST_ADDRESS ?? '10.0.2.2';
  const child = spawn(process.execPath, ['--import', guard, cli, 'pair', '--address', address, '--port', process.env.AGENT_HOST_PORT ?? '47823'], {
    env,
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  let partial = '';
  let uriShown = false;
  child.stderr.on('data', (data: Buffer) => {
    process.stderr.write(data);
    partial += data.toString();
    const lines = partial.split('\n');
    partial = lines.pop()!;
    for (const line of lines) {
      const uri = line.match(/(watchaudio-mcp:\/\/pair\?\S+)/);
      if (uri && !uriShown) {
        uriShown = true;
        out(`URI ${uri[1]}`);
      }
      const code = line.match(/確認コード: (\d{6})/);
      if (code) {
        out(`PC_CODE ${code[1]}`);
        child.stdin.write('y\n');
      }
    }
  });
  return new Promise((resolve) => child.on('close', (code) => resolve(code ?? 1)));
}

async function serve(): Promise<number> {
  let stderr = '';
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: ['--import', guard, cli, '--no-mdns'],
    env,
    stderr: 'pipe',
  });
  transport.stderr?.on('data', (d: Buffer) => {
    stderr += d.toString();
    process.stderr.write(d);
  });
  const client = new Client({ name: 'emulator-check', version: '0' });
  await client.connect(transport);
  // AGENT_HOST_PHASES shortens the sequence for a local run against the JVM fake phone.
  const expected = ['connected', 'disconnected', 'connected', 'disconnected'].slice(0, Number(process.env.AGENT_HOST_PHASES ?? 4));
  const deadline = Date.now() + 15 * 60_000;
  let step = 0;
  while (step < expected.length && Date.now() < deadline) {
    const result = await client.callTool({ name: 'search_transcripts', arguments: { query: 'エージェント' } });
    const text = ((result.content as Array<{ type: string; text?: string }>) ?? [])
      .filter((c) => c.type === 'text').map((c) => c.text).join(' ').replace(/\s+/g, ' ');
    const state = result.isError ? 'disconnected' : 'connected';
    if (state === expected[step]) {
      out(`PHASE ${state} ${text.slice(0, 300)}`);
      if (state === 'connected') {
        const audio = await client.callTool({ name: 'get_audio_clip', arguments: { utterance_id: Number(text.match(/utterance (\d+) \|/)?.[1] ?? 0) } });
        out(`AUDIO_CLIP isError=${audio.isError === true}`);
      }
      step++;
    }
    await new Promise((r) => setTimeout(r, 1_000));
  }
  await client.close();
  out(`EGRESS ${/EGRESS_VIOLATION/.test(stderr) ? 'VIOLATION' : 'none'}`);
  return step === expected.length && !/EGRESS_VIOLATION/.test(stderr) ? 0 : 1;
}

(phase === 'pair' ? pair() : serve()).then(
  (code) => process.exit(code),
  (e: Error) => {
    process.stderr.write(`${e.stack ?? e.message}\n`);
    process.exit(1);
  },
);
