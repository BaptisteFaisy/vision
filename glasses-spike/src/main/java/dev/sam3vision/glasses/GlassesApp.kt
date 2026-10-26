package dev.sam3vision.glasses

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.core.Wearables

/**
 * Point d'entrée du spike : initialise le SDK Meta une seule fois.
 *
 * L'initialisation doit précéder toute autre appel DAT (`createSession`,
 * `startRegistration`, …). L'attestation est désactivée en Developer Mode,
 * d'où les placeholders `APPLICATION_ID`/`CLIENT_TOKEN` à "0".
 */
class GlassesApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Wearables.initialize(this).onFailure { error, _ ->
            Log.e(TAG, "Échec d'initialisation DAT : ${error.description}")
        }
    }

    private companion object {
        const val TAG = "GlassesSpike"
    }
}
