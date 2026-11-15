package org.aionlink.iccoa

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

enum class QuickCheck {
    VERIFIED, REJECTED_TRY_PIN, NEEDS_CONFIRMATION, SERVER_ERROR, WORK_MODE_ERROR,
    BAD_VERSION, MISSING_OR_INVALID_FIELDS, BAD_SIGNATURE
}

/** QUICK reconnect (Xiaomi ClientKeyNegotiator, decompiled 2026-11-16): the phone proves itself with an
 * ECDSA signature by the long-term auth key created at first (PIN) pairing, instead of a PIN proof.
 * The request carries empty authPk/authPkHmac and confirmed=false; the car verifies the signature
 * against the auth public key it stored at first pairing. A response that presents an authPk/proof is
 * not trusted on its face: without a PIN the proof cannot be verified, so the agreement signature is
 * always checked against the stored server key. Single-use candidate; the stored keypair is owned by
 * the caller (Android Keystore) and never wiped here. */
class ExperimentalQuickAuth(storedAuthPair: KeyPair, storedServerAuthPoint: ByteArray,
                            clientId: ByteArray, model: String, workModes: Int) : AutoCloseable {
    private val authPair = storedAuthPair
    private val serverAuthPoint = storedServerAuthPoint.copyOf()
    private val serverAuthKey: ECPublicKey
    private var agreementPair: KeyPair? = null
    private val nonce = ByteArray(32)
    private val id = clientId.copyOf()
    private val phoneModel = model
    private val modes = workModes
    private var issued = false
    private var sessionKey: ByteArray? = null
    init {
        require(serverAuthPoint.size == 64)
        serverAuthKey = ExperimentalAuthCrypto.decodePublicPoint(serverAuthPoint)
        require(id.size in 1..64 && phoneModel.toByteArray(Charsets.UTF_8).size in 1..64 && modes >= 0)
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        agreementPair = generator.generateKeyPair()
        SecureRandom().nextBytes(nonce)
    }

    fun request(userConfirmed: Boolean = false): ExperimentalAuthWire.Request {
        check(!issued) { "Attempt unavailable" }
        issued = true
        try {
            val agreement = agreementPair ?: error("Attempt unavailable")
            val agreementPublic = encode(agreement.public as ECPublicKey)
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(authPair.private)
            signer.update(agreementPublic); signer.update(nonce)
            return ExperimentalAuthWire.Request(1793, ByteArray(0), ByteArray(0),
                agreementPublic, signer.sign(), nonce.copyOf(), id.copyOf(), phoneModel, userConfirmed, modes, "AionLink Open")
        } catch (error: Exception) { close(); throw error }
    }

    /** Consumes the attempt regardless of outcome. Result 1/2/3/4 are the receiver's QUICK verdicts:
     * 1 = quick rejected, delete the peer record and fall back to the PIN; 2 = the receiver asks for a
     * user confirmation before accepting quick; 3 = server error, delete the peer record; 4 = the
     * requested work modes are not accepted. */
    fun verify(response: ExperimentalAuthWire.Response): QuickCheck {
        check(issued && agreementPair != null) { "Attempt unavailable" }
        try {
            if (response.version != 1793) return QuickCheck.BAD_VERSION
            when (response.result ?: 0) {
                1 -> return QuickCheck.REJECTED_TRY_PIN
                2 -> return QuickCheck.NEEDS_CONFIRMATION
                3 -> return QuickCheck.SERVER_ERROR
                4 -> return QuickCheck.WORK_MODE_ERROR
                0 -> Unit
                else -> return QuickCheck.BAD_VERSION
            }
            val peerAgreement = response.agreementPoint ?: return QuickCheck.MISSING_OR_INVALID_FIELDS
            val peerSignature = response.agreementSignature ?: return QuickCheck.MISSING_OR_INVALID_FIELDS
            val peerNonce = response.nonce ?: return QuickCheck.MISSING_OR_INVALID_FIELDS
            if (peerAgreement.size != 64 || peerNonce.size != 32 || peerSignature.size !in 8..80)
                return QuickCheck.MISSING_OR_INVALID_FIELDS
            val localAgreement = encode(agreementPair!!.public as ECPublicKey)
            try { ExperimentalAuthCrypto.decodePublicPoint(peerAgreement) } catch (_: Exception) { return QuickCheck.MISSING_OR_INVALID_FIELDS }
            if (!ExperimentalAuthCrypto.verifyPeerAgreementSignature(serverAuthKey, peerAgreement, localAgreement, peerNonce, nonce, peerSignature))
                return QuickCheck.BAD_SIGNATURE
            sessionKey = deriveSessionKey(peerAgreement, peerNonce)
            return QuickCheck.VERIFIED
        } finally { wipeNegotiation() }
    }

    /** Samsung KeyNegotiator.agreeOnSessionKey: HKDF-SHA256(ECDH, SHA-256(local || peer nonce), "session_key", 16). */
    private fun deriveSessionKey(peerAgreement: ByteArray, peerNonce: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(agreementPair!!.private)
        agreement.doPhase(ExperimentalAuthCrypto.decodePublicPoint(peerAgreement), true)
        val secret = agreement.generateSecret()
        val salt = MessageDigest.getInstance("SHA-256").run { update(nonce); digest(peerNonce) }
        try { return ExperimentalAuthCrypto.hkdfSha256(secret, salt, "session_key".toByteArray(Charsets.UTF_8), 16) }
        finally { secret.fill(0); salt.fill(0) }
    }

    /** Copy of the derived session key for the caller's encrypted channels; the caller must wipe it. */
    fun sessionKeyCopy(): ByteArray = (sessionKey ?: throw IllegalStateException("Session key unavailable")).copyOf()

    /** Observed AuthConfirm cipher: session-sealed UTF-8 "<local group address>:0". Single use. */
    fun confirmCipher(localAddress: String): ByteArray {
        val key = sessionKey ?: throw IllegalStateException("Session key unavailable")
        require(localAddress.isNotEmpty() && localAddress.length <= 64)
        val plain = "$localAddress:0".toByteArray(Charsets.UTF_8)
        try { return ExperimentalAuthCrypto.sessionSeal(key, plain) }
        finally { plain.fill(0); key.fill(0); sessionKey = null }
    }

    private fun wipeNegotiation() {
        serverAuthPoint.fill(0); nonce.fill(0); id.fill(0)
        agreementPair = null
    }
    override fun close() {
        wipeNegotiation()
        sessionKey?.fill(0); sessionKey = null
    }
    override fun toString() = "ExperimentalQuickAuth(redacted)"
    private fun encode(key: ECPublicKey): ByteArray = coordinate(key.w.affineX) + coordinate(key.w.affineY)
    private fun coordinate(n: BigInteger): ByteArray {
        val value = n.toByteArray()
        require(value.size <= 33 && (value.size != 33 || value[0] == 0.toByte()))
        val count = minOf(32, value.size)
        return ByteArray(32).also { System.arraycopy(value, value.size - count, it, 32 - count, count) }
    }
}
