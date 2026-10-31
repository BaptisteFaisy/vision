package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.HomePresetRequest
import dev.sam3vision.app.LiveVoiceRequest
import dev.sam3vision.app.RouteRequest
import dev.sam3vision.core.live.HomeModes
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.picto.PictoHit
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.picto.PictoVoiceOrder
import dev.sam3vision.core.picto.PictoVoiceText
import dev.sam3vision.core.settings.Settings

/**
 * Ordres vocaux picto et chasse maison sur iSee : recherche, chasse et
 * création restent sur place, démo et mot maison seul partent au direct.
 * « Find the X », « Where are my keys » et « où sont mes clés » sans repère
 * armé restent sur place
 * ([onFind] : X rejoint les masques SAM et son pictogramme se crée) ; armé,
 * le repère est chassé sur place (l'overlay le montre, la voix dit où il est),
 * jamais régénéré. Création (« fais un pictogramme de X », « create a picto
 * for X ») : [onCreate] photographie et arme sur place, sans relecture —
 * seul le sans-nom (« de ça ») part au direct. Démo et mot maison seul
 * (chasse SAM) partent en [LiveVoiceRequest]. Tout est confirmé (recherche,
 * chasse, création, démo) ; le reste passe aux ordres suivants (cap,
 * navigation, LLM). Dehors, rien n'est consommé : `false` aussitôt, sans
 * voix ni état — dehors, c'est tout sauf le picto.
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
    private val onCreate: (String) -> Unit = {},
    private val hits: () -> List<PictoHit> = { emptyList() },
    private val placeMode: () -> PictoPlaceMode = { PictoPlaceMode.DEDANS },
) {

    /**
     * Rend `true` quand [text] était un ordre picto ou une chasse maison.
     * L'écran affiche la confirmation, dite par le chemin navigation.
     */
    suspend fun run(text: String): Boolean {
        if (!placeMode().pictoEnabled) return false
        PictoVoiceOrder.parseFind(text)?.let { label ->
            PictoVoiceOrder.huntWord(text, armedLabels())?.let { word -> return hunt(word) }
            onFind(label)
            confirm(PictoVoiceText.findStarted(label, settings().speechLanguage))
            return true
        }
        PictoVoiceOrder.parse(text)?.let { label ->
            if (label.isBlank()) {
                openLive(LiveVoiceRequest.Request(pictoCreate = label), null)
            } else {
                onCreate(label)
            }
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
        PictoVoiceOrder.parseOuSont(text)?.let { label ->
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
     * la voix dit où il est quand il est visible (« Les clés sont devant
     * toi, à 9 heures »), sinon elle confirme sans position (« Je cherche
     * X ») — le direct n'est plus ouvert (tout se passe sur la page blanche).
     */
    private suspend fun hunt(word: String): Boolean {
        val target = hits().firstOrNull { PictoVoiceOrder.matches(word, it.label) }
        if (target == null) {
            confirm(PictoVoiceText.findStarted(word, settings().speechLanguage))
            return true
        }
        val placed = PlacedLabel(target.label, target.x + target.w / 2f, x0 = target.x, x1 = target.x + target.w)
        confirm(PositionText.spotlight(placed, settings().speechLanguage))
        return true
    }

    private suspend fun confirm(line: String) {
        update { it.copy(status = line) }
        speak(line)
    }
}
