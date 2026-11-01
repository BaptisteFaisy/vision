package dev.sam3vision.core.speech

/**
 * Clips anglais SPEC v4 : chaque phrase ou mot fixe parlé hors-ligne, avec son
 * identifiant de clip (`vp_<id>_en.mp3` dans `res/raw`, voix du pack
 * `assets/vp_voice.txt`).
 *
 * Miroir du catalogue (`tools/isee-clips-en-catalog.json`, gardé par
 * `tools/check-isee-clips-en.py`) : tout écart casse le check. Les entrées
 * `REUSE` pointent vers le pack existant (`tools/voicepack-catalog.json`,
 * MP3 déjà embarqués) — aucun doublon audio, le texte est recopié au
 * caractère près.
 *
 * Couverture : dehors niveaux 0 (voitures), 2 (humains), 4 (grillages, murs,
 * barrières), 5 (bordures), 6 (escaliers jalons 4/2/1 + marches), 7 (poteaux,
 * mobilier, divers), 8 (passage), 10 (trottoirs, comportement actuel), 12
 * (repli gauche/droite/tout droit), verdicts traversée, confirmations des 10
 * ordres dictée, recherche dedans (10 objets + trouvé + cadran), nombres (5,
 * 10, 15, 20 mètres + 1 à 12 + heures 1 à 12). Le GPS (manœuvres, rappels) et
 * le chat restent en synthèse réseau, hors scope.
 *
 * Pur Kotlin : testé en JVM, aucune dépendance Android.
 */
object IseeClipsEn {

    // Niveau 0 : voitures en mouvement < 20 m, présence seule, pluriel si plusieurs.
    const val CAR = "car"
    const val CAR_TEXT = "Car."
    const val CARS = "cars"
    const val CARS_TEXT = "Cars."

    // Niveau 2 : humain < 10 m (phrase + cadran [clock] + distance [meters]).
    const val HUMAN_PERSON = "human_person"
    const val HUMAN_PERSON_TEXT = "Person."

    // Niveau 2 imminent (2 m ou moins) : mot immédiat, clip existant.
    const val HUMAN_IMMINENT = "guide_stop"
    const val HUMAN_IMMINENT_TEXT = "Stop."

    // Niveau 4 : grillages + murs + barrières < 6 m.
    const val FENCE = "fence"
    const val FENCE_TEXT = "Fence."
    const val WALL = "wall"
    const val WALL_TEXT = "Wall."
    const val BARRIER = "barrier"
    const val BARRIER_TEXT = "Barrier."

    // Niveau 5 : bordures < 6 m.
    const val CURB_UP = "curb_up"
    const val CURB_UP_TEXT = "Curb up."
    const val CURB_DOWN = "curb_down"
    const val CURB_DOWN_TEXT = "Curb down."

    // Niveau 6 : escaliers, jalons 4/2/1 m + nombre de marches ([number] + STEPS).
    const val STAIRS_UP_4 = "stairs_up_4"
    const val STAIRS_UP_4_TEXT = "Stairs up, 4 meters."
    const val STAIRS_UP_2 = "stairs_up_2"
    const val STAIRS_UP_2_TEXT = "Stairs up, 2 meters."
    const val STAIRS_UP_1 = "stairs_up_1"
    const val STAIRS_UP_1_TEXT = "Stairs up, 1 meter."
    const val STAIRS_DOWN_4 = "stairs_down_4"
    const val STAIRS_DOWN_4_TEXT = "Stairs down, 4 meters."
    const val STAIRS_DOWN_2 = "stairs_down_2"
    const val STAIRS_DOWN_2_TEXT = "Stairs down, 2 meters."
    const val STAIRS_DOWN_1 = "stairs_down_1"
    const val STAIRS_DOWN_1_TEXT = "Stairs down, 1 meter."
    const val STAIRS_STEPS = "stairs_steps"
    const val STAIRS_STEPS_TEXT = "Steps."

