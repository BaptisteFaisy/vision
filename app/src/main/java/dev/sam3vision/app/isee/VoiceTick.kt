package dev.sam3vision.app.isee

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Battement vocal (ordre user du 01/11 : la voix parle toutes les 350 ms) :
 * toutes les [TICK_MS], le vœu courant le plus prioritaire est proposé au
 * bus ([VoiceBus], écrasement par niveau — le bus garde l'arbitrage global,
 * le battement ne fait que réévaluer).
 *
 * Les producteurs continus rapportent leur vœu courant ([report]) en plus de
 * leur post immédiat (la première détection parle sans attendre le battement) :
 * escaliers, compte des marches, grillage, trottoir. [pick] prend le plus
 * petit niveau parmi les vœux frais ([FRESH_MS]) ; [tick] le propose via
 * [offer] (antirépétition SPEC v4 puis bus — un vœu écrasé ou tu par
 * l'antirépétition est re-proposé au battement suivant, jamais perdu dans
 * sa fenêtre de fraîcheur).
 *
 * Hors battement, par construction : les obstacles (re-postés à chaque frame
 * SAM, ~200 ms — plus rapide que le battement), la Traversée (verdict
 * propriétaire, nourri à chaque frame, coupé ici aussi), les événements
 * (GPS, chat, passage, trouvaille find — ponctuels, jamais récurrents) et le
 * repli 12 (son chemin émission + silence 300 ms existe déjà —
 * [FallbackVoice] : un double chemin bégayerait le mot).
 *
 * Dedans, autre chaîne (SPEC §3 : trouvé 0 > user 1 > humain 2 > chat 9 >
 * porte et murs 13) : chaque vœu est tagué ([report]), le battement ne
 * propose que la chaîne du mode courant (exclusif, comme le routeur).
 *
 * Anti-bégaiement : le même (niveau, texte) ne repart pas avant [SAME_MS]
 * (1 s — les clés sans antirépétition, dont le repli, n'ont pas d'autre
 * borne, et un clip ne se coupe pas lui-même à chaque battement). Un refus
 * de [offer] (antirépétition) n'est pas mémorisé : le battement suivant
 * retente. Global de séance : [reset] en début de séance et dans les tests.
 */
internal object VoiceTick {

    /** Battement : la priorité courante est réévaluée toutes les 350 ms. */
    const val TICK_MS = 350L

    /** Fraîcheur d'un vœu rapporté : au-delà, périmé (jamais redit). Prior, à valider rue. */
    const val FRESH_MS = 2_000L

    /** Même (niveau, texte) : ne repart pas avant 1 s (le battement ne coupe jamais un clip en cours). */
    const val SAME_MS = 1_000L

    /** Annonce escaliers (niveau 6, guetteur confirmatif). */
    const val SRC_STAIRS = "stairs"

    /** Compte des marches (niveau 6, battement 300 ms du compteur). */
    const val SRC_STAIRS_COUNT = "stairs-count"

    /** Grillage et pans suivis (niveau 4, guetteur confirmatif). */
    const val SRC_FENCE = "fence"

    /** Trottoir nommé (niveau 10, guetteur confirmatif). */
    const val SRC_SIDEWALK = "sidewalk"

    /** Objet cherché trouvé dedans (cran find 0, détection). */
    const val SRC_FIND = "find"

    /** Porte et murs dedans (cran 13, guetteur de cadran). */
    const val SRC_DOOR_WALL = "door-wall"

    /** Vœu rapporté à [atMs] par une source ([indoor] = chaîne dedans). */
    private data class Entry(val wish: VoiceWish, val atMs: Long, val indoor: Boolean)

    /** Dernier (niveau, texte) offert à [atMs] (anti-bégaiement [SAME_MS]). */
    private data class Offered(val level: Int, val text: String, val atMs: Long)

    /** Vœu courant par source (quatre sources fixes : borné). */
    private val current = HashMap<String, Entry>()

