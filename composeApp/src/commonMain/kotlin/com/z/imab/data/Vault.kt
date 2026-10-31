package com.z.imab.data

import com.z.imab.platform.Crypto
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * An encrypted budget file. The ledger JSON is sealed with AES-256-GCM under a key derived from the
 * passphrase (PBKDF2-HMAC-SHA256, [iterations] rounds, random [salt]). Every device that knows the
 * passphrase derives the same key from the salt in the file; nothing else is shared.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class EncryptedFile(
    @EncodeDefault val format: String = FORMAT,
    @EncodeDefault val version: Int = 1,
    @EncodeDefault val kdf: String = KDF,
    val iterations: Int,
    /** Base64. */
    val salt: String,
    /** Base64 of IV + ciphertext + tag. */
    val data: String,
) {
    companion object {
        const val FORMAT = "imab-encrypted"
        const val KDF = "PBKDF2-HMAC-SHA256"
    }
}

/** A key derived from the passphrase, for the budget whose files use [salt]. */
class VaultKey(val salt: String, val iterations: Int, internal val key: ByteArray) {
    /** For keeping in the system's secure storage on this device. */
    fun export(): String = ImabJson.encodeToString(Stored.serializer(), Stored(salt, iterations, Base64.encode(key)))

    @Serializable
    private data class Stored(val salt: String, val iterations: Int, val key: String)

    companion object {
        fun import(text: String): VaultKey? = runCatching {
            val s = ImabJson.decodeFromString(Stored.serializer(), text)
            VaultKey(s.salt, s.iterations, Base64.decode(s.key)).takeIf { it.key.size == 32 }
        }.getOrNull()
    }
}

/** The file is encrypted and this device doesn't have the key for it. */
class VaultLockedException(val file: EncryptedFile) : Exception("This budget is protected with a passphrase.")

object Vault {
    /** OWASP's recommendation for PBKDF2-HMAC-SHA256. */
    const val ITERATIONS = 600_000
    const val MIN_PASSPHRASE = 10

    /** The encryption wrapper, if [text] is an encrypted IMAB file. */
    fun parse(text: String): EncryptedFile? {
        val t = text.trimStart()
        if (!t.startsWith("{") || !t.contains(EncryptedFile.FORMAT)) return null
        return runCatching { ImabJson.decodeFromString(EncryptedFile.serializer(), Ledger.firstJsonObject(t)) }
            .getOrNull()?.takeIf { it.format == EncryptedFile.FORMAT }
    }

    fun isEncrypted(text: String) = parse(text) != null

    /** A new key with a fresh salt (setting or changing the passphrase). Slow: call off the main thread. */
    fun newKey(passphrase: String): VaultKey {
        val salt = Base64.encode(Crypto.randomBytes(16))
        return VaultKey(salt, ITERATIONS, Crypto.deriveKey(passphrase.toCharArray(), Base64.decode(salt), ITERATIONS))
    }

    /** The key for [file] from [passphrase], or null if the passphrase is wrong. Slow. */
    fun unlock(file: EncryptedFile, passphrase: String): VaultKey? {
        if (file.kdf != EncryptedFile.KDF || file.iterations < 100_000) return null
        val key = VaultKey(file.salt, file.iterations, Crypto.deriveKey(passphrase.toCharArray(), Base64.decode(file.salt), file.iterations))
        return key.takeIf { runCatching { open(file, it) }.isSuccess }
    }

    fun seal(plaintext: String, key: VaultKey): String {
        val file = EncryptedFile(iterations = key.iterations, salt = key.salt, data = "")
        val sealed = Crypto.encrypt(key.key, plaintext.encodeToByteArray(), aad(file))
        return ImabJson.encodeToString(EncryptedFile.serializer(), file.copy(data = Base64.encode(sealed)))
    }

    /** Decrypts [file]; throws if [key] isn't its key or the file was altered. */
    fun open(file: EncryptedFile, key: VaultKey): String {
        if (key.salt != file.salt || key.iterations != file.iterations) throw VaultLockedException(file)
        return Crypto.decrypt(key.key, Base64.decode(file.data), aad(file)).decodeToString()
    }

    /**
     * The plaintext of a stored file: as is when it isn't encrypted, decrypted with [key] when it is.
     * Throws [VaultLockedException] when it's encrypted and [key] can't open it.
     */
    fun reveal(text: String, key: VaultKey?): String {
        val file = parse(text) ?: return text
        if (key == null) throw VaultLockedException(file)
        return runCatching { open(file, key) }.getOrElse { throw VaultLockedException(file) }
    }

    /** The header is bound to the ciphertext, so its values can't be swapped without detection. */
    private fun aad(file: EncryptedFile) =
        "${file.format}|v${file.version}|${file.kdf}|${file.iterations}|${file.salt}".encodeToByteArray()
}