    // Niveau 7 : poteaux + mobilier vertical + divers < 6 m.
    const val POLE = "pole"
    const val POLE_TEXT = "Pole."
    const val LAMP = "lamp"
    const val LAMP_TEXT = "Street lamp."
    const val SIGN = "sign"
    const val SIGN_TEXT = "Sign."
    const val ANIMAL = "animal"
    const val ANIMAL_TEXT = "Animal."
    const val BIN = "bin"
    const val BIN_TEXT = "Trash can."
    const val KEEPSAKE = "keepsake"
    const val KEEPSAKE_TEXT = "Keepsake."

    // Niveau 8 : passage piéton < 6 m (annonce + alignement + arrivée).
    const val CROSSWALK_ALIGNED = "crosswalk_aligned"
    const val CROSSWALK_ALIGNED_TEXT = "Aligned with the crosswalk."
    const val CROSSWALK_AHEAD = "cross_approach_ahead"
    const val CROSSWALK_AHEAD_TEXT = "Crosswalk ahead."
    const val CROSSWALK_LEFT = "cross_approach_left"
    const val CROSSWALK_LEFT_TEXT = "Crosswalk to the left."
    const val CROSSWALK_RIGHT = "cross_approach_right"
    const val CROSSWALK_RIGHT_TEXT = "Crosswalk to the right."
    const val CROSSWALK_ARRIVED = "cross_arrived"
    const val CROSSWALK_ARRIVED_TEXT = "On the crosswalk."

    // Niveau 10 : trottoirs, comportement actuel (clips existants).
    const val SIDEWALK_AHEAD = "sidewalk_ahead"
    const val SIDEWALK_AHEAD_TEXT = "Sidewalk ahead."
    const val SIDEWALK_LEFT = "sidewalk_left"
    const val SIDEWALK_LEFT_TEXT = "Sidewalk on the left."
    const val SIDEWALK_RIGHT = "sidewalk_right"
    const val SIDEWALK_RIGHT_TEXT = "Sidewalk on the right."
    const val SIDEWALK_ACROSS = "sidewalk_across"
    const val SIDEWALK_ACROSS_TEXT = "Sidewalk across the street."

    // Niveau 12 : repli (gauche, droite, tout droit — clips existants).
    const val GO_LEFT = "guide_left"
    const val GO_LEFT_TEXT = "Left."
    const val GO_RIGHT = "guide_right"
    const val GO_RIGHT_TEXT = "Right."
    const val GO_STRAIGHT = "guide_straight"
    const val GO_STRAIGHT_TEXT = "Straight."

    // Traversée exclusive : verdict (feu piéton puis voitures).
    const val VERDICT_CROSS = "verdict_cross"
    const val VERDICT_CROSS_TEXT = "Cross now."
    const val VERDICT_WAIT = "verdict_wait"
    const val VERDICT_WAIT_TEXT = "Don't cross."

    // Ordres dictée : modes dehors/dedans + traversée (clips existants).
    const val OUTDOOR_ON = "outdoor_on"
    const val OUTDOOR_ON_TEXT = "Outdoor mode on."
    const val INDOOR_ON = "indoor_on"
    const val INDOOR_ON_TEXT = "Indoor mode on."
    const val CROSSING_ON = "cross_on"
    const val CROSSING_ON_TEXT = "Crossing mode on."
    const val CROSSING_OFF = "cross_off"
    const val CROSSING_OFF_TEXT = "Crossing mode off."

    // Ordres dictée : recherche d'objet (« Looking for » + objet, composés).
    const val FIND_LOOKING_FOR = "find_looking_for"
    const val FIND_LOOKING_FOR_TEXT = "Looking for"
    const val FIND_STOPPED = "find_stopped"
    const val FIND_STOPPED_TEXT = "Search stopped."

    // Ordres dictée : GPS (la synthèse réseau parle les manœuvres, hors scope).
    const val GPS_STARTED = "gps_started"
    const val GPS_STARTED_TEXT = "Guidance started."
    const val GPS_UNAVAILABLE = "gps_unavailable"
    const val GPS_UNAVAILABLE_TEXT = "Guidance unavailable."
    const val GPS_OFF = "nav_stopped"
    const val GPS_OFF_TEXT = "Guidance stopped."

    // Ordres dictée : lunettes.
    const val GLASSES_ON = "glasses_on"
    const val GLASSES_ON_TEXT = "Glasses on."
    const val GLASSES_OFF = "glasses_off"
    const val GLASSES_OFF_TEXT = "Glasses off."

