package dev.sam3vision.app

import android.content.Context
import dev.sam3vision.core.haptics.HapticPattern

/**
 * Haptique iSee : il ne reste qu'un retour, l'appui long (micro) — une salve franche
 * unique au seuil ([HapticLoop.startHold]), puis silence pendant toute la dictée ;
 * le relâchement laisse une queue de 200 ms. Plus de nappe : aucune vibration
 * pendant que l'utilisateur parle (décision user 28/10).
 *
 * Le moteur (vibreur, envoi des motifs, garde `runCatching`) vit dans [HapticLoop].
 * Le créneau du balayage vertical a été supprimé avec la navigation entre messages.
 */
class IseeHaptics(context: Context) {

    private val motor = HapticLoop(context)

    /** Appui long : salve franche unique, puis silence pendant la dictée. */
    fun holdStart() = motor.startHold()

    /** Relâchement : queue de 200 ms. */
    fun holdEnd() = motor.release()

    /** Traversée : le nouvel état confirmé du feu se sent (vert = double salve, rouge = créneau long). */
    fun crossing(state: dev.sam3vision.core.crossing.CrossingState) {
        val wave = when (state) {
            dev.sam3vision.core.crossing.CrossingState.Green -> HapticPattern.crossingGreen()
            dev.sam3vision.core.crossing.CrossingState.Red -> HapticPattern.crossingRed()
        }
        motor.play(wave)
    }

    /** Coupe tout motif en cours, tenue comprise (arrêt de l'app, geste perdu). */
    fun cancelAll() {
        motor.stop()
    }
}