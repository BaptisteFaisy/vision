package dev.sam3vision.app.live

import dev.sam3vision.core.keyframe.FrameSignal
import dev.sam3vision.core.keyframe.KeyframeDecision
import dev.sam3vision.core.keyframe.KeyframeScheduler
import dev.sam3vision.core.keyframe.KeyframeStats
import dev.sam3vision.core.live.EncodePlan
import dev.sam3vision.core.live.UploadRegulator
import dev.sam3vision.core.live.UploadStatus
import dev.sam3vision.core.model.LiveFrame

/**
 * Envois adaptatifs du direct (Q8+Q9) : paliers de qualité montante + keyframes.
 *
 * Q8 : [UploadRegulator] marche les paliers **qualité seule 0-3** (le bord y est
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
 * Les deux sont **allumés par défaut** : [admit] ne laisse passer que les keyframes
 * et [jpegQuality] suit le palier courant. `configure` suit les Réglages (A/B
 * runtime) : éteint, le direct est exactement celui d'avant (tout passe, qualité
 * constante).
 *
 * Une seule instance par écran, nourrie aux points de feed (`onFrame`/`runFrame` du
 * Direct comme de la sonde iSee) : aucune logique d'envoi n'est dupliquée.
 * Pur JVM : ni Android, ni Compose — testable sans appareil.
 */
class LiveAdaptive(
    /** Qualité JPEG de la chaîne (65 le Direct 512 px, 55 iSee 320 px) : le palier 0. */
    private val baseQuality: Int,
    private val regulator: UploadRegulator = UploadRegulator(QualityTiers),
    private val scheduler: KeyframeScheduler = KeyframeScheduler(),
) {

    /** Poids montant adaptatif, allumé par défaut (Réglages, A/B runtime). */
    var uploadEnabled: Boolean = true
        private set

    /** Keyframes à la demande, allumés par défaut (Réglages, A/B runtime). */
    var keyframesEnabled: Boolean = true
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
     * (écart repris de l'échelle qualité seule [QualityTiers], jamais sous [MIN_QUALITY]).
     */
    val jpegQuality: Int
        get() {
            if (!uploadEnabled) return baseQuality
            val drop = QualityTiers.first().jpegQuality - regulator.plan.jpegQuality
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
        /**
         * Échelle qualité seule : le bord est fixé par CameraX (512 le Direct, 320
         * iSee — jamais touché ici, pas de rebind), seule la qualité descend, par
         * pas de 10, jusqu'à q−30. Le palier 3 (q35 sur le Direct, q25 iSee) n'est
         * pris que sous charge réseau persistante : part réseau lissée > 250 ms
         * pour sonder, puis gain mesuré ≥ 5 ms par palier pour garder — la
         * mécanique du régulateur, seuils inchangés. Dès que le lien respire
         * (< 200 ms) on remonte ; entre les deux on ne bouge pas, et un palier
         * refusé gèle toute sonde 6 frames : pas d'oscillation, plafond intact
         * quand ça passe.
         *
         * Pourquoi la détection reste OK : le bord ne bouge pas (masques,
         * distances et overlay gardent leur géométrie), le serveur rééchantillonne
         * de toute façon à la grille du modèle (518) — q35 à 512 px garde les
         * contours que le modèle voit — et le palier 3 pèse ~14 % d'octets en
         * moins que q45 (table mesurée : 13 486 contre 15 729) : il n'est gardé
         * que si ce poids en moins fait gagner du temps mesuré, jamais par
         * anticipation.
         */
        val QualityTiers: List<EncodePlan> = listOf(
            EncodePlan(512, 65), // 0 : qualité pleine, le comportement actuel
            EncodePlan(512, 55), // 1 : −10
            EncodePlan(512, 45), // 2 : −20
            EncodePlan(512, 35), // 3 : −30, charge persistante seule
        )

        /** Plancher de sécurité : un palier ne descend jamais sous q20. */
        const val MIN_QUALITY = 20
    }
}
