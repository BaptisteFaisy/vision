package dev.sam3vision.core.live

/**
 * Table centrale d'antirépétition de la voix (SPEC v4, dehors) : une seule
 * source pour tous les délais « ne redis pas avant X » des annonces parlées.
 *
 * Contrat partagé (l'intégration suppose cette signature) :
 * `AntiRepeat.allow(clé, nowMs)` — vrai s'il faut parler. Chaque niveau de
 * la spec a sa clé stable ; les autres missions appellent [allow] au moment
 * de poster sur le bus voix, **pour une annonce vraiment parlée** (après
 * écrasement — comme [AttentionCadence.record], une annonce avalée par le
 * lecteur, la synthèse ou une dictée ne consomme pas son tour, sinon
 * l'écrasé mangerait le tour du prochain).
 *
 * Table de la spec (clé = durée) :
 * - `car-moving` = 4 s (niveau 0, voitures en mouvement < 20 m) ;
 * - `human-soft` = 2 s (niveau 2, humains < 10 m) ;
 * - `human-imminent` = immédiat hors table (niveau 2, ≤ 2 m : dit toujours) ;
 * - `gps-reminder` = sans antirépétition (niveau 3, rappel aux 15 s de silence) ;
 * - `fence` = 4 s (niveau 4, grillages + murs + barrières < 6 m) ;
 * - `curb` = 2 s (niveau 5, bordures < 6 m) ;
 * - `stairs` = 2 s (niveau 6, escaliers) ;
 * - `pole` = 2 s (niveau 7, poteaux + mobilier vertical < 6 m) ;
 * - `misc` = 2 s (niveau 7, divers : animal, poubelle, souvenirs < 6 m) ;
 * - `crosswalk-announce` = 1 fois par vue (niveau 8, annonce du passage) ;
 * - `crosswalk-align` = au changement (niveau 8, alignement : pas de délai,
 *   l'appelant n'appelle que quand l'alignement change) ;
 * - `crosswalk-arrival` = 1 fois (niveau 8, arrivée sur le passage) ;
 * - `sidewalk` = 10 s (niveau 10, trottoirs) ;
 * - `verdict` = 3 s avec changement immédiat (traversée : le verdict
 *   nouveau parle tout de suite, voir les clés paramétrées ci-dessous).
 * - `find-found` = 2 s (dedans : trouvaille find, niveau 0 indoor).
 * - `door-wall` = 4 s (dedans : porte et murs, niveau 13).
 * GPS (niveaux 3, 11) et chat (niveau 9) : sans antirépétition — le chat
 * n'a pas de clé (reprise depuis la coupure, pas de redite).
 *
 * Clés paramétrées : `base:état` (ex. `verdict:cross`,
 * `verdict:dont-cross`) — la durée est celle de la base, la mémoire est par
 * clé complète, donc un changement d'état parle immédiatement. Réservé aux
 * niveaux « changement immédiat » (verdict, alignement passage).
 *
 * Clés « 1 fois » (`crosswalk-announce`, `crosswalk-arrival`) : parlent une
 * fois puis se taisent jusqu'à [reset] (nouvelle séance) ou [forget]
 * (nouvelle vue) — il n'y a pas de délai, seulement l'oubli explicite.
 *
 * Clés inconnues : toujours autorisées (sans antirépétition) — une mission
 * qui invente une clé ne se fait jamais taire par une faute de frappe, et
 * l'indoor (objet cherché 2 s, porte et murs 4 s) n'a qu'à réutiliser
 * `human-soft` pour l'humain.
 *
 * Pur JVM, synchrone ([Synchronized] : les guetteurs appellent depuis des
 * coroutines) et testé ([atMs] fourni, comme [AnnouncementGate]). [reset] en
 * début de séance (et dans les tests : l'objet est global).
 */
object AntiRepeat {

    /** Niveau 0 : voitures en mouvement < 20 m — 4 s entre deux annonces. */
    const val KEY_CAR_MOVING = "car-moving"

    /** Niveau 2 : humains < 10 m (phrase + cadran + distance) — 2 s. */
    const val KEY_HUMAN_SOFT = "human-soft"

    /**
     * Niveau 2 : humain imminent (≤ 2 m, mot immédiat) — hors table,
     * toujours autorisé ([allow] ne mémorise rien pour cette clé).
     */
    const val KEY_HUMAN_IMMINENT = "human-imminent"

    /**
     * Niveau 3 : rappel GPS (répète la dernière consigne + distance
     * restante) — sans antirépétition, toujours autorisé.
     */
    const val KEY_GPS_REMINDER = "gps-reminder"

    /** Niveau 4 : grillages + murs + barrières < 6 m — 4 s. */
    const val KEY_FENCE = "fence"

    /** Niveau 5 : bordures < 6 m — 2 s. */
    const val KEY_CURB = "curb"

