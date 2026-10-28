package dev.sam3vision.core.traverse

import dev.sam3vision.core.model.SegObject

/**
 * Seuils **figés** du filtre temporel de traversée.
 *
 * Trois scores, trois décisions : [DETECT_SCORE] admet une détection au comptage,
 * [DISPLAY_SCORE] autorise son affichage, [DROP_SCORE] fait perdre la frame à une
 * piste confirmée. La bande entre [DROP_SCORE] et [DISPLAY_SCORE] est une zone morte :
 * une piste affichée y reste affichée, une piste cachée y reste cachée — aucun
 * scintillement quand le score hésite autour d'un seuil unique.
 */
object TraverseThresholds {

    /** Score minimal pour qu'une détection compte (piste candidate). */
    const val DETECT_SCORE = 0.5f

    /** Score à atteindre au moins une fois pour afficher une piste confirmée. */
    const val DISPLAY_SCORE = 0.6f

    /** Sous ce score, une piste confirmée ne « voit » pas la frame (tick manquant). */
    const val DROP_SCORE = 0.4f

    /** Détections **consécutives** pour confirmer une piste. */
    const val CONFIRM_DETECTIONS = 2

    /** Frames manquantes tolérées avant de décrocher une piste confirmée. */
    const val MAX_MISSING_FRAMES = 2
}

/**
 * Filtre temporel pur, client-only : anti-scintillement des détections `/live`.
 *
 * Une étiquette devient piste après [TraverseThresholds.CONFIRM_DETECTIONS]
 * détections consécutives ≥ [TraverseThresholds.DETECT_SCORE], s'affiche une fois
 * vue ≥ [TraverseThresholds.DISPLAY_SCORE], puis survit à
 * [TraverseThresholds.MAX_MISSING_FRAMES] frames manquantes (boîte gelée) avant de
 * décrocher. Clé = label exact du backend (`/live` n'a pas d'id stable).
 *
 * Pur Kotlin/JVM, sans état Android : consomme les
 * [dev.sam3vision.core.model.LiveFrame.objects] après décodage ; le câblage vit
 * dans le chemin `/live` — voir le stream intégration.
 */
class TraverseState {

    private data class Track(
        var seen: Int = 0,
        var missing: Int = 0,
        var confirmed: Boolean = false,
        var shown: Boolean = false,
        var last: SegObject,
    )

    private val tracks = LinkedHashMap<String, Track>()

    /**
     * Avance d'une frame, renvoie les pistes affichables (confirmées + montrées),
     * boîte courante si vue, gelée pendant les trous tolérés. Ordre stable.
     */
    fun update(detections: List<SegObject>): List<SegObject> {
        val best = bestByLabel(detections)
        for ((label, track) in tracks.toList()) {
            val detection = best[label]
            if (detection != null && detection.score >= aliveBar(track)) {
                see(track, detection, detection.score)
            } else if (track.confirmed) {
                track.missing += 1
                if (track.missing > TraverseThresholds.MAX_MISSING_FRAMES) tracks.remove(label)
            } else {
                tracks.remove(label)
            }
        }
        for ((label, detection) in best) {
            if (label !in tracks && detection.score >= TraverseThresholds.DETECT_SCORE) {
                val track = Track(seen = 1, last = detection)
                tracks[label] = track
                confirm(track, detection.score)
            }
        }
        return tracks.values.filter { it.confirmed && it.shown }.map { it.last }
    }

    /** La piste [label] est-elle confirmée (affichée ou non) ? */
    fun isConfirmed(label: String): Boolean = tracks[label]?.confirmed ?: false

    /** Étiquettes confirmées (affichées ou en attente du seuil d'affichage). */
    val confirmedLabels: Set<String> get() = tracks.filterValues { it.confirmed }.keys

    /** Vide toutes les pistes (changement de scène, arrêt du direct). */
    fun reset() {
        tracks.clear()
    }

    private fun aliveBar(track: Track): Float =
        if (track.confirmed) TraverseThresholds.DROP_SCORE else TraverseThresholds.DETECT_SCORE

    private fun see(track: Track, detection: SegObject, score: Float) {
        track.seen += 1
        track.missing = 0
        track.last = detection
        confirm(track, score)
    }

    private fun confirm(track: Track, score: Float) {
        if (!track.confirmed && track.seen >= TraverseThresholds.CONFIRM_DETECTIONS) {
            track.confirmed = true
        }
        if (track.confirmed && score >= TraverseThresholds.DISPLAY_SCORE) track.shown = true
    }

    private fun bestByLabel(detections: List<SegObject>): Map<String, SegObject> =
        detections.groupBy { it.label }.mapValues { (_, group) -> group.maxBy { it.score } }
}
