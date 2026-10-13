#!/usr/bin/env node
// watch-audio-mcp: the PC side of agent access (docs/research/agent-access.md). Started by the
// agent as a stdio MCP server; `pair` / `unpair` / `list` are run by the user in a terminal.
// It talks to nothing but the paired phone on the local network (and mDNS on the local link).

import { networkInterfaces } from 'node:os';
import { createInterface } from 'node:readline/promises';
import { renderUnicodeCompact } from 'uqr';
import { Bridge } from './bridge.js';
import { ConfigStore } from './config.js';
import { advertise, advertisement } from './mdns.js';
import { serveMcp } from './mcp.js';
import { buildPairingUri, startPairing } from './pairing.js';

export const VERSION = '0.1.0';
const SOFTWARE = `watch-audio-mcp/${VERSION}`;

const USAGE = `watch-audio-mcp ${VERSION} — スマホの watch-audio の文字起こしを、PC の AI エージェントから読むための MCP サーバー

使い方:
  watch-audio-mcp                 MCP サーバーとして起動する（エージェントの MCP 設定に書く）
  watch-audio-mcp pair            スマホとペアリングする（QR コードと確認コードを表示）
  watch-audio-mcp list            ペアリング済みのスマホを表示する
  watch-audio-mcp unpair <名前>   ペアリングを解除する（--all ですべて）

オプション:
  --port <番号>        待ち受けるポート（既定 47823。使用中なら空いているポート。pair で指定すると以後の既定）
  --listen <アドレス>  待ち受けるアドレス（既定: すべて）
  --address <アドレス> QR に入れる PC のアドレス（既定: この PC の LAN の IPv4 アドレス。複数指定可）
  --no-mdns            mDNS で知らせない（スマホは QR のアドレスでつなぐ）
  --ttl-seconds <秒>   pair: 使い捨ての値の有効期間（既定 300、最大 300）

設定と鍵: $WATCH_AUDIO_MCP_HOME、または OS ごとの設定フォルダ（list で表示）。`;

interface Args {
  command: string;
  positional: string[];
  port?: number;
  listen?: string;
  addresses: string[];
  mdns: boolean;
  ttlSeconds?: number;
  all: boolean;
}

function parseArgs(argv: string[]): Args {
  const args: Args = { command: 'serve', positional: [], addresses: [], mdns: true, all: false };
  const rest = [...argv];
  const value = (flag: string) => {
    const v = rest.shift();
    if (v === undefined) throw new Error(`${flag} には値が必要です`);
    return v;
  };
  let commandSet = false;
  while (rest.length) {
    const arg = rest.shift()!;
    switch (arg) {
      case '--port': args.port = Number(value(arg)); break;
      case '--listen': args.listen = value(arg); break;
      case '--address': args.addresses.push(value(arg)); break;
      case '--no-mdns': args.mdns = false; break;
      case '--ttl-seconds': args.ttlSeconds = Number(value(arg)); break;
      case '--all': args.all = true; break;
      case '-h': case '--help': args.command = 'help'; commandSet = true; break;
      case '-v': case '--version': args.command = 'version'; commandSet = true; break;
      default:
        if (arg.startsWith('-')) throw new Error(`不明なオプション: ${arg}`);
        if (!commandSet) {
          args.command = arg;
          commandSet = true;
        } else {
          args.positional.push(arg);
        }
    }
  }
  if (args.port !== undefined && !(Number.isInteger(args.port) && args.port >= 0 && args.port < 65536)) throw new Error('--port が不正です');
  return args;
}

const log = (line: string) => process.stderr.write(`[watch-audio-mcp] ${line}\n`);

/** Non-internal IPv4 addresses of this PC (LAN, tethering, a VPN such as Tailscale). */
function lanAddresses(): string[] {
  return Object.values(networkInterfaces())
    .flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal)
    .map((i) => i!.address);
}

async function serve(args: Args, store: ConfigStore): Promise<void> {
  const identity = store.identity();
  const bridge = new Bridge({ store, software: SOFTWARE, log, listenHost: args.listen, port: args.port });
  const port = await bridge.start();
  const phones = store.load().phones;
  log(`待ち受け中: ポート ${port}（ペアリング済みのスマホ ${phones.length} 台）`);
  if (phones.length === 0) log('まだペアリングしていません。ターミナルで `npx watch-audio-mcp pair` を実行してください。');
  const advertiser = args.mdns ? advertise(advertisement(identity.fingerprint, port, args.addresses.length ? args.addresses : lanAddresses()), log) : undefined;
  const mcp = serveMcp(bridge, VERSION);
  let stopping = false;
  const stop = async () => {
    if (stopping) return;
    stopping = true;
    await Promise.allSettled([advertiser?.stop(), bridge.close(), mcp.close()]);
    process.exit(0);
  };
  process.stdin.on('end', () => void stop()); // the agent went away
  process.stdin.on('close', () => void stop());
  process.on('SIGINT', () => void stop());
  process.on('SIGTERM', () => void stop());
}

