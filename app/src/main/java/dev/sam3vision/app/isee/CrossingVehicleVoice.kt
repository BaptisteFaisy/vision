package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingCue
import dev.sam3vision.core.crossing.CrossingCueQuiet
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.speech.SpeechLanguage
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
 * les états moteurs se disent, après le feu et avant les personnes
 * ([crossingPhraseOf]). Vélos et trottinettes exclus, comme en normal
 * (`CycleWatcher` les annonce en normal ; en Traversée, pas de voix cycles —
 * le veto trafic, lui, ne suit plus que voiture/bus/vélo depuis le 31/10).
 */
internal object CrossingVehicleWatch {

    private val watcher = VehicleStateWatcher()
    private val quiet = CrossingCueQuiet()

    /**
     * Mouvement brut de la dernière frame vue ([VehicleStateWatcher]) :
     * le scan traversée y lit « un véhicule est en mouvement dans cette
     * vue ». Lu après [update] (qui nourrit le guetteur), `false` avant.
     */
    val movingNow: Boolean get() = watcher.movingThisFrame

    /**
     * Voix de la frame, `null` = silence (rien, déjà dit, le feu parle,
     * ou ordre du feu à laisser entier). [cue] non nul = le feu parle sur
     * cette frame : l'ordre est mémorisé et la voix se tait. Mouvement
     * proche ⇒ l'alerte « Stop, … » (urgente côté sonde, lue dans la
     * phrase), sinon la phrase douce. [noLight] vrai = traversée sans feu :
     * le mouvement se dit à toute portée (ordre user du 31/10 — même au-delà
     * de 15 m, dès qu'une voiture en mouvement est détectée), sinon 15 m.
     * Le guetteur n'est nourri que hors silence (miroir de l'annonceur des
     * personnes) : une annonce mûrie pendant le quiet ne part pas en
     * cooldown sourd — elle se reconfirme sur les frames suivantes et parle
     * dès la parole rendue.
     */
    fun update(
        detections: List<Detection>,
        readings: List<DistanceReading>,
        atMs: Long,
        language: SpeechLanguage,
        cue: CrossingCue? = null,
        noLight: Boolean = false,
    ): String? {
        if (quiet.hush(cue, atMs)) return null
        val range = if (noLight) Float.POSITIVE_INFINITY else VehicleStateWatcher.MOVING_METERS
        val found = watcher.update(detections, readings, atMs, language, movingMeters = range) ?: return null
        return found.safetyPhrase ?: found.phrase
    }

    /** Mode coupé : la prochaine traversée ré-annonce sa scène. */
    fun reset() {
        watcher.reset()
        quiet.reset()
    }
}
