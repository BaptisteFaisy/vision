package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.HomePresetRequest
import dev.sam3vision.app.LiveVoiceRequest
import dev.sam3vision.app.RouteRequest
import dev.sam3vision.core.live.HomeModes
import dev.sam3vision.core.picto.PictoVoiceOrder
import dev.sam3vision.core.picto.PictoVoiceText
import dev.sam3vision.core.settings.Settings

/**
 * Ordres vocaux picto et chasse maison sur iSee : recherche et chasse restent
 * sur place, création, démo et mot maison seul partent au direct.
 * « Find the X » et « Where are my keys » sans repère armé restent sur place
 * ([onFind] : X rejoint les masques SAM et son pictogramme se crée) ; armé,
 * le repère est chassé sur place (l'overlay le montre, la voix confirme),
 * jamais régénéré. Création (« fais un pictogramme de X »), démo et mot
 * maison seul (chasse SAM) partent en [LiveVoiceRequest]. Tout est confirmé
 * (recherche, chasse, création, démo) ; le reste passe aux ordres suivants
 * (cap, navigation, LLM).
 *
 * [openLive] ouvre le direct avec la requête et les prompts (`null` =
 * essentiels) ; injecté pour les tests, le défaut parle aux vrais singletons.
 */
class IseePictoVoice(
    private val armedLabels: () -> List<String>,
    private val settings: () -> Settings,
    private val speak: suspend (String) -> Unit,
    private val update: StateUpdate,
    private val openLive: (LiveVoiceRequest.Request, List<String>?) -> Unit = { request, prompts ->
        if (prompts == null) HomePresetRequest.pushDefault() else HomePresetRequest.pushPrompts(prompts)
        LiveVoiceRequest.push(request)
        RouteRequest.push("live")
    },
    private val onFind: (String) -> Unit = {},
) {

    /**
     * Rend `true` quand [text] était un ordre picto ou une chasse maison.
     * L'écran affiche la confirmation, dite par le chemin navigation.
     */
    suspend fun run(text: String): Boolean {
        PictoVoiceOrder.parseFind(text)?.let { label ->
            PictoVoiceOrder.huntWord(text, armedLabels())?.let { word -> return hunt(word) }
            onFind(label)
            confirm(PictoVoiceText.findStarted(label, settings().speechLanguage))
            return true
        }
        PictoVoiceOrder.parse(text)?.let { label ->
            openLive(LiveVoiceRequest.Request(pictoCreate = label), null)
            confirm(PictoVoiceText.started(settings().speechLanguage))
            return true
        }
        if (PictoVoiceOrder.isDemoToggle(text)) {
            openLive(LiveVoiceRequest.Request(pictoDemo = true), null)
            confirm(PictoVoiceText.demo(true, settings().speechLanguage))
            return true
        }
        PictoVoiceOrder.huntWord(text, armedLabels())?.let { word -> return hunt(word) }
        PictoVoiceOrder.parseWhere(text)?.let { label ->
            onFind(label)
            confirm(PictoVoiceText.findStarted(label, settings().speechLanguage))
            return true
        }
        HomeModes.conceptForWord(text)?.let { concept ->
            openLive(LiveVoiceRequest.Request(), listOf(concept))
            return true
        }
        return false
    }

    /**
     * Chasse d'un repère armé, sur place : l'overlay montre déjà le repère,
     * la voix le confirme — le direct n'est plus ouvert (31/10 : tout se
     * passe sur la page blanche).
     */
    private suspend fun hunt(word: String): Boolean {
        confirm(PictoVoiceText.findStarted(word, settings().speechLanguage))
        return true
    }

    private suspend fun confirm(line: String) {
        update { it.copy(status = line) }
        speak(line)
    }
}
