package dev.sam3vision.app.isee

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import dev.sam3vision.core.crossing.CrossingLevels
import dev.sam3vision.core.crossing.CrossingLightDue
import dev.sam3vision.core.crossing.CrossingVerdict
import dev.sam3vision.core.crossing.CrossingVerdictText
import dev.sam3vision.core.live.GuidanceHeartbeat
import dev.sam3vision.core.live.GuidanceInstruction
import dev.sam3vision.core.live.GuidancePulse
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.stairs.StairLabelSignal
import dev.sam3vision.core.stairs.StairText
import dev.sam3vision.core.stairs.StairWatcher
import dev.sam3vision.core.traverse.TraversePhase
import dev.sam3vision.core.traverse.VehicleStateText
import kotlinx.coroutines.delay

/**
 * Cas d'affichage, par priorité décroissante (l'ordre de déclaration tranche) :
 * voiture (danger, miroir voix niveau 0) > escaliers (état escalier) > verdict
 * (traverser/ne-pas-traverser) > direction parlée (défaut, battement 500 ms).
 *
 * [voice] est le niveau voix miroir (le verdict vaut 100 : l'ordre numérique
 * ne suit pas la priorité d'affichage, seul l'ordre de déclaration compte) ;
 * [cadenceMs] est la cadence de ré-affirmation du cas tant qu'il dure (0 =
 * immédiate, à chaque tick). Toutes les cadences sont celles de la voix,
 * réutilisées, jamais recopiées.
 */
internal enum class DisplayLevel(val voice: Int, val cadenceMs: Long) {
    /** Voiture : immédiate dès détection, persistante tant que présente. */
    VEHICLE(OutdoorLevel.CAR_MOVING, 0L),
    /** Escaliers : ré-affirmés toutes les 2 s (comme l'annonce voix). */
    STAIRS(OutdoorLevel.STAIRS, StairWatcher.DEFAULT_COOLDOWN_MS),
    /** Verdict : ré-affirmé toutes les 4 s (statut obligatoire). */
    VERDICT(CrossingLevels.VERDICT, CrossingLightDue.LIGHT_MAX_SILENCE_MS),
    /** Direction parlée : le défaut, à chaque battement 500 ms. */
    DIRECTION(OutdoorLevel.FALLBACK, GuidanceHeartbeat.TICK_MS),
    ;
}

/**
 * L'état écran unique : [text] la direction affichée, [level] son cas de
 * priorité, [atMs] sa dernière (ré-)affirmation. [displayKey] est la clé
 * anti-recomposition : même cas, même texte = rien à redessiner.
 */
internal data class DisplayShot(val text: String, val level: DisplayLevel, val atMs: Long) {
    /** Clé d'unicité visuelle : le niveau et le texte, sans l'horodatage. */
    val displayKey: Pair<DisplayLevel, String> get() = level to text
}

/**
 * Entrées de l'arbitre à un tick : un texte blanc ou nul = cas inactif. Le
 * verdict exige en plus une preuve fraîche ([verdictProofAtMs]).
 */
internal data class DisplayInputs(
    val vehicleText: String? = null,
    val stairsText: String? = null,
    val verdictText: String? = null,
    val verdictProofAtMs: Long? = null,
    val directionText: String? = null,
)

/**
 * Arbitre d'affichage (ordre user du 02/11 : l'écran montre la direction
 * parlée, rafraîchie toutes les 500 ms, sauf cas plus prioritaire) : à chaque
 * [tick], le cas actif le plus prioritaire gagne ; un cas qui apparaît ou
 * disparaît, ou dont le texte change, s'affiche aussitôt (retour au défaut
 * 500 ms dès que le cas se termine) ; un cas stable ne se ré-affirme qu'à sa
 * [cadence][DisplayLevel.cadenceMs]. Le verdict ne s'affiche jamais sans
 * preuve fraîche (même règle que la voix : on ne prononce que sur du prouvé).
 *
 * Pur hors mémoire (injectée par [reset] en test), testé en JVM : l'horloge
 * est fournie, le rendu reste côté Compose ([ArbitratedInstruction]).
 */
