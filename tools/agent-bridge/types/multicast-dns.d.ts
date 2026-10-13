// The part of multicast-dns (https://github.com/mafintosh/multicast-dns) the bridge uses.
declare module 'multicast-dns' {
  import type { EventEmitter } from 'node:events';

  export interface Question {
    name: string;
    type: string;
    class?: string | number;
  }

  export interface Answer {
    name: string;
    type: 'A' | 'AAAA' | 'PTR' | 'SRV' | 'TXT';
    ttl?: number;
    flush?: boolean;
    data: unknown;
  }

  export interface Packet {
    type?: 'query' | 'response';
    questions?: Question[];
    answers?: Answer[];
    additionals?: Answer[];
  }

  export interface Options {
    multicast?: boolean;
    interface?: string;
    port?: number;
    ip?: string;
    ttl?: number;
    loopback?: boolean;
    reuseAddr?: boolean;
  }

  export interface MulticastDns extends EventEmitter {
    respond(packet: Packet, callback?: (error?: Error) => void): void;
    query(packet: Packet, callback?: (error?: Error) => void): void;
    destroy(callback?: () => void): void;
  }

  export default function multicastDns(options?: Options): MulticastDns;
}
