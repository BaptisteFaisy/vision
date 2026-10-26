package dev.sam3vision.core.keyframe

import dev.sam3vision.core.live.LiveStats
import java.io.File
import java.util.Locale
import kotlin.math.roundToLong

/** Politique simulée : une configuration de scheduler, plus le signal qu'on lui donne. */
class SimPolicy(val label: String, val config: KeyframeConfig, val feedSignal: Boolean = true)

/**
 * Cadence fixe = **le même scheduler**, aveugle : seuils inatteignables et signal neutre.
 * Aucune politique n'est réécrite à côté du scheduler — c'est lui qu'on compare à lui-même.
 */
fun fixedCadence(label: String, intervalMs: Long): SimPolicy = SimPolicy(
    label = label,
    config = KeyframeConfig(
        minIntervalMs = intervalMs,
        maxAgeMs = intervalMs,
        errorThreshold = 1f,
        sceneThreshold = 1f,
        emaAlpha = 1f,
        budgetRatePerSec = 1_000f,
        budgetBurst = 1_000,
    ),
    feedSignal = false,
)

/** Un scénario : la vérité terrain + le sort des requêtes. Aucun réseau, aucun GPU, aucun appareil. */
class SimScenario(
    val label: String,
    val scene: KeyframeScene,
    val rttMs: Long = RTT_LAN_MS,
    private val outageFromMs: Long = NEVER,
    private val outageMs: Long = 0L,
    val silent: Boolean = false,
    val recalageFromMs: Long = NEVER,
) {

    /** Une requête partie à [atMs] tombe-t-elle pendant la panne ? */
    fun failsAt(atMs: Long): Boolean =
        outageMs > 0L && atMs >= outageFromMs && atMs < outageFromMs + outageMs

    companion object {
        const val NEVER = Long.MIN_VALUE

        /** A/R filaire mesuré (WAVE2 §0) : 93 ms de plancher réseau, 150 ms retenus ici. */
        const val RTT_LAN_MS = 150L

        /** A/R mesuré depuis le téléphone (1 220–1 260 ms), arrondi. */
        const val RTT_PHONE_MS = 1_200L

        /** SAM muet : aucune réponse n'arrive, jamais. */
        const val RTT_SILENT_MS = 300_000L
    }
}

/** Chiffres d'un (scénario × politique) — « calculé », jamais « mesuré ». */
class SimTally(
    val scenario: String,
    val policy: String,
    val requests: Int,
    val requestsPerSec: Float,
    val successes: Int,
    val timeouts: Int,
    val meanError: Float,
    val p95Error: Float,
    val maxError: Float,
    val maxAgeMs: Long,
    val recalageMs: Long,
    val sceneTriggers: Int,
    val lostTriggers: Int,
    val budgetViolations: Int,
    val inFlightViolations: Int,
    val capViolations: Int,
) {

    val violations: Int get() = budgetViolations + inFlightViolations + capViolations

    /** Une ligne du tableau `core/build/keyframe-sim-u12.txt`. */
    fun row(): String = String.format(
        Locale.ROOT,
        "%-16s %-16s %4d %6.2f %4d %4d %7.3f %6.3f %6.3f %8d %9d %4d %4d %4d %4d %4d",
        scenario, policy, requests, requestsPerSec, successes, timeouts,
        meanError, p95Error, maxError, maxAgeMs, recalageMs,
        budgetViolations, inFlightViolations, capViolations, sceneTriggers, lostTriggers,
    )
}

/** Requête partie, réponse attendue à `respondAtMs` (`rttMs` vaut l'infini si le backend est muet). */
private class Pending(val requestAtMs: Long, val respondAtMs: Long, val fails: Boolean)

/**
 * Déroule un scénario sous une politique, à horloge virtuelle : aucune attente réelle, aucun appel
 * réseau. Les trois compteurs de violations sont les **fautes de décision**, pas les limites
 * physiques : budget d'une seconde franchi, demande acceptée pendant une autre en vol, et
 * `EXPIRED` dû mais non demandé (une attente `STABLE` alors que le plafond d'âge est passé).
 */
fun runScenario(scenario: SimScenario, policy: SimPolicy): SimTally = SimRun(scenario, policy).play()

