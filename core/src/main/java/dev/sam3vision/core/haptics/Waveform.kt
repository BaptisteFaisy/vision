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
 * `IhearHaptics.kt:52`). La garde est donc dans `init`, et l'envoi côté app reste
 * enveloppé dans un `runCatching` : un motif refusé ne doit jamais tuer l'app.
 */
class Waveform(
    val timings: List<Long>,
    val amplitudes: List<Int>,
    val repeatIndex: Int = NO_REPEAT,
) {
    init {
        require(timings.isNotEmpty()) { "au moins un timing est requis" }
        require(timings.size == amplitudes.size) {
            "timings et amplitudes doivent avoir la même taille (${timings.size} vs ${amplitudes.size})"
        }
        require(timings.all { it >= 0 }) { "les timings sont en millisecondes positives" }
        require(amplitudes.all { it == DEFAULT_AMPLITUDE || it in MIN_AMPLITUDE..MAX_AMPLITUDE }) {
            "amplitude hors bornes : $amplitudes"
        }
        require(repeatIndex == NO_REPEAT || repeatIndex in timings.indices) {
            "repeatIndex invalide : $repeatIndex"
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

/** Les motifs de l'app, en `:core` : testables en JVM, partagés par les deux gestes. */
object HapticPattern {
    /** Salve de l'appui long : franche, deux temps forts séparés d'un creux. */
    const val HOLD_MS = 70L
    const val HOLD_OFF_MS = 90L
    const val HOLD_AMPLITUDE = 255

    /** Nappe de la tenue : longue, douce, ce n'est pas un « tick » de notification. */
    const val LOOP_MS = 300L
    const val LOOP_OFF_MS = 60L
    const val LIGHT_AMPLITUDE = 80

    /** Créneau du balayage vertical : léger et court, il marque chaque marche. */
    const val SWIPE_MS = 30L
    const val SWIPE_OFF_MS = 60L
    const val SWIPE_AMPLITUDE = 96

    /** Queue du relâchement : la tension retombe, elle ne s'arrête pas net. */
    const val TAIL_MS = 200L
    const val TAIL_AMPLITUDE = 120

    /**
     * Appui long : la salve part au seuil, puis la nappe **boucle** depuis son premier
     * temps fort jusqu'à l'arrêt — le doigt posé vibre tant qu'il reste posé.
     *
     * Les creux portent leur `0` : chaque timing a son amplitude, sinon l'effet est
     * refusé par le moteur.
     */
    fun holdLoop(
        salveAmplitude: Int = HOLD_AMPLITUDE,
        loopAmplitude: Int = LIGHT_AMPLITUDE,
    ): Waveform = Waveform(
        timings = listOf(0L, HOLD_MS, HOLD_OFF_MS, HOLD_MS, HOLD_OFF_MS, LOOP_MS, LOOP_OFF_MS),
        amplitudes = listOf(0, salveAmplitude, 0, salveAmplitude, 0, loopAmplitude, 0),
        repeatIndex = FIRST_LOOP_INDEX,
    )

    /** Relâchement : une seule salve de [ms], non bouclée. */
    fun releaseTail(
        amplitude: Int = TAIL_AMPLITUDE,
        ms: Long = TAIL_MS,
    ): Waveform = Waveform(timings = listOf(ms), amplitudes = listOf(amplitude))

    /** Balayage vertical : créneau marche/creux répété tant que le doigt glisse. */
    fun swipeCrest(
        amplitude: Int = SWIPE_AMPLITUDE,
        onMs: Long = SWIPE_MS,
        offMs: Long = SWIPE_OFF_MS,
    ): Waveform = Waveform(
        timings = listOf(0L, onMs, offMs),
        amplitudes = listOf(0, amplitude, 0),
        repeatIndex = 1,
    )

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

    /** Index de la nappe dans [holdLoop] : juste après les deux salves. */
    private const val FIRST_LOOP_INDEX = 5
}