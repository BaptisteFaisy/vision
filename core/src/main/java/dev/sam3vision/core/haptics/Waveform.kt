package dev.sam3vision.core.haptics

/**
 * Motif de vibration portable : timings (ms), amplitudes et index de bouclage.
 * Reproduit `VibrationEffect.createWaveform(timings, amplitudes, repeatIndex)` sans
 * dépendre d'Android — la couche app recopie simplement les deux tableaux.
 *
 * Convention AOSP : le premier couple est une attente (durée 0 et amplitude 0 =
 * démarrer tout de suite) et [repeatIndex] pointe le premier élément de la boucle.
 *
 * Un motif incohérent ne casse pas ici mais **à l'envoi** : le crash du balayage
 * vertical venait de trois timings pour deux amplitudes (correctif #129,
 * `IseeHaptics.kt:52`). La garde est donc dans `init`, et l'envoi côté app reste
 * enveloppé dans un `runCatching` : un motif refusé ne doit jamais tuer l'app.
 */
class Waveform(
    val timings: List<Long>,
    val amplitudes: List<Int>,
    val repeatIndex: Int = NO_REPEAT,
) {
    init {
        require(timings.isNotEmpty()) { "at least one timing is required" }
        require(timings.size == amplitudes.size) {
            "timings and amplitudes must have the same size (${timings.size} vs ${amplitudes.size})"
        }
        require(timings.all { it >= 0 }) { "timings are in positive milliseconds" }
        require(amplitudes.all { it == DEFAULT_AMPLITUDE || it in MIN_AMPLITUDE..MAX_AMPLITUDE }) {
            "amplitude out of bounds: $amplitudes"
        }
        require(repeatIndex == NO_REPEAT || repeatIndex in timings.indices) {
            "invalid repeatIndex: $repeatIndex"
        }
    }

    /** Vrai si le motif se rejoue jusqu'à `cancel()`. */
    val looped: Boolean get() = repeatIndex != NO_REPEAT

    fun timingsArray(): LongArray = timings.toLongArray()

    fun amplitudesArray(): IntArray = amplitudes.toIntArray()

    companion object {
        /** `VibrationEffect.DEFAULT_AMPLITUDE` : le moteur choisit sa force. */
        const val DEFAULT_AMPLITUDE = -1
        const val MIN_AMPLITUDE = 0
        const val MAX_AMPLITUDE = 255
        const val NO_REPEAT = -1
    }
}

/** Les motifs de l'app, en `:core` : testables en JVM, partagés par les gestes. */
object HapticPattern {
    /** Salve de l'appui long : franche, deux temps forts séparés d'un creux. */
    const val HOLD_MS = 70L
    const val HOLD_OFF_MS = 90L
    const val HOLD_AMPLITUDE = 255

    /** Queue du relâchement : la tension retombe, elle ne s'arrête pas net. */
    const val TAIL_MS = 200L
    const val TAIL_AMPLITUDE = 120

    /**
     * Appui long : la salve part au seuil, **une seule fois**, puis silence pendant
     * toute la dictée — il n'y a plus de nappe bouclée (décision user 28/10 : aucune
     * vibration pendant que l'utilisateur parle).
     *
     * Les creux portent leur `0` : chaque timing a son amplitude, sinon l'effet est
     * refusé par le moteur.
     */
    fun holdTick(
        salveAmplitude: Int = HOLD_AMPLITUDE,
    ): Waveform = Waveform(
        timings = listOf(0L, HOLD_MS, HOLD_OFF_MS, HOLD_MS),
        amplitudes = listOf(0, salveAmplitude, 0, salveAmplitude),
    )

    /** Relâchement : une seule salve de [ms], non bouclée. */
    fun releaseTail(
        amplitude: Int = TAIL_AMPLITUDE,
        ms: Long = TAIL_MS,
    ): Waveform = Waveform(timings = listOf(ms), amplitudes = listOf(amplitude))

    /** Traversée : le vert se sent — deux salves franches, « vas-y ». */
    const val CROSSING_GREEN_MS = 90L
    const val CROSSING_GREEN_OFF_MS = 110L
    const val CROSSING_GREEN_AMPLITUDE = 255

    /** Traversée : le rouge se sent — un créneau long unique, « stop ». */
    const val CROSSING_RED_MS = 320L
    const val CROSSING_RED_AMPLITUDE = 200

    /** Vert confirmé : deux salves franches séparées d'un creux, une seule fois. */
    fun crossingGreen(): Waveform = Waveform(
        timings = listOf(0L, CROSSING_GREEN_MS, CROSSING_GREEN_OFF_MS, CROSSING_GREEN_MS),
        amplitudes = listOf(0, CROSSING_GREEN_AMPLITUDE, 0, CROSSING_GREEN_AMPLITUDE),
    )

    /** Passage au rouge : un créneau long unique, une seule fois. */
    fun crossingRed(): Waveform = Waveform(
        timings = listOf(0L, CROSSING_RED_MS),
        amplitudes = listOf(0, CROSSING_RED_AMPLITUDE),
    )
}