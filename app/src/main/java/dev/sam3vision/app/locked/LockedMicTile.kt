package dev.sam3vision.app.locked

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * La tuile des réglages rapides : elle reste **atteignable depuis l'écran de verrouillage**,
 * là où la notification peut être masquée par le réglage « contenu sensible ».
 *
 * Elle ne décide rien : elle lit l'état publié ([LockedMicBridge]) et bascule. Service non armé
 * (l'app n'a pas été ouverte depuis le démarrage), elle le dit au lieu d'échouer en silence :
 * un service de premier plan ne se démarre pas depuis une tuile.
 */
class LockedMicTile : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        if (!LockedMicBridge.armed) {
            Toast.makeText(this, "Ouvre d'abord iSee", Toast.LENGTH_SHORT).show()
            return
        }
        if (!LockedMicBridge.toggle(this)) {
            Toast.makeText(this, "micro verrouillé indisponible", Toast.LENGTH_SHORT).show()
            return
        }
        refresh()
    }

    /** Libellé et sous-titre suivent la phase : on lit la tuile sans la toucher. */
    private fun refresh() {
        val tile = qsTile ?: return
        val view = LockedMicBridge.state.value.view()
        tile.label = "iSee micro"
        tile.state = if (LockedMicBridge.armed) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = view.action ?: view.title
        tile.updateTile()
    }
}