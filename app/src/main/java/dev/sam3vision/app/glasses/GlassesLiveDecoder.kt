package dev.sam3vision.app.glasses

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import dev.sam3vision.core.distance.CropGeometry
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Recadrage « remplit la vue » : facteurs `setScale` (pivot = centre) pour une
 * vidéo [srcW]×[srcH] dans une vue [dstW]×[dstH]. La matrice corrige l'étirement
 * préalable (pas un placage complet, qui déformait — constaté sur téléphone) :
 * un facteur vaut 1, l'autre ≥ 1. Pur, testé en JVM (`GlassesVideoCropTest`).
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
 * Recadrage source « remplit le canvas » : rectangle centré du bitmap
 * ([srcW]×[srcH]) au ratio du canvas ([dstW]×[dstH]). Le ratio est conservé
 * ici pour que la matrice « remplit la vue » fasse l'unique upscale (1:1 au
 * nominal). Pur, testé en JVM (`GlassesBitmapCropTest`) ; dégénéré → entier.
 */
internal fun bitmapSourceCrop(srcW: Int, srcH: Int, dstW: Int, dstH: Int): CropRect {
    val crop = CropGeometry.fillViewCrop(srcW, srcH, dstW, dstH)
    return CropRect(crop.x, crop.y, crop.width, crop.height)
}

/** Start code Annex B : sépare les unités NAL dans le flux que le décodeur attend. */
private val ANNEX_B_START = byteArrayOf(0, 0, 0, 1)

/**
 * Format H.265 mémorisé, stable par session (`null` = indécis, oublié par
 * [resetAnnexBMemo]). Volatile : écrit depuis les deux fils décodeurs
 * (idempotent, course bénigne).
 */
@Volatile
private var annexBStable: Boolean? = null

/** Oublie le format mémorisé (session suivante, voir `GlassesVideoLink.clear`). */
internal fun resetAnnexBMemo() {
    annexBStable = null
}

/**
 * Remet un accès H.265 en Annex B si ses unités sont longueur-préfixées (style hvcC,
 * 4 octets big-endian) : `MediaCodec` veut des start codes, pas des longueurs, et reste
 * muet sinon — sans erreur. Détection par essai : start code présent, longueur
 * incohérente ou reste inexpliqué, et les octets repartent tels quels.
 *
 * Détection mémorisée : le format est stable par session, donc un flux détecté
 * Annex B repart tel quel sans nouveau balayage (15 poussées/s × 2 chemins) ;
 * un flux longueur-préfixé garde le chemin complet (chaque accès se convertit,
 * les octets changent à chaque frame). Testé en JVM (`GlassesAnnexBTest`,
 * mémo réinitialisé entre cas).
 */
