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

/** Le lieu que dit le monde sonde : [Settings.outdoorMode] décide. */
internal fun placeOf(settings: Settings): PictoPlaceMode =
    if (settings.outdoorMode) PictoPlaceMode.DEHORS else PictoPlaceMode.DEDANS

/**
 * Monde sonde au lancement : le lieu n'est pas persisté et repart dehors, le
 * monde sonde l'est. Sans ce retour, un « dedans » d'avant la relance laissait
 * la puce sur OUTDOOR avec les 10 prompts d'intérieur : plus aucun masque de
 * rue. Enregistré, pour que les Réglages relisent la même valeur ; un échec
 * d'écriture ne retient pas le lancement.
 */
internal suspend fun outdoorsAtLaunch(loaded: Settings, save: suspend (Settings) -> Unit): Settings {
    if (loaded.outdoorMode) return loaded
    val next = loaded.copy(outdoorMode = true)
    runCatching { save(next) }
    return next
}