    // Dedans : les 10 objets cherchés (humain = clip niveau 2).
    const val KEYS = "keys"
    const val KEYS_TEXT = "Keys."
    const val MEDBOX = "medbox"
    const val MEDBOX_TEXT = "Medicine box."
    const val GLASSES = "glasses"
    const val GLASSES_TEXT = "Glasses."
    const val GLASSES_CASE = "glasses_case"
    const val GLASSES_CASE_TEXT = "Glasses case."
    const val PHONE = "phone"
    const val PHONE_TEXT = "Phone."
    const val SHOES = "shoes"
    const val SHOES_TEXT = "Shoes."
    const val DOOR = "door"
    const val DOOR_TEXT = "Door."
    const val WALLS = "walls"
    const val WALLS_TEXT = "Walls."
    const val SOCKS = "socks"
    const val SOCKS_TEXT = "Socks."
    const val FOUND = "found"
    const val FOUND_TEXT = "Found."

    // Distances rondes (humains dehors, rappels) : 5, 10, 15, 20 mètres.
    const val METERS_5 = "meters_5"
    const val METERS_5_TEXT = "5 meters."
    const val METERS_10 = "meters_10"
    const val METERS_10_TEXT = "10 meters."
    const val METERS_15 = "meters_15"
    const val METERS_15_TEXT = "15 meters."
    const val METERS_20 = "meters_20"
    const val METERS_20_TEXT = "20 meters."

    // Nombres 1 à 12 (marches d'escalier : [number] + STEPS).
    const val N_1 = "n_1"
    const val N_1_TEXT = "One."
    const val N_2 = "n_2"
    const val N_2_TEXT = "Two."
    const val N_3 = "n_3"
    const val N_3_TEXT = "Three."
    const val N_4 = "n_4"
    const val N_4_TEXT = "Four."
    const val N_5 = "n_5"
    const val N_5_TEXT = "Five."
    const val N_6 = "n_6"
    const val N_6_TEXT = "Six."
    const val N_7 = "n_7"
    const val N_7_TEXT = "Seven."
    const val N_8 = "n_8"
    const val N_8_TEXT = "Eight."
    const val N_9 = "n_9"
    const val N_9_TEXT = "Nine."
    const val N_10 = "n_10"
    const val N_10_TEXT = "Ten."
    const val N_11 = "n_11"
    const val N_11_TEXT = "Eleven."
    const val N_12 = "n_12"
    const val N_12_TEXT = "Twelve."

    // Cadran 1 à 12 h (humains dehors, objets trouvés dedans).
    const val CLOCK_1 = "clock_1"
    const val CLOCK_1_TEXT = "1 o'clock."
    const val CLOCK_2 = "clock_2"
    const val CLOCK_2_TEXT = "2 o'clock."
    const val CLOCK_3 = "clock_3"
    const val CLOCK_3_TEXT = "3 o'clock."
    const val CLOCK_4 = "clock_4"
    const val CLOCK_4_TEXT = "4 o'clock."
    const val CLOCK_5 = "clock_5"
    const val CLOCK_5_TEXT = "5 o'clock."
    const val CLOCK_6 = "clock_6"
    const val CLOCK_6_TEXT = "6 o'clock."
    const val CLOCK_7 = "clock_7"
    const val CLOCK_7_TEXT = "7 o'clock."
    const val CLOCK_8 = "clock_8"
    const val CLOCK_8_TEXT = "8 o'clock."
    const val CLOCK_9 = "clock_9"
    const val CLOCK_9_TEXT = "9 o'clock."
    const val CLOCK_10 = "clock_10"
    const val CLOCK_10_TEXT = "10 o'clock."
    const val CLOCK_11 = "clock_11"
    const val CLOCK_11_TEXT = "11 o'clock."
    const val CLOCK_12 = "clock_12"
    const val CLOCK_12_TEXT = "12 o'clock."

    /** Jalon escalier montant pour [meters] (4, 2, 1), `null` sinon. */
    fun stairsUp(meters: Int): String? = when (meters) {
        4 -> STAIRS_UP_4
        2 -> STAIRS_UP_2
        1 -> STAIRS_UP_1
        else -> null
    }

