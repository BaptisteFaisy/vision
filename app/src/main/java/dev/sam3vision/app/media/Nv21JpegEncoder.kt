package dev.sam3vision.app.media

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import dev.sam3vision.app.isQuarterTurn
import dev.sam3vision.app.normalizeDegrees
import dev.sam3vision.app.targetSize
import dev.sam3vision.core.local.LumaPlane
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64

/**
 * Un plan de la frame caméra (YUV 4:2:0), décrit **sans dépendance Android** : c'est
 * ce qui rend la copie NV21 testable en JVM.
 *
 * [base] est l'index du premier octet utile dans [bytes] — la `position` du
 * `ByteBuffer` de CameraX, qui n'est pas toujours nulle. [rowStride] et [pixelStride]
 * sont ceux de `ImageProxy.PlaneProxy` : le pas de pixel vaut 1 pour le plan Y, 2 pour
 * les plans de chroma sur la plupart des pilotes.
 */
class YuvPlane(
    val bytes: ByteBuffer,
    val base: Int,
    val rowStride: Int,
    val pixelStride: Int,
) {
    /** Octet du plan à la ligne [row], colonne [col] ; 0 si la position est hors buffer. */
    fun at(row: Int, col: Int): Byte {
        val index = base + row * rowStride + col * pixelStride
        return if (index in 0 until bytes.limit()) bytes.get(index) else 0
    }
}

/** Les trois plans d'une frame caméra, avec la taille de l'image. */
class Yuv420Planes(
    val width: Int,
    val height: Int,
    val y: YuvPlane,
    val u: YuvPlane,
    val v: YuvPlane,
)

/**
 * Taille de l'image envoyée : celle de [targetSize] (rotation, puis réduction au bord
 * [edge], jamais agrandie) — donc **exactement** la taille de l'ancien chemin `Bitmap`,
 * les masques renvoyés par le serveur et les échelles de l'overlay restent valables —
 * arrondie à l'inférieur sur des dimensions **paires** (le NV21 n'a pas de chroma pour
 * une ligne impaire).
 */
fun nv21TargetSize(width: Int, height: Int, degrees: Int, edge: Int): Pair<Int, Int> {
    val (w, h) = targetSize(width, height, degrees, edge)
    return maxOf(2, w - w % 2) to maxOf(2, h - h % 2)
}

/**
 * Recopie les plans YUV 4:2:0 dans un buffer **NV21** — plan Y, puis V et U
 * entrelacés (V en premier : c'est l'ordre NV21) — en appliquant la rotation [degrees]
 * et la réduction vers [destWidth]×[destHeight] **dans la même passe**.
 *
 * Aucun `Bitmap` n'est créé : plus de conversion YUV→ARGB (≈ 1,2 Mo alloués par
 * frame), plus de matrice de rotation (2ᵉ bitmap). L'échantillonnage est au plus proche
 * voisin : la source est lue aux positions `x * largeurTournée / destWidth` (idem en
 * hauteur), donc la rotation et la réduction ne coûtent qu'une copie.
 *
 * @return le nombre d'octets écrits (`destWidth * destHeight * 3 / 2`), ou -1 si l'entrée
 *   est incohérente — dans ce cas [out] ne contient rien de valide.
 */
fun packNv21(
    planes: Yuv420Planes,
    degrees: Int,
    destWidth: Int,
    destHeight: Int,
    out: ByteArray,
): Int {
    val width = planes.width
    val height = planes.height
    if (width <= 0 || height <= 0 || destWidth <= 0 || destHeight <= 0) return -1
    if (destWidth % 2 != 0 || destHeight % 2 != 0) return -1
    val size = destWidth * destHeight * 3 / 2
    if (out.size < size) return -1
    val turn = normalizeDegrees(degrees)
    val turned = isQuarterTurn(turn)
    val turnedWidth = if (turned) height else width
    val turnedHeight = if (turned) width else height
    val written = copyLuma(planes, turn, turnedWidth, turnedHeight, destWidth, destHeight, out) +
        copyChroma(planes, turn, turnedWidth, turnedHeight, destWidth, destHeight, out)
    return if (written == size) size else -1
}

/**
 * Plan Y : une copie par pixel de destination, la source lue à la position
 * correspondante dans le repère tourné.
 */
private fun copyLuma(
    planes: Yuv420Planes,
    turn: Int,
    turnedWidth: Int,
    turnedHeight: Int,
    destWidth: Int,
    destHeight: Int,
    out: ByteArray,
): Int {
    // Colonnes sources calculées une fois (`destWidth` divisions), pas une fois par
    // ligne (`destWidth × destHeight`) : même formule, même échantillonnage.
    val xs = IntArray(destWidth) { x -> x * turnedWidth / destWidth }
    var offset = 0
    for (y in 0 until destHeight) {
        val ys = y * turnedHeight / destHeight
        for (x in 0 until destWidth) {
            val row = sourceRow(turn, xs[x], ys, planes.width, planes.height)
            val col = sourceCol(turn, xs[x], ys, planes.width, planes.height)
            out[offset++] = planes.y.at(row, col)
        }
    }
    return offset
}

