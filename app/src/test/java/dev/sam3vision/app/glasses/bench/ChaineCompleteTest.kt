package dev.sam3vision.app.glasses.bench

import dev.sam3vision.app.glasses.FrameOutcome
import dev.sam3vision.app.glasses.GlassesAnalysisStep
import dev.sam3vision.app.glasses.JpegFrame
import dev.sam3vision.app.glasses.logCause
import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.net.HttpSamClient
import dev.sam3vision.core.net.HttpLlmClient
import dev.sam3vision.core.usecase.AnalyzeUseCase
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.Base64
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bout en bout de la **logique** de la chaîne du spike, sans appareil ni réseau
 * sortant : frame JPEG réelle → [AnalyzeUseCase] de `:core` → SAM (faux serveur
 * local) → LLM (même serveur) → texte destiné à la voix, via
 * [GlassesAnalysisStep] — le maillon que `BenchScreen` appelle.
 *
 * Le SDK Meta (`com.meta.wearable…`) n'est **jamais** stubbé ici : rien de tout
 * cela ne prouve le matériel ni la lecture audio. Ce que ces tests verrouillent :
 * débrancher SAM, débrancher le LLM, casser le passage frame → description →
 * voix, ou laisser remonter une exception, fait passer la classe au rouge.
 */
class ChaineCompleteTest {

    @Test
    fun `une frame traverse SAM puis le LLM et la voix dit la description`() = runBlocking {
        val frame = frameJpeg()
        val backend = backendDeuxObjets()
        val dits = mutableListOf<String>()
        val journal = mutableListOf<String>()

        val outcome = backend.use { step(backend, dits).onFrame(frame) { journal += it } }

        assertEquals(FrameOutcome.Dit("Devant toi : chaise et table."), outcome)
        assertEquals(listOf("Devant toi : chaise et table."), dits)
        assertTrue(journal.any { it.startsWith("frame 64x64") })
        assertTrue(journal.any { it.contains("chaise") })
    }

    @Test
    fun `la frame encodee part telle quelle a SAM et au LLM`() = runBlocking {
        val frame = frameJpeg()
        val backend = backendDeuxObjets()

        backend.use { step(backend, mutableListOf()).onFrame(frame) {} }

        val versSam = JSONObject(backend.bodies("/segment").single())
        assertEquals(frame.base64, versSam.getString("image_b64"))
        val versLlm = backend.bodies("/chat/completions").single()
        assertTrue("l'image doit voyager jusqu'au LLM", versLlm.contains(frame.base64))
        assertTrue("les labels SAM doivent atteindre le LLM", versLlm.contains("chaise"))
    }

    @Test
    fun `SAM en 500 laisse la description du LLM passer`() = runBlocking {
        val backend = FauxBackend(
            segment = { Reponse(500, """{"detail":"sam en panne"}""") },
            chat = { corps -> reponseChat(descriptionSelonLabels(corps)) },
        )
        val dits = mutableListOf<String>()

        val outcome = backend.use { step(backend, dits).onFrame(frameJpeg()) {} }

        assertEquals(1, backend.bodies("/segment").size)
        assertFalse(
            "un SAM en panne ne doit injecter aucun label",
            backend.bodies("/chat/completions").single().contains("chaise"),
        )
        assertEquals(FrameOutcome.Dit("Aucun objet segmenté."), outcome)
        assertEquals(listOf("Aucun objet segmenté."), dits)
    }

    @Test
    fun `un LLM sans contenu ne dit rien et ne remonte pas`() = runBlocking {
        val backend = FauxBackend(chat = { reponseContenu("") })
        val dits = mutableListOf<String>()

        val outcome = backend.use { step(backend, dits).onFrame(frameJpeg()) {} }

        assertEquals(FrameOutcome.Muet("réponse inattendue : contenu vide"), outcome)
        assertTrue("aucun texte ne doit partir à la voix", dits.isEmpty())
    }

    /**
     * Constat du comportement réel, pas une garantie souhaitée : quand le modèle
     * répond `{"description":""}`, `parseAnalysis` de `:core` retombe sur le JSON
     * brut, qui part alors tel quel vers la voix. Ce test est la preuve du trou ;
     * il devra changer de camp le jour où `:core` filtrera les descriptions vides.
     */
    @Test
    fun `constat, une description vide en JSON est lue telle quelle par la voix`() = runBlocking {
        val backend = FauxBackend(chat = { reponseChat("") })
        val dits = mutableListOf<String>()

        val outcome = backend.use { step(backend, dits).onFrame(frameJpeg()) {} }

        assertEquals(FrameOutcome.Dit("""{"description":"","tags":["chaise"]}"""), outcome)
        assertEquals(listOf("""{"description":"","tags":["chaise"]}"""), dits)
    }

