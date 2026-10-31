package dev.sam3vision.app.live

import android.content.Context
import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.distance.Pinhole
import dev.sam3vision.core.model.Detection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Visée d'étalonnage : la plus grande personne vue par le direct, en pixels de
 * l'image analysée ([imageWidthPx], 512 sur le direct), horodatée. L'écran
 * Réglages la lit pour résoudre le champ ([CalibrationSolver]) — pas de tuyau
 * entre ViewModels, un état partagé suffit, la visée est éphémère.
 */
data class CalibrationSnap(
    val boxHeightPx: Float,
    val imageWidthPx: Int,
    val atMs: Long,
)

/**
 * Dernière personne cadrée par le direct, en flux : le traversée y verse chaque
 * frame publiée ([note]), les Réglages y lisent ([latest]). En mémoire seulement :
 * une visée ne survit pas au kill — et ne doit pas (repointer, c'est vérifier).
 */
object CalibrationSnapshots {

    /** Score minimal : une personne devinée à 0,2 ne calibre rien. */
    const val MIN_SCORE = 0.5f

    /** Fraîcheur maximale d'une visée à la résolution (5 min, large). */
    const val MAX_AGE_MS = 300_000L

    /** Premiers mots canoniques qui désignent une personne (voir [KnownSizes]). */
    internal val PERSON_WORDS = setOf(
        "person", "personne", "homme", "femme", "enfant", "human", "humain",
        "man", "woman", "child", "kid", "seated", "sitting",
    )

    private val _latest = MutableStateFlow<CalibrationSnap?>(null)

    /** Dernière visée, `null` si personne n'a été cadré depuis [clear]. */
    val latest: StateFlow<CalibrationSnap?> = _latest

    /**
     * Retient la plus grande personne de [detections] ([width] × [height], à
     * [atMs]) : boîte la plus haute, non coupée, score tenu. Rien sinon (la
     * visée précédente survit — un cadre vide n'efface pas une visée).
     */
    fun note(detections: List<Detection>, width: Int, height: Int, atMs: Long) {
        if (width <= 0 || height <= 0) return
        val tallest = detections
            .filter { it.score >= MIN_SCORE && it.box.size >= 4 && isPerson(it.label) }
            .filter { !Pinhole.isVerticallyCut(it.box, height) }
            .maxByOrNull { it.box[3] - it.box[1] }
            ?: return
        _latest.value = CalibrationSnap(tallest.box[3] - tallest.box[1], width, atMs)
    }

    /** Oublie la visée (nouvelle séance). */
    fun clear() {
        _latest.value = null
    }

    /** Vrai si [snap] est résoluble à [nowMs] (fraîcheur [MAX_AGE_MS]). */
    fun isFresh(snap: CalibrationSnap?, nowMs: Long): Boolean =
        snap != null && nowMs >= snap.atMs && nowMs - snap.atMs <= MAX_AGE_MS

    private fun isPerson(label: String): Boolean =
        PERSON_WORDS.contains(KnownSizes.canonical(label).substringBefore(' '))
}

/**
 * Témoin d'étalonnage persisté : vrai quand le champ des Réglages vient d'une
 * mesure (solveur), faux quand il est saisi à la main ou effacé — à côté de la
 * valeur, dans ses propres prefs (comme `CameraHeightStore`, pas dans `Settings`
 * dont le codec chiffré a sa parité à garder).
 */
class CalibrationStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Le champ courant a-t-il été mesuré (plutôt que saisi) ? */
    fun isCalibrated(): Boolean = prefs.getBoolean(KEY, false)

    /** Mémorise la provenance du champ courant. */
    fun setCalibrated(done: Boolean) {
        prefs.edit().putBoolean(KEY, done).apply()
    }

    private companion object {
        const val PREFS = "calibration"
        const val KEY = "fovCalibrated"
    }
}
