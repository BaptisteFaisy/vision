package dev.sam3vision.app.isee.vm

import dev.sam3vision.core.picto.PictoHit
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings

/**
 * Les ordres de **mode** rejoués par le routeur de dictée (spec v4) sous
 * leurs phrases canoniques : lieu (« mode dehors », « mode dedans »)
 * d'abord, puis pictos (« Find the X », chasse). L'ordre « maison » est
 * supprimé (spec v4 : le mot part au LLM comme question). Un ordre de mode
 * ne fait aucun appel réseau lui-même (la génération du picto, elle, en
 * fait un).
 *
 * Construits ensemble parce qu'ils partagent tout (settings, voix, état) et
 * que l'ordre d'essai doit rester écrit à un seul endroit : une phrase qui
 * n'est pas un ordre de mode rend `false`. Le lieu courant va au picto
 * vocal, muet dehors.
 */
class IseeModeOrders(
    settings: () -> Settings,
    speak: suspend (String) -> Unit,
    update: StateUpdate,
    pictoLabels: () -> List<String>,
    onFind: (String) -> Unit = {},
    onCreate: (String) -> Unit = {},
    pictoHits: () -> List<PictoHit> = { emptyList() },
    placeMode: () -> PictoPlaceMode = { PictoPlaceMode.DEDANS },
) {

    private val place = PlaceModeOrder(settings, speak, update)
    private val picto = IseePictoVoice(pictoLabels, settings, speak, update, onFind = onFind, onCreate = onCreate, hits = pictoHits, placeMode = placeMode)

    /** Vrai si [text] portait un ordre de mode (texte consommé, rien d'autre à faire). */
    suspend fun run(text: String): Boolean =
        place.run(text) || picto.run(text)
}
