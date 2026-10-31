package dev.sam3vision.core.live

/** Devenir d'une frame instrumentée : affichée, jamais dessinée (hors écran) ou en échec. */
enum class FrameOutcome { DISPLAYED, OFF_SCREEN, FAILED }

/**
 * Profil de latence du direct : p50/p95 **par étage** sur une fenêtre glissante, la latence
 * perçue (capteur → premier pixel), l'aller-retour (étage [LatencyStage.HTTP] seul), la part
 * serveur et la part réseau, plus le compte des frames hors écran et en échec.
 *
 * La fenêtre et les percentiles viennent de [RollingStat]/[Percentiles] — les mêmes que
 * [LiveStats] : aucune copie locale. Le profil ne mesure pas la latence **perçue** comme
 * [LiveStats] (qui suit la latence d'un envoi, file + A/R).
 *
 * Coût borné (banc JVM, [LatencyBenchTest]) : `record` ≤ 2 µs/frame, [summary] ≤ 70 µs.
 */
class LatencyProfile(maxSamples: Int = RollingStat.DEFAULT_WINDOW) {

    /** Une fenêtre par étage, dans l'ordre de [LatencyStage]. */
    private val stages = Array(LatencyStage.entries.size) { RollingStat(maxSamples) }

    /** Latence perçue : mêlée à aucun étage, pour ne pas confondre perçu et A/R. */
    private val perceived = RollingStat(maxSamples)

    /** Durée serveur et part réseau annoncées par le backend. */
    private val server = RollingStat(maxSamples)
    private val network = RollingStat(maxSamples)

    private var offScreen = 0L
    private var failed = 0L

    /** Enregistre une frame : chaque étage mesuré, puis son devenir. */
    @Synchronized
    fun record(frame: LatencyFrame, outcome: FrameOutcome = FrameOutcome.DISPLAYED) {
        var i = 0
        while (i < stages.size) {
            val value = frame.stageUs(LatencyStage.entries[i])
            if (value >= 0L) stages[i].record(value)
            i++
        }
        if (frame.perceivedUs >= 0L) perceived.record(frame.perceivedUs)
        if (frame.serverUs >= 0L) server.record(frame.serverUs)
        if (frame.networkUs >= 0L) network.record(frame.networkUs)
        when (outcome) {
            FrameOutcome.DISPLAYED -> Unit
            FrameOutcome.OFF_SCREEN -> offScreen++
            FrameOutcome.FAILED -> failed++
        }
    }

    /** Nombre de frames complètes mesurées (jusqu'au premier pixel). */
    val samples: Int get() = perceived.samples

    /** Frames capturées mais jamais dessinées : elles ne pèsent pas dans la latence perçue. */
    val offScreenFrames: Long get() = synchronized(this) { offScreen }

    /** Frames en échec (transport, serveur) : comptées même sans chronologie. */
    val failedFrames: Long get() = synchronized(this) { failed }

    /** Mesures disponibles pour un étage. */
    fun count(stage: LatencyStage): Int = stages[stage.ordinal].samples

    /** p50 d'un étage (µs) ; `-1` si aucune mesure — jamais un zéro inventé. */
    fun p50Us(stage: LatencyStage): Long = percentile(stage, Percentiles.P50)

    /** p95 d'un étage (µs) ; `-1` si aucune mesure. */
    fun p95Us(stage: LatencyStage): Long = percentile(stage, Percentiles.P95)

    /** p50 de la latence perçue (µs), `-1` si aucune frame complète. */
    val perceivedP50Us: Long get() = percentileOf(perceived, Percentiles.P50)

    /** p95 de la latence perçue (µs), `-1` si aucune frame complète. */
    val perceivedP95Us: Long get() = percentileOf(perceived, Percentiles.P95)

    /** p50 de l'aller-retour (étage `http`), `-1` si aucune mesure. */
    val rttP50Us: Long get() = p50Us(LatencyStage.HTTP)

    /** p95 de l'aller-retour (étage `http`), `-1` si aucune mesure. */
    val rttP95Us: Long get() = p95Us(LatencyStage.HTTP)

    /** p50 de la durée serveur annoncée (`elapsed_ms`), `-1` si le backend ne l'envoie pas. */
    val serverP50Us: Long get() = percentileOf(server, Percentiles.P50)

    /** p50 de la part réseau (A/R − serveur), `-1` si elle n'est pas calculable. */
    val networkP50Us: Long get() = percentileOf(network, Percentiles.P50)

    /** Fin de séance : mesures et compteurs oubliés. */
    @Synchronized
    fun reset() {
        var i = 0
        while (i < stages.size) {
            stages[i].reset()
            i++
        }
        perceived.reset()
        server.reset()
        network.reset()
        offScreen = 0L
        failed = 0L
    }

