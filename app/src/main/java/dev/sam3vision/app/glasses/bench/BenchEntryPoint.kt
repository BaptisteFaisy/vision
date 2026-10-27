package dev.sam3vision.app.glasses.bench

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Demande d'ouverture du banc posée **hors composition** : c'est ce qui garde la
 * recette adb utilisable alors que le banc n'est plus une `Activity`
 * (`am start -n dev.sam3vision.app/.MainActivity --ez mock true --ez analyze true`).
 *
 * `AppNav` observe [pending] et pousse `Route.Bench` ; `BenchScreen` consomme les
 * options au premier affichage. Une seule demande vivante à la fois : sans
 * consommation, un retour sur l'écran rejouerait un lancement déjà fait.
 */
internal object BenchEntryPoint {

    private val _pending = MutableStateFlow<SpikeLaunch.Options?>(null)

    val pending: StateFlow<SpikeLaunch.Options?> = _pending

    fun request(options: SpikeLaunch.Options) {
        _pending.value = options
    }

    /** Rend les options en attente et remet le compteur à zéro. */
    fun consume(): SpikeLaunch.Options? = _pending.value.also { _pending.value = null }
}