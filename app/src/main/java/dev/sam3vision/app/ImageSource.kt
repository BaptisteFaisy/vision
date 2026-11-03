package dev.sam3vision.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import dev.sam3vision.app.cleanup.CapturePaths
import dev.sam3vision.app.media.jpegBytesToBase64
import kotlin.math.max
import kotlin.math.min

/** Côté client : redimensionne et encode la photo avant envoi (bord max, JPEG, base64). */
const val MAX_IMAGE_EDGE = 1024
const val JPEG_QUALITY = 85

/**
 * Fichier JPEG neuf dans le stockage **privé** de l'app (`Android/data/<pkg>/files/Pictures`),
 * jamais sur le stockage partagé : c'est exactement le périmètre que purge
 * `CaptureCleanupWorker` chaque heure. Le nom garde le préfixe `capture-`.
 */
fun newCacheFile(context: Context): File = CapturePaths.newImageFile(context)

/** Copie l'URI choisi (galerie) vers un fichier du cache interne. */
fun copyUriToCache(context: Context, uri: Uri, target: File): Boolean = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(target).use { output -> input.copyTo(output, 8192) }
    } != null
}.getOrDefault(false)

/** Décode un fichier image en réduisant l'échantillon pour rester sous [maxEdge]. */
fun decodeScaled(file: File, maxEdge: Int = MAX_IMAGE_EDGE): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val sample = max(1, max(bounds.outWidth, bounds.outHeight) / maxEdge)
    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

/** Décode un fichier image, puis le ramène exactement sous [maxEdge] (puissance de 2 insuffisante). */
fun decodeScaledExact(file: File, maxEdge: Int = MAX_IMAGE_EDGE): Bitmap? {
    val decoded = decodeScaled(file, maxEdge) ?: return null
    val longest = max(decoded.width, decoded.height)
    if (longest <= maxEdge) return decoded
    val ratio = maxEdge.toFloat() / longest
    return Bitmap.createScaledBitmap(
        decoded,
        max(1, (decoded.width * ratio).toInt()),
        max(1, (decoded.height * ratio).toInt()),
        true,
    )
}

/** Bitmap → base64 JPEG sans préfixe `data:` (l'encodage base64 vient de `media`). */
fun bitmapToJpegBase64(bitmap: Bitmap, quality: Int = JPEG_QUALITY): String =
    jpegBytesToBase64(bitmapToJpeg(bitmap, quality))

/**
 * Tampon JPEG réutilisé, un par fil : `reset()` au lieu d'un `ByteArrayOutputStream`
 * neuf (et son tableau de 16 Ko) par frame. Le tableau rendu par `toByteArray()`
 * est une copie, donc le réemploi est invisible à l'appelant ; un tampon par fil,
 * pas de verrou (l'encodage ne se réentre pas).
 */
private val jpegScratch = ThreadLocal.withInitial { ByteArrayOutputStream(16 * 1024) }

/** Bitmap → JPEG brut : le direct binaire n'a pas besoin du détour base64. */
fun bitmapToJpeg(bitmap: Bitmap, quality: Int = JPEG_QUALITY): ByteArray {
    // 16 Ko d'emblée : un 320/q55 y tient sans regrow ; le tampon garde sa plus
    // grande taille atteinte (un 480/q65 l'agrandit une fois, puis réemploi).
    val buffer = jpegScratch.get() ?: ByteArrayOutputStream(16 * 1024)
    buffer.reset()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
    return buffer.toByteArray()
}

/** Mini image monochrome pour tester un endpoint LLM sans consommer de crédit. */
fun testBitmap(color: Int = 0xFF2E7D32.toInt()): Bitmap =
    Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

/** Échelle d'affichage : l'overlay doit être dessiné dans les mêmes bornes que la photo. */
fun overlayScaleFor(viewportWidth: Float, bitmapWidth: Int): Float =
    if (bitmapWidth <= 0) 1f else min(1f, viewportWidth / bitmapWidth)

/** Ramène un angle dans `[0, 360)` (les HAL caméra livrent parfois des valeurs négatives). */
fun normalizeDegrees(degrees: Int): Int = ((degrees % 360) + 360) % 360

/** Vrai si la rotation échange largeur et hauteur (90° ou 270°). */
fun isQuarterTurn(degrees: Int): Boolean = normalizeDegrees(degrees) % 180 != 0