/** Une passe : l'état de la boucle virtuelle, tenu hors de [runScenario] pour rester lisible. */
private class SimRun(private val scenario: SimScenario, private val policy: SimPolicy) {

    private var now = 0L
    private var keyframeAt = -1L
    private var pending: Pending? = null
    private var freeAt = 0L
    private var inFlightViolations = 0
    private var capViolations = 0
    private var maxError = 0f
    private var maxAge = 0L
    private var recalageMs = -1L
    private var truth = FrameSignal.Still
    private val requestsAt = ArrayList<Long>()
    private val errors = LiveStats((scenario.scene.durationMs / KeyframeScene.TICK_MS).toInt() + 2)
    private val scheduler = KeyframeScheduler(policy.config) { now }

    fun play(): SimTally {
        while (now <= scenario.scene.durationMs) {
            deliverDue()
            observe()
            decide()
            now += KeyframeScene.TICK_MS
        }
        return tally()
    }

    /** Répond à la demande échue : le keyframe produit est daté de l'instant de **décision**. */
    private fun deliverDue() {
        val flight = pending ?: return
        if (scenario.silent || now < flight.respondAtMs) return
        scheduler.onKeyframeResult(
            success = !flight.fails,
            latencyMs = flight.respondAtMs - flight.requestAtMs,
            residualError = scenario.scene.signalAt(now, flight.requestAtMs).reprojectionError,
            requestAtMs = flight.requestAtMs,
        )
        if (!flight.fails) keyframeAt = flight.requestAtMs
        pending = null
    }

    /** Ce que l'utilisateur voit : l'erreur et l'âge des masques en service, à cette frame. */
    private fun observe() {
        truth = scenario.scene.signalAt(now, keyframeAt)
        maxError = maxOf(maxError, truth.reprojectionError)
        maxAge = maxOf(maxAge, ageMs())
        errors.record((truth.reprojectionError * 1000f).roundToLong())
    }

    private fun decide() {
        val feed = if (policy.feedSignal) truth else FrameSignal.Still
        when (val decision = scheduler.onFrame(feed)) {
            is KeyframeDecision.Request -> accept()
            is KeyframeDecision.Hold -> {
                if (decision.reason == HoldReason.STABLE && ageMs() >= policy.config.effectiveMaxAgeMs) {
                    capViolations += 1
                }
            }
        }
    }

    /** Note la demande : fenêtre en vol, échéance de réponse, et recalage après une coupure. */
    private fun accept() {
        requestsAt += now
        if (requestsAt.size > 1 && now < freeAt) inFlightViolations += 1
        freeAt = minOf(now + scenario.rttMs, now + policy.config.inFlightTimeoutMs)
        pending = Pending(now, now + scenario.rttMs, scenario.failsAt(now))
        if (recalageMs < 0L && scenario.recalageFromMs != SimScenario.NEVER &&
            now >= scenario.recalageFromMs
        ) {
            recalageMs = now - scenario.recalageFromMs
        }
    }

    private fun ageMs(): Long = if (keyframeAt < 0L) now else now - keyframeAt

    private fun tally(): SimTally = SimTally(
        scenario = scenario.label,
        policy = policy.label,
        requests = requestsAt.size,
        requestsPerSec = requestsAt.size * 1000f / scenario.scene.durationMs,
        successes = scheduler.stats.successes,
        timeouts = scheduler.stats.timeoutsDeclared,
        meanError = errors.averageMs / 1000f,
        p95Error = errors.p95Ms / 1000f,
        maxError = maxError,
        maxAgeMs = maxAge,
        recalageMs = recalageMs,
        sceneTriggers = scheduler.stats.triggerCount(Trigger.SCENE_CHANGE),
        lostTriggers = scheduler.stats.triggerCount(Trigger.LOST),
        budgetViolations = countBudgetViolations(requestsAt, policy.config.maxRequestsPerSecond),
        inFlightViolations = inFlightViolations,
        capViolations = capViolations,
    )
}

