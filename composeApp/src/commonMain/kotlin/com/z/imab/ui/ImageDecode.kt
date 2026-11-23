package com.z.imab.ui

import androidx.compose.ui.graphics.ImageBitmap

/** A JPEG or PNG as an image to show, or null if it can't be read. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?
