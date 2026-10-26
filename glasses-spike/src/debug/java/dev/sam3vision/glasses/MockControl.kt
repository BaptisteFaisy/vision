package dev.sam3vision.glasses

import android.content.Context

/**
 * Porte de la démo sans matériel : n'existe que dans l'APK **debug**.
 *
 * En release, cette classe est remplacée par celle de `src/release` (mêmes
 * signatures, sans effet) et l'artefact `mwdat-mockdevice` n'est plus dans
 * l'APK (`debugImplementation`, `build.gradle.kts`) : une paire simulée ne peut
 * donc plus être prise pour de vraies lunettes dans un build public.
 */
internal object MockControl {

    /** Vrai si la démo sans matériel est dans ce build : le bouton « Mock » s'affiche. */
    const val available = true

    /** Active le mock et l'alimente avec la caméra du téléphone. */
    fun start(context: Context, log: (String) -> Unit) {
        MockGlassesHarness.enable(context, log)
        MockGlassesHarness.usePhoneCameraFeed(context, log)
    }

    /** Retire les paires simulées, y compris celles laissées par un run précédent. */
    fun dropSimulatedPairs(context: Context, log: (String) -> Unit) =
        MockGlassesHarness.dropSimulatedPairs(context, log)
}