package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.PedLightGate
import dev.sam3vision.core.live.LightClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * La **couleur** du feu relue localement, au plus tard toutes les 150 ms
 * ([LightClock]), sur la frame caméra la plus fraîche.
 *
 * Pourquoi ce seau existe : l'état venait de la frame envoyée à SAM, donc il
 * avait l'âge de l'aller-retour — 422 ms médians (`docs/LATENCE-BUDGET.md`).
 * Or `PedLightGate` est 100 % embarqué et pur : rien n'oblige la couleur à
 * attendre le serveur. La boîte qui dit **où** est le feu continue de venir de
 * SAM (une frame sur deux ou trois, et ça suffit : un feu ne se déplace pas) ;
 * la couleur qui dit **quoi** se relit sur la dernière image du capteur.
 *
 * Ce que ce n'est pas : un remplacement de l'annonce vocale. La voix garde son
 * hystérésis (`CrossingAnnouncer`) ; ici on affiche une lecture instantanée,
 * bornée à une par 150 ms, et le doute s'affiche comme un doute.
 */
class CrossingLocalLight(
    private val scope: CoroutineScope,
    private val background: CoroutineDispatcher,
    /** Dernière boîte connue, celle du serveur : `null` = aucun feu à lire. */
    private val boxOf: () -> List<Float>?,
    private val clock: LightClock = LightClock(),
    /** Lecture injectable : les tests s'en servent, la prod prend [readLight]. */
    private val read: (String, List<Float>) -> CrossingState = ::readLight,
) {

    private val _ui = MutableStateFlow<CrossingLight?>(null)

    /** Dernière lecture locale (`null` = rien lu encore). */
    val ui: StateFlow<CrossingLight?> = _ui.asStateFlow()

    /**
     * Frame caméra : lit le feu si une boîte est connue et si le créneau de
     * 150 ms est libre. Ne bloque jamais l'appelant — le décodage part sur
     * [background].
     */
    fun offerFrame(jpegB64: String, nowMs: Long = System.currentTimeMillis()) {
        if (jpegB64.isBlank()) return
        val box = boxOf() ?: return
        if (!clock.due(nowMs)) return
        scope.launch(background) { _ui.value = CrossingLight(box, read(jpegB64, box)) }
    }

    /** Mode coupé ou nouvelle séance : plus rien à afficher, créneau rendu. */
    fun clear() {
        clock.reset()
        _ui.value = null
    }
}

/**
 * Lecture plateforme : crop de la boîte dans le JPEG, puis porte anti-confusion.
 * `Unknown` quand l'image ou la boîte est illisible — le doute vaut « attendez ».
 */
internal fun readLight(jpegB64: String, box: List<Float>): CrossingState {
    val crop = CrossingPixels.frame(jpegB64, box) ?: return CrossingState.Unknown
    return PedLightGate.verdict(crop.argb, crop.width, crop.height)
}

/**
 * Le seau **unique de l'écran iSee** : la caméra du téléphone et le pont des
 * lunettes nourrissent le même seau, donc l'étiquette affichée vient de la
 * dernière frame disponible, quelle que soit la source.
 *
 * Un objet de processus plutôt qu'un `remember` par carte : la rue des lunettes
 * alimente la sonde depuis un autre composable que celui qui dessine l'overlay
 * ([GlassesProbeWiring] vs [IseePortraitCamera]) — deux instances voudraient
 * dire un seau qui ne reçoit jamais de frame.
 */
internal object CrossingLightPump {

    @Volatile
    private var pump: CrossingLocalLight? = null

    /** Installe le seau une fois par session d'écran (idempotent). */
    fun install(
        scope: CoroutineScope,
        background: CoroutineDispatcher,
        boxOf: () -> List<Float>?,
    ): CrossingLocalLight {
        pump?.let { return it }
        return CrossingLocalLight(scope, background, boxOf).also { pump = it }
    }

    /** Dernier seau installé (`null` avant la première carte caméra). */
    val current: CrossingLocalLight? get() = pump

    /** Frame caméra : lit la couleur si un seau existe et si le créneau est libre. */
    fun offerFrame(jpegB64: String) {
        pump?.offerFrame(jpegB64)
    }
}