    @Test
    fun `un LLM en 500 reste propre et ne dit rien`() = runBlocking {
        val backend = FauxBackend(chat = { Reponse(500, """{"detail":"llm en panne"}""") })
        val dits = mutableListOf<String>()

        val outcome = backend.use { step(backend, dits).onFrame(frameJpeg()) {} }

        assertEquals(FrameOutcome.Muet("""erreur HTTP 500 : {"detail":"llm en panne"}"""), outcome)
        assertTrue(dits.isEmpty())
    }

    @Test
    fun `une description blanche est muette`() = runBlocking {
        val dits = mutableListOf<String>()
        val step = GlassesAnalysisStep(
            analyze = { Result.success(Analysis("   ", emptyList(), emptyList())) },
            say = { dits += it; null },
        )

        assertEquals(FrameOutcome.Muet("description vide"), step.onFrame(frameJpeg()) {})
        assertTrue(dits.isEmpty())
    }

    /**
     * Le journal d'un échec réseau doit nommer la classe de l'exception **et** sa
     * cause : c'est cette ligne qui a mis au jour le DNS du téléphone, un
     * `message` seul ne le disait pas.
     */
    @Test
    fun `un echec journalise la classe et la cause de l exception`() {
        val journal = mutableListOf<String>()
        val erreur = ConnectException("connexion refusée")
            .apply { initCause(UnknownHostException("sam.local")) }

        val raison = logCause(erreur, { journal += it })

        assertEquals("connexion refusée", raison)
        assertTrue("classe absente : $journal", journal.single().contains("ConnectException"))
        assertTrue("cause absente : $journal", journal.single().contains("UnknownHostException"))
    }

    // --- outillage -----------------------------------------------------------

    /** Backend qui segmente deux objets et décrit selon les labels reçus. */
    private fun backendDeuxObjets() = FauxBackend(
        segment = { Reponse(200, SEGMENT_DEUX_OBJETS) },
        chat = { corps -> reponseChat(descriptionSelonLabels(corps)) },
    )

    /** Le vrai pipeline de `:core` branché sur [backend], la voix capturée dans [dits]. */
    private fun step(backend: FauxBackend, dits: MutableList<String>): GlassesAnalysisStep {
        val analyser = AnalyzeUseCase(
            HttpLlmClient(backend.baseUrl, API_KEY_DE_TEST, "modele-de-test"),
            HttpSamClient(backend.baseUrl),
        )
        return GlassesAnalysisStep(analyze = { analyser.invoke(it) }, say = { dits += it; null })
    }

    /** Le faux LLM décrit selon les labels que SAM lui a transmis : preuve du câblage. */
    private fun descriptionSelonLabels(corps: String): String =
        if (corps.contains("chaise")) "Devant toi : chaise et table." else "Aucun objet segmenté."

    /** Réponse du LLM au format OpenAI, autour du texte [contenu] rendu par le modèle. */
    private fun reponseContenu(contenu: String): Reponse {
        val corps = JSONObject().put(
            "choices",
            JSONArray().put(JSONObject().put("message", JSONObject().put("content", contenu))),
        ).toString()
        return Reponse(200, corps)
    }

    /** Texte rendu par le modèle : le JSON `description`/`tags` attendu par `:core`. */
    private fun reponseChat(description: String): Reponse = reponseContenu(
        JSONObject().put("description", description).put("tags", JSONArray().put("chaise")).toString(),
    )

    private companion object {
        const val API_KEY_DE_TEST = "cle-de-test"
        const val SEGMENT_DEUX_OBJETS =
            """{"width":64,"height":64,"objects":[
                 {"label":"chaise","score":0.93,"box":[1,2,3,4],"mask_b64":"AAAA"},
                 {"label":"table","score":0.81,"box":[5,6,7,8],"mask_b64":"BBBB"}
               ]}"""
    }
}

/**
 * Frame JPEG 64×64 réelle, embarquée en base64 : aucun `java.awt`/`javax.imageio`
 * (l'AGP compile les tests unitaires en `-no-jdk`) et surtout aucune ressource
 * externe à charger. L'asset `frame-64.jpg` du spike n'a pas suivi la copie des
 * sources ; l'embarquer évite un test rouge pour un fichier oublié.
 *
 * Aucun serveur de test ne décode cette image : ce que la classe vérifie est la
 * circulation du base64 dans le pipeline, pas le contenu des pixels.
 */
internal fun frameJpeg(): JpegFrame {
    val octets = Base64.getDecoder().decode(FRAME_JPEG_B64)
    require(octets.size > 4 && octets[0] == 0xFF.toByte() && octets[1] == 0xD8.toByte()) {
        "le JPEG de test doit commencer par SOI"
    }
    require(octets.last() == 0xD9.toByte()) { "le JPEG de test doit finir par EOI" }
    return JpegFrame(FRAME_JPEG_B64, LARGEUR_FRAME, HAUTEUR_FRAME, capturedAtMs = 1L)
}

