package dev.sam3vision.core.crossing

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.LivePrompts

/**
 * Niveaux vocaux du mode Traversée (SPEC v4, traversée exclusive) : le verdict
 * parle en premier, puis les humains, puis les bordures du passage. Chat et GPS
 * sont muets tant que le mode est actif, la dictée reste active.
 *
 * Échelle du bus : **le plus petit nombre gagne**. Le verdict reste au cran 100
 * (ordre user du 02/11 : les voitures ne parlent qu'en Traversée, le cran 0
 * dehors n'a plus de producteur) : il passe donc devant les humains (101) et les
 * bordures (102), mais un mot d'obstacle dehors (2..12) le précède encore.
 * La protection contre l'écrasement n'est plus le niveau mais le **ré-armement** :
 * `VoiceBus.post` rend `false` quand la phrase est jetée, et
 * [CrossingVerdictAnnouncer.rearm] la rejoue dès la frame suivante (mesuré A9
 * §3 D8 : sans lui, « Don't cross. » se perdait jusqu'à 2 s, indéfiniment sous
 * bavardage continu).
 */
object CrossingLevels {
    /** Verdict (feu piéton puis voitures) : prioritaire sur humains et bordures. */
    const val VERDICT = 100

    /** Humains du passage, après le verdict. */
    const val HUMAN = 101

    /** Bordures du passage, après les humains. */
    const val CURB = 102
}

/** Verdict de traversée : traverser, ou attendre. */
enum class CrossingVerdict { Cross, DontCross }

/**
 * Feu piéton vu par le verdict : [Red] (« don't cross »), [Green] (« cross »,
 * voitures ou pas), [Absent] (aucun feu en vue — les voitures tranchent).
 *
 * [Unknown] est l'absence de **preuve** lisible : boîte manquante sur une frame,
 * couleur illisible, vert pas encore confirmé ([LightEvidenceGate]). Il impose
 * « Don't cross. » avant tout le reste, sans quoi un raté de détection vaudrait
 * autorisation de traverser (mesure A9 §3 D1).
 */
enum class CrossingVerdictLight { Red, Green, Absent, Unknown }

/**
 * Phrases du verdict, en anglais uniquement (clips) : [DONT_CROSS] quand le feu
 * est rouge ou qu'une voiture bouge hors vert, [CROSS] sinon. [clipId] est le
 * clip stable de la phrase — zéro synthèse à la volée sur ce chemin.
 */
object CrossingVerdictText {
    const val DONT_CROSS = "Don't cross."
    const val CROSS = "Cross."

    const val DONT_CROSS_CLIP = "verdict_dont_cross"
    const val CROSS_CLIP = "verdict_cross"

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
 * Règle du verdict (SPEC v4) : feu piéton rouge = don't cross ; vert = cross,
 * voitures en mouvement ou pas (le vert gagne toujours, ordre user du 02/11) ;
 * absent = voitures en mouvement ? don't cross : cross. Véhicules = voiture
 * uniquement ([isCarLabel]) : bus, vélo, camion et moto ne tranchent jamais.
 *
 * [decide] prend aussi le **veto trafic** (`TraverseVeto` : véhicule proche, en
 * approche suivie, ou non prouvé immobile). Ce veto pilotait déjà l'haptique et
 * la pastille ; sans lui la voix contredisait la vibration (« Cross. » sur vert
 * pendant que le téléphone disait rouge — A9 §3 D7). Un veto présent interdit
 * tout feu vert, et couvre aussi les véhicules que [isCarLabel] ignore (bus,
 * camion, moto).
 *
 * Pur et testé en JVM.
 */
object CrossingVerdictRule {

    /** Vrai si [label] (brut, tel que renvoyé par SAM) désigne une voiture. */
    fun isCarLabel(label: String): Boolean {
        val key = KnownSizes.canonical(label)
        return key == "car" || key == "cars" || key == "voiture" || key == "voitures"
    }

    /**
     * Verdict pour [light], [carMoving] (une voiture bouge sur la frame) et
     * [veto] (le veto trafic de la frame, `false` = pas de veto connu).
     */
    fun decide(
        light: CrossingVerdictLight,
        carMoving: Boolean,
        veto: Boolean = false,
    ): CrossingVerdict = when {
        light == CrossingVerdictLight.Red -> CrossingVerdict.DontCross
        light == CrossingVerdictLight.Unknown -> CrossingVerdict.DontCross
        veto -> CrossingVerdict.DontCross
        light == CrossingVerdictLight.Green -> CrossingVerdict.Cross
        carMoving -> CrossingVerdict.DontCross
        else -> CrossingVerdict.Cross
    }

    /**
     * Verdict pour une [Evidence] (sortie de [LightEvidenceGate]) : seule
     * `Evidence.Green` peut mener à « Cross. », `Unknown` comme `Absent` imposent
     * « Don't cross. » ([Evidence.toVerdictLight]).
     */
    fun decide(evidence: Evidence, carMoving: Boolean, veto: Boolean = false): CrossingVerdict =
        decide(evidence.toVerdictLight(), carMoving, veto)
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
 * les [REPEAT_MS] ms (2 s, ordre user du 02/11/2026). Pur et testé en JVM : [atMs] est fourni.
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
    /**
     * Le post a été **refusé par le bus** (niveau écrasé, escaliers, micro) :
     * la phrase n'a pas été émise, donc la répétition redevient due tout de
     * suite et la frame suivante la rejoue (`onFrame` rend un signal dès que
     * `atMs - lastSpokenAtMs >= repeatMs`). Sans ce ré-armement, l'annonceur
     * croyait avoir parlé : 2 s de silence, ou davantage sous bavardage
     * continu (A9 §3 D8). Appelé seulement sur refus : un verdict émis n'est
     * jamais doublé (l'haptique a déjà vibré sur le front).
     */
    fun rearm(atMs: Long) {
        val last = lastSpokenAtMs ?: return
        lastSpokenAtMs = minOf(last, atMs - repeatMs)
    }

    fun reset() {
        announced = null
        lastSpokenAtMs = null
        lastCue = null
    }

    companion object {
        /** Répétition d'un verdict maintenu (2 s, changement immédiat). */
        const val REPEAT_MS = 2_000L
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
