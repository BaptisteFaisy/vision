package dev.sam3vision.core.live

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.SegObject
import java.util.Base64
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Budget CPU du chemin frame du direct : **capture → JPEG → base64 → upload**.
 *
 * Relevés appareil (doc projet) sur un cycle de 253 ms : capture→JPEG 99 ms, upload
 * 154 ms. Le poste le plus cher n'est donc ni le GPU ni l'encodage JPEG (déjà sorti du
 * chemin critique), mais **ce qui est recopié autour** : base64, conversions
 * `ByteArray`→`String`, allocations par frame. Ce banc chiffre ces recopies en JVM, à
 * `System.nanoTime()`, 250 passes après 50 de chauffe.
 *
 * Deux règles :
 * 1. le test **imprime** son tableau (visible dans le rapport JUnit, `<system-out>`) ;
 * 2. il n'échoue que sur des seuils **très larges**, à plus de dix fois le coût mesuré :
 *    il attrape une régression franche (une copie de 30 Ko ajoutée par frame, un
 *    `distinct()` sur un `String` de 28 Ko), jamais le bruit d'une machine chargée.
 *
 * Ce qui n'est pas mesurable ici est dit « non mesuré » : le coût de `Bitmap.compress`
 * (99 ms), la radio, et le décodage base64 côté serveur demandent le téléphone.
 */
class LiveFrameBudgetTest {

    private val bench = FrameBench()

    /** JPEG de référence : 6 Ko, ce que produit une frame 320 px en qualité 55. */
    private val jpeg = ByteArray(JPEG_BYTES) { (it * 31 % 251).toByte() }

    /** Le même contenu en base64, 8 192 caractères : ce qui part réellement sur le réseau. */
    private val jpegB64 = Base64.getEncoder().encodeToString(jpeg)

    @Test
    fun `budget CPU du chemin frame`() {
        measureBase64()
        measureStrings()
        measureStats()
        measureFrameBookkeeping()
        println(bench.report("BUDGET FRAME — capture → JPEG → base64 → upload (JVM, µs/op)"))

        assertBase64Budget()
        assertFrameBudget()
    }

    // ————— Base64 : l'aller-retour qui précède chaque envoi —————

    /** Encodeur réutilisé : `encode(src, dst)` n'alloue pas de tableau de sortie. */
    private val reusable = ByteArray((JPEG_BYTES + 2) / 3 * 4)

    private fun measureBase64() {
        val encoder = Base64.getEncoder()
        val decoder = Base64.getDecoder()
        bench.measure("base64 encode (encodeToString)") {
            encoder.encodeToString(jpeg).length.toLong()
        }
        bench.measure("base64 encode (tampon réutilisé)") {
            val n = encoder.encode(jpeg, reusable)
            String(reusable, 0, n, Charsets.ISO_8859_1).length.toLong()
        }
        bench.measure("base64 decode") { decoder.decode(jpegB64).size.toLong() }
        bench.measure("base64 aller-retour") {
            decoder.decode(encoder.encodeToString(jpeg)).size.toLong()
        }
        bench.measure("base64 longueur par ligne (aucune)") {
            // Une nouvelle ligne dans le base64 casse le décodage serveur : on la cherche.
            var breaks = 0L
            var i = 0
            while (i < jpegB64.length) {
                if (jpegB64[i] == '\n') breaks++
                i++
            }
            breaks
        }
    }

    // ————— Chaînes : ce qui est recopié entre le JPEG et le corps HTTP —————

    private fun measureStrings() {
        bench.measure("ByteArray→String (UTF-8, 8 Ko)") { String(jpeg, Charsets.UTF_8).length.toLong() }
        bench.measure("ByteArray→String (ISO-8859-1, 8 Ko)") {
            String(jpeg, Charsets.ISO_8859_1).length.toLong()
        }
        bench.measure("String→ByteArray (UTF-8, 8 Ko)") { jpegB64.toByteArray(Charsets.UTF_8).size.toLong() }
        bench.measure("copie de tableau (6 Ko)") { jpeg.copyOf().size.toLong() }
        bench.measure("copie de chaîne (substring 8 Ko)") { jpegB64.substring(16).length.toLong() }
        bench.measure("JSON concaténé (8 Ko)") { (JSON_HEAD + jpegB64 + JSON_TAIL).length.toLong() }
        bench.measure("JSON StringBuilder pré-dimensionné") {
            val sb = StringBuilder(JSON_HEAD.length + jpegB64.length + JSON_TAIL.length)
            sb.append(JSON_HEAD).append(jpegB64).append(JSON_TAIL)
            sb.length.toLong()
        }
    }