    /** Niveau 6 : escaliers (jalons 4/2/1 + marches) — 2 s. */
    const val KEY_STAIRS = "stairs"

    /** Niveau 7 : poteaux + mobilier vertical (lampadaire, panneau) < 6 m — 2 s. */
    const val KEY_POLE = "pole"

    /** Niveau 7 : divers (animal, poubelle, souvenirs) < 6 m — 2 s. */
    const val KEY_MISC = "misc"

    /**
     * Niveau 8 : annonce du passage piéton — 1 fois par vue (parle une fois,
     * puis silence jusqu'à [forget] ou [reset]).
     */
    const val KEY_CROSSWALK_ANNOUNCE = "crosswalk-announce"

    /**
     * Niveau 8 : alignement sur le passage — au changement (pas de délai :
     * l'appelant n'appelle que quand l'alignement change, `crosswalk-align:état`
     * possible mais inutile puisque chaque appel passe).
     */
    const val KEY_CROSSWALK_ALIGN = "crosswalk-align"

    /**
     * Niveau 8 : arrivée sur le passage — 1 fois (parle une fois, puis
     * silence jusqu'à [forget] ou [reset]).
     */
    const val KEY_CROSSWALK_ARRIVAL = "crosswalk-arrival"

    /** Niveau 10 : trottoirs (comportement actuel) — 10 s. */
    const val KEY_SIDEWALK = "sidewalk"

    /**
     * Traversée : verdict (feu piéton puis voitures) — 3 s, changement
     * immédiat : postez `verdict:état` (un état nouveau parle aussitôt, le
     * même état attend 3 s).
     */
    const val KEY_VERDICT = "verdict"

    /** Dedans : trouvaille find (objet cherché détecté) — 2 s. */
    const val KEY_FIND_FOUND = "find-found"

    /** Dedans : porte et murs (niveau 13) — 4 s. */
    const val KEY_DOOR_WALL = "door-wall"

    /** Durée « 1 fois » : ne s'écoule jamais, seul [forget] / [reset] ré-arme. */
    const val ONCE_MS = Long.MAX_VALUE

    /** Instant de la dernière annonce autorisée, par clé complète. */
    private val saidAt = HashMap<String, Long>()

    /**
     * Vrai si la [key] peut parler à [nowMs] (première fois, délai écoulé,
     * ou clé sans antirépétition) — et mémorise le passage le cas échéant.
     * Durée nulle ou clé inconnue : toujours vrai, rien n'est mémorisé.
     * `base:état` : durée de la base, mémoire par clé complète.
     */
    @Synchronized
    fun allow(key: String, nowMs: Long): Boolean {
        val durationMs = durationMs(key)
        if (durationMs <= 0L) return true
        val last = saidAt[key]
        if (last != null && nowMs - last < durationMs) return false
        saidAt[key] = nowMs
        return true
    }

    /**
     * Durée d'antirépétition de [key] en ms ([ONCE_MS] = 1 fois, `0` =
     * sans antirépétition). `base:état` résout la base ; clé inconnue = `0`
     * (jamais bloquante). Pure : ne touche pas la mémoire.
     */
    fun durationMs(key: String): Long = when (baseOf(key)) {
        KEY_CAR_MOVING -> 4_000L
        KEY_HUMAN_SOFT -> 2_000L
        KEY_FENCE -> 4_000L
        KEY_CURB -> 2_000L
        KEY_STAIRS -> 2_000L
        KEY_POLE -> 2_000L
        KEY_MISC -> 2_000L
        KEY_SIDEWALK -> 10_000L
        KEY_VERDICT -> 3_000L
        KEY_FIND_FOUND -> 2_000L
        KEY_DOOR_WALL -> 4_000L
        KEY_CROSSWALK_ANNOUNCE, KEY_CROSSWALK_ARRIVAL -> ONCE_MS
        // Imminent, rappel GPS, alignement : sans délai (toujours autorisés).
        KEY_HUMAN_IMMINENT, KEY_GPS_REMINDER, KEY_CROSSWALK_ALIGN -> 0L
        // Clés inconnues : sans antirépétition (jamais bloquantes).
        else -> 0L
    }

    /** Nouvelle séance (et début de test : l'objet est global) : tout est oublié. */
    @Synchronized
    fun reset() {
        saidAt.clear()
    }

    /**
     * Oublie [key] (clé complète, `base:état` compris) : la prochaine annonce
     * parlera — « 1 fois par vue » (nouvelle vue) et « écrasé re-détecté
     * actuel » (la re-détection parle, pas la redite).
     */
    @Synchronized
    fun forget(key: String) {
        saidAt.remove(key)
    }

    /** Base d'une clé (`verdict:cross` → `verdict`, clé simple inchangée). */
    private fun baseOf(key: String): String = key.substringBefore(':')
}
