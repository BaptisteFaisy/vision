package dev.sam3vision.core.live

import dev.sam3vision.core.net.RecordingLiveClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simulation déterministe de la cadence du Direct : **horloge virtuelle**, caméra 30 fps
 * (une frame offerte toutes les 33 ms), une réponse par envoi après `latence` ms, une seule
 * frame en vol. Les deux régulateurs reçoivent exactement les mêmes entrées :
 *
 *  - **avant** : l'échelle à trois paliers (350/500/900 ms, figée ici — le Direct
 *    suit la cadence régulée depuis O8) et **pas de réserve** — une frame offerte
 *    pendant un envoi est jetée ;
 *  - **après** : [LivePump] — cadence continue ([LiveSession.intervalMsContinuous], latence/2
 *    bornée 33-900 ms) et **une** frame en réserve, envoyée dès la libération du créneau.
 *
 * Ce n'est pas une mesure terrain : le modèle ne chiffre ni l'encodage JPEG ni le réseau, il
 * compare deux régulateurs à entrées identiques. « Âge » = âge moyen de l'image affichée
 * (échantillonné à chaque frame caméra), la grandeur du « 1220-1260 ms » du téléphone. Le
 * tableau sort dans le `<system-out>` du rapport JUnit ; les seuils sont larges, seule une
 * régression franche fait échouer le test.
 */
class LiveCadenceSimTest {

    /** Marge d'âge tolérée : le modèle est déterministe, le test ne doit pas être fragile. */
    private val ageToleranceMs = 20.0

    /** Latences de référence : 41 ms (B200), 245 ms (H200), 422 ms (A100 eu-west, mesuré). */
    private val latenciesMs = listOf(41L, 245L, 422L)

    /** Résultat d'une simulation : débit, fraîcheur, et deux invariants de file. */
    private data class Run(
        val ips: Double,
        val averageAgeMs: Double,
        val overlap: Int,
        val dropped: Long,
    )

    private data class Row(val latencyMs: Long, val before: Run, val after: Run)

    @Test
    fun `la pompe continue bat l_ancienne echelle sans mettre deux frames en vol`() {
        val rows = latenciesMs.map { latency ->
            Row(latency, Sim(latency, pumped = false).run(), Sim(latency, pumped = true).run())
        }
        printTable(rows)
        assertProgress(rows)
    }

    /** Le tableau imprimé : lisible dans le rapport JUnit, une ligne par latence. */
    private fun printTable(rows: List<Row>) {
        println("CADENCE — horloge virtuelle, caméra 30 fps, une frame en vol")
        println("latence | avant (350/500/900 ms, sans réserve) | après (latence/2, réserve 1)")
        rows.forEach { row ->
            println(
                "${row.latencyMs} ms | avant ${ips(row.before)} / ${age(row.before)}" +
                    " | après ${ips(row.after)} / ${age(row.after)}" +
                    " | ${row.after.dropped} frames écrasées en réserve",
            )
        }
    }

    /** Les deux promesses de u01 : plus de débit, image plus fraîche, jamais deux en vol. */
    private fun assertProgress(rows: List<Row>) {
        rows.forEach { row ->
            assertTrue(
                "débit à ${row.latencyMs} ms : ${ips(row.before)} → ${ips(row.after)}",
                row.after.ips > row.before.ips,
            )
            assertTrue(
                "âge à ${row.latencyMs} ms : ${age(row.before)} → ${age(row.after)}",
                row.after.averageAgeMs <= row.before.averageAgeMs + ageToleranceMs,
            )
            assertEquals("avant : une frame en vol", 0, row.before.overlap)
            assertEquals("après : une frame en vol", 0, row.after.overlap)
        }
        val fast = rows.first()
        assertTrue(
            "à 41 ms la fraîcheur fait plus que doubler : ${age(fast.before)} → ${age(fast.after)}",
            fast.after.averageAgeMs < fast.before.averageAgeMs / 2,
        )
    }

