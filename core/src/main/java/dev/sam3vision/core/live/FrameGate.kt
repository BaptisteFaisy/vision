package dev.sam3vision.core.live

import java.util.concurrent.atomic.AtomicReference

/**
 * Régulateur « goutte-à-goutte » sans file : au plus [capacity] frames en vol.
 * Si tous les créneaux sont pris, la nouvelle frame est jetée (jamais mise en
 * attente). Sûre entre threads (callback caméra sur un thread de fond).
 *
 * Un créneau par défaut (comportement historique du Direct). La page caméra d'iSee
 * en autorise deux, mais [LiveSession] redescend à un seul dès que la latence dépasse
 * son budget : deux frames en vol ne valent le coup que si le backend les traite en
 * parallèle, sinon la seconde attend la première et la latence double pour rien.
 *
 * **Époque** ([generation]) : arrêter le direct doit arrêter *vraiment*. Un simple
 * « occupation = 0 » ne suffit pas — la requête déjà partie reste suspendue et, à son
 * retour, elle publierait son résultat (l'écran se rallume tout seul) puis libérerait le
 * créneau d'une frame de la séance suivante. [invalidate] fait donc tourner l'époque :
 * les frames admises avant appartiennent au passé, leur réponse est reconnue comme
 * périmée par [isCurrent] et leur [finish] ne touche plus rien.
 */
class FrameGate(slots: Int = 1) {

    /**
     * Créneaux autorisés **maintenant** : le régulateur de [LiveSession] le baisse à 1
     * quand la latence dépasse le budget, et le remonte quand le backend respire.
     */
    @Volatile
    var capacity: Int = slots.coerceAtLeast(1)
        set(value) {
            field = value.coerceAtLeast(1)
        }

    /** Occupation du régulateur : l'époque courante et le nombre de créneaux pris. */
    private class State(val era: Long, val used: Int)

    private val state = AtomicReference(State(era = 0L, used = 0))

    /** Époque courante : toute frame admise sous une époque antérieure est périmée. */
    val generation: Long get() = state.get().era

    /** Vrai si la frame admise sous l'époque [era] est encore d'actualité. */
    fun isCurrent(era: Long): Boolean = state.get().era == era

    /** Vrai si l'appelant obtient un créneau ; faux = frame à jeter. */
    fun tryStart(): Boolean = tryStart(generation)

    /** Comme [tryStart], mais au titre de l'époque [era] : refusé si elle est révolue. */
    fun tryStart(era: Long): Boolean {
        while (true) {
            val current = state.get()
            if (current.era != era) return false
            if (current.used >= capacity) return false
            val next = State(current.era, current.used + 1)
            if (state.compareAndSet(current, next)) return true
        }
    }

    /** Libère un créneau, quel que soit le sort de la frame en vol. */
    fun finish() = finish(generation)

    /**
     * Libère le créneau de l'époque [era]. Sans effet si l'époque a tourné : ce créneau
     * n'existe plus (rendu par [invalidate]), et le décrémenter volerait celui d'une
     * frame de la séance suivante.
     */
    fun finish(era: Long) {
        while (true) {
            val current = state.get()
            if (current.era != era || current.used == 0) return
            val next = State(current.era, current.used - 1)
            if (state.compareAndSet(current, next)) return
        }
    }

    /**
     * Révoque l'époque : les créneaux sont rendus tout de suite et toutes les frames
     * encore en vol deviennent périmées — leur réponse sera jetée, pas publiée.
     * Renvoie la nouvelle époque.
     */
    fun invalidate(): Long {
        while (true) {
            val current = state.get()
            val next = State(current.era + 1L, 0)
            if (state.compareAndSet(current, next)) return next.era
        }
    }

    /** Au moins une frame est-elle en vol ? */
    val inFlight: Boolean get() = state.get().used > 0

    /** Créneaux encore libres : 0 = la frame suivante serait jetée. */
    val freeSlots: Int get() = (capacity - state.get().used).coerceAtLeast(0)
}