package org.aionlink.iccoa

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.*
import org.junit.Test

/** Synthetic fixtures only. These tests do not prove compatibility with an AION. */
class ExperimentalQuickAuthTest {
    private fun point(key: ECPublicKey): ByteArray {
        fun part(number: BigInteger): ByteArray {
            val bytes = number.toByteArray(); val size = minOf(bytes.size, 32)
            return ByteArray(32).also { System.arraycopy(bytes, bytes.size - size, it, 32 - size, size) }
        }
        return part(key.w.affineX) + part(key.w.affineY)
    }
    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** Synthetic car: signs its agreement the way the normal peer does, with the stored server key. */
    private fun peer(req: ExperimentalAuthWire.Request, serverAuth: KeyPair, result: Int = 0,
                     corruptSignature: Boolean = false): ExperimentalAuthWire.Response {
        val agreement = keyPair()
        val agreementBytes = point(agreement.public as ECPublicKey)
        val peerNonce = ByteArray(32) { (it + 1).toByte() }
        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign(serverAuth.private); update(agreementBytes + req.agreementPoint + peerNonce + req.nonce)
        }
        val signature = signer.sign()
        if (corruptSignature) signature[signature.lastIndex] = (signature.last().toInt() xor 1).toByte()
        // QUICK success carries no authPk/proof (fields 2/3 absent).
        return ExperimentalAuthWire.Response(1793, null, null, agreementBytes, signature, peerNonce, result, 1)
    }

    @Test fun quickRequestHasEmptyAuthFieldsAndSilentConfirmation() {
        val stored = keyPair(); val server = keyPair()
        ExperimentalQuickAuth(stored, point(server.public as ECPublicKey), "own-id".toByteArray(), "test-phone", 1).use { attempt ->
            val request = attempt.request()
            assertEquals(0, request.authPoint.size)
            assertEquals(0, request.proof.size)
            assertFalse(request.confirmed)
            assertEquals(1793, request.version)
            val checker = Signature.getInstance("SHA256withECDSA").apply {
                initVerify(stored.public); update(request.agreementPoint + request.nonce)
            }
            assertTrue(checker.verify(request.agreementSignature))
            // The QUICK request still encodes: field 9 is false, authPk/proof are empty blobs.
            val input = com.google.protobuf.CodedInputStream.newInstance(ExperimentalAuthWire.requestBody(request))
            assertEquals(8, input.readTag()); assertEquals(1793, input.readInt32())
            assertEquals(18, input.readTag()); assertEquals(0, input.readByteArray().size)
            assertEquals(26, input.readTag()); assertEquals(0, input.readByteArray().size)
            assertEquals(34, input.readTag()); assertEquals(64, input.readByteArray().size)
            assertEquals(42, input.readTag()); assertTrue(input.readByteArray().isNotEmpty())
            assertEquals(50, input.readTag()); assertEquals(32, input.readByteArray().size)
            assertEquals(58, input.readTag()); assertArrayEquals("own-id".toByteArray(), input.readByteArray())
            assertEquals(66, input.readTag()); assertEquals("test-phone", input.readStringRequireUtf8())
            assertEquals(72, input.readTag()); assertFalse(input.readBool())
            assertEquals(80, input.readTag()); assertEquals(1, input.readInt32())
            assertEquals(90, input.readTag()); assertEquals("AionLink Open", input.readStringRequireUtf8())
            assertEquals(0, input.readTag())
        }
    }

    @Test fun quickVerifyAcceptsServerSignedAgreement() {
        val stored = keyPair(); val server = keyPair()
        ExperimentalQuickAuth(stored, point(server.public as ECPublicKey), "own-id".toByteArray(), "test-phone", 1).use { attempt ->
            val request = attempt.request()
            assertEquals(QuickCheck.VERIFIED, attempt.verify(peer(request, server)))
            assertTrue(attempt.confirmCipher("192.168.49.2").isNotEmpty())
            try { attempt.confirmCipher("192.168.49.2"); fail("confirmation reused") } catch (_: IllegalStateException) { }
            try { attempt.verify(peer(request, server)); fail("attempt reused") } catch (_: IllegalStateException) { }
        }
    }

    @Test fun quickResultCodesMapToFallbacks() {
        val expected = mapOf(1 to QuickCheck.REJECTED_TRY_PIN, 2 to QuickCheck.NEEDS_CONFIRMATION,
            3 to QuickCheck.SERVER_ERROR, 4 to QuickCheck.WORK_MODE_ERROR)
        for ((result, check) in expected) {
            val stored = keyPair(); val server = keyPair()
            ExperimentalQuickAuth(stored, point(server.public as ECPublicKey), "own-id".toByteArray(), "test-phone", 1).use { attempt ->
                val request = attempt.request()
                assertEquals(check, attempt.verify(peer(request, server, result)))
                try { attempt.confirmCipher("192.168.49.2"); fail("unverified confirmation") } catch (_: IllegalStateException) { }
            }
        }
    }

    @Test fun quickBadSignatureAndMissingFieldsAreRejected() {
        val stored = keyPair(); val server = keyPair()
        ExperimentalQuickAuth(stored, point(server.public as ECPublicKey), "own-id".toByteArray(), "test-phone", 1).use { attempt ->
            val request = attempt.request()
            assertEquals(QuickCheck.BAD_SIGNATURE, attempt.verify(peer(request, server, corruptSignature = true)))
        }
        ExperimentalQuickAuth(stored, point(server.public as ECPublicKey), "own-id".toByteArray(), "test-phone", 1).use { attempt ->
            attempt.request()
            assertEquals(QuickCheck.MISSING_OR_INVALID_FIELDS,
                attempt.verify(ExperimentalAuthWire.Response(1793, null, null, null, null, null, 0, 1)))
        }
        ExperimentalQuickAuth(stored, point(server.public as ECPublicKey), "own-id".toByteArray(), "test-phone", 1).use { attempt ->
            attempt.request()
            assertEquals(QuickCheck.BAD_VERSION,
                attempt.verify(ExperimentalAuthWire.Response(1792, null, null, ByteArray(64), ByteArray(70), ByteArray(32), 0, 1)))
        }
    }

    @Test fun quickRejectsCorruptStoredServerKey() {
        val stored = keyPair()
        try {
            ExperimentalQuickAuth(stored, ByteArray(64) { 9 }, "own-id".toByteArray(), "test-phone", 1)
            fail("corrupt stored key accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