/** 64×64 en JPEG qualité 70, 504 octets (SOI `FFD8` … EOI `FFD9`). */
private const val FRAME_JPEG_B64 =
    "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAoHBwgHBgoICAgLCgoLDhgQDg0NDh0VFhEYIx8lJCIfIiEmKzcvJik0KSEiMEExNDk7" +
        "Pj4+JS5ESUM8SDc9Pjv/2wBDAQoLCw4NDhwQEBw7KCIoOzs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7" +
        "Ozs7Ozs7Ozv/wAARCABAAEADASIAAhEBAxEB/8QAGAABAQEBAQAAAAAAAAAAAAAAAAIBAwT/xAAhEAEBAAECBgMAAAAAAAAAAAAA" +
        "AQIDQRESEzEycVFSkf/EABoBAQACAwEAAAAAAAAAAAAAAAABBQIDBAb/xAAeEQADAAMAAwEBAAAAAAAAAAAAAQIDETESITIzQf/a" +
        "AAwDAQACEQMRAD8AAAAAAAAAAAy5THvWdTD5Yu5XpsyU0+IoT1MPlszxyvCUVy+MOaX8NBNyuzdGOr4aqtT0oRMquXiXjqOiMk1w" +
        "562zk662zkq836MscXwgvS8r6QvS8r6Y4vtE5PhnTJK8pxiHocDXh6KPMn5BWPdKsZunM0oeyMSfmtE62zk9FkveSnLj9Z+KbJhd" +
        "VvZbRlUzrR516XlfTry4/WfhJJ2kiIwOaT2KzKlrRrLjK0dapr2jmaT6ZMZGgl066FKXD//Z"

private const val LARGEUR_FRAME = 64
private const val HAUTEUR_FRAME = 64

private data class Reponse(val code: Int, val body: String)

/**
 * Faux backend du spike : deux routes, la réponse décidée par le test, les corps
 * reçus conservés pour vérifier ce qui a réellement circulé.
 *
 * `com.sun.net.httpserver` et MockWebServer sont hors du classpath de ces tests
 * (AGP compile sans JDK complet, cache Gradle sans mockwebserver) : d'où ce
 * serveur minimal sur [ServerSocket], volontairement réduit au strict nécessaire
 * (une requête par connexion, réponse à `Content-Length`).
 */
private class FauxBackend(
    private val segment: (String) -> Reponse = { Reponse(200, SEGMENT_OK) },
    private val chat: (String) -> Reponse = { Reponse(200, CHAT_DEFAUT) },
) : AutoCloseable {

    private val received: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))

    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true, name = "faux-backend-accept") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    /** Corps des requêtes reçues pour [path], dans l'ordre d'arrivée. */
    fun bodies(path: String): List<String> = synchronized(received) {
        received.filter { it.first == path }.map { it.second }
    }

    private fun serve(socket: Socket) = socket.use { connexion ->
        val entree = connexion.getInputStream()
        val requete = runCatching { lire(entree) }.getOrNull() ?: return@use
        received += requete.first to requete.second
        val reponse = if (requete.first == "/segment") segment(requete.second) else chat(requete.second)
        ecrire(connexion, reponse)
    }

    /** Une requête = une ligne de départ, des en-têtes, puis `Content-Length` octets. */
    private fun lire(entree: InputStream): Pair<String, String> {
        val depart = ligne(entree)?.split(' ') ?: error("requête vide")
        var longueur = 0
        while (true) {
            val entete = ligne(entree) ?: break
            if (entete.isEmpty()) break
            val coupe = entete.indexOf(':')
            if (coupe > 0 && entete.substring(0, coupe).trim().equals("Content-Length", ignoreCase = true)) {
                longueur = entete.substring(coupe + 1).trim().toIntOrNull() ?: 0
            }
        }
        val corps = ByteArray(longueur)
        var lus = 0
        while (lus < longueur) {
            val n = entree.read(corps, lus, longueur - lus)
            if (n <= 0) break
            lus += n
        }
        return depart.getOrElse(1) { "" } to corps.copyOf(lus).toString(Charsets.UTF_8)
    }

    private fun ligne(entree: InputStream): String? {
        val texte = StringBuilder()
        while (true) {
            val octet = entree.read()
            if (octet == -1) return if (texte.isEmpty()) null else texte.toString()
            if (octet == '\n'.code) return texte.toString().trimEnd('\r')
            texte.append(octet.toChar())
        }
    }

    private fun ecrire(socket: Socket, reponse: Reponse) {
        val corps = reponse.body.toByteArray(Charsets.UTF_8)
        val entete = "HTTP/1.1 ${reponse.code} X\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${corps.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(entete.toByteArray(Charsets.UTF_8))
            write(corps)
            flush()
        }
    }

    override fun close() {
        runCatching { server.close() }
    }

    private companion object {
        const val SEGMENT_OK = """{"width":64,"height":64,"objects":[]}"""
        const val CHAT_DEFAUT = """{"choices":[]}"""
    }
}