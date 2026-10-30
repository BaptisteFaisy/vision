package dev.sam3vision.app.live

import dev.sam3vision.core.keyframe.FrameSignal
import dev.sam3vision.core.keyframe.KeyframeDecision
import dev.sam3vision.core.keyframe.KeyframeScheduler
import dev.sam3vision.core.keyframe.KeyframeStats
import dev.sam3vision.core.live.UploadLadder
import dev.sam3vision.core.live.UploadRegulator
import dev.sam3vision.core.live.UploadStatus
import dev.sam3vision.core.model.LiveFrame

/**
 * Envois adaptatifs du direct (Q8+Q9) : paliers de qualité montante + keyframes.
 *
 * Q8 : [UploadRegulator] marche les paliers **qualité seule 0-2** (le bord y est
 * constant) d'après la part réseau mesurée (aller-retour − `elapsed_ms`) ; seule la
 * qualité JPEG appliquée à l'encodeur bouge — **jamais le bord**, donc sans rebind
 * CameraX. Le palier 0 rend la qualité de la chaîne : allumé sur un lien sain, rien
 * ne change.
 *
 * Q9 : [KeyframeScheduler] ne fait envoyer qu'une frame clé quand les masques en
 * service sont périmés (~2/s au lieu de ~5/s) ; entre deux keyframes, la propagation
 * locale (`boxPredictionEnabled`) comble l'intervalle — sans elle, les masques ont
 * jusqu'à 500 ms. Le signal est neutre (pas d'estimateur branché) plus la présence
 * d'objets de la dernière réponse : `STARTUP`/`EXPIRED`/`LOST` décident seuls.
 *
 * Les deux sont **éteints par défaut** (`configure`) : éteint, [admit] laisse tout
 * passer et [jpegQuality] rend la constante — le direct est exactement celui d'avant.
 * C'est ce qui permet l'A/B runtime depuis les Réglages.
 *
 * Une seule instance par écran, nourrie aux points de feed (`onFrame`/`runFrame` du
 * Direct comme de la sonde iSee) : aucune logique d'envoi n'est dupliquée.
 * Pur JVM : ni Android, ni Compose — testable sans appareil.
 */
class LiveAdaptive(
    /** Qualité JPEG de la chaîne (65 le Direct 512 px, 55 iSee 320 px) : le palier 0. */
    private val baseQuality: Int,
    private val regulator: UploadRegulator = UploadRegulator(UploadLadder.Tiers.take(QUALITY_TIERS)),
    private val scheduler: KeyframeScheduler = KeyframeScheduler(),
) {

    /** Poids montant adaptatif allumé (Réglages, A/B runtime). */
    var uploadEnabled: Boolean = false
        private set

    /** Keyframes à la demande allumés (Réglages, A/B runtime). */
    var keyframesEnabled: Boolean = false
        private set

    /** Objets présents à la dernière réponse (le signal du scheduler, défaut : oui). */
    private var objectsPresent = true

    /** Instant de la demande en vol (`null` = aucune) : clé d'appariement des réponses. */
    private var pendingAtMs: Long? = null

    /** Palier courant du régulateur (0 = qualité pleine, la valeur d'avant). */
    val tier: Int get() = regulator.tier

    /** Part réseau lissée (ms), pour la ligne d'état. */
    val shareMs: Long get() = regulator.shareMs

    /** Compteurs du scheduler (ratio keyframes/frames, motifs, latence). */
    val stats: KeyframeStats get() = scheduler.stats

    /**
     * Qualité JPEG à appliquer à l'encodeur : la constante éteint, le palier allumé
     * (écart repris de l'échelle, jamais sous [MIN_QUALITY]).
     */
    val jpegQuality: Int
        get() {
            if (!uploadEnabled) return baseQuality
            val drop = UploadLadder.Tiers.first().jpegQuality - UploadLadder.plan(tier).jpegQuality
            return (baseQuality - drop).coerceAtLeast(MIN_QUALITY)
        }

    /** Nouvelle séance : drapeaux A/B puis remise à zéro (paliers, scheduler, présence). */
    fun configure(uploadEnabled: Boolean, keyframesEnabled: Boolean) {
        this.uploadEnabled = uploadEnabled
        this.keyframesEnabled = keyframesEnabled
        reset()
    }

    /**
     * Une frame caméra : `true` = envoyer, `false` = retenir (le masque se propage
     * localement). Éteint, toujours `true`. N'appeler que si l'envoi suit vraiment :
     * une frame admise puis jetée doit passer par [onDropped], sinon le créneau du
     * scheduler reste pris jusqu'au timeout (12 s sans envoi).
     */
    fun admit(): Boolean {
        if (!keyframesEnabled) return true
        return when (val decision = scheduler.onFrame(FrameSignal(0f, 0f, objectsPresent))) {
            is KeyframeDecision.Request -> { pendingAtMs = decision.atMs; true }
            is KeyframeDecision.Hold -> false
        }
    }

    /**
     * Une réponse vient d'arriver (`null` = jetée sans envoi) : le régulateur apprend
     * la part réseau (aller-retour − `elapsed_ms`, échec compris), le scheduler solde
     * son keyframe, la présence suit le dernier succès. Renvoie le statut du
     * régulateur, `null` s'il est éteint ou si rien n'est parti.
     */
    fun onSettled(roundTripMs: Long, result: Result<LiveFrame>?, base64Length: Int): UploadStatus? {
        if (result == null) {
            releaseDropped()
            return null
        }
        val frame = result.getOrNull()
        val serverMs = frame?.elapsedMs ?: 0L
        val status = if (uploadEnabled) {
            regulator.onFrame((roundTripMs - serverMs).coerceAtLeast(0L), base64Length * 3 / 4)
        } else {
            null
        }
        if (frame != null) objectsPresent = frame.objects.isNotEmpty()
        releaseResult(result.isSuccess, roundTripMs)
        return status
    }

    /** Frame admise mais jamais envoyée (créneau pris) : le keyframe en vol est libéré. */
    fun onDropped() = releaseDropped()

    /** Oublie tout : paliers, scheduler, présence — arrêt, nouveaux concepts, séance. */
    fun reset() {
        regulator.reset()
        scheduler.reset()
        objectsPresent = true
        pendingAtMs = null
    }

    /** Solde la demande en vol (succès ou échec réseau) ; sans demande, rien à solder. */
    private fun releaseResult(success: Boolean, latencyMs: Long) {
        val atMs = pendingAtMs ?: return
        pendingAtMs = null
        if (!keyframesEnabled) return
        scheduler.onKeyframeResult(success, latencyMs, requestAtMs = atMs)
    }

    /**
     * Libère une demande admise mais jamais envoyée : comptée en échec (le repli
     * ralentit les demandes suivantes — un créneau pris dit déjà que ça coince).
     */
    private fun releaseDropped() {
        val atMs = pendingAtMs ?: return
        pendingAtMs = null
        if (!keyframesEnabled) return
        scheduler.onKeyframeResult(false, 0L, requestAtMs = atMs)
    }

    companion object {
        /** Paliers câblés : 0-2, qualité seule (le bord y est constant, pas de rebind). */
        const val QUALITY_TIERS = 3

        /** Plancher de sécurité : un palier ne descend jamais sous q20. */
        const val MIN_QUALITY = 20
    }
}
