// The bridge's identity: an ECDSA P-256 key and a minimal self-signed X.509 v3 certificate for it,
// built with node:crypto only (the same shape as shared/agent SelfSignedCertificate.kt). Nobody
// checks it against a certificate authority: each side pins the other's key fingerprint.

import { createHash, generateKeyPairSync, randomBytes, sign, X509Certificate, type KeyObject } from 'node:crypto';

/** UTCTime 2020-01-01T00:00:00Z. */
const NOT_BEFORE = '200101000000Z';
/** GeneralizedTime 9999-12-31T23:59:59Z (RFC 5280: no well-defined expiration). */
const NOT_AFTER = '99991231235959Z';
/** AlgorithmIdentifier ecdsa-with-SHA256 (1.2.840.10045.4.3.2). */
const ECDSA_WITH_SHA256 = Buffer.from('300a06082a8648ce3d040302', 'hex');
/** OID id-at-commonName (2.5.4.3). */
const OID_COMMON_NAME = Buffer.from('0603550403', 'hex');

function length(size: number): Buffer {
  if (size < 0x80) return Buffer.from([size]);
  if (size < 0x100) return Buffer.from([0x81, size]);
  if (size < 0x10000) return Buffer.from([0x82, size >> 8, size & 0xff]);
  return Buffer.from([0x83, size >> 16, (size >> 8) & 0xff, size & 0xff]);
}

function tlv(tag: number, content: Buffer): Buffer {
  return Buffer.concat([Buffer.from([tag]), length(content.length), content]);
}

const sequence = (...parts: Buffer[]) => tlv(0x30, Buffer.concat(parts));
const set = (...parts: Buffer[]) => tlv(0x31, Buffer.concat(parts));

/** DER INTEGER of an unsigned big-endian value. */
function integer(value: Buffer): Buffer {
  let start = 0;
  while (start < value.length - 1 && value[start] === 0) start++;
  const trimmed = value.subarray(start);
  return tlv(0x02, (trimmed[0]! & 0x80) !== 0 ? Buffer.concat([Buffer.from([0]), trimmed]) : trimmed);
}

/** A self-signed certificate (DER) for [privateKey]'s public key. */
export function selfSignedCertificate(privateKey: KeyObject, publicKey: KeyObject, commonName: string): Buffer {
  const spki = publicKey.export({ type: 'spki', format: 'der' });
  const name = sequence(set(sequence(OID_COMMON_NAME, tlv(0x0c, Buffer.from(commonName, 'utf8')))));
  const serial = randomBytes(16);
  serial[0] = (serial[0]! & 0x7f) | 0x40;
  const tbs = sequence(
    tlv(0xa0, integer(Buffer.from([2]))),
    integer(serial),
    ECDSA_WITH_SHA256,
    name,
    sequence(tlv(0x17, Buffer.from(NOT_BEFORE, 'ascii')), tlv(0x18, Buffer.from(NOT_AFTER, 'ascii'))),
    name,
    spki,
  );
  const signature = sign('sha256', tbs, privateKey); // DER ECDSA-Sig-Value
  return sequence(tbs, ECDSA_WITH_SHA256, tlv(0x03, Buffer.concat([Buffer.from([0]), signature])));
}

/** Base64url (no padding) SHA-256 of a DER SubjectPublicKeyInfo. */
export function keyFingerprint(spki: Buffer): string {
  return createHash('sha256').update(spki).digest('base64url');
}

/** [keyFingerprint] of a certificate's public key. */
export function certificateFingerprint(certificateDer: Buffer): string {
  const spki = new X509Certificate(certificateDer).publicKey.export({ type: 'spki', format: 'der' });
  return keyFingerprint(spki);
}

export interface Identity {
  /** PKCS#8 PEM. */
  keyPem: string;
  certificatePem: string;
  fingerprint: string;
}

export function toPem(der: Buffer, label: string): string {
  const lines = der.toString('base64').match(/.{1,64}/g) ?? [];
  return `-----BEGIN ${label}-----\n${lines.join('\n')}\n-----END ${label}-----\n`;
}

export function fromPem(pem: string): Buffer {
  return Buffer.from(pem.replace(/-----(BEGIN|END) [A-Z ]+-----/g, '').replace(/\s+/g, ''), 'base64');
}

/** A new P-256 key with its self-signed certificate. */
export function createIdentity(commonName: string): Identity {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const der = selfSignedCertificate(privateKey, publicKey, commonName);
  return {
    keyPem: privateKey.export({ type: 'pkcs8', format: 'pem' }).toString(),
    certificatePem: toPem(der, 'CERTIFICATE'),
    fingerprint: keyFingerprint(publicKey.export({ type: 'spki', format: 'der' })),
  };
}
