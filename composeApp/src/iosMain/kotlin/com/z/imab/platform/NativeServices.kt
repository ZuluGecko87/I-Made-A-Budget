package com.z.imab.platform

import platform.Foundation.NSData

/**
 * What only Swift can do on iPhone, supplied by the app (iosApp/iosApp/NativeServices.swift):
 * AES-GCM from CryptoKit, the Keychain, and receipts (camera, Photos, Vision, the share sheet).
 */
interface NativeServices {
    /** AES-256-GCM with a fresh random 96-bit nonce: nonce, then ciphertext, then the 16-byte tag. */
    fun sealAesGcm(key: NSData, plaintext: NSData, associatedData: NSData): NSData?

    /** Reverses [sealAesGcm]; null if the key is wrong or the data was changed. */
    fun openAesGcm(key: NSData, sealed: NSData, associatedData: NSData): NSData?

    fun loadKey(): String?

    fun saveKey(value: String): Boolean

    fun deleteKey()

    fun hasCamera(): Boolean

    /**
     * Photographs a receipt (the document camera, which finds and flattens the page) or picks one
     * from Photos. [done] gets an upright JPEG scaled down to keep, or null if cancelled, or an
     * error message. Called on the main thread.
     */
    fun captureReceipt(camera: Boolean, done: (NSData?, String?) -> Unit)

    /**
     * Reads the text in a JPEG with Vision. [done] gets one line per piece of text, "x, y, width,
     * height, text" separated by tabs (fractions of the image, y from the top), or null on failure.
     */
    fun recognizeText(jpeg: NSData, done: (String?) -> Unit)

    /** Shows the share sheet for the file at [path]. [done] says whether it was sent or saved. */
    fun shareFile(path: String, done: (Boolean) -> Unit)
}

/** Set once at launch, before anything is read or encrypted. */
object Native {
    lateinit var services: NativeServices
}

/** For the Xcode tests, which use the shared code without starting the app. */
fun installServicesForTests(services: NativeServices) {
    Native.services = services
}
