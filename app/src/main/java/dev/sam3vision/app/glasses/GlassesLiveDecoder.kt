package dev.sam3vision.app.glasses

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Recadrage « remplit la vue » pour un `TextureView` : les facteurs à passer à
 * `setTransform` via `setScale(scaleX, scaleY, viewW / 2f, viewH / 2f)` (pivot = centre
 * de la vue), pour une vidéo [srcW]×[srcH] dans une vue [dstW]×[dstH].
 *
 * Un `TextureView` étire d'abord son buffer pour remplir la vue, **puis** applique la
 * matrice en coordonnées vue : la matrice n'est donc pas le placage complet
 * (échelle uniforme + décalage — qui sur-zoomait et déformait la rue, constaté sur
 * téléphone), mais la **correction** qui rend le total uniforme et centré. L'un des
 * deux facteurs vaut toujours 1 (l'axe déjà rempli par l'étirement), l'autre ≥ 1
 * (l'axe à recadrer). Pur, testé en JVM (`GlassesVideoCropTest`).
 */
data class TextureCrop(val scaleX: Float, val scaleY: Float)

fun textureViewCrop(srcW: Int, srcH: Int, dstW: Int, dstH: Int): TextureCrop {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return TextureCrop(1f, 1f)
    val baseX = dstW / srcW.toFloat()
    val baseY = dstH / srcH.toFloat()
    val fill = maxOf(baseX, baseY)
    return TextureCrop(fill / baseX, fill / baseY)
}

/**
 * Recadrage source « remplit le canvas » : le rectangle centré du bitmap
 * ([srcW]×[srcH]) qui a exactement le ratio du canvas ([dstW]×[dstH]).
 *
 * Le chemin bitmap du direct dessine dans un canvas à taille vidéo (voir
 * `IseeGlassesLive`) que la matrice « remplit la vue » agrandit ensuite : le
 * bitmap doit donc y entrer en conservant son ratio, sinon l'image est
 * étirée puis re-zoomée par la matrice (double mise à l'échelle). Cas
 * nominal : bitmap et canvas à taille vidéo → rectangle plein, dessin 1:1,
 * et la matrice fait l'unique upscale. Pur, testé en JVM
 * (`GlassesBitmapCropTest`) ; dégénéré → bitmap entier, jamais de crash.
 */
internal fun bitmapSourceCrop(srcW: Int, srcH: Int, dstW: Int, dstH: Int): CropRect {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return CropRect(0, 0, srcW, srcH)
    // Comparaison sans flottant : source plus large que le canvas ?
    val wide = srcW.toLong() * dstH
    val tall = dstW.toLong() * srcH
    if (wide == tall) return CropRect(0, 0, srcW, srcH)
    if (wide > tall) {
        val w = (srcH.toLong() * dstW / dstH).toInt().coerceIn(1, srcW)
        return CropRect((srcW - w) / 2, 0, w, srcH)
    }
    val h = (srcW.toLong() * dstH / dstW).toInt().coerceIn(1, srcH)
    return CropRect(0, (srcH - h) / 2, srcW, h)
}

/** Start code Annex B : sépare les unités NAL dans le flux que le décodeur attend. */
private val ANNEX_B_START = byteArrayOf(0, 0, 0, 1)

/**
 * Remet un accès H.265 en Annex B si ses unités sont longueur-préfixées (style hvcC,
 * 4 octets big-endian) : `MediaCodec` veut des start codes, pas des longueurs, et reste
 * muet sinon — sans erreur. Détection par essai : start code présent, longueur
 * incohérente ou reste inexpliqué, et les octets repartent tels quels. Pur, testé en JVM.
 */
fun annexB(bytes: ByteArray): ByteArray {
    if (bytes.size < 8 || startsWithAnnexB(bytes)) return bytes
    val out = ByteArrayOutputStream(bytes.size + 16)
    var pos = 0
    while (pos + 4 <= bytes.size) {
        val length = ((bytes[pos].toInt() and 0xFF) shl 24) or
            ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
            (bytes[pos + 3].toInt() and 0xFF)
        if (length <= 0 || pos + 4 + length > bytes.size) return bytes
        out.write(ANNEX_B_START)
        out.write(bytes, pos + 4, length)
        pos += 4 + length
    }
    if (pos != bytes.size) return bytes
    return out.toByteArray()
}

/** Vrai si les octets commencent par un start code Annex B (long ou court). */
internal fun startsWithAnnexB(bytes: ByteArray): Boolean {
    if (bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte()) {
        if (bytes[2] == 1.toByte()) return true
        if (bytes[2] == 0.toByte() && bytes[3] == 1.toByte()) return true
    }
    return false
}

/**
 * Faut-il tenter le décodage bitmap avant le codec ? Non pour un bitstream déjà en
 * Annex B : `BitmapFactory` y échoue à coup sûr après 1 à 3 ms (15 i/s, c'est ~45 ms
 * de CPU jetées par seconde). Les images fixes (JPEG/PNG/WebP, jamais `00 00 01…` en
 * tête) et le brut non compressé gardent la sonde ; le longueur-préfixé ambigu aussi
 * (prudence : [annexB] tranche déjà ce cas vers Annex B, la sonde reste le repli sûr).
 * Pur, testé en JVM.
 */
internal fun shouldProbeBitmap(frame: GlassesFrame): Boolean {
    if (!frame.compressed) return true
    return !startsWithAnnexB(frame.bytes)
}

/** Premiers octets en hexadécimal (une ligne de diagnostic, jamais le flux entier). */
private fun hexPrefix(bytes: ByteArray, count: Int = 16): String =
    bytes.take(count).joinToString(" ") { "%02x".format(it) }

/**
 * Types d'unités NAL H.265 vus dans un accès Annex B (0-21 tranches, 32 VPS, 33 SPS,
 * 34 PPS, 39/40 SEI). Pur, testé en JVM : c'est lui qui dit si le flux porte sa config
 * en bande (32/33/34 présents) ou si le décodeur meurt de faim sans erreur.
 */
fun nalTypes(bytes: ByteArray): Set<Int> {
    val found = mutableSetOf<Int>()
    var i = 0
    while (i + 4 < bytes.size) {
        val long = bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() &&
            bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()
        val short = !long && bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() &&
            bytes[i + 2] == 1.toByte()
        if (!long && !short) {
            i += 1
            continue
        }
        val header = i + (if (long) 4 else 3)
        if (header + 1 < bytes.size) found.add((bytes[header].toInt() shr 1) and 0x3F)
        i = header + 2
    }
    return found
}

/**
 * Rend les frames du flux des lunettes sur une `Surface` (celle de l'accueil iSee).
 *
 * Trois cas, dans l'ordre : les frames de configuration codec nourrissent le décodeur sans
 * rien afficher ; une frame décodable en bitmap (JPEG compressé, ou brut ARGB_8888) est
 * dessinée telle quelle ; sinon le bitstream H.265 part vers `MediaCodec`, qui rend
 * directement sur la surface. Le décodage bitmap est exactement celui de `GlassesFrames`
 * (les octets copiés y repassent en `VideoFrame`) : zéro duplication.
 *
 * Un seul appelant à la fois (le collecteur du flux) ; `setSurface`/`release` viennent de
 * l'UI — tout est donc verrouillé, et rien ne lève jamais (un rendu raté rend `false`).
 */
class GlassesLiveDecoder(
    private val onVideoSize: (Int, Int) -> Unit = { _, _ -> },
    private val onNote: (GlassesMessage) -> Unit = {},
) {
    private val lock = Any()
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var codecWidth = 0
    private var codecHeight = 0
    private var closed = false
    private var noteCount = 0
    private var renders = 0L
    private var firstRenderMs = 0L
    /** Dessin bitmap filtré : sans `isFilterBitmap`, tout rééchantillonnage aliaserait. */
    private val bitmapPaint = Paint().apply { isFilterBitmap = true }
    private var bitmapAnnounced = false
    private var csd: ByteArray? = null
    private var csdAnnounced = false
    private var configSeen = 0
    private var inputFed = 0L
    private var drained = 0L
    private val nalSeen = mutableSetOf<Int>()

    /** La surface de l'accueil (`null` = l'accueil ne regarde pas, on ne décode rien). */
    fun setSurface(next: Surface?) = synchronized(lock) { surface = next }

    /**
     * L'accueil regarde-t-il ? Sans surface, `render` refuse sans rien tenter :
     * ce n'est pas un échec de décodage, et la mesure B2 ne doit pas le compter.
     */
    val hasSurface: Boolean get() = synchronized(lock) { surface != null }

    /**
     * Rend une frame copiée ; `true` = une image est affichée (l'accueil peut marquer le
     * direct vivant). Une frame de config codec rend `false` : elle prépare, sans image.
     */
    fun render(frame: GlassesFrame): Boolean = synchronized(lock) {
        if (closed) return false
        val target = surface ?: return false
        if (frame.width <= 0 || frame.height <= 0 || frame.bytes.isEmpty()) return false
        return runCatching { renderLocked(target, frame) }.getOrElse { dropCodecLocked(); false }
    }

    private fun renderLocked(target: Surface, frame: GlassesFrame): Boolean {
        if (frame.codecConfig) {
            stashConfig(target, frame)
            return false
        }
        if (shouldProbeBitmap(frame)) {
            frame.asSdkFrame().toBitmapOrNull()?.let {
                try {
                    return drawBitmap(target, frame, it)
                } finally {
                    // Bitmap décodé pour ce seul dessin (15 i/s) : libéré aussitôt.
                    runCatching { it.recycle() }
                }
            }
        }
        if (!frame.compressed) {
            // Brut non ARGB (YUV du banc, jamais en production) : rien à en faire ici.
            noteFailure(GlassesMessages.videoUndecodable())
            return false
        }
        return feedCodec(target, frame)
    }

    /**
     * Dessin bitmap : l'image remplit le canvas à taille vidéo en gardant son ratio
     * ([bitmapSourceCrop], peinture filtrée) ; le recadrage « remplit la vue » est posé
     * une seule fois, par la vue elle-même. Le verrou du canvas est toujours rendu.
     */
    private fun drawBitmap(target: Surface, frame: GlassesFrame, bitmap: Bitmap): Boolean {
        val canvas = runCatching { target.lockCanvas(null) }.getOrNull() ?: return false
        try {
            if (canvas.width <= 0 || canvas.height <= 0) return false
            // Ratio conservé (recadrage source centré), plein canvas : cas nominal
            // 1:1, et la matrice de la vue fait ensuite l'unique upscale.
            val crop = bitmapSourceCrop(bitmap.width, bitmap.height, canvas.width, canvas.height)
            val src = Rect(crop.x, crop.y, crop.x + crop.width, crop.y + crop.height)
            canvas.drawBitmap(bitmap, src, Rect(0, 0, canvas.width, canvas.height), bitmapPaint)
            onVideoSize(frame.width, frame.height)
            announceBitmap(frame, bitmap)
            return countRender(true)
        } catch (_: Exception) {
            return false
        } finally {
            runCatching { target.unlockCanvasAndPost(canvas) }
        }
    }

    /**
     * Config H.265 (VPS/SPS/PPS) : mémorisée puis posée en `csd-0` à la configuration —
     * beaucoup de décodeurs ignorent la config envoyée en buffer avec le drapeau, et ne
     * sortent alors jamais rien, sans erreur. Une config nouvelle recrée le décodeur.
     */
    private fun stashConfig(target: Surface, frame: GlassesFrame) {
        configSeen += 1
        if (configSeen <= 3) Log.i(TAG, "direct lunettes : config #$configSeen (${frame.bytes.size} o)")
        if (csd == null || !frame.bytes.contentEquals(csd)) {
            csd = frame.bytes.copyOf()
            dropCodecLocked()
            ensureCodec(target, frame.width, frame.height)
        }
    }

    /** Envoie un accès H.265 au décodeur ; `true` = au moins une image rendue. */
    private fun feedCodec(target: Surface, frame: GlassesFrame): Boolean {
        val decoder = ensureCodec(target, frame.width, frame.height) ?: run {
            noteFailure(GlassesMessages.videoUndecodable())
            return false
        }
        val index = runCatching { decoder.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrNull() ?: return false
        if (index < 0) return false
        val input = runCatching { decoder.getInputBuffer(index) }.getOrNull() ?: return false
        input.clear()
        val access = annexB(frame.bytes)
        if (input.remaining() < access.size) return false
        input.put(access)
        inputFed += 1
        if (inputFed == 1L) {
            Log.i(TAG, "direct lunettes : 1re entrée codec (${frame.bytes.size} o → ${access.size} o, pts=${frame.presentationTimeUs})")
            Log.i(TAG, "direct lunettes : tête ${hexPrefix(frame.bytes)}")
        }
        if (inputFed <= NAL_SURVEY_FRAMES) {
            val fresh = nalTypes(access) - nalSeen
            if (fresh.isNotEmpty()) {
                nalSeen += fresh
                Log.i(TAG, "direct lunettes : NAL ${fresh.sorted()} (entrée $inputFed)")
            }
        }
        decoder.queueInputBuffer(index, 0, access.size, maxOf(0L, frame.presentationTimeUs), 0)
        return drain(decoder)
    }

    /** Vide les sorties vers la surface ; annonce la taille réelle quand le codec la donne. */
    private fun drain(decoder: MediaCodec): Boolean {
        val info = MediaCodec.BufferInfo()
        var rendered = false
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    decoder.releaseOutputBuffer(index, true)
                    rendered = true
                    drained += 1
                    if (drained == 1L) Log.i(TAG, "direct lunettes : 1re sortie codec")
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> announceSize(decoder.outputFormat)
                else -> {
                    if (drained == 0L && inputFed % 100 == 0L) {
                        Log.w(TAG, "direct lunettes : $inputFed entrées, 0 sortie (config vue : $configSeen)")
                    }
                    return countRender(rendered)
                }
            }
        }
    }

    private fun announceSize(format: MediaFormat) {
        val width = runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
        val height = runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
        onVideoSize(width, height)
    }

    /** Décodeur H.265 paresseux : créé à la première frame, recréé si la taille change. */
    private fun ensureCodec(target: Surface, width: Int, height: Int): MediaCodec? {
        val current = codec
        if (current != null && width == codecWidth && height == codecHeight) return current
        dropCodecLocked()
        val effectiveCsd = csd ?: GlassesVideoLink.fallbackCsd
        if (effectiveCsd != null && !csdAnnounced) {
            csdAnnounced = true
            Log.i(TAG, "direct lunettes : csd-0 ${effectiveCsd.size} o (${if (csd != null) "flux" else "repli"})")
        }
        val created = runCatching {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC).apply {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height)
                effectiveCsd?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
                configure(format, target, null, 0)
                start()
            }
        }.getOrNull() ?: return null
        codec = created
        codecWidth = width
        codecHeight = height
        Log.i(TAG, "direct lunettes : décodeur H.265 ${width}×${height}")
        onVideoSize(width, height)
        return created
    }

    private fun dropCodecLocked() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        codecWidth = 0
        codecHeight = 0
    }

    /** Libère le décodeur (fin de composition) : tout rendu ultérieur est refusé. */
    fun release() = synchronized(lock) {
        if (closed) return
        closed = true
        dropCodecLocked()
        surface = null
    }

    /** Un rendu sur 300 journalisé (preuve de vie au logcat, avec la cadence mesurée). */
    private fun countRender(rendered: Boolean): Boolean {
        if (!rendered) return false
        renders += 1
        if (renders == 1L) firstRenderMs = SystemClock.elapsedRealtime()
        if (renders % LOG_EVERY_RENDERS == 0L) {
            val elapsed = SystemClock.elapsedRealtime() - firstRenderMs
            Log.i(TAG, "direct lunettes : $renders images rendues" + fpsSuffix(renders, elapsed))
        }
        return true
    }

    /** Première image bitmap : journalisée une fois (dit le format livré par le flux). */
    private fun announceBitmap(frame: GlassesFrame, bitmap: Bitmap) {
        if (bitmapAnnounced) return
        bitmapAnnounced = true
        Log.i(TAG, "direct lunettes : première image bitmap ${bitmap.width}×${bitmap.height} (flux ${frame.width}×${frame.height})")
    }

    /**
     * Panne de rendu : journalisée (1re fois puis une sur 100, le flux bat à 15 i/s),
     * annoncée à l'utilisateur une seule fois.
     */
    private fun noteFailure(message: GlassesMessage) {
        noteCount += 1
        if (noteCount == 1 || noteCount % 100 == 0) Log.w(TAG, "direct lunettes : ${message.text} (échec $noteCount)")
        if (noteCount == 1) runCatching { onNote(message) }
    }

    private companion object {
        const val TAG = "GlassesVideo"
        const val INPUT_TIMEOUT_US = 5_000L
        const val LOG_EVERY_RENDERS = 300L
        const val NAL_SURVEY_FRAMES = 50L
    }
}
