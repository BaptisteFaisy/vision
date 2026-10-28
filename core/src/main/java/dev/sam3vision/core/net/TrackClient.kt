package dev.sam3vision.core.net

import dev.sam3vision.core.model.TrackFrame
import dev.sam3vision.core.model.TrackHealth
import dev.sam3vision.core.model.TrackSessionStart
import dev.sam3vision.core.model.TrackedObject
import okhttp3.OkHttpClient
import org.json.JSONObject

/** Une frame du tracker doit répondre vite : garde-fou à 15 s (le direct est en 30 fps). */
const val TRACK_TIMEOUT_SECONDS: Long = 15L

/** User-Agent propre au tracker, identifiable côté backend. */
internal const val TRACK_USER_AGENT = "$USER_AGENT track"

/**
 * Implémentation HTTP réelle ([TrackClient]), contrat `docs/LIVE-SPEC.md` du backend
 * `sam3-track` : `POST /session`, `POST /frame`, `DELETE /session/{id}`, `GET /health`.
 * Réutilise le client HTTP unique de `:core` (délai 15 s), `Json.kt` et `Errors.kt`.
 *
 * Un 404 sur `/frame` signifie « session inconnue » (backend redémarré, session
 * expirée) : c'est à l'appelant de recréer une session — voir `TrackRunner`.
 */
class HttpTrackClient(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultHttpClient(TRACK_TIMEOUT_SECONDS),
) : TrackClient {

    override suspend fun start(imageB64: String, prompts: List<String>): Result<TrackSessionStart> {
        return postBuilt("/session") { trackStartJson(imageB64, prompts) }
            .then { raw -> catchingResult { parseSessionStart(raw) } }
    }

    override suspend fun frame(sessionId: String, imageB64: String): Result<TrackFrame> {
        return postBuilt("/frame") { trackFrameJson(sessionId, imageB64) }
            .then { raw -> catchingResult { parseFrame(raw) } }
    }

    override suspend fun close(sessionId: String): Result<Unit> =
        client.executeBuilt { requestTo(baseUrl, "/session/" + sessionId.trim(), "DELETE", userAgent = TRACK_USER_AGENT) }
            .then { outcome -> outcome.requireSuccess() }
            .map { Unit }

    override suspend fun health(): Result<TrackHealth> =
        client.executeBuilt { requestTo(baseUrl, "/health", userAgent = TRACK_USER_AGENT) }
            .then { outcome -> outcome.requireSuccess() }
            .then { raw -> catchingResult { parseTrackHealth(raw) } }

    /** Un POST JSON sur le tracker, corps assemblé en IO et non rejouable. */
    private suspend fun postBuilt(path: String, buildBody: () -> String): Result<String> =
        client.executeBuilt { requestTo(baseUrl, path, "POST", OneShotJsonBody(buildBody()), TRACK_USER_AGENT) }
            .then { outcome -> outcome.requireSuccess() }
}

/** Corps `/session` manuel : base64 brut, prompts échappés. */
internal fun trackStartJson(imageB64: String, prompts: List<String>): String {
    val out = StringBuilder(imageB64.length + 128)
    out.append("{\"image_b64\":\"").append(imageB64).append("\",\"prompts\":[")
    prompts.forEachIndexed { index, prompt ->
        if (index > 0) out.append(',')
        out.append('"').append(jsonEscape(prompt)).append('"')
    }
    out.append("]}")
    return out.toString()
}

/** Corps `/frame` manuel : ids et base64 bruts (aucun échappement utile). */
internal fun trackFrameJson(sessionId: String, imageB64: String): String =
    "{\"session_id\":\"" + jsonEscape(sessionId) + "\",\"image_b64\":\"" + imageB64 + "\"}"

internal fun parseSessionStart(raw: String): TrackSessionStart {
    val json = parse(raw) ?: throw ApiError.BadResponse("/session : JSON illisible")
    val start = TrackSessionStart(
        sessionId = json.str("session_id"),
        width = json.intOr("width"),
        height = json.intOr("height"),
        objects = parseTrackedObjects(json),
    )
    if (start.sessionId.isBlank()) throw ApiError.BadResponse("/session : session_id manquant")
    return start
}

internal fun parseFrame(raw: String): TrackFrame {
    val json = parse(raw) ?: throw ApiError.BadResponse("/frame : JSON illisible")
    return TrackFrame(
        width = json.intOr("width"),
        height = json.intOr("height"),
        objects = parseTrackedObjects(json),
    )
}

internal fun parseTrackHealth(raw: String): TrackHealth {
    val json = parse(raw) ?: throw ApiError.BadResponse("/health : JSON illisible")
    return TrackHealth(
        ok = json.optBoolean("ok", false),
        model = json.str("model"),
        gpu = json.str("gpu"),
        maxObjects = json.intOr("max_objects"),
    )
}

/** `objects` → objets suivis ; l'`id` vient du backend, les autres champs de `Json.kt`. */
private fun parseTrackedObjects(json: JSONObject): List<TrackedObject> =
    json.objects("objects").map { obj ->
        val fields = obj.maskedFields()
        TrackedObject(
            id = obj.intOr("id", NO_TRACK_ID),
            label = fields.label,
            score = fields.score,
            box = fields.box,
            maskPngB64 = fields.maskB64,
        )
    }

/**
 * Valeur d'`id` quand le backend l'omet : objet détecté mais pas encore identifié
 * dans le temps. À traiter comme « nouveau », jamais comme un suivi.
 */
const val NO_TRACK_ID: Int = -1