    /** Dernier vœu offert (`null` = aucun : le prochain parle). */
    private var lastOffer: Offered? = null

    /**
     * Rapporte le vœu courant de [source] à [nowMs] (`null` = la source n'a
     * plus rien à dire : son vœu est effacé). Pur hors mémoire, testé en JVM.
     */
    @Synchronized
    fun report(source: String, wish: VoiceWish?, nowMs: Long, indoor: Boolean = false) {
        if (wish == null) current.remove(source)
        else current[source] = Entry(wish, nowMs, indoor)
    }

    /**
     * Vœu courant le plus prioritaire à [nowMs] : le plus petit niveau parmi
     * les vœux frais et non blancs, ex æquo au plus récemment rapporté —
     * `null` = silence (rien de frais). Pur hors mémoire, testé en JVM.
     */
    @Synchronized
    fun pick(nowMs: Long, indoor: Boolean = false): VoiceWish? =
        current.values
            .filter { it.indoor == indoor && nowMs - it.atMs < FRESH_MS && it.wish.text.isNotBlank() }
            .minWithOrNull(compareBy({ it.wish.level }, { -it.atMs }))
            ?.wish

    /**
     * Un battement à [nowMs] : propose [pick] via [offer] (antirépétition
     * puis bus). `false` sans rien offrir (silence, même vœu dans [SAME_MS],
     * ou refus de [offer] — retenté au battement suivant). Testé en JVM.
     */
    @Synchronized
    fun tick(nowMs: Long, indoor: Boolean = false, offer: (VoiceWish?, Long) -> Boolean = ::offer): Boolean {
        val wish = pick(nowMs, indoor) ?: return false
        val last = lastOffer
        if (last != null && last.level == wish.level && last.text == wish.text &&
            nowMs - last.atMs < SAME_MS
        ) {
            return false
        }
        if (!offer(wish, nowMs)) return false
        lastOffer = Offered(wish.level, wish.text, nowMs)
        return true
    }

    /** Nouvelle séance (et tests : l'objet est global) : vœux et offre oubliés. */
    @Synchronized
    fun reset() {
        current.clear()
        lastOffer = null
    }
}

/**
 * Battement voix de la sonde : toutes les [tickMs] (350 ms), le vœu courant
 * le plus prioritaire ([VoiceTick]) est proposé au bus. Coupé en Traversée
 * (le verdict y est propriétaire du son). Démarré par [observeHazards],
 * avec les guetteurs. Fin fil mince : la boucle vit dans [voiceTickJob].
 * [indoor] vrai : chaîne dedans (SPEC §3), sinon chaîne dehors.
 */
internal fun LiveProbe.observeVoiceTick(
    scope: CoroutineScope,
    tickMs: Long = VoiceTick.TICK_MS,
    nowMs: () -> Long = { System.currentTimeMillis() },
    offer: (VoiceWish?, Long) -> Boolean = ::offer,
    indoor: () -> Boolean = { false },
): Job = voiceTickJob(scope, { state.value.crossingActive }, tickMs, nowMs, offer, indoor)

/**
 * Boucle du battement, hors sonde : toutes les [tickMs], propose le vœu
 * courant au bus, sauf en Traversée ([crossingActive]). Testée en JVM au
 * temps virtuel (miroir du compteur de marches : scope séparé, boucle
 * infinie annulée en fin de test). [indoor] : la chaîne proposée.
 */
internal fun voiceTickJob(
    scope: CoroutineScope,
    crossingActive: () -> Boolean,
    tickMs: Long = VoiceTick.TICK_MS,
    nowMs: () -> Long = { System.currentTimeMillis() },
    offer: (VoiceWish?, Long) -> Boolean = ::offer,
    indoor: () -> Boolean = { false },
): Job = scope.launch {
    while (true) {
        delay(tickMs)
        if (crossingActive()) continue
        VoiceTick.tick(nowMs(), indoor(), offer)
    }
}
