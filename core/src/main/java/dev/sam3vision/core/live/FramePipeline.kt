package dev.sam3vision.core.live

/**
 * Ce que l'écran Direct attend d'un pipeline de frames, **quel que soit le transport** :
 *
 * - [inFlight] : un seul créneau en vol — la frame suivante est jetée, jamais mise en attente ;
 * - [intervalMsAdaptive] : intervalle de capture conseillé à cet instant ;
 * - [generation] / [isCurrent] : l'époque de la séance. Arrêter le direct fait tourner
 *   l'époque, donc la réponse d'une frame encore en vol est reconnue **périmée** et n'est
 *   pas publiée (sinon l'écran d'un direct arrêté se rallume tout seul) ;
 * - [stats] : latence bout-en-bout mesurée côté téléphone.
 *
 * Deux implémentations, un seul consommateur (la cadence du direct et le `LiveViewModel`
 * de `:app`) : [LiveSession] pour `/live` en WebSocket (défaut), [TrackRunner] pour le
 * tracker par sessions (`/session` + `/frame`, ids stables) — sans dupliquer la cadence,
 * l'époque ni la publication.
 */
interface FramePipeline {

    /** Une frame est-elle en train d'être traitée ? */
    val inFlight: Boolean

    /** Intervalle de capture conseillé (ms) d'après la latence mesurée. */
    fun intervalMsAdaptive(): Long

    /** Époque de la séance : elle change à chaque [reset]. */
    val generation: Long

    /** Vrai si une frame admise sous l'époque [era] est encore d'actualité. */
    fun isCurrent(era: Long): Boolean

    /** Latence bout-en-bout des frames (ms), mesurée côté téléphone. */
    val stats: LiveStats

    /** Arrêt (ou relance) de la séance : la frame en vol devient périmée, les mesures sont oubliées. */
    fun reset()
}