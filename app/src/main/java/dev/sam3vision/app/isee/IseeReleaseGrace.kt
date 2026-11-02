package dev.sam3vision.app.isee

import android.os.Handler
import android.os.Looper

/** Grâce avant extinction GPU à la mise en fond : un appui message ne tue plus la carte. */
internal const val BACKGROUND_GRACE_MS = 60_000L

/** Tâche planifiée annulable (main `Handler` en prod, faux manuel en test). */
internal fun interface Cancellable {
    fun cancel()
}

/**
 * Extinction GPU différée d'iSee : `arm` à la mise en fond, `cancel` au retour,
 * tir unique à l'échéance — un aller-retour de moins d'une minute retrouve la
 * carte chaude au lieu de payer 30 s de réveil. `shouldRelease` évite d'éteindre
 * une carte déjà retombée (socket fermé : `Modal` l'a réduite, un `/release`
 * réveillerait un conteneur pour le suicider). Gel Android : le tir ne fait
 * qu'éteindre — jamais d'arrêt de sonde, aucun ordre d'exécution dangereux.
 * Actions passées à chaque bras : la sonde armée est celle du moment, jamais
 * une référence gardée.
 */
internal class ReleaseGrace(
    private val delayMs: Long = BACKGROUND_GRACE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val schedule: (Long, () -> Unit) -> Cancellable = MainPostDelayed,
) {
    private var pending: Cancellable? = null
    private var armedAtMs: Long? = null
    private var armedRelease: (() -> Boolean)? = null
    private var armedExtinguish: (() -> Unit)? = null

    /** Vrai entre [arm] et [cancel] ou le tir : les tests lisent le bras. */
    val armed: Boolean get() = pending != null

    /** Arme (réarme) : tout bras précédent est annulé, un seul tir à venir. */
    fun arm(shouldRelease: () -> Boolean = { true }, extinguish: () -> Unit) {
        cancel()
        armedAtMs = clock()
        armedRelease = shouldRelease
        armedExtinguish = extinguish
        pending = schedule(delayMs) { fire() }
    }

    /** Annule le bras : le retour au premier plan ne doit plus rien éteindre. */
    fun cancel() {
        pending?.cancel()
        pending = null
        armedAtMs = null
        armedRelease = null
        armedExtinguish = null
    }

    /** Échéance : un seul tir par bras, jamais prématuré, jamais sur carte retombée. */
    private fun fire() {
        pending = null
        val at = armedAtMs
        val release = armedRelease
        val extinguish = armedExtinguish
        armedAtMs = null
        armedRelease = null
        armedExtinguish = null
        if (at == null || extinguish == null || clock() - at < delayMs) return
        if (release?.invoke() == false) return
        extinguish()
    }
}

/** Ordonnanceur prod : `postDelayed` sur le thread principal, annulé par `cancel`. */
private val MainPostDelayed: (Long, () -> Unit) -> Cancellable = { delay, task ->
    val handler = Handler(Looper.getMainLooper())
    val runnable = Runnable { task() }
    handler.postDelayed(runnable, delay)
    Cancellable { handler.removeCallbacks(runnable) }
}
