package dev.sam3vision.app.glasses

import android.content.Context

/**
 * Cycle de vie du maintien de fond de la session lunettes-iSee : le téléphone
 * en veille (écran éteint, app en arrière-plan) garde le lien Bluetooth, le
 * flux caméra/micro et la voix via le service de premier plan, au lieu de
 * laisser Android geler le processus et couper la session.
 *
 * Même motif que le guidage vocal ([dev.sam3vision.app.voicenav.GuidanceLifecycle]),
 * sans contrat inter-lots : le service est livré dans le même lot, les appels
 * sont directs. Ne lève jamais.
 */
object GlassesSessionLifecycle {

    /**
     * Seule une session en cours (ou en démarrage) mérite le service de fond :
     * au repos ou arrêtée, il n'y a rien à maintenir. Pur, testé en JVM.
     */
    fun wantsBackground(phase: SessionPhase): Boolean =
        phase == SessionPhase.STARTED || phase == SessionPhase.STARTING

    /**
     * Démarre le maintien de fond ; les démarrages doublés sont sans effet (le
     * service ignore les démarrages répétés). Un démarrage refusé (arrière-plan
     * sous Android 12+) se rejoue à la prochaine reprise, jamais un FATAL.
     */
    fun startBackground(context: Context) {
        GlassesSessionService.start(context)
    }

    /** Arrête le maintien de fond ; no-op s'il ne tourne pas. */
    fun stopBackground(context: Context) {
        GlassesSessionService.stop(context)
    }
}
