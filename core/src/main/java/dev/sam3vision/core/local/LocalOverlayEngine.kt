package dev.sam3vision.core.local

import dev.sam3vision.core.live.LiveStats
import dev.sam3vision.core.mask.MaskMotion

/**
 * Pourquoi le calque peint — ou s'abstient — à un tick donné. Le motif est affiché dans
 * la ligne d'état du direct : un calque qui ne repeint rien doit le dire (sans
 * estimateur, keyframe périmé, peinture trop chère), jamais faire croire à un gain.
 */
enum class LocalReason {
    /** Le calque redessine l'image : mouvement fiable, keyframe récent, cadence tenue. */
    PAINT,

    /** Le calque est éteint (réglage ou drapeau de build). */
    DISABLED,

    /** Aucun keyframe reçu encore : rien à propager. */
    NO_KEYFRAME,

    /** Le keyframe est trop vieux (plus de `maxKeyframeAgeMs`) : le masque n'a plus de sens. */
    STALE_KEYFRAME,

    /** Confiance trop faible, côté keyframe ou côté mouvement : on garde l'image du serveur. */
    LOW_CONFIDENCE,

    /** Pas d'estimateur branché (lane A absente) ou pas de luminance : le calque est inerte. */
    NO_ESTIMATOR,

    /** L'estimateur a échoué (exception ou estimation absente) : on compte les échecs. */
    ESTIMATOR_FAILED,

    /** Cadence non tenue : la frame arrive trop tôt par rapport à la dernière image peinte. */
    TOO_EARLY,
}

/**
 * Verdict d'un tick du calque : [paint] dit s'il faut redessiner, [reason] pourquoi,
 * [motion] porte l'estimation retenue (bornée), [intervalMs] la cadence effective.
 */
data class LocalTick(
    val paint: Boolean,
    val reason: LocalReason,
    val motion: MaskMotion? = null,
    val keyframeAgeMs: Long = 0L,
    val intervalMs: Long = 0L,
) {
    /** Cadence tenue par le calque, en ips (0 tant qu'il ne peint rien). */
    val fps: Float get() = if (intervalMs <= 0L) 0f else 1_000f / intervalMs
}

/**
 * Cerveau du calque local (lane L13, agent D/u13) : il décide **quand** propager et
 * **quoi** peindre, il ne fait aucune estimation et aucun dessin — l'estimation vient de
 * la couture [MotionSource], le dessin de l'app.
 *
 * Le contrat de fusion (`docs/L13-FUSION.md` §3) impose [tick], [setMotion] et
 * [LocalReason] ; [onKeyframe] et [notePaint] s'y ajoutent parce que la référence
 * (luminance, confiance, âge) et le coût de peinture ne sont pas porté par le tick.
 *
 * Tout est pur JVM : aucun Android ici, donc testable sans appareil.
 */
