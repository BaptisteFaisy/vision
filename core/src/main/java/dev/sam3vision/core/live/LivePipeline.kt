package dev.sam3vision.core.live

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.net.LiveClient
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Semaphore

/**
 * Pipeline multi-frames du direct : contrairement à [LiveSession] (une seule frame en vol),
 * il laisse [maxParallel] requêtes partir en parallèle et n'affiche que la **plus récente**
 * des réponses reçues. C'est ce qui débride la cadence : le téléphone n'attend plus le
 * round-trip complet avant d'envoyer la frame suivante, le GPU enchaîne.
 *
 * `/live` est sans état : l'ordre des réponses n'a donc aucun sens métier, seule la
 * fraîcheur du résultat compte. D'où deux règles, et elles suffisent :
 *
 * - **zéro file** : plus de [maxParallel] frames en vol, la nouvelle est **jetée**
 *   ([submit] renvoie `null`) — l'appelant caméra n'est jamais mis en attente ;
 * - **écrasement monotonique** : un ticket plus vieux que le dernier affiché n'est pas
 *   compté (anti-rebond du rendu), donc une réponse en retard ne réécrit jamais un
 *   affichage postérieur.
 *
 * Mesuré sur A100 eu-west (relevé user, hors de cet arbre) : 1 frame en vol = 150 ms d'aller-retour,
 * 8 en vol = 300 ms pour 5,9 ips. Le multi-frames coûte +150 ms et rend 8× le débit : la
 * fenêtre vaut le coup. Le serveur, lui, sérialise ses inférences (`max_containers=1` +
 * verrou GPU) : au-delà de quelques frames en vol, on ne gagne plus de débit, on ne
 * fabrique que de la latence — d'où la borne [DEFAULT_PARALLEL].
 */
class LivePipeline(
    private val client: LiveClient,
    private val maxParallel: Int = DEFAULT_PARALLEL,
    prompts: List<String> = FAST_PROMPTS,
    private val workEdge: Int = WORK_EDGE,
    private val maxObjects: Int = MAX_OBJECTS,
    val stats: LiveStats = LiveStats(),
    val serverStats: LiveStats = LiveStats(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Créneaux d'envoi : autant de frames en vol, jamais plus. */
    private val permits = Semaphore(maxParallel.coerceAtLeast(1))

    /** Ticket de la dernière frame partie, et de la dernière réponse affichée. */
    private val seq = AtomicInteger(0)
    private val shown = AtomicInteger(-1)
    private val accepted = AtomicInteger(0)

    /**
     * Concepts normalisés **une fois** : [prompts] est figé à la construction, or
     * [LivePrompts.normalize] (découpe, doublons, longueur) tournait à chaque frame — du
     * CPU payé deux fois par seconde pour une liste qui ne change pas en séance.
     */
    private val normalizedPrompts: List<String> by lazy { LivePrompts.normalize(prompts) }

    /** Frames parties depuis le dernier [reset]. */
    val sent: Int get() = seq.get()

    /** Réponses affichées : les réponses plus vieilles que la dernière affichée ne comptent pas. */
    val displayed: Int get() = accepted.get()

    /** Créneaux occupés (≤ [maxParallel]). */
    val inFlight: Int get() = maxParallel.coerceAtLeast(1) - permits.availablePermits

    /**
     * Envoie une frame si le pipeline n'est pas plein. Renvoie `null` quand la frame est
     * jetée (pipeline saturé, ou image vide) — l'appelant ne doit jamais bloquer la caméra.
     * L'échec serveur est consigné comme les autres : il alimente [stats] et libère le créneau.
     */
    suspend fun submit(jpegB64: String): Result<LiveFrame>? {
        if (jpegB64.isBlank()) return null
        if (!permits.tryAcquire()) return null
        try {
            val ticket = seq.incrementAndGet()
            val startedAt = clock()
            val result = client.live(jpegB64, normalizedPrompts, workEdge, maxObjects)
            stats.record((clock() - startedAt).coerceAtLeast(0L))
            result.getOrNull()?.let { serverStats.record(it.elapsedMs) }
            if (result.isSuccess && ticket > shown.get()) {
                shown.set(ticket)
                accepted.incrementAndGet()
            }
            return result
        } finally {
            permits.release()
        }
    }

    /**
     * Intervalle d'envoi visant [targetFps] images par seconde, borné
     * [MIN_INTERVAL_MS]..[MAX_INTERVAL_MS] (33..500 ms) : on n'envoie jamais plus vite que le
     * capteur (30 fps) et jamais plus lentement que 2 fps, plancher d'utilité du direct.
     */
    fun intervalMsFor(targetFps: Int): Long = when {
        targetFps >= TARGET_FPS -> MIN_INTERVAL_MS
        targetFps <= 2 -> MAX_INTERVAL_MS
        else -> (1000L / targetFps).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }

    /** Arrêt du direct ou changement de concepts : fenêtre et compteurs à zéro. */
    fun reset() {
        seq.set(0)
        shown.set(-1)
        accepted.set(0)
        stats.reset()
        serverStats.reset()
    }

    companion object {
        /**
         * Frames en vol. Six, pas plus : au-delà, la file GPU du serveur transforme le débit
         * en latence (6 frames en vol = 1220 ms affichés contre 600-715 ms à une seule, sur
         * A100 `eu-west`) et une réponse arrivée tard n'apporte plus rien d'utile.
         */
        const val DEFAULT_PARALLEL = 6

        /** Largeur de travail envoyée au serveur (JPEG 512 px q65 côté caméra). */
        const val WORK_EDGE = 512

        /** Objets à l'écran : borne haute du contrat `/live`. */
        const val MAX_OBJECTS = 12

        /** Cadence visée : celle du capteur. */
        const val TARGET_FPS = 30

        /**
         * Un seul concept : c'est le **premier réglage de cadence**, pas la résolution. Le
         * coût serveur est batché par concept et `work_edge` ne sert à rien (512→768 :
         * 240→247 ms), alors que la liste des concepts, si.
         */
        val FAST_PROMPTS: List<String> = listOf("person")

        /** 30 fps : plancher d'intervalle (33 ms). */
        const val MIN_INTERVAL_MS = 1000L / 30

        /** 2 fps : plafond d'intervalle (500 ms). */
        const val MAX_INTERVAL_MS = 1000L / 2
    }
}