    /** Résumé figé : une seule lecture, tous les chiffres (p50/p95 par étage et totaux). */
    fun summary(): LatencySummary {
        val p50 = LongArray(stages.size)
        val p95 = LongArray(stages.size)
        var i = 0
        while (i < stages.size) {
            val stage = LatencyStage.entries[i]
            p50[i] = p50Us(stage)
            p95[i] = p95Us(stage)
            i++
        }
        return LatencySummary(
            perceivedP50Us = perceivedP50Us,
            perceivedP95Us = perceivedP95Us,
            rttP50Us = rttP50Us,
            rttP95Us = rttP95Us,
            serverP50Us = serverP50Us,
            networkP50Us = networkP50Us,
            stageP50Us = p50,
            stageP95Us = p95,
            samples = samples,
            offScreenFrames = offScreenFrames,
            failedFrames = failedFrames,
        )
    }

    /** Percentile d'un étage, `-1` si la fenêtre est vide. */
    private fun percentile(stage: LatencyStage, p: Double): Long =
        percentileOf(stages[stage.ordinal], p)

    /** Percentile d'une fenêtre, `-1` si elle est vide. */
    private fun percentileOf(stat: RollingStat, p: Double): Long =
        if (stat.samples == 0) -1L else stat.percentile(p)
}

/**
 * Résumé figé d'un [LatencyProfile] : p50/p95 par étage, perçu, A/R (`http` seul), serveur,
 * réseau, et le compte des frames hors écran et en échec. [line] et [stages] rendent le
 * format journalisable en français, **sans locale** (virgule décimale), préfixé pour que
 * `adb logcat | grep LatencyProfile` retrouve p50/p95, hors-écran et échecs.
 */
class LatencySummary(
    val perceivedP50Us: Long,
    val perceivedP95Us: Long,
    val rttP50Us: Long,
    val rttP95Us: Long,
    val serverP50Us: Long,
    val networkP50Us: Long,
    private val stageP50Us: LongArray,
    private val stageP95Us: LongArray,
    val samples: Int,
    val offScreenFrames: Long,
    val failedFrames: Long,
) {

    /** p50 figé d'un étage (µs), `-1` si non mesuré au moment du résumé. */
    fun p50Us(stage: LatencyStage): Long = stageP50Us[stage.ordinal]

    /** p95 figé d'un étage (µs), `-1` si non mesuré au moment du résumé. */
    fun p95Us(stage: LatencyStage): Long = stageP95Us[stage.ordinal]

    /** Part de la file dans la latence perçue, en % entier ; `-1` si non calculable. */
    val queueSharePercent: Int
        get() {
            val total = perceivedP50Us
            val queue = p50Us(LatencyStage.QUEUE)
            if (total <= 0L || queue < 0L) return -1
            return ((queue * 100L + total / 2L) / total).toInt()
        }

    /**
     * Ligne d'état : « LatencyProfile felt 674.7/795.4 ms · RTT 426.2/492.3 · server 300.0
     * · network 126.2 · queue 27% · off-screen 15 · failures 3 ». Vide tant qu'aucune frame
     * complète n'est mesurée (pas de zéros affichés).
     */
    fun line(): String {
        if (samples == 0) return ""
        return buildString(LINE_CAPACITY) {
            append(TAG).append("felt ").append(pairMs(perceivedP50Us, perceivedP95Us)).append(" ms")
            append(SEP).append("RTT ").append(pairMs(rttP50Us, rttP95Us))
            append(SEP).append("server ").append(msText(serverP50Us))
            append(SEP).append("network ").append(msText(networkP50Us))
            val share = queueSharePercent
            if (share >= 0) append(SEP).append("queue ").append(share).append("%")
            append(SEP).append("off-screen ").append(offScreenFrames)
            append(SEP).append("failures ").append(failedFrames)
        }
    }

    /** Une ligne par étage, p50/p95 : « LatencyProfile capt 24,5/25,9 ms ». */
    fun stages(): List<String> = LatencyStage.entries.map { stage ->
        "$TAG${stage.label} ${pairMs(p50Us(stage), p95Us(stage))} ms"
    }
}

/** Préfixe de journalisation : `adb logcat | grep LatencyProfile` retrouve toutes les lignes. */
private const val TAG = "LatencyProfile "

/** Grandeur non mesurée : trait cadratin plutôt qu'un `-1` dans une phrase. */
private const val MISSING = "—"

/** Séparateur de la ligne d'état (« · », U+00B7). */
private const val SEP = " · "

/** Largeur d'une ligne d'état : sept étages et quatre totaux, jamais dépassée. */
private const val LINE_CAPACITY = 240

/** Microsecondes en millisecondes anglaises (« 674.7 »), [MISSING] si non mesuré. */
private fun msText(us: Long): String {
    if (us < 0L) return MISSING
    val tenths = (us + 50L) / 100L
    return "${tenths / 10L}.${tenths % 10L}"
}

/** « p50/p95 » d'une grandeur, dans la même unité. */
private fun pairMs(p50: Long, p95: Long): String = "${msText(p50)}/${msText(p95)}"