    /** Jalon escalier descendant pour [meters] (4, 2, 1), `null` sinon. */
    fun stairsDown(meters: Int): String? = when (meters) {
        4 -> STAIRS_DOWN_4
        2 -> STAIRS_DOWN_2
        1 -> STAIRS_DOWN_1
        else -> null
    }

    /** Clip du nombre [n] (1 à 12, marches), `null` hors plage. */
    fun number(n: Int): String? = when (n) {
        1 -> N_1
        2 -> N_2
        3 -> N_3
        4 -> N_4
        5 -> N_5
        6 -> N_6
        7 -> N_7
        8 -> N_8
        9 -> N_9
        10 -> N_10
        11 -> N_11
        12 -> N_12
        else -> null
    }

    /** Clip du cadran [hour] (1 à 12), `null` hors plage. */
    fun clock(hour: Int): String? = when (hour) {
        1 -> CLOCK_1
        2 -> CLOCK_2
        3 -> CLOCK_3
        4 -> CLOCK_4
        5 -> CLOCK_5
        6 -> CLOCK_6
        7 -> CLOCK_7
        8 -> CLOCK_8
        9 -> CLOCK_9
        10 -> CLOCK_10
        11 -> CLOCK_11
        12 -> CLOCK_12
        else -> null
    }

    /** Clip de la distance ronde [meters] (5, 10, 15, 20), `null` sinon. */
    fun meters(meters: Int): String? = when (meters) {
        5 -> METERS_5
        10 -> METERS_10
        15 -> METERS_15
        20 -> METERS_20
        else -> null
    }

    /** Les 10 objets cherchés dedans, dans l'ordre de la spec. */
    val indoorObjects: List<String> = listOf(
        KEYS, MEDBOX, GLASSES, GLASSES_CASE, PHONE,
        SHOES, DOOR, WALLS, HUMAN_PERSON, SOCKS,
    )

    /**
     * Nom dicté (« find my X ») → clip de l'objet, `null` si inconnu.
     * Les alias couvrent les pluriels et les noms longs parlés.
     */
    fun findTarget(spoken: String): String? = when (spoken.trim().lowercase()) {
        "keys", "key" -> KEYS
        "medicine box", "meds", "pills", "medication" -> MEDBOX
        "glasses", "eyeglasses", "spectacles" -> GLASSES
        "glasses case", "eyeglasses case", "case" -> GLASSES_CASE
        "phone", "telephone", "mobile" -> PHONE
        "shoes", "shoe" -> SHOES
        "door" -> DOOR
        "walls", "wall" -> WALLS
        "person", "human", "people", "someone" -> HUMAN_PERSON
        "socks", "sock" -> SOCKS
        else -> null
    }

    /** Nom du MP3 embarqué (`res/raw`) pour [clipId]. */
    fun fileName(clipId: String): String = "vp_" + clipId + "_en.mp3"

    /** Registre complet : clipId → texte parlé (nouveaux + réutilisés). */
    val all: Map<String, String> = outdoor() + orders() + indoor() + quantities()