async function pair(args: Args, store: ConfigStore): Promise<number> {
  const hosts = args.addresses.length ? args.addresses : lanAddresses();
  if (hosts.length === 0) {
    log('LAN のアドレスが見つかりません。Wi-Fi などにつないでから実行してください（--address で指定もできます）。');
    return 1;
  }
  if (args.port !== undefined && args.port > 0) {
    // The port the bridge serves on from now on; the phone keeps it with the QR's host.
    const config = store.load();
    config.port = args.port;
    store.save(config);
  }
  const rl = createInterface({ input: process.stdin, output: process.stderr });
  const session = await startPairing({
    store,
    hosts,
    listenHost: args.listen,
    validityMs: args.ttlSeconds !== undefined ? args.ttlSeconds * 1000 : undefined,
    log,
    confirm: async (code, phoneName, cancelled) => {
      process.stderr.write(`\nスマホ「${phoneName}」から接続がありました。\n確認コード: ${code}\n`);
      try {
        const answer = await rl.question('スマホに同じ確認コードが表示されていれば y を入力してください [y/N]: ', { signal: cancelled });
        return /^y(es)?$/i.test(answer.trim());
      } catch {
        return false; // the phone gave up
      }
    },
  });
  const uri = buildPairingUri(session.uri);
  process.stderr.write(`\n${renderUnicodeCompact(uri)}\n\n`);
  process.stderr.write('スマホの watch-audio アプリで「エージェント連携」→「PC をペアリング」を開き、この QR コードを読み取ってください。\n');
  process.stderr.write(`有効期限: ${new Date(session.uri.expiresAt).toLocaleTimeString()}（使い捨て）\n`);
  process.stderr.write(`読み取れないときは次の文字列をアプリに入力: ${uri}\n`);
  try {
    const name = await session.done;
    process.stderr.write(`\nペアリングしました: ${name}\nエージェントの MCP 設定に \`npx watch-audio-mcp\` を追加し、スマホでエージェント接続を ON にすると使えます。\n`);
    return 0;
  } catch (e) {
    process.stderr.write(`\nペアリングできませんでした: ${(e as Error).message}\n`);
    return 1;
  } finally {
    rl.close();
    session.close();
  }
}

function list(store: ConfigStore): void {
  const config = store.load();
  const identity = store.identity();
  process.stdout.write(`PC の名前: ${config.name}\n鍵の指紋: ${identity.fingerprint}\n設定: ${store.dir}\n`);
  if (config.phones.length === 0) {
    process.stdout.write('ペアリング済みのスマホはありません。\n');
    return;
  }
  for (const phone of config.phones) {
    process.stdout.write(`- ${phone.name}（${new Date(phone.pairedAt).toLocaleString()}、指紋 ${phone.fingerprint.slice(0, 12)}…）\n`);
  }
}

function unpair(args: Args, store: ConfigStore): number {
  const config = store.load();
  const target = args.positional[0];
  if (!args.all && !target) {
    process.stderr.write('解除するスマホの名前（または指紋の先頭）を指定してください。すべて解除するには --all。\n');
    return 1;
  }
  const removed = config.phones.filter((p) => args.all || p.name === target || (target && p.fingerprint.startsWith(target)));
  if (removed.length === 0) {
    process.stderr.write(`「${target}」というペアリングはありません。\n`);
    return 1;
  }
  config.phones = config.phones.filter((p) => !removed.includes(p));
  store.save(config);
  for (const p of removed) process.stdout.write(`解除しました: ${p.name}\n`);
  process.stdout.write('スマホのアプリ側のペアリングも「エージェント連携」から取り消してください。\n');
  return 0;
}

async function main(argv: string[]): Promise<number | undefined> {
  let args: Args;
  try {
    args = parseArgs(argv);
  } catch (e) {
    process.stderr.write(`${(e as Error).message}\n\n${USAGE}\n`);
    return 2;
  }
  const store = new ConfigStore();
  switch (args.command) {
    case 'help':
      process.stdout.write(`${USAGE}\n`);
      return 0;
    case 'version':
      process.stdout.write(`${VERSION}\n`);
      return 0;
    case 'serve':
      await serve(args, store);
      return undefined; // keeps running
    case 'pair':
      return pair(args, store);
    case 'list':
      list(store);
      return 0;
    case 'unpair':
      return unpair(args, store);
    default:
      process.stderr.write(`不明なコマンド: ${args.command}\n\n${USAGE}\n`);
      return 2;
  }
}

main(process.argv.slice(2)).then(
  (code) => {
    if (code !== undefined) process.exit(code);
  },
  (e: Error) => {
    process.stderr.write(`watch-audio-mcp: ${e.message}\n`);
    process.exit(1);
  },
);
