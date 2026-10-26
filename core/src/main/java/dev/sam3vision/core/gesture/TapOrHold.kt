package dev.sam3vision.core.gesture

/** Ce qu'un appui isolé doit déclencher. */
enum class PressKind {
    /** Court et immobile : le tap (vidéo, envoi…). */
    TAP,

    /** Tenu sans glisser : l'appui long (dictée). */
    HOLD,

    /** Glissé : ni l'un ni l'autre, c'est un geste de navigation. */
    NONE,
}

/**
 * Arbitrage d'un appui isolé entre le tap et l'appui long, sans dépendance Android.
 *
 * Un même geste ne peut pas produire les deux : la durée décide, et un glissement annule
 * tout. Les seuils par défaut sont **ceux de l'appui long de l'app** ([LongPressTrigger])
 * pour qu'un tap ne coure jamais après une dictée déjà lancée.
 */
object TapOrHold {

    /** Durée au-delà de laquelle l'appui n'est plus un tap (300 ms, comme l'appui long). */
    const val DEFAULT_THRESHOLD_MS = LongPressTrigger.DEFAULT_LONG_PRESS_MS

    /** Tolérance de glissement en pixels (≈ 8 dp). */
    const val DEFAULT_SLOP_PX = LongPressTrigger.DEFAULT_SLOP_PX

    fun classify(
        durationMs: Long,
        movedPx: Float,
        thresholdMs: Long = DEFAULT_THRESHOLD_MS,
        slopPx: Float = DEFAULT_SLOP_PX,
    ): PressKind = when {
        movedPx > slopPx -> PressKind.NONE
        durationMs >= thresholdMs -> PressKind.HOLD
        else -> PressKind.TAP
    }
}