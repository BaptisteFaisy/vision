package dev.sam3vision.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.os.SystemClock
import dev.sam3vision.core.local.LocalOverlayConfig
import dev.sam3vision.core.local.LocalOverlayEngine
import dev.sam3vision.core.local.LocalReason
import dev.sam3vision.core.local.LocalTick
import dev.sam3vision.core.mask.MaskMotion
import dev.sam3vision.core.local.LumaPlane
import dev.sam3vision.core.model.LiveFrame
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Calque local du direct (lane L13, agent D/u13) côté Android : il ne décide rien — la
 * décision est dans [dev.sam3vision.core.local.LocalOverlayEngine] — il **dessine** et
 * alimente le plan de luminance que l'estimateur de mouvement consommera.
 *
 * Le contrat de fusion (`docs/L13-FUSION.md`) impose deux choses ici : une seule surface
 * d'affichage (le calque **remplace** les masques serveur, il ne se superpose pas à eux)
 * et une image à 30 fps **hors** du flux d'état des barres.
 */

/**
 * Drapeau de build `live.local.overlay` : renseigné, il **prime** sur le réglage
 * utilisateur (même règle que les clés injectées au build) ; vide, c'est le réglage qui
 * décide. Valeur inconnue ⇒ réglage, jamais un silence.
 */
fun localOverlayFlag(buildFlag: String, settingEnabled: Boolean): Boolean =
    when (buildFlag.trim().lowercase(Locale.ROOT)) {
        "true", "1", "on", "oui" -> true
        "false", "0", "off", "non" -> false
        else -> settingEnabled
    }

/**
 * Part du calque local dans la ligne d'état : rien quand il est éteint (la pastille
 * « Local OFF » le dit déjà), la cadence tenue quand il peint, et le **motif** quand il ne
 * peint pas — un calque inerte doit se voir.
 */
fun localStatusPart(enabled: Boolean, active: Boolean, fps: Float, note: String = ""): String = when {
    !enabled -> ""
    active -> " · calque local " + String.format(Locale.FRANCE, "%.1f ips", fps)
    note.isBlank() -> " · calque local inerte"
    else -> " · calque local inerte ($note)"
}

/** Motif affiché du dernier tick : court, en français, sans jargon de la machine d'état. */
fun localReasonNote(reason: LocalReason): String = when (reason) {
    LocalReason.PAINT -> ""
    LocalReason.DISABLED -> "éteint"
    LocalReason.NO_KEYFRAME -> "en attente d'un keyframe"
    LocalReason.STALE_KEYFRAME -> "keyframe périmé"
    LocalReason.LOW_CONFIDENCE -> "confiance trop basse"
    LocalReason.NO_ESTIMATOR -> "sans estimateur"
    LocalReason.ESTIMATOR_FAILED -> "estimateur en échec"
    LocalReason.TOO_EARLY -> "cadence"
}

/**
 * La surface de masques reçoit **soit** l'image du calque, **soit** les masques du
 * serveur — jamais les deux (contrat de fusion §2.3) : sans image locale, rien ne change.
 *
 * Le type est paramétré pour que la règle soit testable en JVM, sans `Bitmap` (une JVM
 * nue ne sait pas en fabriquer).
 */
fun <T> overlayLayers(serverOverlays: List<T>, localFrame: T?): List<T> =
    if (localFrame == null) serverOverlays else listOf(localFrame)

/**
 * Transform de rendu du calque, dans le repère du plan masque (pixels) : propagation
 * (translation + échelle) autour du **centre** de la frame, comme la reprojection de la
 * lane B. Pur JVM : testable sans appareil.
 */
data class LocalWarp(
    val dx: Float,
    val dy: Float,
    val scale: Float,
    val pivotX: Float,
    val pivotY: Float,
) {
    /** Matrice de rendu correspondante (Android) : échelle au centre, puis translation. */
    fun into(matrix: Matrix): Matrix = matrix.apply {
        setScale(scale, scale, pivotX, pivotY)
        postTranslate(dx, dy)
    }
}

/**
 * Traduit un [MaskMotion] (pixels du plan de référence 256×103) en transform du plan
 * masque : une seule remise à l'échelle, déléguée à [MaskMotion.scaledBy].
 */
