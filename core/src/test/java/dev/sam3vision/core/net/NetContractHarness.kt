package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Harnais partagé de la batterie de contrat (`*ContractTest`).
 *
 * Un seul constructeur de serveur factice, un seul client « impatient » (preuve du délai
 * dépassé), une seule assertion d'erreur typée et une seule fabrique de réponse de chat :
 * les règles communes aux dix clients de `net/` (matrice 400/401/402/429/500, corps vide,
 * corps illisible) sont vérifiées client par client avec ces briques, jamais re-décrites.
 *
 * Toutes les fonctions tiennent en moins de 50 lignes ; l'objet reste sous 500 lignes.
 */
internal object NetContract {

    /** Clé factice : jamais de vrai secret, même dans un test d'un dépôt public. */
    const val KEY = "cle-de-contrat"

    /** Modèle factice, reconnaissable dans les corps envoyés. */
    const val MODEL = "modele-de-contrat"

    /** Voix ElevenLabs factice. */
    const val VOICE = "voix-de-contrat"

    /** Une seconde d'audio factice : au-delà de l'en-tête WAV de 44 octets. */
    val AUDIO: ByteArray = ByteArray(1_024) { (it % 251).toByte() }

    /** Réponse fixe : [code], [body] et [contentType]. */
    fun server(
        code: Int = 200,
        body: String = "{}",
        contentType: String = "application/json",
    ): TestHttpServer = TestHttpServer { TestHttpServer.Response(code, body, contentType = contentType) }

    /** Réponse 200 retardée de [delayMs] : le client doit abandonner avant. */
    fun slowServer(delayMs: Long = 2_000): TestHttpServer =
        TestHttpServer { TestHttpServer.Response(200, "{}", delayMs = delayMs) }

    /** Réponse audio binaire (TTS) : [bytes] en corps `audio/mpeg`. */
    fun audioServer(bytes: ByteArray = AUDIO): TestHttpServer =
        TestHttpServer { TestHttpServer.Response(200, "", bodyBytes = bytes, contentType = "audio/mpeg") }

    /**
     * Client au budget court (300 ms) : [defaultHttpClient] garde ses réglages de
     * production, seul le budget de l'appel est ramené à l'échelle d'un test.
     */
    fun impatientClient(timeoutMillis: Long = 300): OkHttpClient =
        defaultHttpClient().newBuilder()
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()

    /** Vérifie que [result] échoue avec une [ApiError] du type [type]. */
    fun assertError(result: Result<*>, type: Class<out ApiError>, label: String) {
        val error = result.exceptionOrNull()
        assertTrue("$label : attendu ${type.simpleName}, obtenu $error", type.isInstance(error))
    }

    /** L'erreur d'un [Result] en échec — à n'appeler qu'après [assertError]. */
    fun errorOf(result: Result<*>): ApiError =
        result.exceptionOrNull() as? ApiError
            ?: throw AssertionError("pas d'ApiError dans l'échec : ${result.exceptionOrNull()}")

    /** Vérifie un échec [ApiError.Http] portant exactement [code]. */
    fun assertHttpError(result: Result<*>, code: Int, label: String) {
        val error = result.exceptionOrNull()
        assertTrue("$label : attendu ApiError.Http, obtenu $error", error is ApiError.Http)
        assertEquals("$label : code HTTP", code, (error as ApiError.Http).code)
    }

    /** Monte un serveur qui répond [code] et vérifie le type d'erreur attendu. */
    suspend fun assertCode(
        label: String,
        code: Int,
        expected: Class<out ApiError>,
        call: suspend (String) -> Result<*>,
    ) {
        server(code = code, body = """{"detail":"refus $code"}""").use { server ->
            val result = call(server.baseUrl)
            assertError(result, expected, "$label (HTTP $code)")
            // Un code brut doit être conservé tel quel : 400 ne doit pas devenir 500.
            if (expected == ApiError.Http::class.java) assertHttpError(result, code, "$label (HTTP $code)")
        }
    }

