package com.z.imab

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the budget key on the phone, encrypted with a key held by the Android Keystore. That key
 * never leaves the Keystore (hardware-backed where the phone supports it), so the stored file is
 * useless if copied off the device.
 */
internal class AndroidKeyVault(private val file: File) {
    private val alias = "imab-budget-key-wrap"

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun load(): String? = runCatching {
        if (!file.isFile) return null
        val bytes = file.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, bytes, 0, 12))
        cipher.doFinal(bytes, 12, bytes.size - 12).decodeToString()
    }.getOrNull()

    fun save(value: String): Boolean = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(cipher.iv + cipher.doFinal(value.encodeToByteArray()))
        if (!tmp.renameTo(file)) error("Couldn't save the key")
        true
    }.getOrDefault(false)

    fun delete() {
        file.delete()
    }
}