internal class DisplayArbiter {

    /** Dernier état émis (`null` = silence : le prochain cas parle aussitôt). */
    private var last: DisplayShot? = null

    /**
     * État écran à [nowMs] pour [inputs] : le cas prioritaire, ou `null`
     * (rien à afficher — comme une consigne sans pulse). Un cas stable
     * rendu avant sa cadence garde son [DisplayShot.atMs] d'avant.
     */
    fun tick(nowMs: Long, inputs: DisplayInputs): DisplayShot? {
        val pick = pickLevel(inputs, nowMs) ?: run {
            last = null
            return null
        }
        val prev = last
        if (prev != null && prev.displayKey == pick.displayKey && nowMs - prev.atMs < pick.level.cadenceMs) {
            return prev
        }
        return pick.also { last = it }
    }

    /** Nouvelle séance (et tests : l'arbitre est mémorisé) : état oublié. */
    fun reset() {
        last = null
    }

    /** Cas gagnant horodaté à [nowMs], `null` = silence. */
    private fun pickLevel(inputs: DisplayInputs, nowMs: Long): DisplayShot? {
        inputs.vehicleText?.takeIf { it.isNotBlank() }?.let {
            return DisplayShot(it, DisplayLevel.VEHICLE, nowMs)
        }
        inputs.stairsText?.takeIf { it.isNotBlank() }?.let {
            return DisplayShot(it, DisplayLevel.STAIRS, nowMs)
        }
        val proof = inputs.verdictProofAtMs
        inputs.verdictText?.takeIf { it.isNotBlank() }?.let {
            if (proof != null && nowMs - proof <= VERDICT_FRESH_MS) {
                return DisplayShot(it, DisplayLevel.VERDICT, nowMs)
            }
        }
        inputs.directionText?.takeIf { it.isNotBlank() }?.let {
            return DisplayShot(it, DisplayLevel.DIRECTION, nowMs)
        }
        return null
    }

    companion object {
        /** Battement d'affichage : la direction se rafraîchit toutes les 500 ms. */
        const val TICK_MS: Long = GuidanceHeartbeat.TICK_MS

        /**
         * Fraîcheur exigée de la preuve du verdict : au-delà, la phase
         * publiée est périmée (données SAM vieilles) et ne s'affiche plus —
         * la voix, elle, ne prononce que sur frame prouvée.
         */
        const val VERDICT_FRESH_MS: Long = GuidanceHeartbeat.STALE_AFTER_MS
    }
}

/**
 * Texte voiture : l'alerte immédiate (« Stop, … en mouvement. », mêmes
 * littéraux que la voix) quand la traversée signale un danger ([TraversePhase.ALERTE]) ;
 * `null` sinon. Pur, testé en JVM.
 */
internal fun vehicleDisplayText(crossingActive: Boolean, phase: TraversePhase?, language: SpeechLanguage): String? {
    if (!crossingActive || phase != TraversePhase.ALERTE) return null
    val name = VehicleStateText.nameOf("car") ?: return null
    return VehicleStateText.safetyPhrase(name, language)
}

/**
 * Texte escaliers : l'annonce d'état (« Escalier montant, à 11 heures. »,
 * mêmes littéraux que la voix) quand SAM nomme un escalier hors Traversée
 * (la voix escaliers y est coupée, l'affichage la suit) ; `null` sinon. Pur,
 * testé en JVM.
 */
internal fun stairsDisplayText(crossingActive: Boolean, found: StairLabelSignal, language: SpeechLanguage): String? {
    if (crossingActive || !found.detected) return null
    return StairText.phrase(language, found.direction, found.centerX)
}

/**
 * Texte verdict : la phrase du verdict voix (« Cross. » / « Don't cross. »,
 * littéraux des clips, anglais comme la voix) en Traversée — seul
 * [TraversePhase.TRAVERSER] autorise (même règle que [traverseTag]) ;
 * `null` sinon. Pur, testé en JVM.
 */