class LocalOverlayEngine(
    initial: LocalOverlayConfig = LocalOverlayConfig(),
    motion: MotionSource = NoMotion,
) {

    /** Réglages effectifs (drapeau de build compris) : [normalized] les ramène en bornes. */
    var config: LocalOverlayConfig = initial.normalized()
        private set

    /** Estimateur branché : tant que c'est [NoMotion], le calque reste inerte. */
    @Volatile
    private var source: MotionSource = motion

    /** Luminance du keyframe de référence : l'entrée de l'estimateur (écrite par le thread IO). */
    @Volatile
    private var keyframeLuma: ByteArray? = null

    /** Cadence effective entre deux images peintes ; ralentit si la peinture est chère. */
    private var intervalMs: Long = this.config.targetIntervalMs

    /** Instant de la dernière image peinte ; [NO_INSTANT] tant qu'aucune n'a été peinte. */
    private var lastPaintAtMs: Long = NO_INSTANT

    /** Échecs d'estimateur consécutifs ; à [LocalOverlayConfig.maxFailures], on abandonne. */
    private var failures: Int = 0

    /**
     * Coût de peinture par image : [LiveStats] est **réutilisé tel quel** (fenêtre
     * glissante de `Long`), les valeurs y sont des **microsecondes** — c'est ce qui
     * permet de voir un budget de 4 ms sans perdre la moitié du milliseconde.
     */
    private val paintStats = LiveStats(PAINT_WINDOW)

    /** Remplace les réglages (bascule utilisateur, drapeau de build) et recale la cadence. */
    fun setConfig(config: LocalOverlayConfig) {
        this.config = config.normalized()
        intervalMs = intervalMs.coerceIn(this.config.targetIntervalMs, this.config.maxIntervalMs)
    }

    /** Branche l'estimateur (lane A/u10) : les échecs précédents repartent de zéro. */
    fun setMotion(source: MotionSource) {
        this.source = source
        failures = 0
    }

    /**
     * Nouveau keyframe : sa luminance devient la référence de l'estimateur et les échecs
     * repartent de zéro (le keyframe suivant peut, lui, repartir proprement).
     */
    fun onKeyframe(luma: ByteArray?) {
        keyframeLuma = luma
        failures = 0
    }

    /** Oublie tout : nouvelle session de direct. */
    fun reset() {
        keyframeLuma = null
        failures = 0
        intervalMs = config.targetIntervalMs
        lastPaintAtMs = NO_INSTANT
        paintStats.reset()
    }

    /**
     * Décision pour une frame caméra.
     *
     * @param frameAtMs instant de la frame (ms, même horloge que l'appelant)
     * @param luma luminance de la frame (plan 256×103) ; `null` ⇒ calque inerte
     * @param keyframeAgeMs âge du keyframe de référence ; négatif ⇒ aucun keyframe
     * @param confidence confiance du keyframe (score SAM maximal)
     */
    fun tick(frameAtMs: Long, luma: ByteArray?, keyframeAgeMs: Long, confidence: Float): LocalTick {
        val refusal = refusal(frameAtMs, luma, keyframeAgeMs, confidence)
        if (refusal != null) return refuse(refusal, keyframeAgeMs)
        val frame = luma ?: return refuse(LocalReason.NO_ESTIMATOR, keyframeAgeMs)
        val motion = estimate(frame) ?: return refuse(LocalReason.ESTIMATOR_FAILED, keyframeAgeMs)
        if (motion.confidence < config.minConfidence) return refuse(LocalReason.LOW_CONFIDENCE, keyframeAgeMs)
        lastPaintAtMs = frameAtMs
        return LocalTick(
            paint = true,
            reason = LocalReason.PAINT,
            motion = motion,
            keyframeAgeMs = keyframeAgeMs,
            intervalMs = intervalMs,
        )
    }

    /**
     * Coût de peinture d'une image propagée, en microsecondes. Au-delà du budget
     * (`paintBudgetMs`), la cadence est divisée par deux — le calque préfère moins
     * d'images fraîches à une image qui saccade. Elle remonte d'autant quand la peinture
     * redevient bon marché, sans jamais dépasser le plancher `minFps`.
     */
    fun notePaint(paintUs: Long) {
        paintStats.record(paintUs)
        if (paintStats.samples < PAINT_WARMUP) return
        val average = paintStats.averageMs
        intervalMs = when {
            average > config.paintBudgetUs -> (intervalMs * 2).coerceAtMost(config.maxIntervalMs)
            average * 2 < config.paintBudgetUs -> (intervalMs / 2).coerceAtLeast(config.targetIntervalMs)
            else -> intervalMs
        }
    }

    /** Motifs de refus, du moins cher au plus cher : l'estimateur n'est appelé qu'ensuite. */
    private fun refusal(
        frameAtMs: Long,
        luma: ByteArray?,
        keyframeAgeMs: Long,
        confidence: Float,
    ): LocalReason? = when {
        !config.enabled -> LocalReason.DISABLED
        keyframeAgeMs < 0L -> LocalReason.NO_KEYFRAME
        keyframeAgeMs > config.maxKeyframeAgeMs -> LocalReason.STALE_KEYFRAME
        tooEarly(frameAtMs) -> LocalReason.TOO_EARLY
        confidence < config.minConfidence -> LocalReason.LOW_CONFIDENCE
        failures >= config.maxFailures -> LocalReason.ESTIMATOR_FAILED
        luma == null || source === NoMotion -> LocalReason.NO_ESTIMATOR
        else -> null
    }

    /** Cadence : la frame arrive-t-elle trop tôt par rapport à la dernière image peinte ? */
    private fun tooEarly(frameAtMs: Long): Boolean =
        lastPaintAtMs != NO_INSTANT && frameAtMs - lastPaintAtMs < intervalMs

    /**
     * Estimation bornée : une exception de l'estimateur n'arrête jamais le direct, et
     * chaque échec compte — au bout de `maxFailures`, l'estimateur n'est plus appelé
     * avant le keyframe suivant. Un mouvement hors des bornes de [MaskMotion] (échelle
     * divergente, NaN) compte comme un échec : le warp le refuse déjà, le calque aussi.
     */
    private fun estimate(luma: ByteArray): MaskMotion? {
        val motion = try {
            source.estimateSince(keyframeLuma, luma)
        } catch (_: Throwable) {
            null
        }
        if (motion == null || !motion.isUsable) {
            failures += 1
            return null
        }
        failures = 0
        return motion
    }

    /** Tick de refus : rien n'est peint, l'intervalle courant est conservé. */
    private fun refuse(reason: LocalReason, keyframeAgeMs: Long): LocalTick =
        LocalTick(paint = false, reason = reason, keyframeAgeMs = keyframeAgeMs, intervalMs = intervalMs)

    companion object {
        /** Instant « jamais peint » : évite de tester `0` (un instant valide). */
        const val NO_INSTANT = Long.MIN_VALUE

        /** Fenêtre de mesure du coût de peinture (échantillons). */
        const val PAINT_WINDOW = 10

        /** Nombre d'échantillons avant que la cadence ne bouge (une frame ne décide pas). */
        const val PAINT_WARMUP = 2
    }
}