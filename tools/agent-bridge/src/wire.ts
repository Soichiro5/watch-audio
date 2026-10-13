// The wire format between the bridge and the phone (shared/agent AgentWire.kt; both sides test
// against shared/agent/testdata). A frame is a 4-byte big-endian length and that many bytes of
// UTF-8 JSON: one message whose "type" names its kind.

export const PROTOCOL_VERSION = 1;

/** Largest frame either side accepts (an audio clip of 30 s fits). */
export const MAX_FRAME_BYTES = 4 * 1024 * 1024;

export interface JsonObject {
  [key: string]: unknown;
}

export type AgentMessage =
  | { type: 'hello'; protocol: number; role: 'phone' | 'bridge'; name: string; software: string }
  | { type: 'pair_request'; protocol: number; token: string; name: string }
  | { type: 'pair_pending' }
  | { type: 'pair_confirm' }
  | { type: 'pair_result'; name: string; port: number }
  | { type: 'rpc'; message: JsonObject }
  | { type: 'error'; code: string; message: string }
  | { type: 'bye'; reason: string };

export const ErrorCode = {
  UNSUPPORTED_PROTOCOL: 'unsupported_protocol',
  PAIRING_INVALID: 'pairing_invalid',
  PAIRING_EXPIRED: 'pairing_expired',
  PAIRING_REJECTED: 'pairing_rejected',
  BAD_MESSAGE: 'bad_message',
} as const;

export const ByeReason = {
  ACCESS_OFF: 'access_off',
  REVOKED: 'revoked',
  SHUTDOWN: 'shutdown',
} as const;

export class FrameError extends Error {}

function isObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isString(value: unknown): value is string {
  return typeof value === 'string';
}

function isInt(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value);
}

/** Checks the shape of a decoded message; unknown keys are ignored (as in Kotlin). */
export function parseMessage(value: unknown): AgentMessage {
  if (!isObject(value) || !isString(value.type)) throw new FrameError('not a message');
  const v = value;
  switch (v.type) {
    case 'hello':
      if (isInt(v.protocol) && (v.role === 'phone' || v.role === 'bridge') && isString(v.name) && isString(v.software)) {
        return { type: 'hello', protocol: v.protocol, role: v.role, name: v.name, software: v.software };
      }
      break;
    case 'pair_request':
      if (isInt(v.protocol) && isString(v.token) && isString(v.name)) {
        return { type: 'pair_request', protocol: v.protocol, token: v.token, name: v.name };
      }
      break;
    case 'pair_pending':
      return { type: 'pair_pending' };
    case 'pair_confirm':
      return { type: 'pair_confirm' };
    case 'pair_result':
      if (isString(v.name) && isInt(v.port)) return { type: 'pair_result', name: v.name, port: v.port };
      break;
    case 'rpc':
      if (isObject(v.message)) return { type: 'rpc', message: v.message };
      break;
    case 'error':
      if (isString(v.code) && isString(v.message)) return { type: 'error', code: v.code, message: v.message };
      break;
    case 'bye':
      if (isString(v.reason)) return { type: 'bye', reason: v.reason };
      break;
    default:
      throw new FrameError(`unknown message type ${String(v.type)}`);
  }
  throw new FrameError(`malformed ${v.type} message`);
}

/** [message] as one frame. */
export function encodeFrame(message: AgentMessage): Buffer {
  const body = Buffer.from(JSON.stringify(message), 'utf8');
  if (body.length > MAX_FRAME_BYTES) throw new FrameError(`frame of ${body.length} bytes exceeds ${MAX_FRAME_BYTES}`);
  const header = Buffer.alloc(4);
  header.writeUInt32BE(body.length, 0);
  return Buffer.concat([header, body]);
}

/** Splits a byte stream into messages; feed whatever the socket delivered. */
export class FrameDecoder {
  private buffer: Buffer = Buffer.alloc(0);

  constructor(private readonly maxFrameBytes: number = MAX_FRAME_BYTES) {}

  /** @throws FrameError on an oversized frame or a malformed message. */
  feed(chunk: Buffer): AgentMessage[] {
    this.buffer = this.buffer.length === 0 ? chunk : Buffer.concat([this.buffer, chunk]);
    const messages: AgentMessage[] = [];
    while (this.buffer.length >= 4) {
      const size = this.buffer.readUInt32BE(0);
      if (size > this.maxFrameBytes) throw new FrameError(`frame of ${size} bytes exceeds ${this.maxFrameBytes}`);
      if (this.buffer.length < 4 + size) break;
      const body = this.buffer.subarray(4, 4 + size);
      this.buffer = this.buffer.subarray(4 + size);
      let parsed: unknown;
      try {
        parsed = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(body));
      } catch (e) {
        throw new FrameError(`not JSON: ${(e as Error).message}`);
      }
      messages.push(parseMessage(parsed));
    }
    return messages;
  }
}
