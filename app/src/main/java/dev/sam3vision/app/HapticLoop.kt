package dev.sam3vision.app

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import dev.sam3vision.core.haptics.HapticPattern
import dev.sam3vision.core.haptics.Waveform

/**
 * Le moteur haptique de l'app : **un seul** vibreur (résolu une fois, [resolveVibrator])
 * et **un seul** envoi de motif. Les motifs eux-mêmes vivent en `:core`
 * ([HapticPattern]) : testables en JVM, partagés par les deux gestes.
 *
 * Les trois temps de l'appui long :
 * - [startHold] : la salve franche de [HapticPattern.holdLoop] au seuil, puis une
 *   **nappe légère** tant que le doigt reste posé — un retour continu, pas un « tick » ;
 * - [release] : la nappe est coupée et la **queue de 200 ms** marque le relâchement ;
 * - [cue] : bip bref de fin de dictée, à jouer **micro fermé** — le micro reste ouvert
 *   le temps que la fin du dernier mot arrive, un bip joué avant entrerait dans la
 *   transcription (c'est pour ça que la dictée l'appelle après `stop()`, pas ici).
 *
 * Toute erreur du moteur est avalée : l'appui long doit rester fonctionnel sans vibreur.
 * L'effet est **construit dans** le `runCatching` — leçon du crash du balayage vertical
 * (#129) : un motif refusé levait `IllegalArgumentException` hors de la garde et
 * fermait l'app au premier geste.
 */
class HapticLoop(context: Context) {

    private val vibrator: Vibrator? = resolveVibrator(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())

    /** Envoie [wave] (rejoué jusqu'à [stop] si `wave.looped`) ; sans moteur, ne fait rien. */
    fun play(wave: Waveform) {
        val motor = vibrator?.takeIf { it.hasVibrator() } ?: return
        val amplitudes = if (motor.hasAmplitudeControl()) wave.amplitudesArray() else defaultAmplitudes(wave)
        runCatching {
            motor.vibrate(VibrationEffect.createWaveform(wave.timingsArray(), amplitudes, wave.repeatIndex))
        }
    }

    /** Appui long : salve franche au seuil, puis nappe pendant toute la tenue. */
    fun startHold() = play(HapticPattern.holdLoop())

    /** Relâchement : la nappe est coupée, la queue de 200 ms est posée. */
    fun release() {
        stop()
        play(HapticPattern.releaseTail())
    }

    /** Coupe tout motif en cours, tenue comprise. */
    fun stop() {
        runCatching { vibrator?.cancel() }
    }

    /** Bip de fin de dictée : bref, discret, jamais chevauché par une nappe. */
    fun cue() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, CUE_VOLUME)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, CUE_MS)
            // Le moteur de tonalité ne se libère qu'après la sortie du son.
            handler.postDelayed({ runCatching { tone.release() } }, CUE_MS + CUE_GRACE_MS)
        }
    }

    /** Moteur sans contrôle de force : chaque amplitude non nulle devient « défaut ». */
    private fun defaultAmplitudes(wave: Waveform): IntArray =
        IntArray(wave.amplitudes.size) { index ->
            if (wave.amplitudes[index] == 0) 0 else Waveform.DEFAULT_AMPLITUDE
        }

    companion object {
        /** Volume du bip (sur 100) : un repère, il ne couvre pas la voix. */
        private const val CUE_VOLUME = 40

        /** Durée du bip : courte, ce n'est pas une sonnerie. */
        private const val CUE_MS = 120

        /** Marge avant de libérer le moteur de tonalité, le son doit avoir le temps de sortir. */
        private const val CUE_GRACE_MS = 80L
    }
}

/** Vibreur du système : le gestionnaire sur API 31+, le service historique avant. */
private fun resolveVibrator(context: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) vibratorFromManager(context) else legacyVibrator(context)

@RequiresApi(Build.VERSION_CODES.S)
private fun vibratorFromManager(context: Context): Vibrator? =
    (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator

@Suppress("DEPRECATION") // VIBRATOR_SERVICE reste le seul point d'entrée sous Android 12.
private fun legacyVibrator(context: Context): Vibrator? =
    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator