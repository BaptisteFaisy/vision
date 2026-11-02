package dev.sam3vision.app.voicenav

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Cycle de vie du guidage hors écran : l'app en fond (home, verrouillage)
 * garde le GPS et la voix via le service de premier plan W1, au premier
 * plan l'écran suffit et le service s'efface.
 *
 * Le service est désigné par son nom ([SERVICE_CLASS], contrat W1) plutôt
 * que par sa classe : ce fichier compile sans lui et devient actif dès
 * son merge (`resolveService` rend `null` avant, chaque appel est no-op).
 * Ne lève jamais (vérifié au retour premier plan comme à la mise en fond).
 */
object GuidanceLifecycle {

    private const val TAG = "GuidanceLifecycle"

    /**
     * Service de fond du guidage (lot W1) : ce nom verrouille le contrat
     * inter-lots — si W1 le nomme autrement, le fond reste au comportement
     * actuel (pas de service) au lieu de planter ([GuidanceLifecycleTest]).
     */
    const val SERVICE_CLASS = "dev.sam3vision.app.voicenav.GuidanceTrackingService"

    /**
     * Seul le guidage actif mérite le service de fond : en confirmation
     * aucun GPS ne tourne, au repos il n'y a rien à maintenir.
     */
    fun wantsBackground(state: VoiceNavUiState): Boolean =
        state is VoiceNavUiState.Guiding

    /**
     * Faut-il démarrer le service de fond pour cet état ? Un **changement de configuration**
     * (rotation, redimensionnement) n'est pas une sortie d'écran : l'activité revient dans
     * la même passe, le guidage continue, et démarrer le service à ce moment-là le fait
     * partir au pire instant (caméra démontée, Compose reconstruit, thread principal occupé).
     * Fonction pure, testée en JVM (`GuidanceLifecycleTest`).
     */
    fun shouldStartBackground(state: VoiceNavUiState, changingConfigurations: Boolean): Boolean =
        !changingConfigurations && wantsBackground(state)

    /**
     * Démarre le service de fond ; no-op si W1 absent, sans permission d'arrière-plan ou
     * pendant un changement de configuration. Idempotent (le service ignore les démarrages
     * doublés). Un refus système est **tracé** : c'est la panne silencieuse à instruire.
     */
    fun startBackground(context: Context) {
        val app = context.applicationContext
        if (!servicePresent(app)) return
        if (hostIsChangingConfigurations(context)) return
        runCatching { app.startForegroundService(serviceIntent(app)) }
            .onFailure { Log.w(TAG, "démarrage du guidage refusé : ${it.javaClass.simpleName}") }
    }

    /**
     * Arrête le service de fond ; no-op s'il ne tourne pas (arrêt direct,
     * sans passer par son action stop : aucun nom d'action à partager).
     */
    fun stopBackground(context: Context) {
        val app = context.applicationContext
        runCatching { app.stopService(serviceIntent(app)) }
    }

    /**
     * Vrai si le contexte vient d'une Activity en pleine reconfiguration (rotation,
     * redimensionnement de fenêtre). Défense en profondeur : les appelants directs
     * ([VoiceNavRoute], accueil iSee) gardent déjà le cas.
     */
    private fun hostIsChangingConfigurations(context: Context): Boolean {
        var cursor: Context? = context
        while (cursor is ContextWrapper) {
            if (cursor is Activity) return cursor.isChangingConfigurations
            cursor = cursor.baseContext
        }
        return false
    }

    /** Intent explicite vers le service W1, par nom de classe. */
    private fun serviceIntent(app: Context): Intent =
        Intent().setComponent(ComponentName(app, SERVICE_CLASS))

    /** `true` si le service W1 est déclaré (donc démarrable). */
    private fun servicePresent(app: Context): Boolean =
        runCatching {
            app.packageManager.resolveService(serviceIntent(app), PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull() != null
}
