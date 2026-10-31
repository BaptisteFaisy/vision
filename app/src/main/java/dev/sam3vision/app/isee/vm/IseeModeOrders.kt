package dev.sam3vision.app.isee.vm

import dev.sam3vision.core.picto.PictoHit
import dev.sam3vision.core.settings.Settings

/**
 * Les ordres de **mode** de l'accueil iSee, dans l'ordre où la dictée les
 * essaie : maison (« maison »), puis pictos (« décris les portes », « Find
 * the X », « Where are my keys »). Un ordre de mode ne fait aucun appel
 * réseau lui-même (la
 * génération du picto, elle, en fait un) et ne part jamais au LLM.
 *
 * Construits ensemble parce qu'ils partagent tout (settings, voix, état) et
 * que l'ordre d'essai doit rester écrit à un seul endroit : une phrase qui
 * n'est pas un ordre de mode rend `false` et poursuit son chemin.
 */
class IseeModeOrders(
    settings: () -> Settings,
    speak: suspend (String) -> Unit,
    update: StateUpdate,
    pictoLabels: () -> List<String>,
    onFind: (String) -> Unit = {},
    onCreate: (String) -> Unit = {},
    pictoHits: () -> List<PictoHit> = { emptyList() },
) {

    private val maison = MaisonEnterOrder(settings, speak, update)
    private val picto = IseePictoVoice(pictoLabels, settings, speak, update, onFind = onFind, onCreate = onCreate, hits = pictoHits)

    /** Vrai si [text] portait un ordre de mode (texte consommé, rien d'autre à faire). */
    suspend fun run(text: String): Boolean =
        maison.run(text) || picto.run(text)
}
