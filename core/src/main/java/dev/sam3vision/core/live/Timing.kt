package dev.sam3vision.core.live

import kotlin.math.max
import kotlin.math.min

/**
 * Temps du guidage rapproché : en foule, tout ce qui décide se juge en
 * **secondes**, jamais en mètres (audit foule du 01/11/2026).
 *
 * Le constat qui a créé ce fichier : `RANGE_METERS = 4` et une cadence de
 * 1,4-1,7 décisions/s laissent **deux images** à un piéton qui arrive de
 * face (4 m fermés à 2,7 m/s = 1,43 s), et la décision est prise sur une
 * image vieille de 0,65-0,70 s — soit **1,9 m d'erreur**, une largeur de
 * trottoir. Un seuil en mètres ne dit rien de ce temps-là ; un TTC si.
 *
 * Trois services, tous purs et testés en JVM :
 * - [rangeFor] : la portée suit la vitesse de marche (l'horizon reste
 *   [HORIZON_S] secondes devant, borné par [MIN_RANGE_M]/[MAX_RANGE_M]) ;
 * - [ttcSeconds] : temps de contact = distance / vitesse de fermeture ;
 * - [projectDistance] : distance **maintenant**, en rattrapant l'âge de la
 *   mesure (une frame de 0,7 s décrit un monde qui a avancé).
 */
object Timing {

    /** Vitesse de marche de référence (m/s) : moyenne piétonne retenue partout. */
    const val WALK_MPS = 1.4f

    /**
     * Horizon de décision (s) : la portée historique de 4 m à [WALK_MPS]
     * (4 / 1,4 ≈ 2,9). C'est le temps qu'on veut voir venir, pas une distance.
     */
    const val HORIZON_S = 2.9f

    /** Portée plancher (m) : sous 3 m on ne voit plus venir un piéton à 3 m/s. */
    const val MIN_RANGE_M = 3f

    /** Portée plafond (m) : au-delà, les distances monoculaires ne valent plus rien. */
    const val MAX_RANGE_M = 8f

    /**
     * Âge au-delà duquel une frame ne décrit plus le monde (ms) : 1,2 s à
     * 1,4 m/s = 1,7 m parcourus. Une décision plus vieille **n'affirme
     * jamais que la voie est libre** ([isStale]).
     */
    const val STALE_MS = 1_200L

    /** TTC d'urgence (s) : sous ce temps de contact, on parle tout de suite, sans cadence. */
    const val URGENT_TTC_S = 1.2f

    /** TTC « bientôt » (s) : sous ce temps, on signale même sans approche confirmée. */
    const val SOON_TTC_S = 2.2f

    /** Vitesse de fermeture minimale retenue (m/s) : sous ce bruit, on ne conclut pas à l'approche. */
    const val MIN_CLOSING_MPS = 0.15f

    /**
     * Portée de décision pour une vitesse de marche [speedMps] : l'horizon
     * [HORIZON_S] devant soi, borné. [speedMps] absente ou absurde ⇒ portée
     * historique (4 m), pour ne rien changer aux appelants qui n'ont pas de GPS.
     */
    fun rangeFor(speedMps: Float?): Float {
        val speed = speedMps?.takeIf { it.isFinite() && it > 0f } ?: WALK_MPS
        return min(MAX_RANGE_M, max(MIN_RANGE_M, speed * HORIZON_S))
    }

    /**
     * Temps de contact (s) entre [distanceM] et une fermeture [closingMps]
     * (positif = ça se rapproche). `null` si la distance manque, si l'objet
     * s'éloigne, ou si la fermeture est sous [MIN_CLOSING_MPS] (immobile :
     * pas de contact prévisible, ce n'est pas une urgence).
     */
    fun ttcSeconds(distanceM: Float?, closingMps: Float?): Float? {
        val distance = distanceM?.takeIf { it.isFinite() && it > 0f } ?: return null
        val closing = closingMps?.takeIf { it.isFinite() } ?: return null
        if (closing < MIN_CLOSING_MPS) return null
        return distance / closing
    }

    /**
     * Distance **à l'instant de la décision** : la mesure a [ageMs] d'âge, et
     * l'objet se rapproche de [closingMps]. On retire donc `closing × âge`.
     * Sans fermeture connue (pas de piste), la mesure est rendue telle quelle
     * — on ne devine pas un mouvement qu'on n'a pas vu.
     * Le résultat est borné à `[0, distance]` : une projection ne fait jamais
     * reculer un objet, et ne le fait jamais passer derrière soi.
     */
    fun projectDistance(distanceM: Float?, closingMps: Float?, ageMs: Long): Float? {
        val distance = distanceM?.takeIf { it.isFinite() && it > 0f } ?: return null
        val age = ageMs.coerceAtLeast(0L)
        if (age == 0L) return distance
        val closing = closingMps?.takeIf { it.isFinite() && it > 0f } ?: return distance
        val projected = distance - closing * (age / 1_000f)
        return projected.coerceIn(0f, distance)
    }

    /** Âge d'une mesure (ms), jamais négatif (horloges désynchronisées tolérées). */
    fun ageOf(capturedAtMs: Long, nowMs: Long): Long = (nowMs - capturedAtMs).coerceAtLeast(0L)

    /** Vrai si une mesure de [ageMs] est trop vieille pour affirmer quoi que ce soit. */
    fun isStale(ageMs: Long): Boolean = ageMs > STALE_MS

    /** Vrai si [ttcS] est une urgence (contact sous [URGENT_TTC_S]). */
    fun isUrgent(ttcS: Float?): Boolean = ttcS != null && ttcS <= URGENT_TTC_S
}
