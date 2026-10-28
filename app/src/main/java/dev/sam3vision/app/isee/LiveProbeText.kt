package dev.sam3vision.app.isee

import dev.sam3vision.core.live.TimingsText
import java.util.Locale
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.DistanceText
import dev.sam3vision.core.net.ApiError

/** Longueur maximale d'un endpoint affiché : au-delà, le milieu est coupé. */
const val ENDPOINT_MAX_CHARS = 46

/** Nombre de distances citées dans le détail : au-delà, la ligne devient illisible. */
const val DISTANCE_LINES_MAX = 3

/**
 * Ligne compacte du bandeau : « SAM · 1,2 ips · 640 ms · 8 objets · plus proche ≈ 3,2 m ».
 * Fonction pure, testée en JVM — l'UI ne formate jamais de nombre elle-même.
 */
fun liveProbeHeadline(state: LiveProbeState): String {
    if (state.error != null && state.frames == 0) return "SAM · ${state.error}"
    val rate = if (state.fps <= 0f) "…" else "${DistanceText.decimal(state.fps, 1, Locale.FRANCE)} ips"
    val latency = if (state.latencyMs <= 0L) "…" else "${state.latencyMs} ms"
    val objects = if (state.frames == 0) "…" else "${state.objects} objets"
    val near = state.nearest?.let { " · plus proche ${DistanceText.approximate(it.meters)}" }.orEmpty()
    return "SAM · $rate · $latency · $objects$near"
}

/**
 * Chronologie **sur une seule ligne** (« latence 348 · capt 22 · up 61 · srv 92 · rtt 153 ·
 * masque 37 · pixel 8 »), chaîne vide avant la première frame mesurée. Le formatage est
 * celui de [TimingsText] : une seule implémentation, testée en JVM.
 */
fun liveProbeTimingsLine(state: LiveProbeState): String = TimingsText.compact(state.timings)

/**
 * Détail du panneau de debug : endpoint, résolution, latences (moyenne, p95, serveur),
 * chronologie poste par poste, étages serveur reçus, nombre de frames, concepts reconnus,
 * distances mesurées, phrase annoncée et dernière erreur. Liste vide = rien à montrer.
 */
fun liveProbeDetails(state: LiveProbeState): List<String> = buildList {
    if (state.endpoint.isNotBlank()) add("endpoint : ${shortEndpoint(state.endpoint)}")
    if (state.width > 0 && state.height > 0) add("frame : ${state.width}×${state.height}")
    if (state.frames > 0) {
        add("latence : ${state.latencyMs} ms · p95 ${state.p95Ms} ms · serveur ${state.serverMs} ms")
        add("${state.frames} frames · ${state.masks.size} masques")
        add("transport : ${state.transport}")
    }
    if (state.timings.known) {
        addAll(TimingsText.detail(state.timings))
        add(TimingsText.servers(state.timings.server))
    }
    if (state.conceptsSent.isNotEmpty()) {
        add("suivis (${state.conceptsSent.size}) : ${state.conceptsSent.joinToString(", ")}")
    }
    if (state.labels.isNotEmpty()) add("reconnus : ${state.labels.joinToString(", ")}")
    if (state.gpu.isNotBlank()) add("gpu : ${state.gpu}")
    if (state.modelsLine.isNotBlank()) add(state.modelsLine)
    distanceSummary(state.distanceLayer?.readings.orEmpty())?.let { add("distances : $it") }
    state.spoken?.let { add("dit : $it") }
    state.error?.let { add("erreur : $it") }
}

/**
 * Distance de chaque objet mesuré, du plus proche au plus loin : « chaise ≈ 1,1 m ·
 * personne ≈ 3,2 m ». Fonction pure (aucun Android) : testée en JVM.
 */
fun distanceSummary(readings: List<DistanceReading>): String? {
    val usable = readings.filter { it.meters.isFinite() && it.meters > 0f }.sortedBy { it.meters }
    if (usable.isEmpty()) return null
    return usable.take(DISTANCE_LINES_MAX)
        .joinToString(" · ") { "${it.label} ${DistanceText.approximate(it.meters)}" }
}

/** Endpoint raccourci : 46 caractères utiles plutôt qu'une URL de 120 px de large. */
fun shortEndpoint(endpoint: String): String {
    val clean = endpoint.removePrefix("https://").removePrefix("http://").trimEnd('/')
    if (clean.length <= ENDPOINT_MAX_CHARS) return clean
    val head = clean.take(ENDPOINT_MAX_CHARS / 2 - 2)
    val tail = clean.takeLast(ENDPOINT_MAX_CHARS / 2 - 1)
    return "$head…$tail"
}

/**
 * Ce qui se dit **au centre de la fenêtre** tant qu'aucun masque n'est affiché :
 * réveil du GPU en cours, backend arrêté, ou simple attente de la première analyse.
 * `null` dès que la première frame est arrivée (les masques parlent d'eux-mêmes).
 */
fun liveProbeNotice(state: LiveProbeState): String? {
    if (state.frames > 0) return null
    state.error?.let { return it }
    if (state.warming) return "réveil du GPU SAM… (≈ 30 s au premier lancement)"
    return "en attente de la première analyse…"
}

/**
 * Message d'échec lisible. Un délai dépassé = conteneur Modal endormi (~30 s de
 * réveil) : on le dit, et la frame suivante réessaie d'elle-même.
 */
fun probeFailureText(error: Throwable): String =
    if (error is ApiError.Timeout) "réveil du GPU en cours… (nouvel essai à la frame suivante)"
    else error.message ?: "échec de l'appel SAM"
