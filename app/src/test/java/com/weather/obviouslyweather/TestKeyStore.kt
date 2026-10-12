// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.*
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/** JVM stand-in for hardware key storage; encryption still uses the normal JCE AES/GCM cipher. */
internal class TestKeyStoreProvider : Provider("AndroidKeyStore", 1.0, "Test key storage") {
    init {
        put("KeyStore.AndroidKeyStore", TestKeyStore::class.java.name)
        put("KeyGenerator.AES", TestAesGenerator::class.java.name)
    }
}

class TestKeyStore : KeyStoreSpi() {
    companion object { val keys = ConcurrentHashMap<String, SecretKey>() }
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
    override fun engineGetCertificate(alias: String): Certificate? = null
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
    override fun engineGetCreationDate(alias: String) = Date(0)
    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<Certificate>?) { keys[alias] = key as SecretKey }
    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<Certificate>?) = throw UnsupportedOperationException()
    override fun engineSetCertificateEntry(alias: String, certificate: Certificate) = throw UnsupportedOperationException()
    override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
    override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys)
    override fun engineContainsAlias(alias: String) = keys.containsKey(alias)
    override fun engineSize() = keys.size
    override fun engineIsKeyEntry(alias: String) = keys.containsKey(alias)
    override fun engineIsCertificateEntry(alias: String) = false
    override fun engineGetCertificateAlias(certificate: Certificate): String? = null
}

class TestAesGenerator : KeyGeneratorSpi() {
    private var alias = ""
    override fun engineInit(random: SecureRandom?) = Unit
    override fun engineInit(size: Int, random: SecureRandom?) = Unit
    override fun engineInit(parameters: AlgorithmParameterSpec, random: SecureRandom?) {
        alias = (parameters as KeyGenParameterSpec).keystoreAlias
    }
    override fun engineGenerateKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        .also { TestKeyStore.keys[alias] = it }
}
