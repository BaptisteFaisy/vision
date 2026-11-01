package dev.sam3vision.app.isee

import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Bascule dedans/dehors cote sonde : le lieu vocal pilote aussi
 * [Settings.outdoorMode] (persiste) puis resynchronise la sonde SAM. Sans
 * ca, l'ecran dit dedans mais la sonde suit les prompts dehors (sans les
 * cles). Idempotent : un lieu redit ne re-sauvegarde pas.
 */
internal class IseePlaceSync(
    private val settings: () -> Settings,
    private val updateSettings: (Settings) -> Unit,
    private val scope: CoroutineScope,
    private val save: suspend (Settings) -> Unit,
    private val syncProbe: () -> Unit,
) {
    /** [mode] bascule : persiste le monde sonde et resynchronise. */
    fun onPlaceMode(mode: PictoPlaceMode) {
        val outdoor = mode == PictoPlaceMode.DEHORS
        if (settings().outdoorMode != outdoor) {
            val next = settings().copy(outdoorMode = outdoor)
            updateSettings(next)
            scope.launch { runCatching { save(next) } }
        }
        syncProbe()
    }
}
