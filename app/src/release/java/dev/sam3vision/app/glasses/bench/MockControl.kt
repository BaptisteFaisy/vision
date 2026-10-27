package dev.sam3vision.app.glasses.bench

import android.content.Context

/**
 * Porte de la démo sans matériel en **release** : rien.
 *
 * L'artefact `mwdat-mockdevice` n'est pas dans l'APK public
 * (`debugImplementation`), donc cette variante ne peut ni apparier ni retirer une
 * paire simulée. Le contrat est celui de `src/debug` ([BenchMock]) pour que
 * l'appelant ([BenchScreen]) soit identique dans les deux variantes.
 */
internal object MockControl : BenchMock {

    /** Faux en release : le bouton « Mock » n'existe pas dans cet APK. */
    const val available = false

    @Suppress("UNUSED_PARAMETER")
    override fun start(context: Context, log: (String) -> Unit) =
        log("appareil simulé absent de ce build (release)")

    /** Rien à retirer : aucune paire simulée ne peut exister ici. */
    @Suppress("UNUSED_PARAMETER")
    override fun dropSimulatedPairs(context: Context, log: (String) -> Unit) = Unit
}