package dev.sam3vision.core.keyframe

/**
 * Ce que l'appelant **mesure** par frame — jamais des pixels (contrat complet :
 * `docs/KEYFRAME-SCHEDULER.md`). Les trois chiffres viennent de la propagation locale :
 *
 * - [reprojectionError] : désaccord moyen entre le masque warpé et l'image courante, rapporté à
 *   l'amplitude typique du fond, borné à `1` (u11). `0` = masque aligné, `1` = masque sans
 *   rapport. Le scheduler en fait une **EMA** : il réagit à une tendance, pas à une frame.
 * - [sceneChange] : rupture de scène par rapport au keyframe en cours (u10 ou histogramme).
 * - [objectsPresent] : `false` quand plus aucun masque n'est disponible à l'affichage.
 */
data class FrameSignal(
    val reprojectionError: Float,
    val sceneChange: Float,
    val objectsPresent: Boolean,
) {

    /**
     * Même signal, débarrassé du bruit d'un estimateur cassé : `NaN`, ±infini et valeurs hors
     * `[0, 1]` sont ramenés dans la plage. Un estimateur fou ne doit **jamais** bloquer les
     * keyframes (sinon l'écran garde un masque faux pour toujours).
     */
    val sanitized: FrameSignal
        get() {
            val error = clamp01(reprojectionError)
            val scene = clamp01(sceneChange)
            return if (error == reprojectionError && scene == sceneChange) {
                this
            } else {
                FrameSignal(error, scene, objectsPresent)
            }
        }

    companion object {
        /** Aucune mesure : masque présent, aucun mouvement détecté (banc, cadence forcée). */
        val Still = FrameSignal(reprojectionError = 0f, sceneChange = 0f, objectsPresent = true)

        /** Plus aucun masque : l'écran n'a plus rien à peindre. */
        val Lost = FrameSignal(reprojectionError = 1f, sceneChange = 1f, objectsPresent = false)
    }
}

/** Borne une mesure dans `[0, 1]` ; `NaN` vaut `0`, ±infini tombe sur la borne. */
internal fun clamp01(value: Float): Float =
    if (value.isNaN()) 0f else value.coerceIn(0f, 1f)

/** Motif d'une demande de keyframe (compté dans `KeyframeStats.triggerCount`). */
enum class Trigger {
    /** Aucun keyframe reçu depuis le démarrage de la session. */
    STARTUP,

    /** Rupture de scène : les masques en service ne valent plus rien. */
    SCENE_CHANGE,

    /** Plus aucun masque (objet sorti, segmentation muette) : il faut une frame clé neuve. */
    LOST,

    /** Erreur de reprojection lissée au-dessus du seuil pendant `errorFrames` frames. */
    REPROJECTION,

    /** Le keyframe en service a dépassé son plafond d'âge. */
    EXPIRED,

    /** Nouvelle tentative après un échec, repli écoulé. */
    RETRY,
    ;

    /**
     * Urgence : elle passe outre le **délai minimal** (l'écran affiche déjà du faux, attendre
     * 200 ms ne coûte que de la fausseté) et peut consommer l'unique jeton d'urgence du budget.
     * La coupure de scène et la perte de masque ne se réparent pas par propagation locale.
     */
    val isUrgent: Boolean
        get() = this == SCENE_CHANGE || this == LOST
}

/** Motif d'une attente : pourquoi le scheduler ne demande **rien** maintenant. */
enum class HoldReason {
    /** Un keyframe est déjà en vol — un seul à la fois (file GPU). */
    IN_FLIGHT,

    /** Délai minimal entre deux demandes non encore écoulé. */
    MIN_INTERVAL,

    /** Backend en panne : le repli impose d'attendre avant de rejouer. */
    BACKOFF,

    /** Budget de requêtes épuisé (et jeton d'urgence déjà pris). */
    BUDGET,

    /** Masques frais et alignés : la propagation locale suffit. */
    STABLE,
}

/**
 * Décision rendue par [KeyframeScheduler.onFrame] : une par frame caméra, jamais bloquante.
 */
sealed interface KeyframeDecision {

    /**
     * Envoyer la frame clé maintenant. [atMs] est l'instant de la décision : c'est lui qu'il faut
     * rappeler à [KeyframeScheduler.onKeyframeResult] (`requestAtMs`) pour apparier la réponse.
     */
    data class Request(val trigger: Trigger, val atMs: Long) : KeyframeDecision

    /** Propager localement : rien à envoyer. [retryInMs] = attente avant un nouvel examen. */
    data class Hold(val reason: HoldReason, val retryInMs: Long) : KeyframeDecision
}