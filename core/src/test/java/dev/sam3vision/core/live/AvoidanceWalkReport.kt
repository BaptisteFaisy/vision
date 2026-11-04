package dev.sam3vision.core.live

import java.util.Locale
import kotlin.math.abs

/** Nombre à [digits] décimales, point décimal quelle que soit la locale du serveur. */
internal fun walkFmt(value: Double, digits: Int): String = String.format(Locale.ROOT, "%.${digits}f", value)

/** Une scène jouée sur toutes ses seeds : les grandeurs du tableau. */
class SceneStats(val scene: WalkScene, val runs: List<WalkOutcome>) {
    val meanGapM: Double get() = runs.map { it.minGapM }.average()
    val worstGapM: Double get() = runs.minOf { it.minGapM }
    val contacts: Int get() = runs.count { it.contact }
    val tight: Int get() = runs.count { it.minGapM < WalkModel.TIGHT_GAP_M }
    val sideWords: Double get() = runs.map { it.sideWords }.average()
    val sideChanges: Double get() = runs.map { it.sideChanges }.average()
    val driftM: Double get() = runs.map { abs(it.finalX) }.average()
    val seenRate: Double get() = rate { it.seenFrames }
    val measuredRate: Double get() = rate { it.measuredFrames }
    val voiceA: Double get() = runs.map { run -> run.said.count { it.voice == 'A' } }.average()
    val voiceB: Double get() = runs.map { run -> run.said.count { it.voice == 'B' } }.average()

    private fun rate(count: (WalkOutcome) -> Int): Double =
        runs.sumOf(count).toDouble() / runs.sumOf { it.frames }.coerceAtLeast(1)
}

/** Joue les scènes et met en forme le tableau : une ligne par scène, une ligne totale. */
object WalkReport {
    /** Cadences de décision jouées, dans l'ordre du rapport. */
    val RATES_HZ: List<Double> = listOf(5.0, 1.5)

    /** Graine d'une marche : fixe, distincte par scène et par seed. */
    fun seedOf(sceneIndex: Int, run: Int): Long = 1_000L * (sceneIndex + 1) + run

    /** Toutes les scènes à [decisionHz], [WalkModel.SEEDS] seeds chacune. */
    fun playAll(decisionHz: Double): List<SceneStats> =
        WALK_SCENES.mapIndexed { index, scene ->
            SceneStats(scene, (0 until WalkModel.SEEDS).map { WalkRun(scene, seedOf(index, it), decisionHz).play() })
        }

    /** Le rapport complet, 5 Hz puis 1,5 Hz. */
    fun render(): String = buildString {
        appendLine("MARCHE EN BOUCLE FERMÉE - ${WalkModel.SEEDS} seeds, 1,2 m/s, image vieille de 0,7 s")
        for (rate in RATES_HZ) {
            val stats = playAll(rate)
            appendLine()
            appendLine("== décision à ${rateName(rate)} ==")
            appendLine(HEADER)
            stats.forEach { appendLine(row(it)) }
            appendLine(total(stats))
            appendLine()
            appendLine("-- contrôle du modèle à ${rateName(rate)} --")
            appendLine(CHECK_HEADER)
            stats.forEach { appendLine(checkRow(it)) }
        }
    }

    /**
     * Trace, décision par décision, du premier contact de chaque scène à [decisionHz] :
     * de quoi distinguer un défaut du modèle d'un mot vraiment dit par le code.
     */
    fun contactTraces(decisionHz: Double): String = buildString {
        WALK_SCENES.forEachIndexed { index, scene ->
            val seed = (0 until WalkModel.SEEDS).map { seedOf(index, it) }
                .firstOrNull { seed -> worthTracing(scene, WalkRun(scene, seed, decisionHz).play()) }
                ?: return@forEachIndexed
            appendLine("### ${scene.id} ${scene.title}, seed $seed")
            WalkRun(scene, seed, decisionHz) { appendLine(it) }.play()
            appendLine()
        }
    }

    /** Une marche à relire : un contact, ou une scène calme que le marcheur a quittée. */
    private fun worthTracing(scene: WalkScene, outcome: WalkOutcome): Boolean =
        outcome.contact || (scene.calm && abs(outcome.finalX) > CALM_DRIFT_M)

    private const val HEADER =
        "scène                     | écart moy | pire   | contacts | <0,25 | L/R par seed | chgts par seed | dérive"
    private const val CHECK_HEADER =
        "scène                     | frames vues | frames mesurées | voix A par seed | voix B par seed"

    private fun row(stats: SceneStats): String = line(
        name(stats.scene),
        stats.meanGapM, stats.worstGapM, stats.contacts, stats.tight,
        stats.sideWords, stats.sideChanges,
        if (stats.scene.calm) walkFmt(stats.driftM, 2) else "-",
    )

    private fun total(all: List<SceneStats>): String = line(
        "TOTAL",
        all.map { it.meanGapM }.average(), all.minOf { it.worstGapM },
        all.sumOf { it.contacts }, all.sumOf { it.tight },
        all.map { it.sideWords }.average(), all.map { it.sideChanges }.average(),
        walkFmt(all.filter { it.scene.calm }.map { it.driftM }.average(), 2),
        all.sumOf { it.runs.size },
    )

    private fun line(
        name: String,
        meanGap: Double,
        worstGap: Double,
        contacts: Int,
        tight: Int,
        sideWords: Double,
        sideChanges: Double,
        drift: String,
        runs: Int = WalkModel.SEEDS,
    ): String = name.padEnd(NAME_WIDTH) + " | " + walkFmt(meanGap, 2).padStart(9) + " | " +
        walkFmt(worstGap, 2).padStart(6) + " | " + "$contacts/$runs".padStart(8) + " | " +
        "$tight/$runs".padStart(5) + " | " + walkFmt(sideWords, 1).padStart(12) + " | " +
        walkFmt(sideChanges, 1).padStart(14) + " | " + drift.padStart(6)

    private fun checkRow(stats: SceneStats): String =
        name(stats.scene).padEnd(NAME_WIDTH) + " | " + percent(stats.seenRate).padStart(11) + " | " +
            percent(stats.measuredRate).padStart(15) + " | " + walkFmt(stats.voiceA, 1).padStart(15) + " | " +
            walkFmt(stats.voiceB, 1).padStart(15)

    private fun name(scene: WalkScene): String = "${scene.id.padEnd(3)} ${scene.title}"

    private fun percent(rate: Double): String = walkFmt(rate * 100.0, 0) + " %"

    private fun rateName(rate: Double): String =
        walkFmt(rate, 1).removeSuffix(".0").replace('.', ',') + " Hz"

    private const val NAME_WIDTH = 25

    /** Dérive (m) au-delà de laquelle une scène calme mérite sa trace. */
    private const val CALM_DRIFT_M = 0.5
}
