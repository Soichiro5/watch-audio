// Loaded with `node --import` into the bridge under test: the bridge only listens (the phone
// connects to it) and multicasts mDNS on the local link. Any outbound TCP connection, HTTP
// request or other UDP destination is reported on stderr as EGRESS_VIOLATION and refused.

import dgram from 'node:dgram';
import net from 'node:net';

const MDNS = new Set(['224.0.0.251:5353', 'ff02::fb:5353']);

function violation(what: string): never {
  process.stderr.write(`EGRESS_VIOLATION ${what}\n`);
  throw new Error(`outbound network use refused in the e2e test: ${what}`);
}

// Accepted sockets never call connect(); only outbound ones do.
net.Socket.prototype.connect = function (...args: unknown[]) {
  // net.connect() passes its normalized [options, callback] array as the only argument.
  const [first, second] = Array.isArray(args[0]) ? (args[0] as unknown[]) : args;
  const target = typeof first === 'object' && first !== null
    ? `${(first as { host?: string; path?: string }).host ?? (first as { path?: string }).path}:${(first as { port?: number }).port ?? ''}`
    : `${String(second ?? 'localhost')}:${String(first)}`;
  return violation(`tcp ${target}`);
} as typeof net.Socket.prototype.connect;

const send = dgram.Socket.prototype.send;
dgram.Socket.prototype.send = function (this: dgram.Socket, ...args: unknown[]) {
  // send(msg, [offset, length,] port, address, cb)
  const numbers = args.filter((a) => typeof a === 'number') as number[];
  const port = numbers[numbers.length - 1];
  const address = args.find((a, i) => i > 0 && typeof a === 'string') as string | undefined;
  if (!MDNS.has(`${address}:${port}`)) violation(`udp ${address}:${port}`);
  return send.apply(this, args as never);
} as typeof dgram.Socket.prototype.send;

globalThis.fetch = (() => violation('fetch')) as typeof fetch;
