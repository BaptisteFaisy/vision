package dev.sam3vision.core.local

import dev.sam3vision.core.mask.GrayMask
import dev.sam3vision.core.mask.MaskPropagator
import dev.sam3vision.core.mask.WarpFilter

/**
 * Chaîne de propagation locale (M4, lane L13) : la **décision** ([LocalOverlayEngine],
 * D/u13) branchée sur le **warp CPU** ([MaskPropagator], B/u11). Le moteur dit quand
 * propager, le warp ramène le masque du keyframe sur la frame courante — pur JVM, aucun
 * Android, donc testé et mesuré sans appareil.
 *
 * C'est le premier appelant en prod de `B` (`docs/L13-FUSION.md` §1 le disait « présent,
 * non branché ») : `propagate` enchaîne `tick` et `warpInto` dans un tampon réutilisé
 * (**zéro allocation par frame** en régime permanent), et le coût mesuré du warp est
 * rendu au moteur (`notePaint`) pour que la cadence adaptative voie le vrai prix.
 *
 * Le mouvement est estimé dans le plan de référence ([LumaPlane], 256×103) et remis à
 * l'échelle du masque ([dev.sam3vision.core.mask.MaskMotion.scaledBy]) — le même idiome
 * que `localWarpFor` côté app, qui fait la même remise pour la matrice de rendu.
 *
 * Filtre par défaut [WarpFilter.NEAREST] : les keyframes sont des masques binaires
 * (0/255) et le bilinéaire y laisserait une frange à 127, sous le seuil 128 (contrat de
 * fusion §7). Un masque à niveaux doux (PNG gris) peut passer `BILINEAR` au constructeur.
 *
 * Point d'insertion côté app (**documenté, non posé** — câblage UI différé, voir la PR) :
 * `LiveMaskDecoder` peint aujourd'hui des `Bitmap` coloriés et jette le `GrayMask`
 * (`decodeMaskAny`, `Masks.kt`) ; la session ne retient que ces bitmaps. Brancher cette
 * chaîne demande : retenir un `GrayMask` par objet au keyframe, appeler [propagate] à
 * chaque tick dans `LiveLocalOverlaySession.paint()`, puis colorier vers le bitmap
 * ping-pong (`colorizeGrayToBitmap`, `Masks.kt`, tampons réutilisés). Non fait ici : ce
 * chemin `Bitmap` n'est pas testable en JVM et exige une validation sur téléphone.
 */
class LocalPropagator(
    private val engine: LocalOverlayEngine = LocalOverlayEngine(),
    private val warp: MaskPropagator = MaskPropagator(),
    private val filter: WarpFilter = WarpFilter.NEAREST,
    private val clock: () -> Long = System::nanoTime,
) {

    /** Keyframe retenu **par référence** : décodé frais à chaque keyframe, jamais muté après. */
    private var keyframe: GrayMask? = null

    /** Instant du keyframe ; 0 = aucun (même idiome que `LiveLocalOverlaySession`). */
    private var keyframeAtMs = 0L

    /** Confiance SAM du keyframe (meilleur score d'objet, 0 = rien à propager). */
    private var keyframeConfidence = 0f

    /** Tampon de sortie réutilisé : réalloué seulement si la taille de grille change. */
    private var dst: GrayMask? = null

    /** Dernier verdict du moteur : motif et cadence pour la ligne d'état. */
    var lastTick: LocalTick = LocalTick(false, LocalReason.NO_KEYFRAME)
        private set

    /** Branche l'estimateur (couture [MotionSource]) : les échecs repartent de zéro. */
    fun setMotion(source: MotionSource) = engine.setMotion(source)

    /** Remplace les réglages (bascule utilisateur, drapeau de build) et recale la cadence. */
    fun setConfig(config: LocalOverlayConfig) = engine.setConfig(config)

    /**
     * Nouveau keyframe : [mask] devient la référence du warp (retenu par référence —
     * l'appelant ne touche plus à son tampon après cet appel), [luma] celle de
     * l'estimateur, [confidence] le score SAM maximal de la frame. Un masque `null`
     * annule le keyframe (plus rien à propager).
     */
    fun onKeyframe(mask: GrayMask?, luma: ByteArray?, confidence: Float, atMs: Long) {
        keyframe = mask
        keyframeConfidence = confidence
        keyframeAtMs = if (mask == null) 0L else atMs
        engine.onKeyframe(luma)
    }

    /**
     * Propage le keyframe sur la frame courante. `null` = rien à dessiner ([lastTick]
     * dit pourquoi : pas de keyframe, keyframe périmé, confiance trop basse, cadence).
     * Le refus du warp (mouvement aberrant) rend aussi `null` : l'appelant garde alors
     * l'image précédente, comme pour un refus moteur.
     */
    fun propagate(nowMs: Long, luma: ByteArray?): GrayMask? {
        val tick = engine.tick(nowMs, luma, keyframeAge(nowMs), keyframeConfidence)
        lastTick = tick
        if (!tick.paint) return null
        val src = keyframe ?: return null
        val motion = tick.motion ?: return null
        val out = bufferFor(src)
        val started = clock()
        val ok = warp.warpInto(src, out, motion.scaledBy(stepFor(src)), filter)
        engine.notePaint((clock() - started) / NANOS_PER_US)
        return if (ok) out else null
    }

    /** Nouvelle session de direct : keyframe, tampon de sortie et cadence sont oubliés. */
    fun reset() {
        engine.reset()
        keyframe = null
        keyframeAtMs = 0L
        keyframeConfidence = 0f
        dst = null
        lastTick = LocalTick(false, LocalReason.NO_KEYFRAME)
    }

    /** Âge du keyframe ; négatif = aucun (le moteur refuse en `NO_KEYFRAME`). */
    private fun keyframeAge(nowMs: Long): Long =
        if (keyframeAtMs == 0L) -1L else nowMs - keyframeAtMs

    /** Tampon de sortie : réutilisé tant que la taille colle, sinon réalloué (rythme keyframe). */
    private fun bufferFor(src: GrayMask): GrayMask {
        val current = dst
        if (current != null && current.width == src.width && current.height == src.height &&
            current.pixels.size == src.size
        ) {
            return current
        }
        return GrayMask(src.width, src.height, ByteArray(src.size)).also { dst = it }
    }

    /** Pas grille luma → grille masque : 0,5 à 512×206 (le mouvement y double). */
    private fun stepFor(src: GrayMask): Double =
        LumaPlane.WIDTH.toDouble() / src.width.coerceAtLeast(1)

    private companion object {
        /** Nanosecondes par microseconde : `notePaint` parle en microsecondes. */
        const val NANOS_PER_US = 1_000L
    }
}