    private fun outdoor(): Map<String, String> = mapOf(
        CAR to CAR_TEXT,
        CARS to CARS_TEXT,
        HUMAN_PERSON to HUMAN_PERSON_TEXT,
        HUMAN_IMMINENT to HUMAN_IMMINENT_TEXT,
        FENCE to FENCE_TEXT,
        WALL to WALL_TEXT,
        BARRIER to BARRIER_TEXT,
        CURB_UP to CURB_UP_TEXT,
        CURB_DOWN to CURB_DOWN_TEXT,
        STAIRS_UP_4 to STAIRS_UP_4_TEXT,
        STAIRS_UP_2 to STAIRS_UP_2_TEXT,
        STAIRS_UP_1 to STAIRS_UP_1_TEXT,
        STAIRS_DOWN_4 to STAIRS_DOWN_4_TEXT,
        STAIRS_DOWN_2 to STAIRS_DOWN_2_TEXT,
        STAIRS_DOWN_1 to STAIRS_DOWN_1_TEXT,
        STAIRS_STEPS to STAIRS_STEPS_TEXT,
        POLE to POLE_TEXT,
        LAMP to LAMP_TEXT,
        SIGN to SIGN_TEXT,
        ANIMAL to ANIMAL_TEXT,
        BIN to BIN_TEXT,
        KEEPSAKE to KEEPSAKE_TEXT,
        CROSSWALK_ALIGNED to CROSSWALK_ALIGNED_TEXT,
        CROSSWALK_AHEAD to CROSSWALK_AHEAD_TEXT,
        CROSSWALK_LEFT to CROSSWALK_LEFT_TEXT,
        CROSSWALK_RIGHT to CROSSWALK_RIGHT_TEXT,
        CROSSWALK_ARRIVED to CROSSWALK_ARRIVED_TEXT,
        SIDEWALK_AHEAD to SIDEWALK_AHEAD_TEXT,
        SIDEWALK_LEFT to SIDEWALK_LEFT_TEXT,
        SIDEWALK_RIGHT to SIDEWALK_RIGHT_TEXT,
        SIDEWALK_ACROSS to SIDEWALK_ACROSS_TEXT,
        GO_LEFT to GO_LEFT_TEXT,
        GO_RIGHT to GO_RIGHT_TEXT,
        GO_STRAIGHT to GO_STRAIGHT_TEXT,
        VERDICT_CROSS to VERDICT_CROSS_TEXT,
        VERDICT_WAIT to VERDICT_WAIT_TEXT,
    )

    private fun orders(): Map<String, String> = mapOf(
        OUTDOOR_ON to OUTDOOR_ON_TEXT,
        INDOOR_ON to INDOOR_ON_TEXT,
        CROSSING_ON to CROSSING_ON_TEXT,
        CROSSING_OFF to CROSSING_OFF_TEXT,
        FIND_LOOKING_FOR to FIND_LOOKING_FOR_TEXT,
        FIND_STOPPED to FIND_STOPPED_TEXT,
        GPS_STARTED to GPS_STARTED_TEXT,
        GPS_UNAVAILABLE to GPS_UNAVAILABLE_TEXT,
        GPS_OFF to GPS_OFF_TEXT,
        GLASSES_ON to GLASSES_ON_TEXT,
        GLASSES_OFF to GLASSES_OFF_TEXT,
    )

    private fun indoor(): Map<String, String> = mapOf(
        KEYS to KEYS_TEXT,
        MEDBOX to MEDBOX_TEXT,
        GLASSES to GLASSES_TEXT,
        GLASSES_CASE to GLASSES_CASE_TEXT,
        PHONE to PHONE_TEXT,
        SHOES to SHOES_TEXT,
        DOOR to DOOR_TEXT,
        WALLS to WALLS_TEXT,
        SOCKS to SOCKS_TEXT,
        FOUND to FOUND_TEXT,
    )

    private fun quantities(): Map<String, String> = mapOf(
        METERS_5 to METERS_5_TEXT,
        METERS_10 to METERS_10_TEXT,
        METERS_15 to METERS_15_TEXT,
        METERS_20 to METERS_20_TEXT,
        N_1 to N_1_TEXT,
        N_2 to N_2_TEXT,
        N_3 to N_3_TEXT,
        N_4 to N_4_TEXT,
        N_5 to N_5_TEXT,
        N_6 to N_6_TEXT,
        N_7 to N_7_TEXT,
        N_8 to N_8_TEXT,
        N_9 to N_9_TEXT,
        N_10 to N_10_TEXT,
        N_11 to N_11_TEXT,
        N_12 to N_12_TEXT,
        CLOCK_1 to CLOCK_1_TEXT,
        CLOCK_2 to CLOCK_2_TEXT,
        CLOCK_3 to CLOCK_3_TEXT,
        CLOCK_4 to CLOCK_4_TEXT,
        CLOCK_5 to CLOCK_5_TEXT,
        CLOCK_6 to CLOCK_6_TEXT,
        CLOCK_7 to CLOCK_7_TEXT,
        CLOCK_8 to CLOCK_8_TEXT,
        CLOCK_9 to CLOCK_9_TEXT,
        CLOCK_10 to CLOCK_10_TEXT,
        CLOCK_11 to CLOCK_11_TEXT,
        CLOCK_12 to CLOCK_12_TEXT,
    )
}
