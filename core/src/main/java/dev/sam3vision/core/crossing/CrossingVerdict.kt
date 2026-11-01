package dev.sam3vision.core.crossing

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.LivePrompts

/**
 * Niveaux vocaux du mode Traversée (SPEC v4, traversée exclusive) : le verdict
 * parle en premier, puis les humains, puis les bordures du passage. Chat et GPS
 * sont muets tant que le mode est actif, la dictée reste active.
 */
object CrossingLevels {
    /** Verdict (feu piéton puis voitures) : prioritaire sur tout le reste. */
    const val VERDICT = 100

    /** Humains du passage, après le verdict. */
    const val HUMAN = 101

    /** Bordures du passage, après les humains. */
    const val CURB = 102
}

/** Verdict de traversée : traverser, ou attendre. */
enum class CrossingVerdict { Cross, DontCross }

/**
 * Feu piéton vu par le verdict : [Red] (« don't cross »), [Green] (les voitures
 * tranchent), [Absent] (aucun feu en vue — les voitures tranchent aussi).
 */
enum class CrossingVerdictLight { Red, Green, Absent }

/**
 * Phrases du verdict, en anglais uniquement (clips) : [DONT_CROSS] quand le feu
 * est rouge ou qu'une voiture bouge, [CROSS] sinon. [clipId] est le clip stable
 * de la phrase — zéro synthèse à la volée sur ce chemin.
 */
object CrossingVerdictText {
    const val DONT_CROSS = "Don't cross."
    const val CROSS = "Cross."

    const val DONT_CROSS_CLIP = "verdict-dont-cross"
    const val CROSS_CLIP = "verdict-cross"

    fun phrase(verdict: CrossingVerdict): String = when (verdict) {
        CrossingVerdict.Cross -> CROSS
        CrossingVerdict.DontCross -> DONT_CROSS
    }

    fun clipId(verdict: CrossingVerdict): String = when (verdict) {
        CrossingVerdict.Cross -> CROSS_CLIP
        CrossingVerdict.DontCross -> DONT_CROSS_CLIP
    }
}

/**
 * Règle du verdict (SPEC v4) : feu piéton rouge = don't cross ; vert ou absent =
 * voitures en mouvement ? don't cross : cross. Véhicules = voiture uniquement
 * ([isCarLabel]) : bus, vélo, camion et moto ne tranchent jamais.
 *
 * Pur et testé en JVM.
 */
object CrossingVerdictRule {

    /** Vrai si [label] (brut, tel que renvoyé par SAM) désigne une voiture. */
    fun isCarLabel(label: String): Boolean {
        val key = KnownSizes.canonical(label)
        return key == "car" || key == "cars" || key == "voiture" || key == "voitures"
    }

    /** Verdict pour [light] et [carMoving] (une voiture bouge sur la frame). */
    fun decide(light: CrossingVerdictLight, carMoving: Boolean): CrossingVerdict = when {
        light == CrossingVerdictLight.Red -> CrossingVerdict.DontCross
        carMoving -> CrossingVerdict.DontCross
        else -> CrossingVerdict.Cross
    }
}

/**
 * Signal du verdict à prononcer : [phrase] (anglais, [clipId] joint), [verdict],
 * [changed] vrai quand le verdict vient de changer (coupe tout, passage au rouge
 * comme au vert : contredire vite est sûr dans les deux sens).
 */
data class CrossingVerdictCue(
    val phrase: String,
    val verdict: CrossingVerdict,
    val changed: Boolean,
    val clipId: String,
)

/**
 * Annonceur du verdict : le premier verdict parle aussitôt, un changement parle
 * aussitôt ([CrossingVerdictCue.changed]), un verdict maintenu se répète toutes
 * les [REPEAT_MS] ms (3 s, SPEC v4). Pur et testé en JVM : [atMs] est fourni.
 */
class CrossingVerdictAnnouncer(
    private val repeatMs: Long = REPEAT_MS,
) {
    private var announced: CrossingVerdict? = null
    private var lastSpokenAtMs: Long? = null

    /** Dernier signal produit (`null` tant que rien n'a été annoncé). */
    var lastCue: CrossingVerdictCue? = null
        private set

    /**
     * Verdict [verdict] à [atMs] : le signal à prononcer, ou `null` pour garder
     * le silence (verdict maintenu dont la répétition n'est pas encore due).
     */
    fun onFrame(atMs: Long, verdict: CrossingVerdict): CrossingVerdictCue? {
        val previous = announced
        if (previous == null || previous != verdict) {
            announced = verdict
            lastSpokenAtMs = atMs
            val cue = CrossingVerdictCue(
                CrossingVerdictText.phrase(verdict), verdict,
                changed = previous != null, CrossingVerdictText.clipId(verdict),
            )
            lastCue = cue
            return cue
        }
        val last = lastSpokenAtMs ?: return null
        if (atMs - last < repeatMs) return null
        lastSpokenAtMs = atMs
        val cue = CrossingVerdictCue(
            CrossingVerdictText.phrase(verdict), verdict,
            changed = false, CrossingVerdictText.clipId(verdict),
        )
        lastCue = cue
        return cue
    }

    /** Oublie verdict et délai (nouvelle traversée, nouvelle séance). */
    fun reset() {
        announced = null
        lastSpokenAtMs = null
        lastCue = null
    }

    companion object {
        /** Répétition d'un verdict maintenu (SPEC v4, changement immédiat). */
        const val REPEAT_MS = 3_000L
    }
}

/**
 * Publication vocale traversée : [level] (100/101/102), [text] anglais,
 * [clipId] joint (`null` = synthèse, réservé au chat et au GPS — jamais ici).
 */
data class CrossingPost(val level: Int, val text: String, val clipId: String?)

/**
 * Ordre exclusif de la traversée : verdict > humains > bordures. Chaque place
 * `null` se tait (pas de remplissage : un verdict seul ne fait pas parler les
 * bordures). Pur et testé en JVM.
 */
object CrossingPriority {
    fun order(verdict: CrossingPost?, human: CrossingPost?, curb: CrossingPost?): List<CrossingPost> =
        listOfNotNull(verdict, human, curb)
}

/**
 * Concepts du mode Traversée (SPEC v4) : le feu piéton (le verdict), la voiture
 * seule (bus, vélo, camion, moto sortis), la personne et le passage (niveaux 101
 * et 102, annoncés par leurs missions), la bordure (niveau 102). [base] ensuite,
 * borné à [LivePrompts.MAX] — le feu d'abord, jamais coupé.
 */
object CrossingVerdictPrompts {
    val PROMPTS: List<String> = listOf(
        CrossingPrompts.LIGHT,
        "car",
        CrossingPrompts.PERSON,
        CrossingPrompts.CROSSWALK,
        "curb",
    )

    fun promptsFor(base: List<String> = emptyList()): List<String> =
        LivePrompts.normalize(PROMPTS + base)
}

/**
 * Exclusivité du mode Traversée : chat et GPS muets, dictée active. Le routeur
 * (hors `:core`) y lit qui parle : tout post chat/GPS est jeté tant que
 * [traversing] est vrai, les ordres dictée (`crossing off`, …) restent écoutés.
 */
object CrossingExclusivity {
    /** Chat muet en traversée (file reprise à la sortie, jamais perdue). */
    fun chatMuted(traversing: Boolean): Boolean = traversing

    /** GPS muet en traversée (ni manœuvres ni rappels). */
    fun gpsMuted(traversing: Boolean): Boolean = traversing

    /** Dictée toujours active, y compris en traversée. */
    fun dictationActive(): Boolean = true
}
