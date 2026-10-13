// The serving side: listens on the LAN for the paired phone (mutual TLS 1.3; only certificates of
// paired phones complete the handshake, and their key fingerprint is checked again against the
// current pairings), and forwards the agent's tool calls to it.

import { createServer, type SecureContextOptions, type Server, type TLSSocket } from 'node:tls';
import { watchFile, unwatchFile } from 'node:fs';
import { certificateFingerprint } from './certificate.js';
import type { ConfigStore } from './config.js';
import { messageReader } from './pairing.js';
import { ByeReason, encodeFrame, ErrorCode, PROTOCOL_VERSION, type AgentMessage, type JsonObject } from './wire.js';

export interface ToolResult {
  content: Array<Record<string, unknown>>;
  isError?: boolean;
  [key: string]: unknown;
}

export const NOT_CONNECTED_MESSAGE =
  'スマホがつながっていません。スマホの watch-audio アプリで「エージェント接続」を ON にしてください（PC と同じネットワークにいる必要があります）。' +
  ' (The phone is not connected: turn on agent access in the watch-audio app on the phone, on the same network as this PC.)';

export const TIMEOUT_MESSAGE =
  'スマホから時間内に応答がありませんでした。もう一度試してください。 (The phone did not answer in time; try again.)';

export interface BridgeOptions {
  store: ConfigStore;
  software: string;
  log: (line: string) => void;
  /** Interface to listen on (all by default). */
  listenHost?: string;
  /** Port to try first (the configured one by default); 0 = any. */
  port?: number;
  callTimeoutMs?: number;
  helloTimeoutMs?: number;
}

interface Pending {
  resolve: (result: ToolResult) => void;
  timer: NodeJS.Timeout;
}

/** One authenticated phone connection. */
class PhoneConnection {
  private nextId = 1;
  private readonly pending = new Map<number, Pending>();

  constructor(readonly socket: TLSSocket, readonly fingerprint: string, readonly name: string) {}

  send(message: AgentMessage): void {
    this.socket.write(encodeFrame(message));
  }

  call(name: string, args: JsonObject, timeoutMs: number): Promise<ToolResult> {
    const id = this.nextId++;
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        resolve(errorResult(TIMEOUT_MESSAGE));
      }, timeoutMs);
      this.pending.set(id, { resolve, timer });
      this.send({
        type: 'rpc',
        message: { jsonrpc: '2.0', id, method: 'tools/call', params: { name, arguments: args } },
      });
    });
  }

  /** A JSON-RPC response from the phone. */
  answer(message: JsonObject): void {
    const id = message.id;
    if (typeof id !== 'number') return;
    const pending = this.pending.get(id);
    if (!pending) return;
    this.pending.delete(id);
    clearTimeout(pending.timer);
    const result = message.result as ToolResult | undefined;
    if (result && Array.isArray(result.content)) {
      pending.resolve({ content: result.content, isError: result.isError === true });
    } else {
      const error = message.error as { message?: unknown } | undefined;
      pending.resolve(errorResult(`スマホがエラーを返しました: ${String(error?.message ?? 'unknown')}`));
    }
  }

  /** Fails the calls still waiting (the connection ended). */
  close(): void {
    for (const [id, pending] of this.pending) {
      clearTimeout(pending.timer);
      pending.resolve(errorResult(NOT_CONNECTED_MESSAGE));
      this.pending.delete(id);
    }
  }
}

export function errorResult(text: string): ToolResult {
  return { content: [{ type: 'text', text }], isError: true };
}

export class Bridge {
  private server?: Server;
  private phone?: PhoneConnection;
  private listeners: Array<(connected: boolean, name?: string) => void> = [];

  constructor(private readonly options: BridgeOptions) {}

  get connectedPhone(): string | undefined {
    return this.phone?.name;
  }

  onConnectionChange(listener: (connected: boolean, name?: string) => void): void {
    this.listeners.push(listener);
  }

  /** TLS options trusting exactly the paired phones' certificates. */
  private secureContextOptions(): SecureContextOptions {
    const identity = this.options.store.identity();
    const phones = this.options.store.load().phones;
    return {
      key: identity.keyPem,
      cert: identity.certificatePem,
      // An empty list would fall back to the system CAs: use our own certificate, which no phone has.
      ca: phones.length > 0 ? phones.map((p) => p.certificatePem) : [identity.certificatePem],
      minVersion: 'TLSv1.3',
    };
  }

