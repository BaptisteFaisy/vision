package dev.sam3vision.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptique iSee : deux motifs **distinguables d'emblée**, comme demandé :
 * - appui long (micro) : une salve franche au démarrage (deux temps, amplitude forte),
 * - balayage vertical : un créneau léger continu tant que le doigt glisse.
 */
class IhearHaptics(context: Context) {

    private val vibrator: Vibrator

    init {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /** Appui long : salve perceptible unique (deux temps forts séparés d'un creux). */
    fun holdStart() {
        waveform(
            longArrayOf(0, HOLD_ON_MS, HOLD_OFF_MS, HOLD_ON_MS),
            intArrayOf(0, HOLD_AMP, 0, HOLD_AMP),
            -1,
        )
    }

    /** Relâchement : micro-signal de fin, volontairement court. */
    fun holdEnd() {
        vibrateSafe {
            VibrationEffect.createOneShot(END_ON_MS, END_AMP)
        }
    }

    /**
     * Balayage vertical démarré : créneau léger **répété** (doigt qui glisse) —
     * ressentit nettement différent de la salve de l'appui long.
     */
    fun verticalSwipeStart() {
        if (swiping) return
        swiping = true
        // trois amplitudes pour trois timings : le creux porte aussi son 0,
        // sinon VibrationEffect.createWaveform lève IllegalArgumentException
        // et l'app crashe dès la première marche du balayage.
        waveform(
            longArrayOf(0, SWIPE_ON_MS, SWIPE_OFF_MS),
            intArrayOf(0, SWIPE_AMP, 0),
            1, // répète la boucle « marche/creux » tant que le doigt glisse
        )
    }

    /** Fin du balayage vertical : créneau arrêté. */
    fun verticalSwipeEnd() {
        if (!swiping) return
        swiping = false
        vibrator.cancel()
    }

    fun cancelAll() {
        swiping = false
        vibrator.cancel()
    }

    /** Construction + envoi protégés : un paramètre invalide ne doit jamais tuer l'app —
     * l'effet est construit *dans* le runCatching. */
    private fun waveform(timings: LongArray, amplitudes: IntArray, repeat: Int) {
        vibrateSafe { VibrationEffect.createWaveform(timings, amplitudes, repeat) }
    }

    private inline fun vibrateSafe(effect: () -> VibrationEffect) {
        runCatching { vibrator.vibrate(effect()) }
    }

    private var swiping = false

    companion object {
        private const val HOLD_ON_MS = 70L
        private const val HOLD_OFF_MS = 90L
        private const val HOLD_AMP = 255
        private const val END_ON_MS = 35L
        private const val END_AMP = 120
        private const val SWIPE_ON_MS = 30L
        private const val SWIPE_OFF_MS = 60L
        private const val SWIPE_AMP = 96
    }
}
