package dev.sam3vision.app.glasses.bench

import android.content.Context

/**
 * Contrat de l'appareil simulé, partagé par les deux variantes de build.
 *
 * `MockControl` a deux implémentations — `src/debug` (le vrai mock) et
 * `src/release` (un stub muet) — choisies par la variante, alors que le code du
 * banc est compilé dans les deux. Cette interface est donc la seule définition
 * de l'API : une signature qui bouge d'un côté casse la compilation de l'autre,
 * au lieu de dériver en silence.
 *
 * `available` reste hors de l'interface, en `const val` par variante (`true` en
 * debug, `false` en release) : c'est une constante de compilation, et c'est ce
 * qui permet à l'appelant de retirer le bouton « Mock » de l'APK public.
 */
internal interface BenchMock {

    /** Active l'appareil simulé en debug ; journalise et ne fait rien en release. */
    fun start(context: Context, log: (String) -> Unit)

    /** Retire les paires simulées, y compris celles laissées par un run précédent. */
    fun dropSimulatedPairs(context: Context, log: (String) -> Unit)
}