/**
 * Taille finale d'une frame après rotation de [degrees] puis réduction au bord [edge] :
 * largeur et hauteur sont échangées sur un quart de tour, l'image n'est jamais agrandie.
 * Fonction **pure** (aucun `Bitmap`), testée en JVM : c'est elle qui pilote la matrice
 * unique d'[uprightScaled].
 */
fun targetSize(width: Int, height: Int, degrees: Int, edge: Int): Pair<Int, Int> {
    val rotatedWidth = if (isQuarterTurn(degrees)) height else width
    val rotatedHeight = if (isQuarterTurn(degrees)) width else height
    val longest = max(rotatedWidth, rotatedHeight)
    if (longest <= 0 || longest <= edge) return rotatedWidth to rotatedHeight
    val ratio = edge.toFloat() / longest
    return max(1, (rotatedWidth * ratio).toInt()) to max(1, (rotatedHeight * ratio).toInt())
}

/** Ce qu'[uprightScaled] doit faire : le cas décide du chemin le moins cher. */
internal enum class UprightOp {
    /** Rien à faire : l'image est déjà droite et sous le bord. */
    IDENTITY,
    /** Droite mais trop grande : simple réduction, sans matrice. */
    SCALE,
    /** À tourner mais sous le bord : rotation seule, sans rééchantillonnage. */
    ROTATE,
    /** À tourner et à réduire : matrice unique, comme avant. */
    ROTATE_SCALE,
}

/**
 * Décision pure d'[uprightScaled] : opération, angle normalisé et taille cible
 * ([targetSize]). Testée en JVM (`UiLogicTest`), le `Bitmap` n'est créé qu'après.
 */
internal data class UprightPlan(val op: UprightOp, val turn: Int, val targetWidth: Int, val targetHeight: Int)

internal fun uprightPlan(width: Int, height: Int, degrees: Int, edge: Int): UprightPlan {
    val turn = normalizeDegrees(degrees)
    val quarterTurn = isQuarterTurn(turn)
    // Dimensions dans le repère déjà tourné : c'est là que l'échelle s'applique.
    val turnedWidth = if (quarterTurn) height else width
    val turnedHeight = if (quarterTurn) width else height
    val (targetWidth, targetHeight) = targetSize(width, height, degrees, edge)
    val scales = targetWidth != turnedWidth || targetHeight != turnedHeight
    val op = when {
        turn == 0 && !scales -> UprightOp.IDENTITY
        turn == 0 -> UprightOp.SCALE
        !scales -> UprightOp.ROTATE
        else -> UprightOp.ROTATE_SCALE
    }
    return UprightPlan(op, turn, targetWidth, targetHeight)
}

/**
 * Redresse **et** réduit en une seule passe : rotation [degrees] et échelle au bord [edge]
 * dans la même matrice, donc un seul rééchantillonnage — là où `rotateBitmap` puis
 * `scaleToEdge` créaient deux bitmaps (≈ 2,7 ms et un alloc par frame économisés).
 * Renvoie [src] tel quel si rien ne change ; une réduction seule passe par
 * `createScaledBitmap` (sans matrice) et une rotation seule sans filtre
 * (un quart de tour exact n'a rien à rééchantillonner).
 */
fun uprightScaled(src: Bitmap, degrees: Int, edge: Int): Bitmap {
    if (src.width <= 0 || src.height <= 0) return src
    val plan = uprightPlan(src.width, src.height, degrees, edge)
    when (plan.op) {
        UprightOp.IDENTITY -> return src
        UprightOp.SCALE ->
            return Bitmap.createScaledBitmap(src, plan.targetWidth, plan.targetHeight, true)
        UprightOp.ROTATE -> {
            val matrix = Matrix().apply { postRotate(plan.turn.toFloat()) }
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, false)
        }
        UprightOp.ROTATE_SCALE -> {
            val quarterTurn = isQuarterTurn(plan.turn)
            val turnedWidth = if (quarterTurn) src.height else src.width
            val turnedHeight = if (quarterTurn) src.width else src.height
            val matrix = Matrix().apply {
                postRotate(plan.turn.toFloat())
                postScale(plan.targetWidth.toFloat() / turnedWidth, plan.targetHeight.toFloat() / turnedHeight)
            }
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        }
    }
}
