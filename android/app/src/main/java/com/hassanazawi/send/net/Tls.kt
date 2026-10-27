package com.hassanazawi.send.net

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * The phone's identity: an EC key that never leaves the Android Keystore, with the self-signed
 * certificate the Keystore creates for it. The PC pins this certificate's SHA-256 at pairing.
 */
object Tls {
    private const val ALIAS = "send_tls_v2"

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    private fun ensureKey() {
        if (keystore().containsAlias(ALIAS)) return
        val now = System.currentTimeMillis()
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        kpg.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                // Conscrypt hands the TLS stack's precomputed digest to NONEwithECDSA, so NONE must be allowed
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(X500Principal("CN=Send"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(java.util.Date(now - 86_400_000L))
                .setCertificateNotAfter(java.util.Date(now + 20L * 365 * 86_400_000L))
                .build()
        )
        kpg.generateKeyPair()
    }

    /** Always answers with the one Keystore key, whatever the client asks for. */
    private class OneKey(private val ks: KeyStore) : X509ExtendedKeyManager() {
        private val chain: Array<X509Certificate> by lazy { arrayOf(ks.getCertificate(ALIAS) as X509Certificate) }
        private val key: PrivateKey by lazy { ks.getKey(ALIAS, null) as PrivateKey }

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String {
            Engine.dbg("tls: chooseServerAlias($keyType)")
            return ALIAS
        }
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String {
            Engine.dbg("tls: chooseEngineServerAlias($keyType)")
            return ALIAS
        }
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getCertificateChain(alias: String?): Array<X509Certificate> {
            Engine.dbg("tls: getCertificateChain")
            return chain
        }
        override fun getPrivateKey(alias: String?): PrivateKey {
            Engine.dbg("tls: getPrivateKey ${key.algorithm}")
            return key
        }
    }

    fun context(): SSLContext {
        ensureKey()
        return SSLContext.getInstance("TLS").apply { init(arrayOf(OneKey(keystore())), null, null) }
    }

    fun fingerprint(): String {
        ensureKey()
        return sha256(keystore().getCertificate(ALIAS).encoded).toHex()
    }
}
