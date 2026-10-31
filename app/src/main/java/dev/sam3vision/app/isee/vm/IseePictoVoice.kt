package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.HomePresetRequest
import dev.sam3vision.app.LiveVoiceRequest
import dev.sam3vision.app.RouteRequest
import dev.sam3vision.core.live.HomeModes
import dev.sam3vision.core.picto.PictoVoiceOrder
import dev.sam3vision.core.picto.PictoVoiceText
import dev.sam3vision.core.settings.Settings

/**
 * Ordres vocaux picto et chasse maison sur iSee : transfère au direct.
 * « Find the X » reste sur place ([onFind] : X rejoint les masques SAM et
 * son pictogramme se crée), comme « Where are my keys » sans repère armé
 * (armé, il reste chassé, jamais régénéré) ; création
 * (« fais un pictogramme de X »), démo et chasse d'un repère armé partent
 * en [LiveVoiceRequest] ; un mot maison seul part en chasse SAM sur ce
 * seul mot. Tout est confirmé (recherche, création, démo) ou muet
 * (chasse, le direct parle) ; le reste passe aux ordres suivants (cap,
 * navigation, LLM).
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
        PictoVoiceOrder.huntWord(text, armedLabels())?.let { word ->
            openLive(LiveVoiceRequest.Request(pictoHunt = word), null)
            return true
        }
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

    private suspend fun confirm(line: String) {
        update { it.copy(status = line) }
        speak(line)
    }
}
