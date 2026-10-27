package dev.sam3vision.app.glasses.bench

import android.content.Context

/**
 * Porte de la démo sans matériel : n'existe que dans l'APK **debug** d'iSee.
 *
 * En release, cette classe est remplacée par celle de `src/release` (même contrat
 * [BenchMock], sans effet) et l'artefact `mwdat-mockdevice` n'est plus dans l'APK
 * (`debugImplementation`, `app/build.gradle.kts`) : une paire simulée ne peut donc
 * plus être prise pour de vraies lunettes dans un build public.
 */
internal object MockControl : BenchMock {

    /** Vrai si la démo sans matériel est dans ce build : le bouton « Mock » s'affiche. */
    const val available = true

    /** Active le mock et l'alimente avec la caméra du téléphone. */
    override fun start(context: Context, log: (String) -> Unit) {
        MockGlassesHarness.enable(context, log)
        MockGlassesHarness.usePhoneCameraFeed(context, log)
    }

    /** Retire les paires simulées, y compris celles laissées par un run précédent. */
    override fun dropSimulatedPairs(context: Context, log: (String) -> Unit) =
        MockGlassesHarness.dropSimulatedPairs(context, log)
}