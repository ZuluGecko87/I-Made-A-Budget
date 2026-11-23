package com.z.imab

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.z.imab.receipt.OcrLine
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/** Receipt photos on Android: made upright and small, and read with ML Kit on the phone. */
internal object AndroidReceipts {
    private const val MAX_SIDE = 1600

    /** The photo at [uri] as a JPEG, the right way up, at most [MAX_SIDE] on its longest side. */
    fun normalize(resolver: ContentResolver, uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Can't read that photo.")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Can't read that photo.")
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
        }
        val factor = minOf(1f, MAX_SIDE.toFloat() / max(decoded.width, decoded.height))
        m.postScale(factor, factor)
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        val bytes = ByteArrayOutputStream().use { s -> out.compress(Bitmap.CompressFormat.JPEG, 80, s); s.toByteArray() }
        if (out !== decoded) out.recycle()
        decoded.recycle()
        return bytes
    }

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** The text in [jpeg], with each line's box as fractions of the photo. */
    suspend fun readText(jpeg: ByteArray): List<OcrLine> {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("Can't read that photo.")
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val text = suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        return text.textBlocks.flatMap { it.lines }.map { line ->
            val b = line.boundingBox
            if (b == null) OcrLine(line.text) else OcrLine(line.text, b.left / w, b.top / h, b.width() / w, b.height() / h)
        }.also { bitmap.recycle() }
    }
}
