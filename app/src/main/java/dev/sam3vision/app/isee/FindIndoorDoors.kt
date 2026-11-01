package dev.sam3vision.app.isee

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.AntiRepeat
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.picto.FindIndoorClips
import dev.sam3vision.core.picto.FindIndoorLevels
import dev.sam3vision.core.picto.FindIndoorTarget
import dev.sam3vision.core.picto.PictoPlaceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Porte et murs dedans (spec v4 : le plus bas de la chaîne dedans).
 *
 * La plus proche porte ou mur lu dans les distances SAM (suivis dedans :
 * prompts indoor, pas de chasse) : le cadran seul, sans distance — dedans,
 * « où » suffit. Pur, testé en JVM.
 */
object IndoorDoorWall {

    /** Porte ou mur : deux voix, deux séries de clips. */
    enum class Kind { DOOR, WALL }

    /** Le constat : nature, cadran, texte et clip anglais. */
    data class Cue(val kind: Kind, val clock: Int, val text: String, val clipId: String)

    /**
     * Constat pour ces lectures ([width]/[height] : taille frame, boîtes en
     * pixels), `null` si ni porte ni mur lisible. La plus proche gagne
     * ([DistanceReading.meters]) ; une boîte illisible est ignorée.
     */
    fun cueOf(readings: List<DistanceReading>, width: Int, height: Int): Cue? {
        if (width <= 0 || height <= 0) return null
        val nearest = readings
            .filter { it.box.size >= 4 && kindOf(it.label) != null }
            .minByOrNull { it.meters } ?: return null
        val kind = kindOf(nearest.label) ?: return null
        val centerX = ((nearest.box[0] + nearest.box[2]) / 2f) / width
        val clock = PositionText.clockHour(centerX.coerceIn(0f, 1f))
        val clip = when (kind) {
            Kind.DOOR -> FindIndoorClips.doorAt(clock)
            Kind.WALL -> FindIndoorClips.wallAt(clock)
        }
        return Cue(kind, clock, clip.text, clip.id)
    }

    /** Porte, mur, ou rien (autres labels, pièces sans porte ni mur). */
    private fun kindOf(label: String): Kind? = when (FindIndoorTarget.fromSpoken(KnownSizes.canonical(label))) {
        FindIndoorTarget.DOOR -> Kind.DOOR
        FindIndoorTarget.WALL -> Kind.WALL
        else -> null
    }
}

/**
 * Guetteur porte et murs : le changement (nature ou cadran) parle aussitôt,
 * le même constat attend l'anti-répétition ([AntiRepeat]). Remis à
 * zéro avec les autres resets de séance. Testé en JVM.
 */
class IndoorDoorWatcher {
    private var lastKind: IndoorDoorWall.Kind? = null
    private var lastClock: Int? = null

    /**
     * Constat à dire pour ce [cue] (`null` = plus rien : le guetteur
     * oublie). [allow] est la signature de la table centrale (clé, horloge).
     */
    fun onCue(cue: IndoorDoorWall.Cue?, nowMs: Long, allow: (key: String, nowMs: Long) -> Boolean): IndoorDoorWall.Cue? {
        if (cue == null) {
            reset()
            return null
        }
        if (cue.kind != lastKind || cue.clock != lastClock) {
            lastKind = cue.kind
            lastClock = cue.clock
            return cue
        }
        return if (allow(AntiRepeat.KEY_DOOR_WALL, nowMs)) cue else null
    }

    /** Oublie le constat (nouvelle séance, sortie de dedans). */
    fun reset() {
        lastKind = null
        lastClock = null
    }
}

/**
 * Guetteur porte et murs de la sonde, miroir du trottoir : les lectures de
 * distance lues dans l'état, sans toucher à `LiveProbe`. Dedans uniquement
 * ([placeMode]) et jamais en Traversée ; le constat part en clip anglais
 * ([post], signature de VoiceBus), au cran porte et murs ([FindIndoorLevels]).
 * Lecture séquentielle, comme le trottoir : un constat confirmé ne s'annule pas.
 */
fun LiveProbe.observeIndoorDoors(
    scope: CoroutineScope,
    post: (level: Int, text: String, clipId: String?) -> Unit,
    allow: (key: String, nowMs: Long) -> Boolean,
    placeMode: () -> PictoPlaceMode,
): IndoorDoorWatcher {
    val watcher = IndoorDoorWatcher()
    scope.launch {
        state.collect { st ->
            if (st.crossingActive || placeMode() != PictoPlaceMode.DEDANS) return@collect
            val readings = st.distanceLayer?.readings ?: return@collect
            val cue = IndoorDoorWall.cueOf(readings, st.width, st.height)
            val out = watcher.onCue(cue, System.currentTimeMillis(), allow) ?: return@collect
            post(FindIndoorLevels.DOOR_WALL, out.text, out.clipId)
        }
    }
    return watcher
}
