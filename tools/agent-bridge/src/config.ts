// Where the bridge keeps its key and the paired phones: one directory per user, readable by the
// user only (0700 directory, 0600 files; on Windows the per-user %APPDATA% ACLs apply). Nothing
// here leaves the PC.

import { chmodSync, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { homedir, hostname } from 'node:os';
import { join } from 'node:path';
import { certificateFingerprint, createIdentity, fromPem, type Identity } from './certificate.js';

/** Port the bridge listens on unless it is taken (then any free port, found by mDNS). */
export const DEFAULT_PORT = 47823;

export interface PairedPhone {
  name: string;
  /** Key fingerprint of the phone (base64url SHA-256 of its public key). */
  fingerprint: string;
  /** The phone's certificate (PEM), trusted as-is for the TLS handshake. */
  certificatePem: string;
  /** Epoch ms. */
  pairedAt: number;
}

export interface BridgeConfig {
  version: 1;
  /** Shown on the phone. */
  name: string;
  port: number;
  phones: PairedPhone[];
}

/** The configuration directory: $WATCH_AUDIO_MCP_HOME, or the platform's per-user config place. */
export function configDir(env: NodeJS.ProcessEnv = process.env): string {
  if (env.WATCH_AUDIO_MCP_HOME) return env.WATCH_AUDIO_MCP_HOME;
  switch (process.platform) {
    case 'darwin':
      return join(homedir(), 'Library', 'Application Support', 'watch-audio-mcp');
    case 'win32':
      return join(env.APPDATA ?? join(homedir(), 'AppData', 'Roaming'), 'watch-audio-mcp');
    default:
      return join(env.XDG_CONFIG_HOME ?? join(homedir(), '.config'), 'watch-audio-mcp');
  }
}

/** Writes [content] readable by the owner only, atomically (temp file + rename). */
function writePrivate(path: string, content: string): void {
  const temp = `${path}.tmp-${process.pid}`;
  writeFileSync(temp, content, { mode: 0o600 });
  chmodSync(temp, 0o600);
  renameSync(temp, path);
}

export class ConfigStore {
  constructor(readonly dir: string = configDir()) {}

  private get configPath() {
    return join(this.dir, 'config.json');
  }

  private get keyPath() {
    return join(this.dir, 'identity.key.pem');
  }

  private get certificatePath() {
    return join(this.dir, 'identity.cert.pem');
  }

  private ensureDir(): void {
    mkdirSync(this.dir, { recursive: true, mode: 0o700 });
    chmodSync(this.dir, 0o700);
  }

  /** The bridge's key and certificate, created on first use. */
  identity(): Identity {
    if (existsSync(this.keyPath) && existsSync(this.certificatePath)) {
      const keyPem = readFileSync(this.keyPath, 'utf8');
      const certificatePem = readFileSync(this.certificatePath, 'utf8');
      return { keyPem, certificatePem, fingerprint: fingerprintOfPem(certificatePem) };
    }
    this.ensureDir();
    const identity = createIdentity('watch-audio-mcp');
    writePrivate(this.keyPath, identity.keyPem);
    writePrivate(this.certificatePath, identity.certificatePem);
    return identity;
  }

  /** The current configuration (read from disk every time, so `unpair` takes effect at once). */
  load(): BridgeConfig {
    if (!existsSync(this.configPath)) {
      return { version: 1, name: defaultName(), port: DEFAULT_PORT, phones: [] };
    }
    const parsed = JSON.parse(readFileSync(this.configPath, 'utf8')) as Partial<BridgeConfig>;
    return {
      version: 1,
      name: typeof parsed.name === 'string' && parsed.name ? parsed.name : defaultName(),
      port: typeof parsed.port === 'number' ? parsed.port : DEFAULT_PORT,
      phones: Array.isArray(parsed.phones) ? parsed.phones.filter(isPairedPhone) : [],
    };
  }

  save(config: BridgeConfig): void {
    this.ensureDir();
    writePrivate(this.configPath, JSON.stringify(config, null, 2) + '\n');
  }

  get path(): string {
    return this.configPath;
  }
}

function isPairedPhone(value: unknown): value is PairedPhone {
  const v = value as PairedPhone;
  return typeof v === 'object' && v !== null && typeof v.name === 'string' && typeof v.fingerprint === 'string' &&
    typeof v.certificatePem === 'string' && typeof v.pairedAt === 'number';
}

function defaultName(): string {
  return hostname().replace(/\.local$/, '').slice(0, 64) || 'PC';
}

function fingerprintOfPem(pem: string): string {
  return certificateFingerprint(fromPem(pem));
}
