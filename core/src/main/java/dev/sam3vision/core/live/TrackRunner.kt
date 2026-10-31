package dev.sam3vision.core.live

import dev.sam3vision.core.model.TrackFrame
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.TrackClient

/**
 * Orchestration du tracker vidéo par sessions, calquée sur [LiveSession] : une frame
 * en vol au maximum ([FrameGate]), la nouvelle frame est **jetée** si le créneau est
 * pris — la caméra n'attend jamais le réseau.
 *
 * Cycle : [start] ouvre une session (`POST /session`) et rend la frame d'ouverture (les
 * objets de la frame 0 analysée par le backend), [submit] envoie les frames suivantes
 * (`POST /frame`) en conservant les **ids stables**, [stop] ferme la session.
 *
 * Session perdue (404 : backend redémarré, session expirée) → [submit] recrée une
 * session avec la dernière frame reçue et renvoie les objets de cette réouverture.
 * **Une seule recréation par appel** : jamais de boucle, un 404 répété remonte l'erreur.
 */
class TrackRunner(
    private val client: TrackClient,
    private val gate: FrameGate = FrameGate(),
    override val stats: LiveStats = LiveStats(),
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Âge maximal d'une session : au-delà, la frame suivante rouvre une session
     * (re-keyframe) au lieu d'un `/frame`. Sans ça, un objet entré dans le champ
     * après l'ouverture ne serait **jamais** détecté — impensable rue piétonne,
     * où un vélo ou une personne peut surgir devant l'utilisateur.
     */
    private val rekeyMs: Long = DEFAULT_REKEY_MS,
) : FramePipeline {

    /** État immuable d'une session : remplacé en bloc, jamais muté. */
    private data class Session(
        val id: String,
        val width: Int,
        val height: Int,
        val prompts: List<String>,
        val startedAtMs: Long,
    )

    @Volatile
    private var current: Session? = null

    /**
     * Dernier échec d'ouverture : sa cause est rejouée telle quelle pendant
     * [START_RETRY_MS], sans nouveau `POST /session`. `null` = ré-armé.
     */
    @Volatile
    private var lastStartFailureAtMs: Long? = null

    @Volatile
    private var lastStartError: Throwable? = null

    /** Identifiant de la session en cours, `null` si aucune. */
    val sessionId: String? get() = current?.id

    /** Largeur annoncée par la session (0 tant qu'aucune session). */
    val width: Int get() = current?.width ?: 0

    /** Hauteur annoncée par la session (0 tant qu'aucune session). */
    val height: Int get() = current?.height ?: 0

    /** Une frame est-elle en train d'être traitée ? */
    override val inFlight: Boolean get() = gate.inFlight

    /** Époque de la séance : elle change à chaque [reset] (arrêt, nouvelle séance). */
    override val generation: Long get() = gate.generation

    /** Vrai si une frame admise sous l'époque [era] est encore d'actualité. */
    override fun isCurrent(era: Long): Boolean = gate.isCurrent(era)

    /**
     * Ouvre une session de suivi sur [firstFrameB64] avec [prompts] (normalisés par
     * [LivePrompts.normalize]) et retient la taille renvoyée par le backend. La frame
     * d'ouverture est rendue telle quelle : le backend vient d'analyser cette image,
     * l'écran n'a donc rien de plus à attendre pour la première image.
     *
     * Régulée par [gate] comme [submit] : une seule ouverture en vol, l'appel
     * concurrent échoue vite ([ApiError.BadResponse], sans `POST /session`) — pas
     * `null` comme [submit], la signature non-nullable est le contrat du pont
     * (`runAsFrame`). Après un échec, la même erreur est rejouée sans appel réseau
     * pendant [START_RETRY_MS] ; un succès ré-arme.
     */
    suspend fun start(prompts: List<String>, firstFrameB64: String): Result<TrackFrame> {
        lastStartError?.let { cached ->
            val failedAt = lastStartFailureAtMs
            if (failedAt != null && clock() - failedAt < START_RETRY_MS) return Result.failure(cached)
        }
        if (!gate.tryStart()) {
            return Result.failure(ApiError.BadResponse("tracker: open already in progress"))
        }
        try {
            val normalized = LivePrompts.normalize(prompts)
            return client.start(firstFrameB64, normalized).fold(
                onSuccess = { opened ->
                    lastStartError = null
                    lastStartFailureAtMs = null
                    current = Session(opened.sessionId, opened.width, opened.height, normalized, clock())
                    Result.success(TrackFrame(opened.width, opened.height, opened.objects))
                },
                onFailure = { error ->
                    lastStartFailureAtMs = clock()
                    lastStartError = error
                    Result.failure(error)
                },
            )
        } finally {
            gate.finish()
        }
    }

    /**
     * Envoie une frame au tracker ; `null` si une frame est déjà en vol (frame jetée).
     * Session périmée ([rekeyMs]) → re-keyframe (`/session` neuve : les nouveaux
     * obstacles entrent dans le suivi). La latence bout-en-bout est consignée dans
     * [stats], échec compris.
     */
    suspend fun submit(jpegB64: String): Result<TrackFrame>? {
        if (!gate.tryStart()) return null
        val startedAt = clock()
        try {
            val session = current
                ?: return Result.failure(ApiError.BadResponse("tracker: session not started"))
            val frame = if (startedAt - session.startedAtMs >= rekeyMs) {
                rekey(session, jpegB64)
            } else {
                val result = client.frame(session.id, jpegB64)
                if (sessionLost(result)) recover(jpegB64) else result
            }
            stats.record(clock() - startedAt)
            return frame.map { it.withSessionSize() }
        } finally {
            gate.finish()
        }
    }

    /**
     * Intervalle de capture pour [targetFps], borné au contrat 30 fps :
     * jamais moins de [MIN_INTERVAL_MS] (33 ms), jamais plus de [MAX_INTERVAL_MS] (500 ms).
     */
    fun intervalMsFor(targetFps: Int): Long =
        (1000L / targetFps.coerceAtLeast(1)).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

    /**
     * Cadence du direct : [TARGET_FPS] fixes (33 ms). Le débit réel est de toute façon
     * borné par le serveur — une frame en vol, la suivante part dès que le créneau se
     * libère.
     */
    override fun intervalMsAdaptive(): Long = intervalMsFor(TARGET_FPS)

    /**
     * Ferme la session courante (`DELETE /session/{id}`). L'échec de fermeture n'est
     * pas bloquant : la session expirera côté serveur, l'UI n'a rien à en faire.
     */
    suspend fun stop() {
        val session = current ?: return
        current = null
        client.close(session.id)
        stats.reset()
    }

    /**
     * Arrêt du direct : la frame en vol est **révoquée** (sa réponse sera reconnue
     * périmée par [isCurrent]) et les mesures de la séance sont oubliées.
     */
    override fun reset() {
        gate.invalidate()
        stats.reset()
    }

    private fun sessionLost(result: Result<TrackFrame>): Boolean =
        (result.exceptionOrNull() as? ApiError.Http)?.code == HTTP_NOT_FOUND

    /**
     * Recrée au plus une session sur [jpegB64] et renvoie les objets de la frame
     * d'ouverture (le backend vient d'analyser cette image : inutile de la renvoyer).
     */
    private suspend fun recover(jpegB64: String): Result<TrackFrame> {
        val prompts = current?.prompts ?: LivePrompts.default
        val opened = client.start(jpegB64, prompts).getOrElse { error ->
            current = null
            return Result.failure(error)
        }
        current = Session(opened.sessionId, opened.width, opened.height, prompts, clock())
        return Result.success(TrackFrame(opened.width, opened.height, opened.objects))
    }

    /**
     * Re-keyframe : nouvelle session sur [jpegB64] (les objets entrés depuis
     * l'ouverture entrent dans le suivi), ancienne session fermée au mieux.
     * Si l'ouverture échoue, repli sur un `/frame` de l'ancienne session — un
     * suivi périmé vaut mieux qu'un écran vide rue piétonne.
     */
    private suspend fun rekey(session: Session, jpegB64: String): Result<TrackFrame> {
        val opened = client.start(jpegB64, session.prompts).getOrElse {
            return client.frame(session.id, jpegB64)
        }
        runCatching { client.close(session.id) }
        current = Session(opened.sessionId, opened.width, opened.height, session.prompts, clock())
        return Result.success(TrackFrame(opened.width, opened.height, opened.objects))
    }

    /** Une réponse `/frame` sans taille retombe sur celle de la session. */
    private fun TrackFrame.withSessionSize(): TrackFrame {
        if (width > 0 && height > 0) return this
        val session = current
        return TrackFrame(
            width = if (width > 0) width else session?.width ?: 0,
            height = if (height > 0) height else session?.height ?: 0,
            objects = objects,
        )
    }

    companion object {
        /**
         * Âge maximal d'une session : 1 s. À ~1 m/s, un nouvel obstacle est
         * détecté au pire 1 m plus tard — rue piétonne, 3 m d'aveugle aux
         * nouveautés, c'est trop. Chaque re-keyframe coûte une ouverture
         * `/session` complète (la campagne TRACK-PERF mesurait 3,1 s) pendant
         * laquelle le créneau est tenu et les frames suivantes tombent : le
         * prix de la fraîcheur, borné par le créneau (jamais de file), avec
         * repli sur l'ancienne session si l'ouverture échoue.
         */
        const val DEFAULT_REKEY_MS = 1_000L

        /**
         * Après un [start] en échec, la même erreur est rejouée sans appel réseau
         * pendant 5 s : backend track absent, chaque frame relancerait sinon un
         * `POST /session` (~180 requêtes 404 observées). Un succès ré-arme aussitôt.
         */
        const val START_RETRY_MS = 5_000L

        /** Cadence visée du direct. */
        const val TARGET_FPS = 30

        /** Plancher d'intervalle : 1000/30 ms. */
        const val MIN_INTERVAL_MS = 1000L / 30

        /** Plafond d'intervalle : 1000/2 ms (2 fps). */
        const val MAX_INTERVAL_MS = 1000L / 2

        private const val HTTP_NOT_FOUND = 404
    }
}