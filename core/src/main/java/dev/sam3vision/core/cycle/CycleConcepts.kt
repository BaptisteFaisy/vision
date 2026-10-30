package dev.sam3vision.core.cycle

import dev.sam3vision.core.distance.KnownSizes

/**
 * Concepts des cycles : ce que le [CycleWatcher] suit pour dire « Attention,
 * vélo en mouvement… » ou « Vélo arrêté… ».
 *
 * Deux engins, FR/EN : le vélo (`bicycle`, `bike`) et la trottinette
 * (`scooter`, `electric scooter` — déjà dans les défauts du direct). Le
 * français reste reconnu : un label « trottinette » compte comme un
 * « scooter ». La moto (`motorcycle`) n'est pas un cycle : elle reste un
 * véhicule moteur annoncé par `VehicleState` (traversée).
 *
 * Liste fermée, miroir de `HazardConcepts` : un concept = un nom parlé
 * ([CycleName]). Le « etc. » s'ajoute ici (clé canonique → nom), jamais en
 * dur dans une phrase.
 */
object CycleConcepts {

    /**
     * Nom parlé du concept : [fr] le nom nu (« vélo », « trottinette »),
     * [en] le nom anglais (« bicycle », « scooter »), [stoppedFr] l'adjectif
     * d'arrêt accordé (« arrêté », « arrêtée »).
     */
    data class CycleName(val fr: String, val en: String, val stoppedFr: String)

    private val VELO = CycleName("vélo", "bicycle", "arrêté")
    private val TROTTINETTE = CycleName("trottinette", "scooter", "arrêtée")

    private val NAMES: Map<String, CycleName> = mapOf(
        "bicycle" to VELO,
        "bike" to VELO,
        "velo" to VELO,
        "scooter" to TROTTINETTE,
        "electric scooter" to TROTTINETTE,
        "trottinette" to TROTTINETTE,
        "trottinette electrique" to TROTTINETTE,
    )

    /** Nom parlé de [label], `null` si ce n'est pas un cycle suivi. */
    fun nameOf(label: String): CycleName? {
        val key = KnownSizes.canonical(label)
        if (key.isEmpty()) return null
        NAMES[key]?.let { return it }
        if (key.endsWith("s") && key.length > 3) return NAMES[key.dropLast(1)]
        return null
    }

    /** Vrai si [label] désigne un cycle suivi (casse, accents, pluriel neutralisés). */
    fun isCycle(label: String): Boolean = nameOf(label) != null

    /**
     * Clé de suivi d'un cycle entre deux frames (« Bikes » et « bike »
     * fusionnent) — sert l'association mouvement et le choix de l'annonce.
     */
    internal fun keyOf(label: String): String {
        val key = KnownSizes.canonical(label)
        if (key.endsWith("s") && key.length > 3) return key.dropLast(1)
        return key
    }
}
