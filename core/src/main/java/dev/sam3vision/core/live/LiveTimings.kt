package dev.sam3vision.core.live

import kotlin.math.roundToLong

/**
 * Chronologie d'une frame du direct / d'iSee, **côté téléphone**.
 *
 * Le trajet complet est `capture → jpeg → envoi → réponse → masques → pixel`. Les postes
 * sont posés par l'écran, la caméra et le ViewModel ; le détail **serveur** vient de
 * `LiveFrame.timingsMs` (le backend ne l'envoie qu'avec `FAST_TIMING=1`).
 *
 * Horloge **monotone** en nanosecondes : `SystemClock.elapsedRealtimeNanos()` est la base
 * d'`ImageProxy.imageInfo.timestamp`, donc la capture du capteur est comparable aux postes
 * locaux. Les deux horloges (téléphone, serveur) ne sont pas synchronisées : on ne mesure
 * que des deltas d'un même côté, et un poste inconnu reste `-1` — jamais un zéro inventé.
 */
enum class LiveStage { CAPTURE, ENCODED, SENT, HEADERS, BODY, RECEIVED, OVERLAYS, PIXEL }

/**
 * Chronologie figée d'une frame, en millisecondes entre deux postes (`-1` = non mesuré).
 * Chaque intervalle couvre une portion **disjointe** du trajet.
 */
data class FrameTimings(
    /** Exposition du capteur → JPEG prêt (rotation, échelle, compression). */
    val captureToJpegMs: Long = -1,
    /** JPEG prêt → frame remise au transport. */
    val jpegToSentMs: Long = -1,
    /** Envoi → réponse complète : serveur + file + radio + descente + parse. */
    val sentToReceivedMs: Long = -1,
    /**
     * Envoi → en-têtes : attente serveur + radio, sans le corps. `-1` quand le transport ne
     * sait pas dater les en-têtes (frame suivante d'un WebSocket déjà ouvert).
     */
    val sentToHeadersMs: Long = -1,
    /** En-têtes → corps complet : descente des octets (HTTP seulement). */
    val headersToBodyMs: Long = -1,
    /** Corps → réponse exploitable : parsing JSON du message. */
    val bodyToReceivedMs: Long = -1,
    /** Réponse → masques décodés et recolorés, prêts à dessiner. */
    val receivedToOverlaysMs: Long = -1,
    /** Masques prêts → premier pixel réellement affiché. */
    val overlaysToPixelMs: Long = -1,
    /** Capture → pixel : la latence perçue, seule mesure honnête. */
    val captureToPixelMs: Long = -1,
    /** `elapsed_ms` du backend, repli quand `timingsMs` n'arrive pas. */
    val serverTotalMs: Long = -1,
    /** Postes serveur reçus (`"timings_ms"`), vide si le levier est éteint. */
    val server: Map<String, Double> = emptyMap(),
) {
    /** Encodage sur le téléphone. */
    val encodeMs: Long get() = captureToJpegMs

    /** Sérialisation + écriture du corps (alias de [jpegToSentMs]). */
    val uploadMs: Long get() = jpegToSentMs

    /** Aller-retour côté client, serveur compris. */
    val rttMs: Long get() = sentToReceivedMs

    /** Durée serveur : le total annoncé, sinon `elapsed_ms`, sinon rien. */
    val serverMs: Long get() = server[SERVER_TOTAL]?.roundToLong() ?: serverTotalMs

    /**
     * Part de l'aller-retour qui n'est **pas** le serveur (radio, proxy, file, parse).
     * `-1` sans chiffre serveur : sans lui, le partage serait une invention.
     */
    val networkMs: Long
        get() = if (sentToReceivedMs >= 0 && serverMs >= 0) sentToReceivedMs - serverMs else -1

    /** Masques + dessin : de la réponse au premier pixel. */
    val renderMs: Long get() = sumKnown(receivedToOverlaysMs, overlaysToPixelMs)

    /**
     * Latence affichée : `capture → pixel` quand le cycle est complet, sinon la somme des
     * postes mesurés (l'écran n'a pas encore dessiné).
     */
    val totalMs: Long
        get() = if (captureToPixelMs >= 0) captureToPixelMs else sumKnown(
            captureToJpegMs, jpegToSentMs, sentToReceivedMs,
            receivedToOverlaysMs, overlaysToPixelMs,
        )

    /** Capture → pixel mesurés : seule chronologie qui vaut preuve. */
    val complete: Boolean get() = captureToPixelMs >= 0

    /**
     * Au moins un poste mesuré : il y a une chronologie à afficher.
     *
     * Chaîne de comparaisons, pas `listOf(...).any { }` : cette lecture tombe à chaque
     * frame publiée, et la liste allouait un tableau plus dix `Long` boxés (mesuré au
     * banc JVM, [LiveFrameBudgetTest]).
     */
    val known: Boolean
        get() = captureToJpegMs >= 0L || jpegToSentMs >= 0L || sentToReceivedMs >= 0L ||
            sentToHeadersMs >= 0L || headersToBodyMs >= 0L || bodyToReceivedMs >= 0L ||
            receivedToOverlaysMs >= 0L || overlaysToPixelMs >= 0L || captureToPixelMs >= 0L ||
            serverMs >= 0L

    /** Rien de mesuré : le panneau n'affiche pas de zéros. */
    val isEmpty: Boolean get() = !known

    /** Ce que la frame coûte au téléphone (encodage, parsing, rendu), hors réseau. */
    val localMs: Long get() = sumKnown(encodeMs, bodyToReceivedMs, renderMs)

    /**
     * Somme des postes posés, `-1` si aucun ne l'est.
     *
     * Cinq paramètres à défaut plutôt qu'un `vararg` : un `vararg` alloue un `long[]`
     * **à chaque appel**, donc à chaque frame publiée, pour deux à cinq valeurs.
     */
    private fun sumKnown(a: Long, b: Long = -1L, c: Long = -1L, d: Long = -1L, e: Long = -1L): Long {
        var total = 0L
        var seen = 0
        if (a >= 0L) {
            total += a
            seen++
        }
        if (b >= 0L) {
            total += b
            seen++
        }
        if (c >= 0L) {
            total += c
            seen++
        }
        if (d >= 0L) {
            total += d
            seen++
        }
        if (e >= 0L) {
            total += e
            seen++
        }
        return if (seen == 0) -1L else total
    }

    companion object {
        /** Clé du total serveur dans `"timings_ms"` (le backend parle aussi `elapsed_ms`). */
        const val SERVER_TOTAL: String = "total"
    }
}

