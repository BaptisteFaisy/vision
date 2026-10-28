package dev.sam3vision.app.isee

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import dev.sam3vision.app.decodeScaledExact
import dev.sam3vision.core.picto.GrayImage
import dev.sam3vision.core.picto.PictoTemplate
import dev.sam3vision.core.picto.buildTemplate
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/** Côté max d'une frame de reconnaissance : 96 px suffisent au gabarit 24 px. */
const val PICTO_FRAME_EDGE = 96

/** Côté max de la photo envoyée à la génération : le détail sans le poids. */
const val PICTO_PHOTO_EDGE = 768

/** Qualité JPEG de la photo envoyée : objet net, upload de quelques centaines de Ko. */
private const val PICTO_PHOTO_QUALITY = 75

/**
 * Frame de la sonde (JPEG base64) → niveaux de gris réduits pour le matcher.
 * `null` si indécodable : la frame suivante arrive aussitôt, rien à signaler.
 */
fun decodeFrameGray(jpegB64: String, edge: Int = PICTO_FRAME_EDGE): GrayImage? {
    return runCatching {
        val bytes = Base64.decode(jpegB64, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
        }) ?: return null
        bitmapGray(bitmap, edge).also { bitmap.recycle() }
    }.getOrElse { null }
}

/** Bitmap → niveaux de gris, côté max [edge] (ratio conservé). */
fun bitmapGray(bitmap: Bitmap, edge: Int): GrayImage {
    val longest = max(bitmap.width, bitmap.height)
    val scaled = if (longest <= edge) {
        bitmap
    } else {
        Bitmap.createScaledBitmap(bitmap, bitmap.width * edge / longest, bitmap.height * edge / longest, true)
    }
    return try {
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        GrayImage(IntArray(pixels.size) { i -> luminance(pixels[i]) }, scaled.width, scaled.height)
    } finally {
        if (scaled !== bitmap) scaled.recycle()
    }
}

/** Photo → JPEG d'envoi, `null` si illisible. */
fun photoToJpeg(file: File, edge: Int = PICTO_PHOTO_EDGE): ByteArray? {
    val bitmap = runCatching { decodeScaledExact(file, edge) }.getOrNull() ?: return null
    return try {
        ByteArrayOutputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, PICTO_PHOTO_QUALITY, out)) null
            else out.toByteArray().takeIf { it.isNotEmpty() }
        }
    } finally {
        bitmap.recycle()
    }
}

/** Photo → gabarit de reconnaissance (carré central réduit, voir [buildTemplate]). */
fun templateFromPhoto(file: File): PictoTemplate? {
    val bitmap = runCatching { decodeScaledExact(file, PICTO_PHOTO_EDGE) }.getOrNull() ?: return null
    return try {
        buildTemplate(bitmapGray(bitmap, PICTO_PHOTO_EDGE))
    } finally {
        bitmap.recycle()
    }
}

/** Luminance REC.601 d'un pixel ARGB, 0..255. */
private fun luminance(argb: Int): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
}
