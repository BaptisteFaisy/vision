package dev.sam3vision.core.net

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.MASK_ENCODING_BITS
import dev.sam3vision.core.model.MASK_ENCODING_PNG
import dev.sam3vision.core.model.SegObject
import org.json.JSONArray
import org.json.JSONObject

/** Accès tolérants au JSON : jamais d'exception, valeurs par défaut sinon. */
internal fun JSONObject.str(key: String, def: String = ""): String =
    if (!has(key) || isNull(key)) def else optString(key, def)

internal fun JSONObject.intOr(key: String, def: Int = 0): Int =
    if (!has(key) || isNull(key)) def else optInt(key, def)

internal fun JSONObject.floatOr(key: String, def: Float = 0f): Float =
    if (!has(key) || isNull(key)) def else optDouble(key, def.toDouble()).toFloat()

internal fun JSONObject.floats(key: String): List<Float> {
    val arr = optJSONArray(key) ?: return emptyList()
    return (0 until arr.length()).map { i -> arr.optDouble(i, 0.0).toFloat() }
}

internal fun JSONObject.strings(key: String): List<String> {
    val arr = optJSONArray(key) ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
}

internal fun JSONObject.objects(key: String): List<JSONObject> {
    val arr = optJSONArray(key) ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
}

/**
 * Champs communs à tout objet segmenté par SAM, quel que soit le modèle Kotlin cible
 * (`SegObject` pour `/segment` et `/live`, `TrackedObject` pour le tracker).
 * Un seul endroit lit `label` / `score` / `box` / `mask_b64`.
 */
internal data class MaskedFields(
    val label: String,
    val score: Float,
    val box: List<Float>,
    val maskB64: String,
)

/** Lit les champs d'un objet segmenté, avec les mêmes valeurs par défaut partout. */
internal fun JSONObject.maskedFields(): MaskedFields = MaskedFields(
    label = str("label", DEFAULT_LABEL),
    score = floatOr("score"),
    box = floats("box"),
    maskB64 = str("mask_b64"),
)

/**
 * Extrait le premier objet JSON équilibré d'un texte libre (le modèle encadre
 * souvent sa réponse de ``` ou de phrases). `null` si aucun objet complet.
 */
internal fun extractFirstJsonObject(text: String): String? {
    val start = text.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until text.length) {
        val c = text[i]
        when {
            escaped -> escaped = false
            c == '\\' && inString -> escaped = true
            c == '"' -> inString = !inString
            inString -> Unit
            c == '{' -> depth++
            c == '}' -> {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
    }
    return null
}

/** Chemins connus du texte dans les réponses de transcription (Meta et autres). */
private val TRANSCRIPT_PATHS = listOf(
    "text",
    "transcript",
    "transcription",
    "output_text",
    "result.text",
    "result.transcript",
    "output.text",
    "data.text",
)

/**
 * Texte d'une réponse de transcription : **JSON uniquement** (champs connus, `segments`
 * recollés). `null` si le corps n'est pas du JSON ou si rien d'exploitable n'y est trouvé —
 * une page HTML d'erreur servie en 200 ne doit jamais devenir la dictée affichée. Les deux
 * moteurs (Meta et ElevenLabs) répondent toujours du JSON.
 */
internal fun extractTranscript(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
    TRANSCRIPT_PATHS.forEach { path -> json.textAt(path)?.let { return it } }
    val segments = json.segments("").ifEmpty { json.segments("result") }
    return segments.joinToString(" ").takeIf { it.isNotBlank() }
}

/** Valeur texte à un chemin pointé (`result.text`), `null` si absente ou nulle. */
internal fun JSONObject.textAt(path: String): String? {
    val parent = path.substringBeforeLast('.', "")
    val key = path.substringAfterLast('.')
    val node = objectAt(parent) ?: return null
    if (!node.has(key) || node.isNull(key)) return null
    return node.optString(key)
}

/** Sous-objet pointé (`""` = soi-même), `null` si un étage manque. */
internal fun JSONObject.objectAt(path: String): JSONObject? =
    if (path.isBlank()) this else path.split('.').fold(this as JSONObject?) { node, part -> node?.optJSONObject(part) }

/** `segments` = liste de chaînes ou d'objets `{text}`, recollés par une espace. */
internal fun JSONObject.segments(path: String): List<String> {
    val arr = objectAt(path)?.optJSONArray("segments") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        when (val item = arr.opt(i)) {
            is String -> item.takeIf { it.isNotBlank() }
            is JSONObject -> item.optString("text").takeIf { it.isNotBlank() }
            else -> null
        }
    }
}

