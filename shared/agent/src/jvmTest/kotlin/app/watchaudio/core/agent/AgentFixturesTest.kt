package app.watchaudio.core.agent

import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The wire format against the JSON files in `shared/agent/testdata`, which tools/agent-bridge's tests read
 * too (their reference values were computed independently, in Python).
 */
class AgentFixturesTest {
    // Gradle runs JVM tests with the module directory as the working directory.
    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(File("testdata/$name").readText()).jsonObject

    @Test
    fun everyMessageRoundTrips() {
        val messages = fixture("messages.json")
        for ((name, json) in messages) {
            val decoded = AgentWire.decode(json.toString())
            assertEquals(json, Json.parseToJsonElement(AgentWire.encode(decoded)), name)
        }
        assertEquals(PairConfirm, AgentWire.decode(messages.getValue("pair_confirm").toString()))
        val call = AgentWire.decode(messages.getValue("rpc_call").toString()) as Rpc
        assertEquals("tools/call", call.message["method"]!!.jsonPrimitive.content)
    }

    @Test
    fun framesMatchTheVector() {
        val frames = fixture("frames.json")
        val vector = frames.getValue("bye_shutdown").jsonObject
        val bytes = vector.getValue("hex").jsonPrimitive.content.hexToByteArray()
        assertContentEquals(bytes, AgentWire.frame(Bye(ByeReason.SHUTDOWN)))
        // Byte by byte: frames may arrive split anywhere.
        val decoder = FrameDecoder()
        val decoded = bytes.flatMap { decoder.feed(byteArrayOf(it)) }
        assertEquals(listOf<AgentMessage>(Bye(ByeReason.SHUTDOWN)), decoded)

        val oversize = frames.getValue("oversize_header_hex").jsonPrimitive.content.hexToByteArray()
        assertFailsWith<FrameException> { FrameDecoder().feed(oversize) }
    }

    @Test
    fun pairingUriAndConfirmationCode() {
        val pairing = fixture("pairing.json")
        val uri = pairing.getValue("uri").jsonPrimitive.content
        val parsed = pairing.getValue("parsed").jsonObject
        val expected = PairingUri(
            version = parsed.getValue("version").jsonPrimitive.int,
            name = parsed.getValue("name").jsonPrimitive.content,
            fingerprint = parsed.getValue("fingerprint").jsonPrimitive.content,
            addresses = parsed.getValue("addresses").jsonArray.map { it.jsonPrimitive.content },
            token = parsed.getValue("token").jsonPrimitive.content,
            expiresAt = parsed.getValue("expiresAt").jsonPrimitive.long,
        )
        assertEquals(expected, PairingUri.parse(uri))
        assertEquals(uri, expected.toUri())
        pairing.getValue("invalid").jsonArray.forEach { bad ->
            assertFailsWith<IllegalArgumentException>(bad.toString()) { PairingUri.parse(bad.jsonPrimitive.content) }
        }

        val c = pairing.getValue("confirmation").jsonObject
        assertEquals(
            c.getValue("code").jsonPrimitive.content,
            confirmationCode(
                c.getValue("token").jsonPrimitive.content,
                c.getValue("bridgeFingerprint").jsonPrimitive.content,
                c.getValue("phoneFingerprint").jsonPrimitive.content,
            ),
        )
    }

    @Test
    fun toolListMatchesTheTools() {
        val tools = fixture("tools.json").getValue("tools").jsonArray.map { it.jsonObject }
        assertEquals(AgentTool.entries.map { it.wireName }, tools.map { it.getValue("name").jsonPrimitive.content })
        for (tool in tools) {
            val name = tool.getValue("name").jsonPrimitive.content
            val properties = tool.getValue("inputSchema").jsonObject.getValue("properties").jsonObject.keys
            assertEquals(AgentTool.of(name)!!.arguments, properties, name)
            val description = tool.getValue("description").jsonPrimitive.content
            if (name != "get_audio_clip") assertEquals(true, "not instructions" in description, "$name must warn about injected instructions")
        }
    }

    @Test
    fun selfSignedCertificateParsesAndVerifies() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val der = SelfSignedCertificate.build(keys.public.encoded, "watch-audio phone", byteArrayOf(0x80.toByte(), 1, 2)) { tbs ->
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keys.private)
                update(tbs)
                sign()
            }
        }
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        cert.verify(keys.public)
        cert.checkValidity()
        assertEquals(3, cert.version)
        assertEquals("CN=watch-audio phone", cert.subjectX500Principal.name)
        assertContentEquals(keys.public.encoded, cert.publicKey.encoded)
        val expected = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(keys.public.encoded))
        assertEquals(expected, keyFingerprint(cert.publicKey.encoded))
    }
}