  /** Starts listening; @return the port. */
  async start(): Promise<number> {
    const server = createServer({ ...this.secureContextOptions(), requestCert: true, rejectUnauthorized: true });
    this.server = server;
    server.on('secureConnection', (socket: TLSSocket) => {
      void this.accept(socket).catch((e: Error) => {
        this.options.log(`接続を閉じました: ${e.message}`);
        socket.destroy();
      });
    });
    server.on('tlsClientError', (error: Error, socket: TLSSocket) => {
      this.options.log(`ペアリングしていない相手の接続を拒否しました (${socket.remoteAddress ?? '?'}: ${error.message})`);
    });
    // `unpair` (another process) edits the configuration: trust follows it at once.
    watchFile(this.options.store.path, { interval: 500 }, () => this.reloadTrust());

    const preferred = this.options.port ?? this.options.store.load().port;
    try {
      await listen(server, preferred, this.options.listenHost);
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code !== 'EADDRINUSE' || preferred === 0) throw e;
      this.options.log(`ポート ${preferred} は使用中のため、空いているポートで待ち受けます（スマホは mDNS で見つけます）。`);
      await listen(server, 0, this.options.listenHost);
    }
    return (server.address() as { port: number }).port;
  }

  private reloadTrust(): void {
    if (!this.server) return;
    this.server.setSecureContext(this.secureContextOptions());
    const phone = this.phone;
    if (phone && !this.options.store.load().phones.some((p) => p.fingerprint === phone.fingerprint)) {
      this.options.log(`ペアリングを解除した ${phone.name} の接続を閉じました。`);
      phone.send({ type: 'bye', reason: ByeReason.REVOKED });
      phone.socket.end();
    }
  }

  private async accept(socket: TLSSocket): Promise<void> {
    const peer = socket.getPeerCertificate(true);
    const fingerprint = peer?.raw ? certificateFingerprint(peer.raw) : undefined;
    // The handshake already required a paired certificate; check the key against the current
    // pairings too (a pairing removed a moment ago, or a certificate reused with another key).
    const paired = this.options.store.load().phones.find((p) => p.fingerprint === fingerprint);
    if (!fingerprint || !paired) {
      this.options.log('ペアリングしていない鍵の接続を拒否しました。');
      socket.destroy();
      return;
    }
    const reader = messageReader(socket);
    const hello = await reader.next(this.options.helloTimeoutMs ?? 10_000);
    if (hello?.type !== 'hello' || hello.role !== 'phone') {
      socket.write(encodeFrame({ type: 'error', code: ErrorCode.BAD_MESSAGE, message: 'expected hello' }));
      socket.end();
      return;
    }
    if (hello.protocol !== PROTOCOL_VERSION) {
      socket.write(encodeFrame({ type: 'error', code: ErrorCode.UNSUPPORTED_PROTOCOL, message: `protocol ${PROTOCOL_VERSION} only` }));
      socket.end();
      return;
    }
    const connection = new PhoneConnection(socket, fingerprint, paired.name);
    connection.send({
      type: 'hello',
      protocol: PROTOCOL_VERSION,
      role: 'bridge',
      name: this.options.store.load().name,
      software: this.options.software,
    });
    // The newest connection wins (e.g. the phone reconnected after changing networks).
    const previous = this.phone;
    this.phone = connection;
    if (previous) previous.socket.destroy();
    this.options.log(`スマホ「${paired.name}」がつながりました。`);
    this.listeners.forEach((l) => l(true, paired.name));
    socket.on('close', () => {
      connection.close();
      if (this.phone === connection) {
        this.phone = undefined;
        this.options.log(`スマホ「${paired.name}」との接続が切れました。`);
        this.listeners.forEach((l) => l(false));
      }
    });
    for (;;) {
      const message = await reader.next(24 * 60 * 60_000);
      if (!message) break;
      if (message.type === 'rpc') connection.answer(message.message);
      else if (message.type === 'bye' || message.type === 'error') break;
    }
    socket.end();
  }

  /** Forwards a tool call to the phone, or explains that it is not connected. */
  async call(name: string, args: JsonObject): Promise<ToolResult> {
    const phone = this.phone;
    if (!phone) return errorResult(NOT_CONNECTED_MESSAGE);
    return phone.call(name, args, this.options.callTimeoutMs ?? 30_000);
  }

  async close(): Promise<void> {
    unwatchFile(this.options.store.path);
    if (this.phone) {
      this.phone.send({ type: 'bye', reason: ByeReason.SHUTDOWN });
      this.phone.socket.end();
    }
    await new Promise<void>((resolve) => {
      if (!this.server) return resolve();
      this.server.close(() => resolve());
      setTimeout(resolve, 1_000).unref(); // a peer that does not close its side
    });
  }
}

function listen(server: Server, port: number, host?: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const onError = (e: Error) => reject(e);
    server.once('error', onError);
    server.listen(port, host, () => {
      server.off('error', onError);
      resolve();
    });
  });
}