/** Construit un tableau JSON de chaînes. */
internal fun jsonArrayOf(values: List<String>): JSONArray = JSONArray().apply { values.forEach { put(it) } }

/** Label d'un objet sans nom dans la réponse du backend. */
internal const val DEFAULT_LABEL = "objet"

/**
 * Parse un corps **déjà lu en octets**, sans passer par `ResponseBody.string()` : évite
 * une String intermédiaire (2 octets par caractère en UTF-16) sur les réponses du direct,
 * qui portent les masques. `null` si le JSON est illisible (jamais d'exception).
 */
internal fun parseBytes(bytes: ByteArray): JSONObject? =
    runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()

/**
 * Une frame du direct, lue depuis les octets de la réponse (aucune `String` intermédiaire) :
 * `POST /live` et le message WebSocket de `/live/ws` ont **exactement la même forme**, donc un
 * seul assemblage pour les deux transports — [liveFrameOf]. Lève [ApiError.BadResponse] si le
 * JSON est illisible (l'appelant enveloppe).
 */
internal fun parseLiveFrameBytes(bytes: ByteArray): LiveFrame =
    liveFrameOf(parseBytes(bytes) ?: throw ApiError.BadResponse("/live : JSON illisible"))

/**
 * Réponse du direct **déjà parsée** : soit l'erreur du serveur, soit la frame. La garde
 * `error` et la construction de [LiveFrame] consomment le même objet — jamais re-parsé.
 */
internal fun liveReplyOf(json: JSONObject): Result<LiveFrame> {
    json.optJSONObject("error")?.let { error -> return Result.failure(error.serverError()) }
    return catchingResult { liveFrameOf(json) }
}

/**
 * Corps d'erreur du contrat `/live` : `{"status":400,"detail":"…"}`.
 *
 * Un statut que le transport HTTP sait déjà typer (401 clé, 402 crédits, 429 quota) garde
 * **le même type qu'en `POST /live`** : le même refus ne doit pas se raconter différemment
 * selon le transport. Les autres statuts restent un [ApiError.BadResponse] dont le message
 * porte le statut et le détail du serveur.
 */
internal fun JSONObject.serverError(): ApiError {
    val status = optInt("status", 0)
    val detail = optString("detail").ifBlank { "erreur serveur" }
    if (status > 0) {
        // Un seul barème d'erreurs pour tout le dépôt : [httpError] décide du type.
        val typed = httpError(status, detail)
        if (typed !is ApiError.Http) return typed
        return ApiError.BadResponse("$status : $detail")
    }
    return ApiError.BadResponse(detail)
}

/**
 * `id` de corrélation d'une trame, lu **sans parser le JSON** : parcours de la tête du message
 * jusqu'à la première clé `"id"` du premier niveau suivie d'un entier. Le contrat `/live/ws`
 * pose `id` en tête de réponse (`{"id":7,"width":…}`), la boucle s'arrête donc après une
 * dizaine de caractères au lieu de construire les ~5 Ko de masques.
 *
 * Renvoie `-1` quand aucun `id` exploitable n'apparaît au premier niveau (texte illisible,
 * absent, ou niché dans un sous-objet) : la trame est alors ignorée, comme un id inconnu.
 */
