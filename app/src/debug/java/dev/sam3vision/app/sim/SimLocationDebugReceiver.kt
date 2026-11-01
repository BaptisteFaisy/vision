package dev.sam3vision.app.sim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Bundle
import android.util.Log

/**
 * **Debug uniquement** — rejoue une position de balade depuis l'hôte, sans téléphone.
 *
 * `adb emu geo fix` ne livre pas de précision exploitable, et iSee jette en silence les fixes
 * sans précision (`NavFix.isUsable()`) ou trop grossiers (`NavTuning.maxAccM`) : le guidage
 * reste muet sans la moindre erreur. Ce receiver, présent seulement dans le manifeste de
 * `debug`, pousse à la place une vraie `Location` complète (précision, heure) dans un test
 * provider `gps`/`network` — le chemin exact qu'écoute `VoiceNavTracker`
 * (`requestLocationUpdates`, 2 s) :
 *
 * `adb shell am broadcast -a dev.sam3vision.app.sim.LOCATION --ef lat 48.85 --ef lon 2.29`
 *
 * `dev.sam3vision.app.sim.LOCATION_CLEAR` retire les test providers (les vrais reprennent).
 * Le build release ne contient ni ce receiver ni la permission associée.
 */
class SimLocationDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sink = sinkOf(context)
        if (intent.action == ACTION_CLEAR) {
            sink.clear()
            return
        }
        when (val parsed = parseSimFix(intent.extras.asTextMap())) {
            is SimParse.Rejected -> Log.e(SIM_LOG_TAG, "position ignorée : ${parsed.reason}")
            is SimParse.Ok -> sink.push(parsed.fix)
        }
    }

    companion object {
        /** Action du broadcast de position (extras `lat`, `lon`, `acc`, `bearing`, `speed`, `ms`). */
        const val ACTION_LOCATION = "dev.sam3vision.app.sim.LOCATION"

        /** Action de nettoyage : retire les test providers, sans tuer l'app. */
        const val ACTION_CLEAR = "dev.sam3vision.app.sim.LOCATION_CLEAR"

        /**
         * Un broadcast réveille une instance neuve : le sink (et donc l'installation des test
         * providers) est porté par le processus, pas par le receiver.
         */
        private var sink: SimLocationSink? = null

        private fun sinkOf(context: Context): SimLocationSink = synchronized(this) {
            sink ?: SimLocationSink(
                context.applicationContext.getSystemService(LocationManager::class.java),
            ).also { sink = it }
        }
    }
}

/**
 * Extras d'un intent en texte : `am --ef` pose un `Float`, `--es` une `String` — la lecture
 * (`toString`) traite les deux, et `null` reste « extra absent ».
 */
private fun Bundle?.asTextMap(): Map<String, String?>? {
    val bundle = this ?: return null
    return bundle.keySet().associateWith { key -> bundle.get(key)?.toString() }
}
