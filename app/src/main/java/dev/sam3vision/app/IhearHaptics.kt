package dev.sam3vision.app

import android.content.Context
import dev.sam3vision.core.haptics.HapticPattern

/**
 * Haptique iSee : deux retours **distinguables d'emblée** :
 * - appui long (micro) : une salve franche au seuil, puis une nappe légère tant que le
 *   doigt reste posé ([HapticLoop.startHold]) ; le relâchement laisse une queue de 200 ms ;
 * - balayage vertical : un créneau léger **répété** tant que le doigt glisse — nettement
 *   différent de la nappe de la tenue.
 *
 * Le moteur (vibreur, envoi des motifs, garde `runCatching`) vit dans [HapticLoop] ; les
 * motifs eux-mêmes en `:core` ([HapticPattern]). Une seule implémentation pour les deux
 * gestes : ce fichier ne fait que l'ordre des appels.
 */
class IhearHaptics(context: Context) {

    private val motor = HapticLoop(context)

    private var swiping = false

    /** Appui long : salve franche puis nappe continue pendant toute la tenue. */
    fun holdStart() = motor.startHold()

    /** Relâchement : nappe coupée, queue de 200 ms. */
    fun holdEnd() = motor.release()

    /** Balayage vertical démarré : créneau léger répété (doigt qui glisse). */
    fun verticalSwipeStart() {
        if (swiping) return
        swiping = true
        motor.play(HapticPattern.swipeCrest())
    }

    /** Fin du balayage vertical : créneau arrêté. */
    fun verticalSwipeEnd() {
        if (!swiping) return
        swiping = false
        motor.stop()
    }

    /** Coupe tout motif en cours, tenue comprise (arrêt de l'app, geste perdu). */
    fun cancelAll() {
        swiping = false
        motor.stop()
    }
}