fun annexB(bytes: ByteArray): ByteArray {
    if (annexBStable == true) return bytes
    if (bytes.size < 8 || startsWithAnnexB(bytes)) {
        if (bytes.size >= 8) annexBStable = true
        return bytes
    }
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
    // Longueur-préfixé avéré : le chemin complet reste la règle (jamais de
    // raccourci vers `return bytes`, qui laisserait passer du hvcC brut).
    annexBStable = false
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
 * Annex B : `BitmapFactory` y échoue à coup sûr après 1 à 3 ms (15 i/s, c'est jusqu'à ~45 ms
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
 * Faut-il (re)créer le codec ? Un codec vivant à taille identique suffit : une config
 * nouvelle qui garde la taille ne doit jamais le recréer (100 à 500 ms de gel par config).
 * Pur, testé en JVM.
 */
internal fun needsCodecRebuild(
    hasCodec: Boolean,
    codecW: Int,
    codecH: Int,
    width: Int,
    height: Int,
): Boolean = !hasCodec || width != codecW || height != codecH

/**
 * Rend les frames des lunettes sur la `Surface` de l'accueil iSee : les configs
 * codec nourrissent le décodeur sans afficher, le bitmap décodable (JPEG, brut
 * ARGB_8888) est dessiné tel quel, sinon le H.265 part vers `MediaCodec` (zéro
 * duplication : le bitmap passe par `GlassesFrames`, comme ailleurs).
 *
 * Un seul appelant de `render` (le collecteur) ; `setSurface`/`release` viennent
 * de l'UI. Seul le cliché est pris sous verrou, le rendu tourne hors verrou, et
 * rien ne lève jamais (un rendu raté rend `false`).
 */
class GlassesLiveDecoder(
    private val onVideoSize: (Int, Int) -> Unit = { _, _ -> },
    private val onNote: (GlassesMessage) -> Unit = {},
) {
    private val lock = Any()
    private var surface: Surface? = null
    /** Volatile : posé par `release` (UI, sous verrou), relu par `render` (hors verrou). */
    @Volatile
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
    fun render(frame: GlassesFrame): Boolean {
        // Cliché sous verrou, rendu hors verrou : `MediaCodec` ne bloque jamais
        // l'UI. `release` en cours de rendu : le `runCatching` jette et rend `false`.
        val target = synchronized(lock) { if (closed) null else surface } ?: return false
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
                    // Bitmap décodé pour ce seul dessin (jusqu'à 15 i/s) : libéré aussitôt.
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
     * Config H.265 (VPS/SPS/PPS) : mémorisée en `csd-0` (les décodeurs ignorent
     * souvent la config envoyée en buffer). Ne recrée que si la taille change.
     */
    private fun stashConfig(target: Surface, frame: GlassesFrame) {
        configSeen += 1
        if (configSeen <= 3) Log.i(TAG, "direct lunettes : config #$configSeen (${frame.bytes.size} o)")
        if (csd != null && frame.bytes.contentEquals(csd)) return
        val rebuild = needsCodecRebuild(codec != null, codecWidth, codecHeight, frame.width, frame.height)
        csd = frame.bytes.copyOf()
        if (!rebuild) {
            // Taille inchangée : le codec vit, seule la config change. Le recréer coûterait
            // 100 à 500 ms de gel par config, pour un format identique.
            Log.i(TAG, "direct lunettes : config à taille ${frame.width}×${frame.height} inchangée, codec conservé")
            return
        }
        dropCodecLocked()
        ensureCodec(target, frame.width, frame.height)
    }

    /** Envoie un accès H.265 au décodeur ; `true` = au moins une image rendue. */
    private fun feedCodec(target: Surface, frame: GlassesFrame): Boolean {
        val decoder = ensureCodec(target, frame.width, frame.height) ?: run {
            noteFailure(GlassesMessages.videoUndecodable())
            return false
        }
        val index = runCatching { decoder.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrNull() ?: return false
        if (index < 0) return false
        val input = runCatching { decoder.getInputBuffer(index) }.getOrNull()
        if (input == null) return recycleInput(decoder, index)
        input.clear()
        val access = annexB(frame.bytes)
        // Accès plus gros que le buffer : rendre l'index vide, sinon `dequeueInputBuffer`
        // rend < 0 ensuite et le décodeur meurt de faim en silence (même règle que
        // `GlassesVideoDecoder.queueInput`).
        if (input.remaining() < access.size) return recycleInput(decoder, index)
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

    /** Rend un buffer d'entrée inutilisé (vide) : sans ça l'index est perdu à jamais. */
    private fun recycleInput(decoder: MediaCodec, index: Int): Boolean {
        runCatching { decoder.queueInputBuffer(index, 0, 0, 0L, 0) }
        return false
    }

    /**
     * Vide les sorties vers la surface (taille réelle annoncée au passage). Sans
     * attente, la sortie de l'accès courant ne sort qu'à l'accès suivant (+67 ms
     * à 15 i/s) : on attend [OUTPUT_WAIT_US] jusqu'à la première image, puis `0`.
     */
    private fun drain(decoder: MediaCodec): Boolean {
        val info = MediaCodec.BufferInfo()
        var rendered = false
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, if (rendered) 0L else OUTPUT_WAIT_US)
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
        if (!needsCodecRebuild(current != null, codecWidth, codecHeight, width, height)) return current
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
                applyDecoderLowLatencyKeys(format, OPERATING_RATE)
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
     * Panne de rendu : journalisée (1re fois puis une sur 100, le flux bat jusqu'à 15 i/s),
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
        /** Attente de la première sortie d'un accès : une demi-période image à 15 i/s. */
        const val OUTPUT_WAIT_US = 33_000L
        /** Cadence annoncée au décodeur (clé `operating-rate`), en images par seconde. */
        const val OPERATING_RATE = 15
        const val LOG_EVERY_RENDERS = 300L
        const val NAL_SURVEY_FRAMES = 50L
    }
}

/**
 * Clés basse latence : pas de file interne, cadence annoncée, priorité temps
 * réel, aucun B-frame. Une clé par `runCatching` (une clé inconnue lève, et
 * sous un seul bloc elle tuerait la création — écran noir, sans erreur).
 */
internal fun applyDecoderLowLatencyKeys(format: MediaFormat, operatingRate: Int) {
    runCatching { format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1) }
    runCatching { format.setInteger(MediaFormat.KEY_OPERATING_RATE, operatingRate) }
    runCatching { format.setInteger(MediaFormat.KEY_PRIORITY, 0) }
    runCatching { format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) }
}
