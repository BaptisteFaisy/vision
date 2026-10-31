package dev.sam3vision.app.live

import android.content.Context
import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.distance.LensSpec
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
 * Esquisse d'étalonnage automatique (non câblée) : une visée mûre — fraîche et
 * assez grande pour une tolérance honnête — est proposée telle quelle, sans
 * rien résoudre ici. Le solveur réclame une taille et une distance d'aide
 * dignes de foi (pas d'hypothèse « adulte à 2 m » : un faux 1,75 m biaise
 * toutes les distances) ; le déclenchement (sonde/direct), la source de
 * confiance et l'écriture Réglages vivent hors de ce fichier.
 */
object AutoCalibration {

    /**
     * Boîte minimale d'une proposition auto (100 px) : en dessous, ±1 px de
     * quantification seul vaut déjà ±1° — un auto qui calibre flou vaut moins
     * qu'un guidé qui fait viser grand.
     */
    const val MIN_AUTO_BOX_PX = 100f

    /**
     * Visée mûre pour un solve auto à [nowMs], `null` sinon (périmée ou trop
     * petite). La taille et la distance de l'aide restent à fournir par
     * l'appelant — jamais devinées (voir [CalibrationSolver]).
     */
    fun propose(snap: CalibrationSnap?, nowMs: Long): CalibrationSnap? {
        if (!CalibrationSnapshots.isFresh(snap, nowMs)) return null
        if (snap == null || !snap.boxHeightPx.isFinite() || snap.boxHeightPx < MIN_AUTO_BOX_PX) return null
        return snap
    }
}

/**
 * Étalonnage mesuré persisté : le champ résolu, son incertitude et sa date —
 * l'état que les Réglages affichent (« mesuré 67,2° ±0,5°, il y a 3 j ») au
 * lieu d'un simple témoin.
 */
data class SavedCalibration(
    val fovDeg: Float,
    val toleranceDeg: Float,
    val atMs: Long,
)

/**
 * Témoin d'étalonnage persisté : vrai quand le champ des Réglages vient d'une
 * mesure (solveur), faux quand il est saisi à la main ou effacé — à côté de la
 * valeur, dans ses propres prefs (comme `CameraHeightStore`, pas dans `Settings`
 * dont le codec chiffré a sa parité à garder). La mesure elle-même (champ,
 * tolérance, date) est persistée avec ([saveCalibration]) : « jamais calibré »
 * se distingue d'« étalonnage effacé », et l'âge s'alerte.
 */
class CalibrationStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Le champ courant a-t-il été mesuré (plutôt que saisi) ? */
    fun isCalibrated(): Boolean = prefs.getBoolean(KEY, false)

    /**
     * Mémorise la provenance du champ courant. `false` (saisie manuelle,
     * effacement) oublie aussi la mesure persistée : un champ tapé à la main
     * n'est plus « mesuré 67,2° ±0,5° ».
     */
    fun setCalibrated(done: Boolean) {
        if (done) {
            prefs.edit().putBoolean(KEY, true).apply()
        } else {
            prefs.edit().remove(KEY).remove(KEY_FOV).remove(KEY_TOLERANCE).remove(KEY_AT).apply()
        }
    }

    /** Persiste la mesure du solveur (champ + tolérance + date) et lève le témoin. */
    fun saveCalibration(fovDeg: Float, toleranceDeg: Float, atMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putBoolean(KEY, true)
            .putFloat(KEY_FOV, fovDeg)
            .putFloat(KEY_TOLERANCE, toleranceDeg)
            .putLong(KEY_AT, atMs)
            .apply()
    }

    /** Dernière mesure persistée, `null` si jamais calibré, effacé ou illisible. */
    fun lastCalibration(): SavedCalibration? {
        if (!prefs.getBoolean(KEY, false)) return null
        val fov = prefs.getFloat(KEY_FOV, Float.NaN)
        val tolerance = prefs.getFloat(KEY_TOLERANCE, Float.NaN)
        val at = prefs.getLong(KEY_AT, 0L)
        if (!fov.isFinite() || fov !in LensSpec.FOV_MIN..LensSpec.FOV_MAX) return null
        if (!tolerance.isFinite() || tolerance < 0f) return null
        if (at <= 0L) return null
        return SavedCalibration(fov, tolerance, at)
    }

    /**
     * Âge de la mesure à [nowMs], `null` si jamais calibré : l'appelant alerte
     * (« jamais calibré : les distances tournent au capteur/repli ») au lieu
     * de mesurer en silence sur un champ par défaut.
     */
    fun calibrationAgeMs(nowMs: Long): Long? {
        val at = lastCalibration()?.atMs ?: return null
        val age = nowMs - at
        return if (age >= 0L) age else null
    }

    private companion object {
        const val PREFS = "calibration"
        const val KEY = "fovCalibrated"
        const val KEY_FOV = "fovDeg"
        const val KEY_TOLERANCE = "fovToleranceDeg"
        const val KEY_AT = "calibratedAtMs"
    }
}
