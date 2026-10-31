package dev.sam3vision.app.isee.vm

import dev.sam3vision.core.picto.PictoHit
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings

/**
 * Les ordres de **mode** de l'accueil iSee, dans l'ordre où la dictée les
 * essaie : lieu (« mode dehors ») d'abord, puis maison (« maison »),
 * puis pictos (« décris les portes », « Find
 * the X », « Where are my keys », « où sont mes clés »). Un ordre de mode
 * ne fait aucun appel
 * réseau lui-même (la
 * génération du picto, elle, en fait un) et ne part jamais au LLM.
 *
 * Construits ensemble parce qu'ils partagent tout (settings, voix, état) et
 * que l'ordre d'essai doit rester écrit à un seul endroit : une phrase qui
 * n'est pas un ordre de mode rend `false` et poursuit son chemin.
 * Le lieu courant va au picto vocal, muet dehors (le texte poursuit vers
 * le cap, la navigation ou le LLM).
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
    private val maison = MaisonEnterOrder(settings, speak, update)
    private val picto = IseePictoVoice(pictoLabels, settings, speak, update, onFind = onFind, onCreate = onCreate, hits = pictoHits, placeMode = placeMode)

    /** Vrai si [text] portait un ordre de mode (texte consommé, rien d'autre à faire). */
    suspend fun run(text: String): Boolean =
        place.run(text) || maison.run(text) || picto.run(text)
}