    /** Monte un serveur qui répond 200 avec [body] et vérifie un [ApiError.BadResponse]. */
    suspend fun assertBadBody(label: String, body: String, call: suspend (String) -> Result<*>) {
        server(body = body).use { server ->
            assertError(call(server.baseUrl), ApiError.BadResponse::class.java, "$label (corps « ${body.take(24)} »)")
        }
    }

    /** Réponse `chat/completions` compatible OpenAI, avec le contenu demandé. */
    fun chatReply(content: String): String = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content))))
        .toString()

    /** Réponse de frame du direct (`/live` et `/live/ws` ont exactement la même forme). */
    fun frameReply(id: Int? = null, width: Int = 512, depth: Float? = null): String = buildString {
        append('{')
        if (id != null) append("\"id\":").append(id).append(',')
        append("\"width\":").append(width)
        append(",\"height\":1138,\"elapsed_ms\":118,\"objects\":[")
        // Deux littéraux complets (jamais de guillemet accolé à la fin d'un raw string :
        // `""""` vaut `"` + fin de chaîne, pas fin de chaîne — JSON invalide).
        if (depth != null) {
            append("""{"label":"person","score":0.91,"box":[1,2,30,40],"mask_b64":"QUJD","depth":$depth}""")
        } else {
            append("""{"label":"person","score":0.91,"box":[1,2,30,40],"mask_b64":"QUJD"}""")
        }
        append("]}")
    }

    /** Message d'erreur du contrat `/live/ws` : la connexion reste ouverte, l'`id` répond. */
    fun errorReply(id: Int, status: Int = 400, detail: String = "prompts inconnus"): String =
        """{"id":$id,"error":{"status":$status,"detail":"$detail"}}"""
}

/**
 * Transport WebSocket sans réseau : capture les messages envoyés, rejoue les réponses du
 * serveur **dans** `send` (comme un socket chaud) et peut se taire, se fermer ou refuser
 * l'ouverture. Seule implémentation de faux transport du dépôt.
 */
internal class ContractWsTransport(
    private var reply: (JSONObject) -> List<String> = { emptyList() },
) : WsTransport {

    private var replyBytes: ((ByteArray) -> List<String>)? = null

    val sent = mutableListOf<String>()
    val sentBytes = mutableListOf<ByteArray>()
    val received = mutableListOf<String>()
    var openedUrl: String? = null
    var openedHeaders: Map<String, String> = emptyMap()
    var openResult = true
    var sendResult = true
    var closed = false
        private set
    var opened = false
        private set

    private var onText: ((String) -> Unit)? = null
    private var onClosed: ((Throwable?) -> Unit)? = null

    override fun open(
        url: String,
        onText: (String) -> Unit,
        onClosed: (Throwable?) -> Unit,
        onOpen: () -> Unit,
        headers: Map<String, String>,
    ): Boolean {
        openedUrl = url
        openedHeaders = headers
        this.onText = onText
        this.onClosed = onClosed
        if (openResult) {
            opened = true
            onOpen()
        }
        return openResult
    }

    override fun send(text: String): Boolean {
        sent += text
        if (!sendResult) return false
        reply(JSONObject(text)).forEach { message -> deliver(message) }
        return true
    }

    /** Trame binaire : enregistrée, rejouée si [replyBytes] est posé (sinon silence). */
    override fun sendBytes(bytes: ByteArray): Boolean {
        sentBytes += bytes
        if (!sendResult) return false
        replyBytes?.invoke(bytes)?.forEach { message -> deliver(message) }
        return true
    }

    /** Message serveur injecté hors d'un envoi (trame tardive, réponse asynchrone). */
    fun deliver(message: String) {
        received += message
        onText?.invoke(message)
    }

    /** Le serveur change de comportement en cours de test (silence, puis réponses). */
    fun answerWith(handler: (JSONObject) -> List<String>) {
        reply = handler
    }

    /** Même bascule pour les trames binaires (M1) : silence tant qu'elle n'est pas posée. */
    fun answerBytesWith(handler: (ByteArray) -> List<String>) {
        replyBytes = handler
    }

    /** Fermeture côté serveur (ou coupure réseau) pendant qu'une frame est en vol. */
    fun disconnect(error: Throwable? = null) = onClosed?.invoke(error)

    override fun close() {
        closed = true
    }
}