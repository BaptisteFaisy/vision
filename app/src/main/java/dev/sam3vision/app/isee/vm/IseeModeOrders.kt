package dev.sam3vision.app.isee.vm

import dev.sam3vision.core.picto.PictoHit
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings

/**
 * Les ordres de **mode** de l'accueil iSee, dans l'ordre où la dictée les
 * essaie : lieu (« mode dehors ») d'abord, puis recherche (« find my X »,
 * « find off »), puis pictos historiques. Un ordre de mode ne fait aucun
 * appel réseau lui-même (la génération du picto, elle, en fait un) et ne
 * part jamais au LLM.
 *
 * Construits ensemble parce qu'ils partagent tout (settings, voix, état) et
 * que l'ordre d'essai doit rester écrit à un seul endroit : une phrase qui
 * n'est pas un ordre de mode rend `false` et poursuit son chemin.
 * Le lieu courant va au picto vocal, muet dehors (le texte poursuit vers
 * le cap, la navigation ou le LLM).
 *
 * Spec v4 : « mode indoor/outdoor » est le seul interrupteur dedans-dehors
 * (l'ordre « maison » est retiré de la chaîne : « maison » part au LLM).
 * [onFindOff] efface la chasse pendante (find off, sortie de dedans) ;
 * [post] porte les clips find (signature de VoiceBus : niveau, texte,
 * clip), branché sur VoiceBus en production (snippet au parent).
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
    onFindOff: () -> Unit = {},
    post: (level: Int, text: String, clipId: String?) -> Unit = { _, _, _ -> },
    onPlaceMode: (PictoPlaceMode) -> Unit = {},
) {

    private val place = PlaceModeOrder(settings, speak, update, onLeaveIndoor = onFindOff, onPlaceMode = onPlaceMode)
    private val find = FindIndoorVoice(update, onFind = onFind, onFindOff = onFindOff, placeMode = placeMode, post = post)
    private val picto = IseePictoVoice(pictoLabels, settings, speak, update, onFind = onFind, onCreate = onCreate, hits = pictoHits, placeMode = placeMode)

    /** Vrai si [text] portait un ordre de mode (texte consommé, rien d'autre à faire). */
    suspend fun run(text: String): Boolean =
        place.run(text) || find.run(text) || picto.run(text)
}
