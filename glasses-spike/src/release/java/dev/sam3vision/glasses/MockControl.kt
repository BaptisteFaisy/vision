package dev.sam3vision.glasses

import android.content.Context

/**
 * Porte de la démo sans matériel en **release** : rien.
 *
 * L'artefact `mwdat-mockdevice` n'est plus dans l'APK public
 * (`debugImplementation`), donc cette variante ne peut ni apparier ni retirer une
 * paire simulée. Les signatures sont celles de `src/debug` pour que l'appelant
 * ([MainActivity]) soit identique dans les deux variantes.
 */
internal object MockControl {

    /** Faux en release : le bouton « Mock » n'existe pas dans cet APK. */
    const val available = false

    @Suppress("UNUSED_PARAMETER")
    fun start(context: Context, log: (String) -> Unit) =
        log("appareil simulé absent de ce build (release)")

    /** Rien à retirer : aucune paire simulée ne peut exister ici. */
    @Suppress("UNUSED_PARAMETER")
    fun dropSimulatedPairs(context: Context, log: (String) -> Unit) = Unit
}