internal fun verdictDisplayText(crossingActive: Boolean, phase: TraversePhase?): String? {
    if (!crossingActive || phase == null) return null
    val verdict = if (phase == TraversePhase.TRAVERSER) CrossingVerdict.Cross else CrossingVerdict.DontCross
    return CrossingVerdictText.phrase(verdict)
}

/**
 * Texte direction : la consigne du battement (« Gauche · ≈ 2,1 m »), celle
 * que la voix dirait, en version lue ; `null` sans pulse. Pur, testé en JVM.
 */
internal fun directionDisplayText(pulse: GuidancePulse?, language: SpeechLanguage): String? {
    if (pulse == null) return null
    return GuidanceInstruction.text(pulse, language)
}

/**
 * Entrées de l'arbitre depuis un instantané sonde : [verdictProofAtMs] est
 * l'instant de la dernière frame en Traversée (la preuve du verdict).
 * Lecture seule : aucune écriture vers la sonde ni vers la voix. Pur, testé.
 */
internal fun displayInputsOf(rest: LiveProbeState, verdictProofAtMs: Long?, language: SpeechLanguage): DisplayInputs =
    DisplayInputs(
        vehicleText = vehicleDisplayText(rest.crossingActive, rest.traversePhase, language),
        stairsText = stairsDisplayText(rest.crossingActive, stairsFoundOf(rest), language),
        verdictText = verdictDisplayText(rest.crossingActive, rest.traversePhase),
        verdictProofAtMs = verdictProofAtMs?.takeIf { rest.crossingActive },
        directionText = directionDisplayText(rest.pulse, language),
    )

/**
 * Liseré de la pastille par cas (mêmes teintes que le reste de la rue :
 * rouge danger, ambre info, vert autorisation). Le verdict lit sa couleur
 * dans son texte ([CrossingVerdictText.CROSS] ou rien) ; la direction ne
 * passe jamais par ici ([InstructionLayer] garde ses couleurs). Pur, testé.
 */
internal fun displayRimArgb(level: DisplayLevel, text: String): Int = when (level) {
    DisplayLevel.VEHICLE -> CROSSING_ACCENT_RED
    DisplayLevel.STAIRS -> CROSSING_ACCENT_OFFER
    DisplayLevel.VERDICT ->
        if (text == CrossingVerdictText.CROSS) CROSSING_ACCENT_GREEN else CROSSING_ACCENT_RED
    DisplayLevel.DIRECTION -> CROSSING_ACCENT_GREEN
}

/**
 * La consigne arbitrée sur la vidéo : l'état écran unique ([DisplayShot]),
 * réévalué à chaque instantané chiffres et toutes les 500 ms (repli
 * d'expiration du verdict quand la sonde est muette). La preuve du verdict
 * est l'avance des frames en Traversée ; la direction garde son rendu
 * ([InstructionLayer], inchangé), les cas prioritaires partagent sa pastille
 * ([InstructionPlate]). Pas de spam de recomposition : l'état n'est posé que
 * quand la clé visuelle ([DisplayShot.displayKey]) change, comme [ProbeSlices].
 */
@Composable
internal fun ArbitratedInstruction(probe: LiveProbe, language: SpeechLanguage = probe.speechLanguage) {
    val rest = probe.collectRest()
    val arbiter = remember { DisplayArbiter() }
    var shot by remember {
        mutableStateOf(arbiter.tick(System.currentTimeMillis(), displayInputsOf(rest, null, language)))
    }
    var proofAtMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(rest.frames, rest.crossingActive) {
        if (rest.crossingActive) proofAtMs = System.currentTimeMillis()
    }
    LaunchedEffect(rest, proofAtMs, language) {
        while (true) {
            val next = arbiter.tick(System.currentTimeMillis(), displayInputsOf(rest, proofAtMs, language))
            if (next?.displayKey != shot?.displayKey) shot = next
            delay(DisplayArbiter.TICK_MS)
        }
    }
    val current = shot ?: return
    if (current.level == DisplayLevel.DIRECTION) {
        InstructionLayer(rest.pulse)
    } else {
        InstructionPlate(current.text, Color(displayRimArgb(current.level, current.text)), 1f)
    }
}
