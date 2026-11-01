package dev.sam3vision.app.isee

/**
 * Antirépétition SPEC v4 : chaque famille d'annonce parle au plus une fois
 * par fenêtre — redemander avant, c'est non. Fenêtres de la spec : voitures
 * 4 s, humains doux 2 s (imminent immédiat), grillages 4 s, bordures 2 s,
 * escaliers 2 s, poteaux et divers 2 s, trottoirs 10 s, verdict 3 s ; rappel
 * GPS 15 s (le silence qui le déclenche) ; passage piéton et chat : aucune
 * (le passage se redit par état — 1 fois par vue, au changement, 1 fois —,
 * le chat ne se tait jamais sur commande temporelle).
 *
 * Contrat partagé : `object AntiRepeat` + `fun allow(key, nowMs): Boolean`,
 * clés stables par niveau et objet. Une clé inconnue parle toujours (aucune
 * fenêtre contractuelle). Sur un seul fil vocal, l'accès est sérialisé.
 */
object AntiRepeat {
    /** Voitures en mouvement (niveau 0). */
    const val CAR_MOVING = "car-moving"

    /** Humains doux (niveau 2, 101 en Traversée). */
    const val HUMAN_SOFT = "human-soft"

    /** Mot imminent humain (niveau 2, 101 en Traversée) : immédiat, jamais tu. */
    const val HUMAN_IMMINENT = "human-imminent"

    /** Rappel GPS sur silence (niveau 3). */
    const val GPS_REMINDER = "gps-reminder"

    /** Grillages, murs et barrières (niveau 4). */
    const val FENCE = "fence"

    /** Bordures (niveau 5, 102 en Traversée). */
    const val CURB = "curb"

    /** Escaliers (niveau 6). */
    const val STAIRS = "stairs"

    /** Poteaux, mobilier vertical et divers (niveau 7). */
    const val POLE = "pole"

    /** Passage piéton : annonce, 1 fois par vue (niveau 8). */
    const val CROSSWALK_ANNOUNCE = "crosswalk-announce"

    /** Passage piéton : alignement, au changement (niveau 8). */
    const val CROSSWALK_ALIGN = "crosswalk-align"

    /** Passage piéton : arrivée, 1 fois (niveau 8). */
    const val CROSSWALK_ARRIVAL = "crosswalk-arrival"

    /** Trottoirs (niveau 10). */
    const val SIDEWALK = "sidewalk"

    /** Verdict de Traversée (niveau 100). */
    const val VERDICT = "verdict"

    /** Fenêtre par clé (ms) : 0 = jamais tu par le temps (état ou immédiat). */
    private val WINDOW_MS: Map<String, Long> = mapOf(
        CAR_MOVING to 4_000L,
        HUMAN_SOFT to 2_000L,
        HUMAN_IMMINENT to 0L,
        GPS_REMINDER to 15_000L,
        FENCE to 4_000L,
        CURB to 2_000L,
        STAIRS to 2_000L,
        POLE to 2_000L,
        CROSSWALK_ANNOUNCE to 0L,
        CROSSWALK_ALIGN to 0L,
        CROSSWALK_ARRIVAL to 0L,
        SIDEWALK to 10_000L,
        VERDICT to 3_000L,
    )

    /** Dernier parlé admis par clé (`null` = jamais). */
    private val lastSpokenMs = mutableMapOf<String, Long>()

    /**
     * Vrai si [key] peut parler à [nowMs] (fenêtre écoulée) : admis = daté.
     * Pur hors registre (horloge fournie), testé en JVM.
     */
    @Synchronized
    fun allow(key: String, nowMs: Long): Boolean {
        val window = WINDOW_MS[key] ?: return true
        if (window <= 0L) return true
        val last = lastSpokenMs[key]
        if (last != null && nowMs - last < window) return false
        lastSpokenMs[key] = nowMs
        return true
    }

    /** Oublie les dates (nouvelle séance, tests). */
    @Synchronized
    fun reset() {
        lastSpokenMs.clear()
    }
}
