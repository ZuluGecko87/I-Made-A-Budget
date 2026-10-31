package com.z.imab.platform

/** Platform cryptography (javax.crypto on Android and desktop). */
expect object Crypto {
    /** PBKDF2-HMAC-SHA256: a 256-bit key from a passphrase. Deliberately slow. */
    fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray

    /** AES-256-GCM with a fresh random 96-bit IV. Returns IV followed by ciphertext and tag. */
    fun encrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray

    /** Reverses [encrypt]. Throws if the key is wrong or the data was changed. */
    fun decrypt(key: ByteArray, sealed: ByteArray, associatedData: ByteArray): ByteArray

    fun randomBytes(count: Int): ByteArray
}