    // ————— Statistiques : lues à chaque frame publiée —————

    private fun measureStats() {
        val stats = LiveStats()
        var n = 0L
        bench.measure("LiveStats : record + moyenne + p95") {
            n++
            stats.record(150L + (n % 60L))
            stats.averageMs + stats.p95Ms
        }
        val rate = FrameRate()
        bench.measure("FrameRate : record + cadence") {
            n++
            rate.record(n * 253L).toLong() + rate.rate.toLong()
        }
    }

    // ————— Ce que chaque frame publiée coûte en plus du réseau —————

    private fun measureFrameBookkeeping() {
        val timings = FRAME_TIMINGS
        bench.measure("FrameTimings : postes + totaux") {
            var acc = if (timings.known) 1L else 0L
            acc += timings.totalMs + timings.localMs + timings.renderMs
            acc += timings.networkMs + timings.serverMs
            acc
        }
        bench.measure("TimingsText : ligne compacte") { TimingsText.compact(timings).length.toLong() }
        measureLabels()
        measureDistances()
        measureReserve()
    }

    /** Étiquettes de la frame : doublons retirés avant de les poser dans l'état. */
    private fun measureLabels() {
        bench.measure("labels : map + distinct (12)") {
            OBJECTS.map { it.label }.distinct().size.toLong()
        }
        val frameLabels = FrameLabels()
        bench.measure("FrameLabels.of : set réutilisé (12)") {
            frameLabels.of(OBJECTS).size.toLong()
        }
    }

    /**
     * Réserve de la frame suivante : l'ancienne version allouait un `QueuedFrame` **à
     * chaque frame** (objet + trois champs), même sur le chemin où rien n'attendait.
     */
    private fun measureReserve() {
        val slot = LatestFrameSlot()
        val out = FramePayload()
        bench.measure("objet par frame (data class 3 champs)") {
            // Ce que l'ancienne réserve construisait à chaque frame, même vide.
            val queued = QueuedLike(jpegB64, 1L, 2L)
            queued.jpegB64.length.toLong()
        }
        bench.measure("LatestFrameSlot : offer + take") {
            slot.offer(jpegB64, 1L, 2L)
            if (slot.take(out)) out.jpegB64.length.toLong() else 0L
        }
    }

    /**
     * Distances rattachées aux boîtes. Les deux stratégies sont mesurées **côte à côte**
     * parce que l'intuition se trompe : le balayage « sans table » est plus lent que
     * l'`associate` qu'il devait remplacer (n² comparaisons structurelles contre n
     * hachages). C'est le balayage qui est rejeté — la mesure est gardée pour que
     * personne ne refasse l'essai en croyant bien faire.
     */
    private fun measureDistances() {
        bench.measure("distances : associate + 12 lectures") {
            val byBox = READINGS.associate { it.box to it.meters }
            var acc = 0f
            var i = 0
            while (i < OBJECTS.size) {
                acc += byBox[OBJECTS[i].box] ?: 0f
                i++
            }
            acc.toLong()
        }
        bench.measure("distances : balayage direct (rejete)") {
            var acc = 0f
            var i = 0
            while (i < OBJECTS.size) {
                acc += metersOf(READINGS, OBJECTS[i].box) ?: 0f
                i++
            }
            acc.toLong()
        }
    }

    /**
     * Mètres de la boîte [box] parmi [readings], sans table intermédiaire.
     *
     * Deux boucles indexées, aucune lambda : une lambda qui capture `box` allouerait un
     * objet **par objet et par frame**, et le banc mesurerait cette allocation-là.
     */
    private fun metersOf(readings: List<DistanceReading>, box: List<Float>): Float? {
        var i = 0
        while (i < readings.size) {
            val reading = readings[i]
            if (reading.box == box) return reading.meters
            i++
        }
        return null
    }

    // ————— Gardes : seuils très larges (~10× le mesuré), anti-régression —————

    /** Base64 : le poste qui pèse le plus après le JPEG lui-même. */
    private fun assertBase64Budget() {
        assertUnder("base64 encode (encodeToString)", BASE64_ENCODE_US)
        assertUnder("base64 aller-retour", BASE64_ROUNDTRIP_US)
        assertUnder("copie de tableau (6 Ko)", COPY_US)
        assertUnder("JSON concaténé (8 Ko)", JSON_CONCAT_US)
    }