/**
 * Marques de la frame en cours, posées par la caméra, le ViewModel et l'écran sur un objet
 * unique ; l'UI ne lit jamais les marques, seulement un instantané via [snapshot].
 *
 * Sûre entre threads : la caméra écrit sur son exécuteur, le réseau sur un autre, l'UI lit.
 */
class LiveTimings(private val clock: () -> Long = System::nanoTime) {

    private val marks = LongArray(LiveStage.entries.size) { UNSET }

    /** Pose un poste ; [atNs] sert quand l'horodatage est déjà connu (capteur caméra). */
    @Synchronized
    fun mark(stage: LiveStage, atNs: Long = clock()) {
        marks[stage.ordinal] = atNs
    }

    /** Efface toutes les marques : nouvelle frame, nouvelle session, sortie d'écran. */
    @Synchronized
    fun reset() {
        marks.fill(UNSET)
    }

    /** Millisecondes entre deux postes, arrondies ; `-1` si l'un manque ou ordre impossible. */
    @Synchronized
    fun between(from: LiveStage, to: LiveStage): Long = betweenLocked(from, to)

    /**
     * Même calcul, verrou **déjà tenu** : [snapshot] lit neuf postes d'un coup et
     * prenait jusqu'ici neuf fois le moniteur de façon ré-entrante, une fois par frame
     * publiée.
     */
    private fun betweenLocked(from: LiveStage, to: LiveStage): Long {
        val start = marks[from.ordinal]
        val end = marks[to.ordinal]
        if (start == UNSET || end == UNSET || end < start) return -1L
        return (end - start + NS_PER_MS / 2) / NS_PER_MS
    }

    /** Instantané immuable pour l'UI (aucune lecture concurrente des marques). */
    @Synchronized
    fun snapshot(serverTotalMs: Long = -1, server: Map<String, Double> = emptyMap()): FrameTimings =
        FrameTimings(
            captureToJpegMs = betweenLocked(LiveStage.CAPTURE, LiveStage.ENCODED),
            jpegToSentMs = betweenLocked(LiveStage.ENCODED, LiveStage.SENT),
            sentToReceivedMs = betweenLocked(LiveStage.SENT, LiveStage.RECEIVED),
            sentToHeadersMs = betweenLocked(LiveStage.SENT, LiveStage.HEADERS),
            headersToBodyMs = betweenLocked(LiveStage.HEADERS, LiveStage.BODY),
            bodyToReceivedMs = betweenLocked(LiveStage.BODY, LiveStage.RECEIVED),
            receivedToOverlaysMs = betweenLocked(LiveStage.RECEIVED, LiveStage.OVERLAYS),
            overlaysToPixelMs = betweenLocked(LiveStage.OVERLAYS, LiveStage.PIXEL),
            captureToPixelMs = betweenLocked(LiveStage.CAPTURE, LiveStage.PIXEL),
            serverTotalMs = serverTotalMs,
            server = server,
        )

    private companion object {
        /** Marque absente : tout horodatage réel lui est supérieur. */
        const val UNSET = Long.MIN_VALUE
        const val NS_PER_MS = 1_000_000L
    }
}
