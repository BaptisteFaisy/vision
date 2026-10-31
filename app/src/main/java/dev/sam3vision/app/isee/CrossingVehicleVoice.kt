package dev.sam3vision.app.isee

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.FrameMotion
import dev.sam3vision.core.traverse.VehicleStateWatcher

/**
 * Voix des véhicules moteurs en mode Traversée, une par processus (même
 * garde des 500 lignes que `CrossingPeopleWatch`) : mouvement proche
 * (≤ 8 m) ⇒ « Stop, car moving. » (langue de la voix, anglais par
 * défaut — le seul stop d'iSee, ordre user du 31/10), mouvement loin et
 * arrêt ⇒ phrase douce (« Car moving, 12 meters away. » / « Car stopped,
 * 2 meters away. »). Le même [VehicleStateWatcher] que le mode normal —
 * mêmes confirmations (2 frames), mêmes silences (4 s en mouvement,
 * 10 s à l'arrêt), mêmes entrées (détections reconstruites des lectures).
 *
 * En mode normal, c'est `observeObstacles` qui parle ; en Traversée, cet
 * observateur est coupé (deux voix se marcheraient dessus) et c'est ici que
 * les états moteurs se disent, devant l'évitement et le statut
 * ([crossingPhraseOf], ordre user du 31/10 — jamais tus, même sur un ordre
 * du feu nouveau). Vélos et trottinettes exclus, comme en normal
 * (`CycleWatcher` les annonce en normal ; en Traversée, pas de voix cycles —
 * le veto trafic, lui, ne suit plus que voiture/bus/vélo depuis le 31/10).
 */
internal object CrossingVehicleWatch {

    private val watcher = VehicleStateWatcher()

    /**
     * Mouvement brut de la dernière frame vue ([VehicleStateWatcher]) :
     * le scan traversée y lit « un véhicule est en mouvement dans cette
     * vue ». Lu après [update] (qui nourrit le guetteur), `false` avant.
     */
    val movingNow: Boolean get() = watcher.movingThisFrame

    /**
     * Voix de la frame, `null` = silence (rien ou déjà dit). Mouvement
     * proche ⇒ l'alerte « Stop, … » (urgente côté sonde, lue dans la
     * phrase), sinon la phrase douce. Nourri à chaque frame, ordre du feu
     * ou pas : la voix véhicules passe devant le statut, elle ne se tait
     * jamais pour lui. [noLight] vrai = traversée sans feu : le mouvement
     * se dit à toute portée (ordre user du 31/10 — même au-delà de 15 m,
     * dès qu'une voiture en mouvement est détectée), sinon 15 m.
     * [motion] (`null` = pas de signal fond) fait juger chaque piste par
     * le fond : une boîte qui suit la caméra (pan sur une voiture garée)
     * ne conclut plus le mouvement — sans feu seul.
     */
    fun update(
        detections: List<Detection>,
        readings: List<DistanceReading>,
        atMs: Long,
        language: SpeechLanguage,
        noLight: Boolean = false,
        motion: FrameMotion? = null,
    ): String? {
        val range = if (noLight) Float.POSITIVE_INFINITY else VehicleStateWatcher.MOVING_METERS
        val felt = if (noLight) motion else null
        val found = watcher.update(detections, readings, atMs, language, motion = felt, movingMeters = range) ?: return null
        return found.safetyPhrase ?: found.phrase
    }

    /** Mode coupé : la prochaine traversée ré-annonce sa scène. */
    fun reset() {
        watcher.reset()
    }
}
