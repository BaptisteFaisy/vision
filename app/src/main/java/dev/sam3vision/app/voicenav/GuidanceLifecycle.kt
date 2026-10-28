package dev.sam3vision.app.voicenav

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

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
     * Démarre le service de fond ; no-op si W1 absent ou sans permission
     * d'arrière-plan. Idempotent (le service ignore les démarrages doublés).
     */
    fun startBackground(context: Context) {
        val app = context.applicationContext
        if (!servicePresent(app)) return
        runCatching {
            val intent = serviceIntent(app)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent)
            else app.startService(intent)
        }
    }

    /**
     * Arrête le service de fond ; no-op s'il ne tourne pas (arrêt direct,
     * sans passer par son action stop : aucun nom d'action à partager).
     */
    fun stopBackground(context: Context) {
        val app = context.applicationContext
        runCatching { app.stopService(serviceIntent(app)) }
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