/**
 * Chroma : une paire V,U par cellule de 2×2 pixels, à la position source de la
 * chroma (ligne et colonne divisées par deux).
 */
private fun copyChroma(
    planes: Yuv420Planes,
    turn: Int,
    turnedWidth: Int,
    turnedHeight: Int,
    destWidth: Int,
    destHeight: Int,
    out: ByteArray,
): Int {
    // Idem luma : colonnes chroma calculées une fois par appel.
    val xs = IntArray(destWidth / 2) { cx -> (cx * 2) * turnedWidth / destWidth }
    var offset = destWidth * destHeight
    for (cy in 0 until destHeight / 2) {
        val ys = (cy * 2) * turnedHeight / destHeight
        for (cx in 0 until destWidth / 2) {
            val row = sourceRow(turn, xs[cx], ys, planes.width, planes.height) / 2
            val col = sourceCol(turn, xs[cx], ys, planes.width, planes.height) / 2
            out[offset++] = planes.v.at(row, col)
            out[offset++] = planes.u.at(row, col)
        }
    }
    return offset - destWidth * destHeight
}

/**
 * Ligne **source** du pixel de destination (x, y) exprimé dans le repère tourné.
 * Les rotations se font dans le sens horaire, comme la matrice de l'ancien chemin.
 */
private fun sourceRow(turn: Int, x: Int, y: Int, width: Int, height: Int): Int = when (turn) {
    0 -> y
    90 -> height - 1 - x
    180 -> height - 1 - y
    else -> x
}

/** Colonne **source** du pixel de destination (x, y) exprimé dans le repère tourné. */
private fun sourceCol(turn: Int, x: Int, y: Int, width: Int, height: Int): Int = when (turn) {
    0 -> x
    90 -> y
    180 -> width - 1 - x
    else -> width - 1 - y
}

/**
 * Chaîne **NV21 directe** : la frame du capteur (YUV 4:2:0) est recopiée en NV21 —
 * rotation et réduction dans la même passe — puis compressée en JPEG par [YuvImage].
 *
 * **Zéro bitmap par frame** : plus de `toBitmap()` (conversion YUV→ARGB), plus de
 * `Bitmap.createBitmap` pour la rotation. Les tampons NV21 et JPEG sont **réutilisés**
 * d'une frame à l'autre : la boucle vidéo n'alloue plus de bitmap par frame.
 *
 * La taille de l'image envoyée est **inchangée** (celle de `targetSize`) : les masques
 * du serveur, les distances et l'overlay restent exactement les mêmes.
 *
 * Une instance par analyseur : les tampons sont mutables, l'objet n'est pas thread-safe.
 */
class Nv21JpegEncoder {

    /** Tampon NV21 réutilisé d'une frame à l'autre (réalloué si la taille change). */
    private var nv21 = ByteArray(0)

    /** Tampon JPEG réutilisé : `reset()` plutôt qu'un nouvel objet par frame. */
    private val jpeg = ByteArrayOutputStream(JPEG_BUFFER_BYTES)

    /**
     * Frame → JPEG base64 **et** plan de luminance [LumaPlane] : la luminance est lue sur
     * le plan Y du NV21 déjà tourné et réduit — même géométrie que l'ancien bitmap
     * `uprightScaled`, donc le calque local ne paie pas un bitmap par frame.
     *
     * Le plan de luminance est un tampon **neuf** : il devient la référence de mouvement
     * du keyframe, il ne doit pas être réécrit par la frame suivante.
     *
     * `null` si le buffer n'est pas en YUV 4:2:0 : l'appelant garde alors son chemin
     * `Bitmap` en repli.
     */
    fun encodeFrame(proxy: ImageProxy, edge: Int, quality: Int): EncodedFrame? {
        val size = pack(proxy, edge) ?: return null
        val bytes = compress(nv21, size.width, size.height, quality) ?: return null
        return EncodedFrame(jpegBytesToBase64(bytes), lumaPlan(size))
    }

    /** Sans la luminance : même implémentation, [encodeFrame] fait tout le travail. */
    fun encodeBase64(proxy: ImageProxy, edge: Int, quality: Int): String? =
        encodeFrame(proxy, edge, quality)?.jpegBase64

    /**
     * Frame → JPEG **brut** (octets, pas de base64) : le direct de l'aidant pousse
     * des frames binaires sur son WebSocket, le détour par le base64 (+33 % de
     * CPU et de mémoire par frame) ne servait qu'à être défait aussitôt.
     *
     * `null` si le buffer n'est pas en YUV 4:2:0 : l'appelant garde alors son
     * chemin `Bitmap` en repli.
     */
    fun encodeJpeg(proxy: ImageProxy, edge: Int, quality: Int): ByteArray? {
        val size = pack(proxy, edge) ?: return null
        return compress(nv21, size.width, size.height, quality)
    }

