package dev.sam3vision.core.picto

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.AntiRepeat

/**
 * Les 10 noms parlés dedans (spec v4) : le concept SAM suivi ([concept],
 * anglais), ses noms parlés ([spoken], canoniques FR + EN) et sa base
 * d'identifiant clip ([clipBase]).
 *
 * Ils ne bornent plus la recherche (l'unique ordre « mode find my X »
 * accepte tout objet) : ils servent la détection porte et murs
 * ([Kind] du direct dedans) et le pont FR-EN de la chasse (« cles » chasse
 * les détections « keys », et l'inverse).
 *
 * « glass » (le verre) est volontairement exclu de [GLASSES] : un verre
 * n'est pas des lunettes. « personne » vise [PERSON] ; « homme » et
 * « femme » ne visent rien (question LLM).
 */
enum class FindIndoorTarget(
    val concept: String,
    val en: String,
    val enCap: String,
    val fr: String,
    val spoken: Set<String>,
    val clipBase: String,
) {
    KEYS("keys", "keys", "Keys", "clés", setOf("keys", "key", "cles", "cle", "clef", "clefs"), "keys"),
    MEDICINE_BOX(
        "medicine box", "medicine box", "Medicine box", "boîte de médicaments",
        setOf("medicine box", "medicine", "medicines", "medicaments", "medicament", "boite de medicaments"),
        "medicine_box",
    ),
    GLASSES("glasses", "glasses", "Glasses", "lunettes", setOf("glasses", "lunettes", "lunette"), "glasses"),
    GLASSES_CASE(
        "glasses case", "glasses case", "Glasses case", "boîtier de lunettes",
        setOf("glasses case", "boitier", "boitier de lunettes", "etui", "etui a lunettes"),
        "glasses_case",
    ),
    PHONE("phone", "phone", "Phone", "téléphone", setOf("phone", "telephone", "portable"), "phone"),
    SHOES("shoes", "shoes", "Shoes", "chaussures", setOf("shoes", "shoe", "chaussures", "chaussure"), "shoes"),
    DOOR("door", "door", "Door", "porte", setOf("door", "porte"), "door"),
    WALL("wall", "wall", "Wall", "murs", setOf("wall", "walls", "mur", "murs"), "wall"),
    PERSON("person", "person", "Person", "humain", setOf("person", "humain", "personne"), "person"),
    SOCKS("socks", "socks", "Socks", "chaussettes", setOf("socks", "sock", "chaussettes", "chaussette"), "socks"),
    ;

    companion object {
        private val BY_SPOKEN: Map<String, FindIndoorTarget> =
            entries.flatMap { target -> target.spoken.map { word -> word to target } }.toMap()

        /**
         * Cible visée par [text] (forme parlée FR ou EN, un ou plusieurs
         * mots), `null` si aucune. Tolérant : casse, accents et ponctuation
         * neutralisés ([KnownSizes.canonical]).
         */
        fun fromSpoken(text: String): FindIndoorTarget? = BY_SPOKEN[KnownSizes.canonical(text)]
    }
}

/**
 * Niveaux du bus vocal dedans (spec v4 : le plus petit gagne).
 * Chaîne : objet cherché ([FIND]) > parole user ([USER]) > humain ([HUMAN])
 * > chat ([CHAT]) > porte et murs ([DOOR_WALL]).
 *
 * [USER], [HUMAN] et [CHAT] reprennent les numéros du dehors (même sens,
 * autres missions). [FIND] reprend le cran 0 (le cran du plus prioritaire
 * du mode : voitures dehors, objet cherché dedans — la règle « seul 0
 * coupe la parole user » vaut donc inchangée dedans). [DOOR_WALL] est un
 * cran neuf (13, hors 0-12 et 100-102) : le cadran des portes et murs
 * dedans ne doit jamais router vers un handler du dehors. Le routeur
 * arbitre par mode exclusif : dedans, seuls ces cinq crans parlent.
 */
object FindIndoorLevels {
    /** Objet cherché trouvé : le cran 0, comme les voitures dehors. */
    const val FIND = 0

    /** Parole user : coupe tout sauf le cran 0, dedans comme dehors. */
    const val USER = 1

    /** Humain dedans (autre mission, même cran que dehors). */
    const val HUMAN = 2

    /** Réponse chat (autre mission, même cran que dehors). */
    const val CHAT = 9

    /** Porte et murs dedans : le plus bas de la chaîne dedans. */
    const val DOOR_WALL = 13

    /** La chaîne dedans, du plus prioritaire au moins prioritaire. */
    val ORDER: List<Int> = listOf(FIND, USER, HUMAN, CHAT, DOOR_WALL)
}

/**
 * Anti-répétition dedans : clés stables (AntiRepeat, autre mission) et
 * durées. Le trouvé se redit à 2 s (comme les humains dehors), sauf
 * changement de cadran (immédiat, comme l'alignement passage) ; porte et
 * murs à 4 s (comme les grillages dehors). Démarrage, fin, refus et mode :
 * one-shot par ordre, sans clé.
 */
object FindIndoorRepeat {
    /** Résultat de recherche : trouvé à un cadran donné (table centrale). */
    const val FIND_FOUND_KEY = AntiRepeat.KEY_FIND_FOUND

    /** Durée du trouvé au même cadran (changement = immédiat). */
    const val FIND_FOUND_MS = 2_000L

    /** Porte et murs dedans (table centrale). */
    const val DOOR_WALL_KEY = AntiRepeat.KEY_DOOR_WALL

    /** Durée porte et murs (changement de cadran = immédiat). */
    const val DOOR_WALL_MS = 4_000L
}

/** Un clip anglais : son entrée catalogue (`vp_<id>_en.mp3`) et son texte. */
data class FindClip(val id: String, val text: String)

/**
 * Clips anglais porte et murs dedans (spec v4 : clips anglais uniquement,
 * zéro réseau). Textes composés : le catalogue voix les déclare par
 * morceaux (`parts`), les MP3 sont générés à part (`gen-voicepack.py`,
 * autre mission). La recherche d'objet, elle, parle en synthèse vocale
 * ([PictoVoiceText]) : aucun clip pré-enregistré ne peut couvrir un objet
 * quelconque. Pur et testé en JVM.
 */
object FindIndoorClips {

    /** Porte dedans au cadran [hour] : « Door at 3 o'clock. » (7 clips). */
    fun doorAt(hour: Int): FindClip = FindClip("indoor_door_" + hour, "Door at " + hour + " o'clock.")

    /** Mur dedans au cadran [hour] : « Wall at 12 o'clock. » (7 clips). */
    fun wallAt(hour: Int): FindClip = FindClip("indoor_wall_" + hour, "Wall at " + hour + " o'clock.")
}

/**
 * Arbitre dedans : parmi les candidats audibles, le plus petit niveau gagne
 * ([FindIndoorLevels]) ; à égalité, le premier (ordre d'appel stable).
 * `null` = rien à dire. L'écrasé n'est jamais redit sauf re-détecté actuel
 * (routeur + AntiRepeat, autre mission). Pur et testé en JVM.
 */
object FindIndoorPriority {

    /** Un candidat audible : niveau, texte et clip. */
    data class Candidate(val level: Int, val text: String, val clipId: String?)

    /** Le gagnant dedans, `null` si aucun candidat. */
    fun decide(candidates: List<Candidate>): Candidate? =
        candidates.minByOrNull { it.level }
}
