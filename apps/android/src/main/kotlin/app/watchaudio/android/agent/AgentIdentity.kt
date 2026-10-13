package app.watchaudio.android.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.watchaudio.core.agent.SelfSignedCertificate
import app.watchaudio.core.agent.keyFingerprint
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * The phone's identity for agent access (#184): an ECDSA P-256 key in the Android Keystore
 * (generated there, never exportable; usable without unlocking so that access keeps working with
 * the screen off) and a self-signed certificate for it in the shape of `shared/agent`
 * [SelfSignedCertificate], which the bridge pins by [fingerprint]. The certificate is public and
 * kept in `no_backup/agent/` (excluded from backups like everything else); without the key it is
 * worthless, and a new key gets a new one.
 *
 * Signing needs DIGEST_NONE too: Conscrypt signs the TLS 1.3 CertificateVerify digest with
 * `NONEwithECDSA` for keys it cannot read.
 */
class AgentIdentity private constructor(val privateKey: PrivateKey, val certificate: X509Certificate) {
    /** [keyFingerprint] of the public key: what the PC stores when pairing. */
    val fingerprint: String = keyFingerprint(certificate.publicKey.encoded)

    companion object {
        const val KEY_ALIAS = "watch-audio-agent-identity"
        private const val COMMON_NAME = "watch-audio phone"

        /** Loads the identity, creating the key and certificate on first use. Blocking (Keystore): call off the main thread. */
        fun loadOrCreate(context: Context): AgentIdentity {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val certificateFile = File(File(context.noBackupFilesDir, "agent"), "identity.der")
            var key = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
            var publicKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey
            if (key == null || publicKey == null) {
                val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(
                        KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                            .build(),
                    )
                }.generateKeyPair()
                key = pair.private
                publicKey = pair.public
                certificateFile.delete()
            }
            val factory = CertificateFactory.getInstance("X.509")
            val stored = runCatching { certificateFile.inputStream().use { factory.generateCertificate(it) as X509Certificate } }.getOrNull()
            val certificate = stored?.takeIf { it.publicKey.encoded.contentEquals(publicKey.encoded) } ?: run {
                val serial = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val der = SelfSignedCertificate.build(publicKey.encoded, COMMON_NAME, serial) { tbs ->
                    Signature.getInstance("SHA256withECDSA").run {
                        initSign(key)
                        update(tbs)
                        sign()
                    }
                }
                certificateFile.parentFile?.mkdirs()
                certificateFile.writeBytes(der)
                factory.generateCertificate(der.inputStream()) as X509Certificate
            }
            return AgentIdentity(key, certificate)
        }
    }
}
