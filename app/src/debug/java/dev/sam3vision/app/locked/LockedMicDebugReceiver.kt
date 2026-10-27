package dev.sam3vision.app.locked

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * **Debug uniquement** — déclenche le geste du micro verrouillé sans doigt.
 *
 * L'écran de verrouillage n'est pas scriptable (`uiautomator` ne voit pas les notifications
 * d'une fenêtre sécurisée) et le service n'est pas exporté (il ne doit pas l'être). Cette
 * receiver, présente seulement dans le manifeste de `debug`, permet donc de rejouer la recette
 * à l'identique :
 *
 * `adb shell am broadcast -a dev.sam3vision.app.locked.DEBUG_TOGGLE`
 *
 * Le build release ne la contient pas : aucun chemin de production ne passe par ici.
 */
class LockedMicDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        LockedMicBridge.toggle(context)
    }
}