    private fun ips(run: Run) = "${"%.2f".format(run.ips)} ips"
    private fun age(run: Run) = "${"%.0f".format(run.averageAgeMs)} ms"

    /**
     * Le modèle : une frame en vol, réponse après [latencyMs], rien d'autre. `pumped`
     * choisit le régulateur ; tout le reste (caméra, créneau, mesures de latence) est
     * commun, sinon la comparaison ne voudrait rien dire.
     */
    private class Sim(
        private val latencyMs: Long,
        private val pumped: Boolean,
    ) {

        /** Le transport n'est jamais appelé : le régulateur décide, l'app enverrait. */
        private val client = RecordingLiveClient()

        /** Horloge virtuelle, en ms (les horodatages de frame sont convertis en ns). */
        private var now = 0L

        private val stats = LiveStats()
        private val gate = FrameGate()
        private val session = LiveSession(client, gate, stats = stats, clock = { now })
        private val pump = if (pumped) LivePump({ session }, { now }) else null
        private val reserved = FramePayload()

        /** Échéance de l'ancien régulateur à paliers (`pumped = false`). */
        private var nextTickAt = 0L
        private var responseAt = Long.MAX_VALUE
        private var flightCapturedMs = 0L
        private var shownCapturedMs = 0L
        private var sent = 0
        private var overlap = 0
        private var ageSumMs = 0.0
        private var samples = 0

        fun run(): Run {
            while (now <= DURATION_MS) {
                if (now == responseAt) onResponse()
                if (now % CAMERA_MS == 0L) onCameraFrame()
                ageSumMs += (now - shownCapturedMs).toDouble()
                samples++
                now++
            }
            return Run(
                ips = sent * 1000.0 / DURATION_MS,
                averageAgeMs = ageSumMs / samples,
                overlap = overlap,
                dropped = pump?.droppedFrames ?: 0L,
            )
        }

        /** Frame caméra : à la pompe de décider (`pumped`), sinon l'ancien régulateur décide. */
        private fun onCameraFrame() {
            val active = pump
            if (active == null) {
                if (session.saturated || now < nextTickAt) return
                nextTickAt = now + steppedIntervalMs(stats.averageMs)
                dispatch(now)
                return
            }
            if (active.offer("jpeg", capturedNs(), capturedNs())) dispatch(now)
        }

        /**
         * Ancienne échelle à paliers du Direct (350/500/900 ms, avant O8) : le
         * bras « avant » la fige localement pour garder la comparaison honnête.
         */
        private fun steppedIntervalMs(averageMs: Long): Long = when {
            averageMs > Cadence.Direct.slowMs -> Cadence.Direct.slowMs
            averageMs < Cadence.Direct.fastMs -> Cadence.Direct.fastMs
            else -> Cadence.Direct.targetMs
        }

        /** Envoi d'une frame : créneau pris, réponse posée à `now + latence`. */
        private fun dispatch(capturedMs: Long) {
            if (session.saturated) {
                overlap++
                return
            }
            gate.tryStart()
            flightCapturedMs = capturedMs
            sent++
            responseAt = now + latencyMs
        }

        /**
         * La réponse arrive : le créneau se libère et la latence entre dans la fenêtre
         * glissante (comme [LiveSession.runFrame] le ferait). La pompe envoie alors la frame
         * de la réserve **tout de suite**, sans attendre l'échéance ; l'ancien régulateur,
         * lui, n'a rien à envoyer.
         */
        private fun onResponse() {
            gate.finish()
            stats.record(latencyMs)
            shownCapturedMs = flightCapturedMs
            responseAt = Long.MAX_VALUE
            val active = pump ?: return
            if (active.send(reserved)) dispatch(reserved.captureAtNs / NS_PER_MS)
        }

        private fun capturedNs() = now * NS_PER_MS

        private companion object {
            /** Durée simulée : 20 s, soit 600 frames caméra. */
            const val DURATION_MS = 20_000L
            const val CAMERA_MS = 33L
            const val NS_PER_MS = 1_000_000L
        }
    }
}