package dev.sam3vision.core.live

/**
 * Pompe du direct : **une frame en vol, une seule en réserve, jamais de file**.
 *
 * La politique de cadence sort du ViewModel pour être testable en JVM, sans caméra ni
 * réseau : [LatestFrameSlot] porte la réserve, [LiveSession.intervalMsContinuous] la
 * cadence continue (latence/2, bornée 33-900 ms — u01, fin de l'échelle 350/500/900).
 *
 *  - [offer] : la caméra dépose une frame. Elle part **maintenant** (`true`) si le créneau
 *    est libre **et** que l'échéance est écoulée ; si une requête est en vol, elle va en
 *    réserve (la plus récente écrase l'ancienne) ; sinon elle est jetée.
 *  - [send] : appelée quand une réponse arrive. La frame de la réserve part **tout de
 *    suite**, sans attendre l'échéance — le cycle tourne à la vitesse de la requête, pas à
 *    celle de la requête **plus** un plancher artificiel : c'est là que l'ancienne échelle
 *    jetait du temps (u01 : 2,03 → 2,47 ips à 422 ms, −71 ms par frame).
 *  - [reset] : nouvelle séance, échéance et réserve remises à zéro.
 *
 * La pompe ne fait que **décider quand** : l'envoi (coroutine, client) reste au
 * propriétaire, qui garde ainsi la main sur la chronologie et la publication. Sûre entre
 * threads : la caméra dépose, le thread des réponses vide.
 */
class LivePump(
    /** Session courante : la pompe suit la session active (le direct peut démarrer après). */
    private val session: () -> LiveSession?,
    /** Horloge en ms, injectable : les tests pilotent le temps (aucun `sleep`). */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Réserve : une place, remplie par la caméra pendant qu'une requête est en vol. */
    private val reserve = LatestFrameSlot()

    /** Contenu de la frame sortie de réserve, réutilisé : zéro allocation par frame. */
    private val reserved = FramePayload()

    /** Échéance du prochain créneau ; `0` = immédiat. */
    private var nextTickAt = 0L

    /** Une frame attend-elle en réserve ? */
    val pending: Boolean get() = reserve.pending

    /** Frames **jetées** parce que la réserve était déjà pleine (jamais mises en file). */
    val droppedFrames: Long get() = reserve.droppedFrames

    /** Nouvelle séance : le prochain créneau est immédiat et la réserve est vidée. */
    fun reset() {
        nextTickAt = 0L
        reserve.clear()
    }

    /**
     * Faut-il convertir la frame qui arrive ? Non si la réserve est déjà pleine (une
     * deuxième image serait du CPU jeté), oui si un créneau est libre ou si la cadence est
     * écoulée. Sans session, non : rien ne partirait.
     */
    fun wantsFrame(): Boolean {
        val active = session() ?: return false
        if (reserve.pending) return false
        if (active.saturated) return true
        return clock() >= nextTickAt
    }

    /**
     * Dépose une frame caméra. `true` = elle doit partir **maintenant** (l'appelant envoie
     * avec ses propres valeurs) ; `false` = elle est en réserve (une requête est en vol) ou
     * jetée (offerte trop tôt, jamais mise en file). Une frame vide n'est jamais ni envoyée
     * ni réservée.
     */
    fun offer(jpegB64: String, captureAtNs: Long = 0L, encodedAtNs: Long = 0L): Boolean {
        if (jpegB64.isBlank()) return false
        val active = session() ?: return false
        if (active.saturated) {
            reserve.offer(jpegB64, captureAtNs, encodedAtNs)
            return false
        }
        val now = clock()
        if (now < nextTickAt) return false
        markTick(active, now)
        return true
    }

    /**
     * Une réponse est arrivée : vide la réserve dans [out] et renvoie `true` si cette frame
     * doit partir **sans attendre l'échéance** (créneau libre). `false` si le créneau est
     * encore pris ou si rien n'attendait ; [out] n'est alors pas modifié.
     */
    fun send(out: FramePayload): Boolean {
        val active = session() ?: return false
        if (active.saturated) return false
        if (!reserve.take(out)) return false
        markTick(active, clock())
        return true
    }

    /** Pose l'échéance suivante sur la cadence continue de la session (latence/2, 33-900 ms). */
    private fun markTick(active: LiveSession, at: Long) {
        nextTickAt = at + active.intervalMsContinuous()
    }
}