fun localWarpFor(motion: MaskMotion, maskWidth: Int, maskHeight: Int): LocalWarp {
    // La cellule du plan de référence vaut `WIDTH / maskWidth` celle du masque : `scaledBy`
    // divise la translation par ce pas, donc la remet à l'échelle du plan masque.
    val step = LumaPlane.WIDTH.toDouble() / maskWidth.coerceAtLeast(1)
    val scaled = motion.scaledBy(step)
    val pivotX = maskWidth / 2f
    val pivotY = maskHeight / 2f
    return LocalWarp(scaled.dx.toFloat(), scaled.dy.toFloat(), scaled.scale.toFloat(), pivotX, pivotY)
}

/**
 * Plan de luminance 256×103 d'une frame, pris sur le bitmap **déjà tourné** de l'analyseur
 * ([uprightScaled]) : c'est le chemin qui évite le piège du plan Y NV21, qui n'est pas
 * tourné (permuter `dx`/`dy` selon `rotationDegrees` n'est pas fait ici).
 *
 * Coût mesuré en JVM (256×103) : 0,26-0,33 ms par frame ; sur ART, 0,5-1,5 ms **attendu,
 * non mesuré**. Le tampon est réutilisé : l'appelant le **copie** s'il doit le garder
 * (luminance du keyframe).
 */
class LumaSampler(
    private val width: Int = LumaPlane.WIDTH,
    private val height: Int = LumaPlane.HEIGHT,
) {

    private var pixels = IntArray(0)
    private val plan = ByteArray(width * height)

    /** Plan de luminance de [bitmap] ; `null` si l'image n'est pas exploitable. */
    fun sample(bitmap: Bitmap): ByteArray? {
        if (bitmap.width <= 0 || bitmap.height <= 0 || bitmap.isRecycled) return null
        val count = bitmap.width * bitmap.height
        if (pixels.size < count) pixels = IntArray(count)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val stepX = bitmap.width.toFloat() / width
        val stepY = bitmap.height.toFloat() / height
        var index = 0
        for (y in 0 until height) {
            val row = (y * stepY).toInt().coerceIn(0, bitmap.height - 1) * bitmap.width
            for (x in 0 until width) {
                val p = pixels[row + (x * stepX).toInt().coerceIn(0, bitmap.width - 1)]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                plan[index] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
                index++
            }
        }
        return plan
    }
}

/**
 * Rendu du calque : les bitmaps **déjà coloriés** du keyframe sont redessinés avec la
 * matrice de mouvement dans une image **ping-pong** — deux tampons réutilisés, aucune
 * allocation par frame (105 472 o/frame évités à 512×206). Le tampon renvoyé n'est jamais
 * celui qu'on redessine ensuite : l'affichage ne clignote pas.
 *
 * Filtrage bilinéaire assumé : les calques sont **colorisés** (ARGB), pas binaires — la
 * frange d'un demi-pixel du masque binaire (127 < 128) n'existe plus ici.
 */
class LiveLocalOverlayPainter {

    private var bufferA: Bitmap? = null
    private var bufferB: Bitmap? = null
    private var toggle = false
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Coût de la dernière peinture, en microsecondes : remesuré en vol (voir `notePaint`). */
    var lastPaintUs: Long = 0L
        private set

    /** Redessine les calques du keyframe ; `null` si rien n'est peignable. */
    fun draw(layers: List<Bitmap>, warp: LocalWarp): Bitmap? {
        val first = layers.firstOrNull() ?: return null
        val target = nextBuffer(first.width, first.height) ?: return null
        val started = SystemClock.elapsedRealtimeNanos()
        val canvas = Canvas(target)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        warp.into(matrix)
        layers.forEach { canvas.drawBitmap(it, matrix, paint) }
        lastPaintUs = (SystemClock.elapsedRealtimeNanos() - started) / NANOS_PER_US
        return target
    }

    /** Libère les tampons : le calque ne dessine plus (fin de session). */
    fun release() {
        bufferA?.recycle()
        bufferB?.recycle()
        bufferA = null
        bufferB = null
        toggle = false
    }

    /** Tampon d'écriture suivant : réalloué seulement si la taille de frame a changé. */
    private fun nextBuffer(width: Int, height: Int): Bitmap? {
        if (bufferA?.width != width || bufferA?.height != height) {
            release()
            bufferA = create(width, height)
            bufferB = create(width, height)
        }
        toggle = !toggle
        return if (toggle) bufferA else bufferB
    }

    private fun create(width: Int, height: Int): Bitmap? =
        if (width <= 0 || height <= 0) null else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private companion object {
        const val NANOS_PER_US = 1_000L
    }
}

/**
 * Confiance du keyframe pour le calque local : le meilleur score d'objet de la frame
 * (0 = aucun objet sûr, donc rien à propager). Fonction pure, testée en JVM.
 */
