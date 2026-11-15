package org.aionlink.open

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import org.aionlink.iccoa.ExperimentalAuthCrypto
import org.json.JSONObject

/** Stored QUICK reconnect state: one long-term auth key per car in the Android Keystore, plus the car's
 * auth public key in a private preferences file. Keyed by the car's BLE address, the same stable
 * identity discovery already uses. Values are never logged or included in diagnostics exports. */
class QuickConnectStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("aionlink_peers", Context.MODE_PRIVATE)

    data class PeerRecord(val serverAuthPoint: ByteArray, val authentication: String?, val mac: String?) {
        override fun toString() = "PeerRecord(redacted)"
    }

    fun peerRecord(serverId: String): PeerRecord? = try {
        val raw = preferences.getString(serverId, null) ?: return null
        val json = JSONObject(raw)
        if (json.optInt("v", 0) != 1) return null
        val point = Base64.decode(json.getString("serverAuthPk"), Base64.NO_WRAP)
        if (point.size != 64) return null
        ExperimentalAuthCrypto.decodePublicPoint(point) // rejects a corrupt stored point
        PeerRecord(point, json.optString("authentication", null)?.takeIf { it.isNotEmpty() },
            json.optString("mac", null)?.takeIf { it.isNotEmpty() })
    } catch (_: Exception) { null }

    fun savePeerRecord(serverId: String, serverAuthPoint: ByteArray, authentication: String?, mac: String?) {
        try {
            val json = JSONObject().put("v", 1)
                .put("serverAuthPk", Base64.encodeToString(serverAuthPoint, Base64.NO_WRAP))
            if (!authentication.isNullOrEmpty()) json.put("authentication", authentication)
            if (!mac.isNullOrEmpty()) json.put("mac", mac)
            preferences.edit().putString(serverId, json.toString()).apply()
        } catch (_: Exception) { }
    }

    fun deletePeerRecord(serverId: String) {
        try { preferences.edit().remove(serverId).apply() } catch (_: Exception) { }
    }

    fun peerCount(): Int = try { preferences.all.size } catch (_: Exception) { 0 }

    /** Returns the stored auth key for this car, creating it on first use. Null on any Keystore failure. */
    fun authKeyPair(serverId: String): KeyPair? = try {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = aliasFor(serverId)
        val entry = store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        if (entry != null) KeyPair(entry.certificate.publicKey, entry.privateKey)
        else KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).build())
        }.generateKeyPair()
    } catch (_: Exception) { null }

    fun deleteAuthKey(serverId: String) {
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry(aliasFor(serverId))
        } catch (_: Exception) { }
    }

    private fun aliasFor(serverId: String) = "client_auth_key_for_server_$serverId"
}
