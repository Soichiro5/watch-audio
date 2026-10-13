// mDNS / DNS-SD advertisement of the listening bridge (`_watchaudio-mcp._tcp`), so the phone finds
// the PC's current address and port. Only multicast to 224.0.0.251:5353 on the local link.
//
// The TXT record does not carry a stable identifier: it has a random nonce per run and
// h = base64url(SHA-256(nonce ‖ key fingerprint))[0..16), so a phone that knows the fingerprint
// recognises its PC, while others on the network cannot track the PC across runs. Whatever the
// phone finds, the TLS handshake still checks the key.

import { createHash, randomBytes } from 'node:crypto';
import multicastDns, { type Answer, type MulticastDns, type Packet } from 'multicast-dns';

export const SERVICE_TYPE = '_watchaudio-mcp._tcp.local';
const TTL_SECONDS = 120;

/** The `h` TXT value for [nonce] (base64url) and the bridge's [fingerprint]. */
export function advertisedKeyHash(nonce: string, fingerprint: string): string {
  return createHash('sha256')
    .update(Buffer.from(nonce, 'base64url'))
    .update(Buffer.from(fingerprint, 'base64url'))
    .digest('base64url')
    .slice(0, 16);
}

export interface Advertisement {
  instance: string;
  host: string;
  port: number;
  addresses: string[];
  txt: string[];
}

export function advertisement(fingerprint: string, port: number, addresses: string[], nonce = randomBytes(8).toString('base64url')): Advertisement {
  const id = randomBytes(3).toString('hex');
  return {
    instance: `watch-audio-mcp-${id}.${SERVICE_TYPE}`,
    host: `watch-audio-mcp-${id}.local`,
    port,
    addresses,
    txt: ['v=1', `n=${nonce}`, `h=${advertisedKeyHash(nonce, fingerprint)}`],
  };
}

/** The records answering a browse for the service ([ttl] 0 = goodbye). */
export function records(ad: Advertisement, ttl = TTL_SECONDS): { answers: Answer[]; additionals: Answer[] } {
  return {
    answers: [{ name: SERVICE_TYPE, type: 'PTR', ttl, data: ad.instance }],
    additionals: [
      { name: ad.instance, type: 'SRV', ttl, flush: true, data: { priority: 0, weight: 0, port: ad.port, target: ad.host } },
      { name: ad.instance, type: 'TXT', ttl, flush: true, data: ad.txt.map((t) => Buffer.from(t, 'utf8')) },
      ...ad.addresses.map((address): Answer => ({ name: ad.host, type: 'A', ttl, flush: true, data: address })),
    ],
  };
}

/** True if [packet] asks for something [ad] answers. */
export function asksFor(packet: Packet, ad: Advertisement): boolean {
  return (packet.questions ?? []).some((q) =>
    (q.name === SERVICE_TYPE && (q.type === 'PTR' || q.type === 'ANY')) ||
    (q.name === '_services._dns-sd._udp.local' && q.type === 'PTR') ||
    (q.name === ad.instance && (q.type === 'SRV' || q.type === 'TXT' || q.type === 'ANY')) ||
    (q.name === ad.host && (q.type === 'A' || q.type === 'ANY')));
}

export interface Advertiser {
  stop(): Promise<void>;
}

/** Starts answering mDNS queries for [ad] and announces it. */
export function advertise(ad: Advertisement, log: (line: string) => void): Advertiser {
  let mdns: MulticastDns;
  try {
    mdns = multicastDns({ reuseAddr: true, loopback: true });
  } catch (e) {
    log(`mDNS を使えません（QR のアドレスで接続できます）: ${(e as Error).message}`);
    return { stop: async () => {} };
  }
  mdns.on('error', (e: Error) => log(`mDNS: ${e.message}`));
  mdns.on('warning', () => {});
  mdns.on('query', (packet: Packet) => {
    if (!asksFor(packet, ad)) return;
    const { answers, additionals } = records(ad);
    if ((packet.questions ?? []).some((q) => q.name === '_services._dns-sd._udp.local')) {
      answers.push({ name: '_services._dns-sd._udp.local', type: 'PTR', ttl: TTL_SECONDS, data: SERVICE_TYPE });
    }
    mdns.respond({ answers, additionals });
  });
  const announce = () => mdns.respond(records(ad));
  announce();
  const again = setTimeout(announce, 1_000);
  again.unref();
  return {
    stop: () =>
      new Promise((resolve) => {
        clearTimeout(again);
        mdns.respond(records(ad, 0), () => mdns.destroy(() => resolve()));
      }),
  };
}
