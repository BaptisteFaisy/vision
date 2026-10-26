package dev.sam3vision.core.live

import dev.sam3vision.core.net.LiveLlmClient

/**
 * Narration continue du direct : une frame sur [minIntervalMs] part au LLM vision,
 * jamais deux en vol ([FrameGate]) — la caméra n'attend donc jamais le réseau.
 *
 * La dernière description est renvoyée au modèle avec la frame suivante pour qu'il
 * raconte ce qui **change** au lieu de répéter la même phrase (continuité).
 */
class LiveNarrator(
    private val llm: LiveLlmClient,
    private val gate: FrameGate = FrameGate(),
    private val minIntervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxPreviousChars: Int = MAX_PREVIOUS_CHARS,
) {

    @Volatile
    private var nextAt = 0L

    @Volatile
    private var lastText = ""

    /** Dernière description obtenue ("" tant qu'aucune). */
    val previous: String get() = lastText

    /** Une analyse est-elle en vol ? */
    val inFlight: Boolean get() = gate.inFlight

    /** Une frame peut-elle partir maintenant ? Cadence écoulée et rien en vol. */
    fun due(now: Long = clock()): Boolean = !gate.inFlight && now >= nextAt

    /**
     * Envoie [jpegB64] au LLM si la cadence le permet ; `null` = frame ignorée
     * (créneau pris ou trop tôt). Un échec consomme quand même la cadence : pas de
     * martèlement d'une API en erreur.
     */
    suspend fun narrate(jpegB64: String, labels: List<String>): Result<String>? {
        if (jpegB64.isBlank() || !gate.tryStart()) return null
        val now = clock()
        if (now < nextAt) {
            gate.finish()
            return null
        }
        nextAt = now + minIntervalMs
        return try {
            llm.describeFrame(jpegB64, labels.take(MAX_LABELS), lastText)
                .onSuccess { text -> lastText = text.take(maxPreviousChars) }
        } finally {
            gate.finish()
        }
    }

    /** Oublie la continuité (nouvelle scène) et libère le créneau. */
    fun reset() {
        gate.finish()
        nextAt = 0L
        lastText = ""
    }

    companion object {
        /** Une analyse toutes les 2 s : le LLM est le maillon lent, pas le détecteur. */
        const val DEFAULT_INTERVAL_MS = 2_000L

        /** Objets cités au modèle : au-delà, la phrase devient un catalogue. */
        const val MAX_LABELS = 8

        /** Longueur de la description gardée pour la continuité. */
        const val MAX_PREVIOUS_CHARS = 300
    }
}