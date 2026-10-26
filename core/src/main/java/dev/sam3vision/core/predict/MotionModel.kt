package dev.sam3vision.core.predict

/**
 * Mouvement d'**un seul id** entre deux réponses serveur.
 *
 * Modèle retenu : vitesse constante lissée (filtre alpha-bêta dégradé en version
 * scalaire), ancre = dernière boîte serveur. Entre deux réponses, la boîte affichée
 * vaut `ancre + vitesse × âge`, bornée à [MAX_HORIZON_MS] pour qu'un objet arrêté
 * ne parte pas en fuite.
 *
 * Le saut de l'ancre est le risque n°1 : si la boîte serveur diverge vraiment de la
 * prédiction (IoU < [minIou]), on **ré-ancre brutalement** plutôt que de lisser — sinon
 * le masque colle au mauvais objet pendant une seconde. En deçà, l'ancre neuve est
 * `serveur × (1 − blend) + prédit × blend`, ce qui absorbe le rebond du tracker.
 */
class MotionModel(
    anchor: Box,
    anchorAtMs: Long,
    private val blend: Float = DEFAULT_BLEND,
    private val beta: Float = DEFAULT_BETA,
    private val minIou: Float = DEFAULT_MIN_IOU,
) {

    /** Ce qu'une observation serveur a fait au modèle — à journaliser pour régler `blend`. */
    enum class Outcome { FOLLOWED, CORRECTED, JUMPED, STALE }

    var anchorBox: Box = anchor
        private set
    var anchorAtMs: Long = anchorAtMs
        private set
    var velocity: Velocity = Velocity.Zero
        private set

    /** Nombre d'observations serveur incorporées : en dessous de 2, la vitesse est une vue. */
    var observations: Int = 0
        private set

    /** Sauts (ré-ancrage brutal) : beaucoup de sauts = tracker instable ou `blend` trop fort. */
    var jumps: Int = 0
        private set

    /**
     * Incorpore la boîte serveur [box] datée de [timestampMs]. [maxSpeedPxPerSec] borne
     * la vitesse (calculée depuis la taille d'image : un objet ne traverse pas l'écran
     * en un tiers de seconde). Renvoie le verdict du garde-fou de dérive.
     */
    fun observe(box: Box, timestampMs: Long, maxSpeedPxPerSec: Float): Outcome {
        val dt = (timestampMs - anchorAtMs) / 1000f
        if (dt <= 0f) {
            anchorBox = box
            return Outcome.STALE
        }
        val predicted = predictAt(timestampMs)
        val raw = rawVelocity(box, dt).limited(maxSpeedPxPerSec)
        return if (box.iou(predicted) < minIou) {
            jump(box, raw, timestampMs)
        } else {
            smooth(box, predicted, raw, timestampMs, maxSpeedPxPerSec)
        }
    }

    /** Boîte attendue à [timestampMs] ; en arrière de l'ancre, on retombe sur l'ancre. */
    fun predictAt(timestampMs: Long): Box {
        val ageMs = (timestampMs - anchorAtMs).coerceIn(0L, MAX_HORIZON_MS)
        val shift = velocity.over(ageMs / 1000f)
        return anchorBox.translated(shift.x, shift.y).grown(shift.width, shift.height)
    }

    /**
     * Confiance de 1 (ancre fraîche) à 0 (âge ≥ [confidenceZeroMs]) : c'est la **fraîcheur
     * du masque serveur**, pas la fiabilité de la vitesse. Un objet vu une seule fois a
     * un masque parfaitement valide — c'est son extrapolation qui est incertaine
     * (voir [velocityReliable]).
     */
    fun confidenceAt(
        timestampMs: Long,
        confidenceZeroMs: Long = CONFIDENCE_ZERO_MS,
    ): Float {
        val ageMs = (timestampMs - anchorAtMs).coerceAtLeast(0L)
        val freshness = 1f - ageMs.toFloat() / confidenceZeroMs.coerceAtLeast(1L)
        return freshness.coerceIn(0f, 1f)
    }

    /** La vitesse est-elle établie ? (au moins [MIN_FOR_VELOCITY] observations serveur). */
    val velocityReliable: Boolean get() = observations >= MIN_FOR_VELOCITY

    private fun jump(box: Box, raw: Velocity, timestampMs: Long): Outcome {
        anchorBox = box
        velocity = raw
        anchorAtMs = timestampMs
        observations += 1
        jumps += 1
        return Outcome.JUMPED
    }

    private fun smooth(
        box: Box,
        predicted: Box,
        raw: Velocity,
        timestampMs: Long,
        maxSpeedPxPerSec: Float,
    ): Outcome {
        val outcome = if (box.iou(predicted) < JUMP_HINT_IOU) Outcome.CORRECTED else Outcome.FOLLOWED
        anchorBox = box.lerp(predicted, blend)
        velocity = Velocity(
            x = velocity.x + beta * (raw.x - velocity.x),
            y = velocity.y + beta * (raw.y - velocity.y),
            width = velocity.width + beta * (raw.width - velocity.width),
            height = velocity.height + beta * (raw.height - velocity.height),
        ).limited(maxSpeedPxPerSec)
        anchorAtMs = timestampMs
        observations += 1
        return outcome
    }

    private fun rawVelocity(box: Box, dtSeconds: Float): Velocity = Velocity(
        x = (box.centerX - anchorBox.centerX) / dtSeconds,
        y = (box.centerY - anchorBox.centerY) / dtSeconds,
        width = (box.width - anchorBox.width) / dtSeconds,
        height = (box.height - anchorBox.height) / dtSeconds,
    )

    companion object {
        /** Poids de la prédiction dans la nouvelle ancre : 0 = aucune inertie, 0,5 = mou. */
        const val DEFAULT_BLEND = 0.2f

        /** Gain de lissage de la vitesse (alpha-bêta, bêta). */
        const val DEFAULT_BETA = 0.3f

        /** Au-dessous de cet IoU, la boîte serveur n'est plus « le même endroit » : saut. */
        const val DEFAULT_MIN_IOU = 0.15f

        /** IoU de favorisation : sous ce seuil, la divergence est consignée sans saut. */
        const val JUMP_HINT_IOU = 0.35f

        /** Extrapolation au-delà de 400 ms : on fige, un modèle linéaire dérape. */
        const val MAX_HORIZON_MS = 400L

        /** Âge auquel la confiance tombe à 0 : à régler sur la latence serveur mesurée. */
        const val CONFIDENCE_ZERO_MS = 450L

        /** En-dessous de cet âge, le masque serveur est encore pertinent. */
        const val MASK_FLOOR = 0.7f

        /** Observations minimum avant de faire confiance à la vitesse estimée. */
        const val MIN_FOR_VELOCITY = 2
    }
}
