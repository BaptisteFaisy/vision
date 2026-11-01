package dev.sam3vision.core.cycle

import dev.sam3vision.core.distance.KnownSizes

/**
 * Concepts des cycles : ce que le [CycleWatcher] suit pour dire « Attention,
 * vélo en mouvement… » ou « Vélo arrêté… ».
 *
 * Trois engins, FR/EN : le vélo (`bicycle`, `bike`, `electric bike`,
 * `e-bike`, `bicyclette`), la trottinette (`scooter`, `electric scooter`,
 * `kick scooter`, `e-scooter`) et le 2-roues générique (`two-wheeler`,
 * `deux-roues`, `2-roues` — reel audit 31/10). Le français reste reconnu :
 * un label « trottinette » compte comme un « scooter ». La moto
 * (`motorcycle`) n'est pas un cycle : elle reste un véhicule moteur
 * annoncé par `VehicleState` (traversée).
 *
 * Liste fermée, miroir de `HazardConcepts` : un concept = un nom parlé
 * ([CycleName]). Le « etc. » s'ajoute ici (clé canonique → nom), jamais en
 * dur dans une phrase.
 */
object CycleConcepts {

    /**
     * Nom parlé du concept : [fr] le nom nu (« vélo », « trottinette »),
     * [en] le nom anglais (« bicycle », « scooter »), [stoppedFr] l'adjectif
     * d'arrêt accordé (« arrêté », « arrêtée ») et [clipKey], la clé du pack
     * voix (`cycle_<clipKey>_moving`, `cycle_<clipKey>_stopped`).
     */
    data class CycleName(
        val fr: String,
        val en: String,
        val stoppedFr: String,
        val clipKey: String,
    )

    private val VELO = CycleName("vélo", "bicycle", "arrêté", "velo")
    private val TROTTINETTE = CycleName("trottinette", "scooter", "arrêtée", "trott")

    /**
     * Les concepts, une fois chacun (le pack voix s'en sert). « deux-roues »
     * n'a pas encore de clips : son mouvement retombe en synthèse.
     */
    fun names(): List<CycleName> = listOf(VELO, TROTTINETTE, DEUX_ROUES)

    private val DEUX_ROUES = CycleName("deux-roues", "two-wheeler", "arrêté", "deuxroues")

    private val NAMES: Map<String, CycleName> = mapOf(
        "bicycle" to VELO,
        "bike" to VELO,
        "velo" to VELO,
        "electric bike" to VELO,
        "electric bicycle" to VELO,
        "e bike" to VELO,
        "ebike" to VELO,
        "velo electrique" to VELO,
        "bicyclette" to VELO,
        "scooter" to TROTTINETTE,
        "electric scooter" to TROTTINETTE,
        "kick scooter" to TROTTINETTE,
        "electric kick scooter" to TROTTINETTE,
        "e scooter" to TROTTINETTE,
        "escooter" to TROTTINETTE,
        "trottinette" to TROTTINETTE,
        "trottinette electrique" to TROTTINETTE,
        "two wheeler" to DEUX_ROUES,
        "deux roues" to DEUX_ROUES,
        "2 roues" to DEUX_ROUES,
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