    /**
     * Frame → JPEG **brut** + plan de luminance [LumaPlane] : le compteur de
     * marches lit la luminance déjà tournée et réduite, sans bitmap. `null`
     * si le buffer n'est pas en YUV 4:2:0 (repli `Bitmap` de l'appelant, sans
     * luminance : le compte vieillit jusqu'à expirer).
     */
    fun encodeJpegLuma(proxy: ImageProxy, edge: Int, quality: Int): JpegLuma? {
        val size = pack(proxy, edge) ?: return null
        val bytes = compress(nv21, size.width, size.height, quality) ?: return null
        return JpegLuma(bytes, lumaPlan(size))
    }

    /**
     * Deux JPEG **bruts** issus d'un seul pack NV21 : la frame SAM et celle du direct
     * aidant partagent la même géométrie (480 px), donc la rotation et la réduction ne
     * sont payées qu'une fois — seule la compression JPEG est faite deux fois (une par
     * qualité). `null` si le buffer n'est pas en YUV 4:2:0 (repli `Bitmap` unique de
     * l'appelant, décodé une fois lui aussi).
     */
    fun encodeJpegPair(proxy: ImageProxy, edge: Int, firstQuality: Int, secondQuality: Int): JpegPair? {
        val size = pack(proxy, edge) ?: return null
        val first = compress(nv21, size.width, size.height, firstQuality) ?: return null
        val second = compress(nv21, size.width, size.height, secondQuality) ?: return null
        return JpegPair(first, second, lumaPlan(size))
    }

    /** Recopie NV21 des plans caméra (rotation et réduction) ; `null` si la forme manque. */
    private fun pack(proxy: ImageProxy, edge: Int): Packed? {
        if (proxy.format != ImageFormat.YUV_420_888) return null
        val planes = planesOf(proxy) ?: return null
        val degrees = normalizeDegrees(proxy.imageInfo.rotationDegrees)
        val (width, height) = nv21TargetSize(planes.width, planes.height, degrees, edge)
        val buffer = bufferFor(width * height * 3 / 2)
        if (packNv21(planes, degrees, width, height, buffer) < 0) return null
        return Packed(width, height)
    }

    /** Plan Y du NV21 (déjà tourné) → plan de référence [LumaPlane], au plus proche voisin. */
    private fun lumaPlan(size: Packed): ByteArray {
        // Tables ligne/colonne (`H + W` divisions `Long`) au lieu d'une paire par
        // pixel (`H × W`) : même formule, même plan.
        val rows = IntArray(LumaPlane.HEIGHT) { y ->
            (y.toLong() * size.height / LumaPlane.HEIGHT).toInt() * size.width
        }
        val cols = IntArray(LumaPlane.WIDTH) { x ->
            (x.toLong() * size.width / LumaPlane.WIDTH).toInt()
        }
        val out = ByteArray(LumaPlane.SIZE)
        var index = 0
        for (y in 0 until LumaPlane.HEIGHT) {
            val row = rows[y]
            for (x in 0 until LumaPlane.WIDTH) {
                out[index++] = nv21[row + cols[x]]
            }
        }
        return out
    }

    /** Les trois plans du buffer caméra ; `null` si la forme attendue n'est pas là. */
    private fun planesOf(proxy: ImageProxy): Yuv420Planes? {
        val planes = proxy.planes
        if (planes.size < 3) return null
        return Yuv420Planes(
            width = proxy.width,
            height = proxy.height,
            y = planes[0].toPlane(),
            u = planes[1].toPlane(),
            v = planes[2].toPlane(),
        )
    }

    /** Plan CameraX → plan pur : la `position` du buffer est l'origine des octets utiles. */
    private fun ImageProxy.PlaneProxy.toPlane(): YuvPlane =
        YuvPlane(buffer, buffer.position(), rowStride, pixelStride)

    /** Tampon NV21 : réalloué seulement quand l'image change de taille. */
    private fun bufferFor(size: Int): ByteArray {
        if (nv21.size < size) nv21 = ByteArray(size)
        return nv21
    }

    /** NV21 → JPEG (encodeur de la plateforme) ; `null` si la compression échoue. */
    private fun compress(source: ByteArray, width: Int, height: Int, quality: Int): ByteArray? {
        jpeg.reset()
        val image = YuvImage(source, ImageFormat.NV21, width, height, null)
        if (!image.compressToJpeg(Rect(0, 0, width, height), quality, jpeg)) return null
        return jpeg.toByteArray()
    }

    companion object {
        /** Capacité initiale du tampon JPEG : une frame à 512 px q65 tient dedans. */
        private const val JPEG_BUFFER_BYTES = 64 * 1024
    }

    /** Taille de la frame packée en NV21 : ce que `packNv21` vient d'écrire. */
    private class Packed(val width: Int, val height: Int)
}

/** Frame encodée : JPEG base64 (sans préfixe `data:`) et son plan de luminance. */
class EncodedFrame(val jpegBase64: String, val luma: ByteArray)

/** Frame JPEG brute et son plan de luminance (compteur de marches). */
class JpegLuma(val jpeg: ByteArray, val luma: ByteArray)

/** Deux JPEG bruts issus d'un seul pack NV21, et leur plan de luminance partagé. */
class JpegPair(val first: ByteArray, val second: ByteArray, val luma: ByteArray)

/** JPEG → base64 sans préfixe `data:` — une seule implémentation pour toute l'app. */
fun jpegBytesToBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