internal fun leadingId(text: String): Int {
    var pos = 0
    var depth = 0
    while (pos < text.length) {
        val open = text.indexOf('"', pos)
        if (open < 0) return -1
        val close = closingQuote(text, open + 1) ?: return -1
        // Tout ce qui sépare deux chaînes est hors texte : accolades et crochets y sont
        // structurels, donc le niveau du JSON se suit sans parser quoi que ce soit.
        for (i in pos until open) when (text[i]) {
            '{', '[' -> depth++
            '}', ']' -> depth--
        }
        val after = nonSpaceAt(text, close + 1)
        // `close - open == 3` (contenu de 2 caractères) d'abord : il garantit que `open + 1`
        // et `open + 2` existent, avant de comparer au nom de la clé.
        val isIdKey = depth == 1 && close - open == 3 &&
            text[open + 1] == 'i' && text[open + 2] == 'd' &&
            after < text.length && text[after] == ':'
        if (isIdKey) {
            val id = intAt(text, after + 1)
            if (id != null) return id
        }
        pos = close + 1
    }
    return -1
}

/** Index du `"` fermant la chaîne ouverte à [from], `null` si elle ne ferme jamais. */
private fun closingQuote(text: String, from: Int): Int? {
    var i = from
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i += 2                       // échappement : le caractère suivant n'est jamais fermant
            '"' -> return i
            else -> i++
        }
    }
    return null
}

/** Index du premier caractère non espace à partir de [from], ou la longueur du texte. */
private fun nonSpaceAt(text: String, from: Int): Int {
    var i = from
    while (i < text.length && text[i].isWhitespace()) i++
    return i
}

/** Entier lu à [from] (espaces et signe tolérés), `null` si aucun chiffre ne suit. */
private fun intAt(text: String, from: Int): Int? {
    val start = nonSpaceAt(text, from)
    val digits = if (start < text.length && (text[start] == '-' || text[start] == '+')) start + 1 else start
    var end = digits
    while (end < text.length && text[end].isDigit()) end++
    if (end == digits) return null
    return text.substring(start, end).toIntOrNull()
}

/**
 * Construction du message de frame, identique pour les deux transports : `null` pour
 * [id] = corps de `POST /live`, un `id` pour la trame WebSocket. Les masques sont
 * **demandés** au format `bits` : un backend qui ignore le champ sert son défaut `png`,
 * et la réponse dit ce qu'il a réellement envoyé.
 */
internal fun liveFrameJson(
    jpegB64: String,
    prompts: List<String>?,
    workEdge: Int,
    maxObjects: Int,
    id: Int? = null,
): String = JSONObject()
    .apply { if (id != null) put("id", id) }
    .put("image_b64", jpegB64)
    .put("prompts", if (prompts == null) JSONObject.NULL else jsonArrayOf(prompts))
    .put("work_edge", workEdge)
    .put("max_objects", maxObjects)
    .put("mask_encoding", MASK_ENCODING_BITS)
    .toString()

/** Assemblage d'une frame depuis un objet JSON déjà parsé : la seule voie de lecture. */
private fun liveFrameOf(json: JSONObject): LiveFrame {
    val objects = json.objects("objects").map { obj ->
        val fields = obj.maskedFields()
        SegObject(
            label = fields.label,
            score = fields.score,
            box = fields.box,
            maskPngB64 = fields.maskB64,
        )
    }
    return LiveFrame(
        width = json.intOr("width"),
        height = json.intOr("height"),
        elapsedMs = json.intOr("elapsed_ms").toLong(),
        objects = objects,
        // Champ absent (backend déployé actuel) ⇒ "png" : l'ancien décodeur reste valable.
        maskEncoding = json.str("mask_encoding", MASK_ENCODING_PNG).lowercase(),
        timingsMs = timingsMsOf(json),
    )
}

/**
 * Postes serveur `"timings_ms"` (millisecondes, flottantes). Absent ou illisible ⇒ carte
 * vide : un backend sans `FAST_TIMING` garde exactement le comportement d'avant.
 */
private fun timingsMsOf(json: JSONObject): Map<String, Double> {
    val node = json.optJSONObject("timings_ms") ?: return emptyMap()
    return node.keys().asSequence()
        .mapNotNull { key ->
            val value = node.optDouble(key, Double.NaN)
            if (value.isFinite()) key to value else null
        }
        .toMap()
}

