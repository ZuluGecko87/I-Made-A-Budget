package com.z.imab.platform

import platform.Foundation.NSData

/**
 * What only Swift can do on iPhone, supplied by the app (iosApp/iosApp/NativeServices.swift):
 * AES-GCM from CryptoKit, and the Keychain.
 */
interface NativeServices {
    /** AES-256-GCM with a fresh random 96-bit nonce: nonce, then ciphertext, then the 16-byte tag. */
    fun sealAesGcm(key: NSData, plaintext: NSData, associatedData: NSData): NSData?

    /** Reverses [sealAesGcm]; null if the key is wrong or the data was changed. */
    fun openAesGcm(key: NSData, sealed: NSData, associatedData: NSData): NSData?

    fun loadKey(): String?

    fun saveKey(value: String): Boolean

    fun deleteKey()
}

/** Set once at launch, before anything is read or encrypted. */
object Native {
    lateinit var services: NativeServices
}

/** For the Xcode tests, which use the shared code without starting the app. */
fun installServicesForTests(services: NativeServices) {
    Native.services = services
}