fun keyframeConfidence(frame: LiveFrame): Float =
    frame.objects.maxOfOrNull { it.score } ?: 0f

/**
 * Session du calque local du direct : elle tient le moteur (la décision), le peintre (le
 * rendu) et le plan de luminance, et publie l'image du calque dans un flux **séparé** de
 * l'état du direct — 30 ips dans l'état feraient recomposer les barres 30 fois par
 * seconde (contrat de fusion §2.4).
 *
 * Elle ne fait **aucune** estimation : tant que la lane A (M52) n'a pas livré
 * `core/…/motion/`, le moteur refuse au motif `NO_ESTIMATOR` et l'écran se comporte
 * exactement comme avant.
 */
class LiveLocalOverlaySession(private val config: LocalOverlayConfig = LocalOverlayConfig()) {

    private val engine = LocalOverlayEngine(config)
    private val painter = LiveLocalOverlayPainter()
    private val sampler = LumaSampler()

    private val _frame = MutableStateFlow<Bitmap?>(null)

    /** Image du calque (30 ips) : à collecter par la seule surface de masques. */
    val frame: StateFlow<Bitmap?> = _frame

    /** Le calque est-il allumé (réglage des Réglages **et** drapeau de build) ? */
    var enabled: Boolean = config.enabled
        private set

    /** Keyframe courant : bitmaps coloriés à redessiner, confiance, instant. */
    @Volatile
    private var layers: List<Bitmap> = emptyList()

    @Volatile
    private var keyframeAtMs = 0L

    @Volatile
    private var keyframeConfidence = 0f

    /** Dernier verdict du moteur : la ligne d'état s'en sert (motif, cadence). */
    var lastTick: LocalTick = LocalTick(false, LocalReason.NO_KEYFRAME)
        private set

    /** Allume ou coupe le calque (bascule du bandeau ou des Réglages). */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        engine.setConfig(config.copy(enabled = enabled))
        if (!enabled) clear()
    }

    /** Plan de luminance d'une frame caméra (256×103) : l'entrée de l'estimateur. */
    fun lumaFor(bitmap: Bitmap): ByteArray? = sampler.sample(bitmap)

    /**
     * Un keyframe du serveur arrive : ses bitmaps **déjà coloriés** et la luminance de la
     * frame deviennent la référence du calque (aucun second décodage, contrat §2.2).
     */
    fun onKeyframe(overlays: List<Bitmap>, confidence: Float, luma: ByteArray?, atMs: Long) {
        layers = overlays
        keyframeAtMs = atMs
        keyframeConfidence = confidence
        engine.onKeyframe(luma)
    }

    /**
     * Tick d'une frame caméra : décision du moteur, puis rendu si le calque doit peindre.
     * Le verdict est rendu à l'appelant (ligne d'état, cadence affichée).
     */
    fun tick(nowMs: Long, luma: ByteArray?): LocalTick {
        if (!enabled) {
            clear()
            lastTick = LocalTick(false, LocalReason.DISABLED)
            return lastTick
        }
        val age = if (keyframeAtMs == 0L) NO_KEYFRAME_AGE else nowMs - keyframeAtMs
        val tick = engine.tick(nowMs, luma, age, keyframeConfidence)
        lastTick = tick
        if (tick.paint) paint(tick) else if (tick.reason != LocalReason.TOO_EARLY) clear()
        return tick
    }

    /** Nouvelle session de direct : keyframe, image peinte et cadence sont oubliés. */
    fun reset() {
        engine.reset()
        layers = emptyList()
        keyframeAtMs = 0L
        keyframeConfidence = 0f
        clear()
        lastTick = LocalTick(false, LocalReason.NO_KEYFRAME)
    }

    /** Libère les tampons de peinture (fin de session). */
    fun release() {
        painter.release()
        clear()
    }

    /** Redessine les calques du keyframe avec la matrice de mouvement. */
    private fun paint(tick: LocalTick) {
        val motion = tick.motion ?: return
        val first = layers.firstOrNull() ?: return
        val image = painter.draw(layers, localWarpFor(motion, first.width, first.height)) ?: return
        engine.notePaint(painter.lastPaintUs)
        _frame.value = image
    }

    /** Plus d'image locale : la surface retombe sur les masques du serveur. */
    private fun clear() {
        _frame.value = null
    }

    companion object {
        /** Âge « pas de keyframe » : négatif, donc refusé par le moteur. */
        const val NO_KEYFRAME_AGE = -1L
    }
}