/** Fenêtres d'une seconde où la borne `rafale + débit + 1` est franchie. */
private fun countBudgetViolations(requestsAt: List<Long>, maxPerSecond: Int): Int {
    var violations = 0
    var start = 0
    for (end in requestsAt.indices) {
        while (requestsAt[end] - requestsAt[start] >= WINDOW_MS) start += 1
        if (end - start + 1 > maxPerSecond) violations += 1
    }
    return violations
}

/** Les 8 scénarios de la passe (10 s, 15 s pour le backend muet). */
fun scenarios(): List<SimScenario> = listOf(
    SimScenario("trepied", KeyframeScene(durationMs = 10_000L)),
    SimScenario(
        "mouvement_lent",
        KeyframeScene(durationMs = 10_000L, driftPerSec = 0.35f, noiseAmplitude = 0.05f),
    ),
    SimScenario("mouvement_rapide", KeyframeScene(durationMs = 10_000L, driftPerSec = 1.2f)),
    SimScenario(
        "coupure_scene",
        KeyframeScene(durationMs = 10_000L, driftPerSec = 0.05f, cutTimesMs = listOf(CUT_MS)),
        recalageFromMs = CUT_MS,
    ),
    SimScenario(
        "masque_perdu",
        KeyframeScene(durationMs = 10_000L, driftPerSec = 0.4f, masksLostFromMs = 4_000L),
    ),
    SimScenario(
        "panne_2s",
        KeyframeScene(durationMs = 10_000L, driftPerSec = 0.6f),
        outageFromMs = 500L,
        outageMs = 2_000L,
    ),
    SimScenario(
        "sam_muet",
        KeyframeScene(durationMs = 15_000L, driftPerSec = 0.5f),
        rttMs = SimScenario.RTT_SILENT_MS,
        silent = true,
    ),
    SimScenario(
        "telephone_a100",
        KeyframeScene(durationMs = 10_000L, driftPerSec = 1f),
        rttMs = SimScenario.RTT_PHONE_MS,
    ),
)

/** Les 4 politiques : deux cadences fixes aveugles, le scheduler, le scheduler + veille statique. */
fun policies(): List<SimPolicy> = listOf(
    fixedCadence("fixe-500ms", 500L),
    fixedCadence("fixe-200ms", 200L),
    SimPolicy("scheduler", KeyframeConfig()),
    SimPolicy("scheduler+veille", KeyframeConfig(allowStaticSlack = true)),
)

/** Tableau complet en texte (en-tête + une ligne par scénario × politique). */
fun simTable(tallies: List<SimTally>): String = buildString {
    appendLine("keyframe-sim-u12 — 8 scénarios × 4 politiques · horloge virtuelle 33 ms")
    appendLine("A/R 150 ms (1 200 ms pour `telephone_a100`, jamais de réponse pour `sam_muet`) ·")
    appendLine("aucun réseau, aucun GPU, aucun téléphone : ces chiffres sont CALCULÉS, pas mesurés.")
    appendLine("violations : budget d'1 s franchi / demande acceptée pendant une autre en vol /")
    appendLine("            EXPIRED dû mais non demandé — 0 partout = décisions saines.")
    appendLine()
    appendLine(
        "scenario         politique        req  req/s succ tout err-moy    p95    max  age-max  recalage" +
            " bud  1vol plaf  scn perdu"
    )
    tallies.forEach { appendLine(it.row()) }
}

/**
 * Écrit le tableau là où la porte de sortie de la lane le lit : `core/build/keyframe-sim-u12.txt`
 * (répertoire de build, donc non versionné — c'est une preuve de test, pas un livrable).
 */
fun writeSimTable(text: String): File {
    val buildDir = listOf(File("core/build"), File("../core/build"), File("build"))
        .firstOrNull { it.isDirectory } ?: File("core/build").apply { mkdirs() }
    return File(buildDir, SIM_FILE_NAME).apply { writeText(text) }
}

/** Nom du fichier de preuve, cité par la porte de sortie de la lane L13. */
const val SIM_FILE_NAME = "keyframe-sim-u12.txt"

private const val WINDOW_MS = 1_000L

/**
 * Instant de la coupure de scène du banc : **volontairement pas** un multiple de 500 ms, sinon la
 * cadence fixe tomberait pile dessus et le recalage comparé serait nul par accident.
 */
private const val CUT_MS = 3_250L