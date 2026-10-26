package dev.sam3vision.app.live

/**
 * Fenêtre de réveil du GPU : ouverte au démarrage d'une session SAM, elle laisse au
 * premier envoi de frame le temps de sortir le conteneur Modal de sa veille (mesuré
 * 8-35 s, la requête est mise en file pendant le démarrage). Tant qu'elle est ouverte,
 * une frame en échec n'est pas une erreur : l'écran affiche « Réveil du GPU A100 40 Go
 * sur Modal… ». Fermée, le même échec devient un vrai message d'erreur.
 *
 * La sonde `GET /health` qui suivait ce réveil a été **supprimée le 2026-10-26** : elle
 * sortait la carte de veille (20 s facturées) sans aucune analyse. Il ne reste que cette
 * fenêtre, tenue par la première frame — d'où l'extraction de la logique, testable seule.
 *
 * Logique pure (horloge injectable, aucun Android) : testée en JVM.
 */
class LiveWakeGuard(
    private val timeoutMs: Long = WAKE_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Fin de la fenêtre d'attente ; `0` = aucune attente en cours. */
    var deadlineAt: Long = 0L
        private set

    /** Le direct démarre : on attend le premier réveil au plus [timeoutMs] ms. */
    fun arm(nowMs: Long = clock()) {
        deadlineAt = nowMs + timeoutMs
    }

    /** Plus d'attente (nouveau démarrage, arrêt) : un échec redevient une erreur. */
    fun reset() {
        deadlineAt = 0L
    }

    /**
     * Une frame en échec est-elle encore du réveil ? `deadlineAt = 0` (fenêtre fermée)
     * répond toujours non, l'erreur doit donc s'afficher tout de suite.
     */
    fun isWaking(nowMs: Long = clock()): Boolean = nowMs < deadlineAt

    companion object {
        /** Attente maximale du premier réveil : 60 s couvrent un démarrage à froid. */
        const val WAKE_TIMEOUT_MS = 60_000L
    }
}