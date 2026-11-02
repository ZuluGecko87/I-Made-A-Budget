package com.z.imab

import com.z.imab.platform.Crypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Every platform must turn a passphrase into the same key, or a budget encrypted on one device
 * wouldn't open on another. Checked against RFC 7914's PBKDF2-HMAC-SHA256 test vectors.
 */
class KeyDerivationTest {
    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun matchesThePublishedVectors() {
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc",
            hex(Crypto.deriveKey("passwd".toCharArray(), "salt".encodeToByteArray(), 1)),
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56",
            hex(Crypto.deriveKey("Password".toCharArray(), "NaCl".encodeToByteArray(), 80000)),
        )
    }

    @Test
    fun nonAsciiPassphrasesUseUtf8() {
        // Accented letters must become the same bytes everywhere (UTF-8); computed independently.
        assertEquals(
            "af1ae7eaf673473312d5efc37548ecc8961b62c9045ba1b41f82f233f4572caf",
            hex(Crypto.deriveKey("café crème brûlée".toCharArray(), "salt".encodeToByteArray(), 1000)),
        )
    }

    @Test
    fun randomBytesAreRandom() {
        val a = Crypto.randomBytes(16)
        assertEquals(16, a.size)
        assertFalse(a.contentEquals(Crypto.randomBytes(16)))
    }
}
