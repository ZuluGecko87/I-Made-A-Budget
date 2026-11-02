package com.z.imab.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.CoreCrypto.kCCSuccess
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import platform.posix.memcpy

/**
 * iPhone cryptography: PBKDF2 and random bytes from CommonCrypto and Security; AES-GCM from
 * CryptoKit through [Native.services]. The output is byte-for-byte the same format as on Android
 * and desktop, so every device opens the same encrypted budget.
 */
@OptIn(ExperimentalForeignApi::class)
actual object Crypto {
    actual fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        // javax.crypto's PBKDF2 uses the passphrase's UTF-8 bytes too.
        val password = passphrase.concatToString().encodeToByteArray()
        val out = ByteArray(32)
        val status = password.usePinned { p ->
            salt.usePinned { s ->
                out.usePinned { o ->
                    CCKeyDerivationPBKDF(
                        kCCPBKDF2, p.addressOf(0).reinterpret(), password.size.convert(),
                        s.addressOf(0).reinterpret(), salt.size.convert(),
                        kCCPRFHmacAlgSHA256, iterations.convert(),
                        o.addressOf(0).reinterpret(), out.size.convert(),
                    )
                }
            }
        }
        password.fill(0)
        check(status == kCCSuccess) { "Couldn't derive the key ($status)." }
        return out
    }

    actual fun encrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        Native.services.sealAesGcm(key.toNSData(), plaintext.toNSData(), associatedData.toNSData())?.toByteArray()
            ?: error("Couldn't encrypt.")

    actual fun decrypt(key: ByteArray, sealed: ByteArray, associatedData: ByteArray): ByteArray {
        require(sealed.size > 12) { "Encrypted data is too short." }
        return Native.services.openAesGcm(key.toNSData(), sealed.toNSData(), associatedData.toNSData())?.toByteArray()
            ?: throw IllegalStateException("Wrong key, or the data was changed.")
    }

    actual fun randomBytes(count: Int): ByteArray {
        val out = ByteArray(count)
        if (count == 0) return out
        val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, count.convert(), it.addressOf(0)) }
        check(status == errSecSuccess) { "No secure random numbers ($status)." }
        return out
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