    /** Le coût « par frame » hors réseau et hors encodage JPEG. */
    private fun assertFrameBudget() {
        assertUnder("LiveStats : record + moyenne + p95", STATS_US)
        assertUnder("FrameRate : record + cadence", RATE_US)
        assertUnder("FrameTimings : postes + totaux", TIMINGS_US)
        assertUnder("TimingsText : ligne compacte", TIMINGS_TEXT_US)
        assertUnder("labels : map + distinct (12)", LABELS_US)
        assertUnder("FrameLabels.of : set réutilisé (12)", LABELS_US)
        assertUnder("distances : associate + 12 lectures", DISTANCES_US)
        assertUnder("distances : balayage direct (rejete)", DISTANCES_US)
        assertUnder("LatestFrameSlot : offer + take", RESERVE_US)
        assertTrue("le banc n'a rien mesuré", bench.all().isNotEmpty())
    }

    /** Échoue si [name] dépasse [limitUs] (ou n'a pas été mesuré). */
    private fun assertUnder(name: String, limitUs: Double) {
        val micros = bench.micros(name)
        assertTrue("« $name » non mesuré", micros >= 0.0)
        assertTrue(
            "« $name » : %.2f µs/op, seuil %.2f µs/op".format(micros, limitUs),
            micros < limitUs,
        )
    }

    /** Forme de l'ancienne réserve (`data class QueuedFrame` de `LiveProbe`), pour comparaison. */
    private data class QueuedLike(val jpegB64: String, val captureAtNs: Long, val encodedAtNs: Long)

    private companion object {
        /** 6 Ko : la frame 320 px qualité 55 de la page caméra (`FramePolicy`). */
        const val JPEG_BYTES = 6_144

        /** Le corps JSON minimal du direct, autour du base64. */
        const val JSON_HEAD = "{\"image_b64\":\""
        const val JSON_TAIL = "\",\"prompts\":[\"personne\",\"chien\"]}"

        /*
         * Seuils d'échec : ~10 à ~100 fois le coût mesuré (voir le tableau imprimé).
         * Le but est d'attraper une régression franche — une copie de 30 Ko ajoutée par
         * frame, un `distinct()` sur la chaîne base64 — jamais le bruit d'un runner
         * partagé ni une pause GC tombée dans une boucle de 250 passes.
         */
        const val BASE64_ENCODE_US = 600.0
        const val BASE64_ROUNDTRIP_US = 1_200.0
        const val COPY_US = 300.0
        const val JSON_CONCAT_US = 500.0
        const val STATS_US = 300.0
        const val RATE_US = 200.0
        const val TIMINGS_US = 300.0
        const val TIMINGS_TEXT_US = 400.0
        const val LABELS_US = 300.0
        const val DISTANCES_US = 400.0
        const val RESERVE_US = 200.0

        /** Étiquettes de la frame de test : trois concepts, donc des doublons. */
        val LABELS = listOf("personne", "chien", "chaise")

        /** Douze objets comme une frame chargée, quelques doublons d'étiquette. */
        val OBJECTS: List<SegObject> = List(12) { i ->
            SegObject(
                label = LABELS[i % LABELS.size],
                score = 0.9f - i * 0.01f,
                box = listOf(i * 20f, i * 10f, i * 20f + 40f, i * 10f + 80f),
                maskPngB64 = "",
            )
        }

        val READINGS: List<DistanceReading> = OBJECTS.mapIndexed { i, obj ->
            DistanceReading(
                trackId = i,
                label = obj.label,
                meters = 1.5f + i * 0.25f,
                rawMeters = 1.4f + i * 0.25f,
                box = obj.box,
            )
        }

        /** Une frame complète mesurée : tous les postes posés. */
        val FRAME_TIMINGS = FrameTimings(
            captureToJpegMs = 22,
            jpegToSentMs = 4,
            sentToReceivedMs = 153,
            sentToHeadersMs = 61,
            headersToBodyMs = 92,
            bodyToReceivedMs = 3,
            receivedToOverlaysMs = 37,
            overlaysToPixelMs = 8,
            captureToPixelMs = 224,
            serverTotalMs = 92,
            server = mapOf("total" to 92.0, "vision" to 47.0, "masks" to 38.0),
        )
    }
}