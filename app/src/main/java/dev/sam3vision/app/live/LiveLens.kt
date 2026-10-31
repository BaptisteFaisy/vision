package dev.sam3vision.app.live

import android.content.Context
import dev.sam3vision.core.distance.LensSpec
import kotlin.math.tan

/**
 * Provenance du calibrage retenu : le drapeau qualité des distances.
 * Le repli 67° n'est plus silencieux — l'appelant sait quand il mesure
 * faute de mieux ([ChosenLens.isFallback], [LensQualityStore]).
 */
enum class LensOrigin {
    /** Champ mesuré ou saisi dans les Réglages (prioritaire, borné caméra). */
    FORCED,

    /** Focale et géométrie lues sur le capteur réel. */
    SENSOR,

    /** Repli 67° : capteur muet et rien de réglé — à signaler, pas à taire. */
    FALLBACK,
}

/** Calibrage retenu + sa provenance (voir [chooseLens]). */
data class ChosenLens(val spec: LensSpec, val origin: LensOrigin) {
    /** Vrai quand on mesure sur le repli 67°, faute de mieux. */
    val isFallback: Boolean get() = origin == LensOrigin.FALLBACK
}

/**
 * Calibrage retenu pour les distances, extrait de `LiveViewModel` (limite de 500 lignes).
 *
 * Règle unique des deux écrans qui mesurent (Direct et sonde iSee — un seul
 * endroit, plus d'écart) : le champ des Réglages prime quand il est valide
 * (borné caméra, même garde que [LensSpec.fromSettings]), sinon la focale du
 * **capteur réel**, sinon le grand-angle de repli 67°. Un forcé hors bornes
 * ou un capteur inutilisable ne produit plus une spec inutilisable (que
 * `DistanceMeter.setLens` rejetterait en gardant l'ancien calibrage, un par
 * écran) : on retombe sur l'étage suivant.
 *
 * Le forcé garde l'aspect du capteur quand il est connu (le vertical suit la
 * vraie géométrie au lieu du 4:3 supposé) ; repli 4:3 sinon.
 */
internal fun chooseLens(forcedFovDeg: Float, sensor: LensSpec?): ChosenLens {
    val usableSensor = sensor?.takeIf { it.usable }
    if (forcedFovDeg.isFinite() && forcedFovDeg in LensSpec.FOV_MIN..LensSpec.FOV_MAX) {
        val aspect = usableSensor?.let(::sensorAspect) ?: DEFAULT_ASPECT
        val spec = LensSpec.fromHorizontalFov(forcedFovDeg, aspect).takeIf { it.usable }
            ?: LensSpec.fromHorizontalFov(forcedFovDeg)
        return ChosenLens(spec, LensOrigin.FORCED)
    }
    if (usableSensor != null) return ChosenLens(usableSensor, LensOrigin.SENSOR)
    return ChosenLens(LensSpec.Default, LensOrigin.FALLBACK)
}

/** Même choix, sans le drapeau (voir [chooseLens]). */
internal fun chosenLens(forcedFovDeg: Float, sensor: LensSpec?): LensSpec =
    chooseLens(forcedFovDeg, sensor).spec

/** Rapport tan(h/2)/tan(v/2) du capteur, 4:3 si illisible. */
private fun sensorAspect(sensor: LensSpec): Float {
    val halfVertical = tan(Math.toRadians(sensor.verticalFovDeg / 2.0))
    val halfHorizontal = tan(Math.toRadians(sensor.horizontalFovDeg / 2.0))
    if (!halfHorizontal.isFinite() || !halfVertical.isFinite() || halfVertical <= 1e-6) return DEFAULT_ASPECT
    val aspect = (halfHorizontal / halfVertical).toFloat()
    return if (aspect.isFinite() && aspect > 0f) aspect else DEFAULT_ASPECT
}

private const val DEFAULT_ASPECT = 4f / 3f

/**
 * Dernier drapeau optique persisté : la liaison caméra le note ([note], depuis
 * `applyLens`/`configure`), les Réglages le lisent ([lastOrigin]) pour alerter
 * quand les distances tournent sur le repli 67°. `null` = jamais noté.
 * Préférences propres, comme `CameraHeightStore` (codec `Settings` intouché).
 */
class LensQualityStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Mémorise la provenance du calibrage courant. */
    fun note(origin: LensOrigin) {
        prefs.edit().putString(KEY, origin.name).apply()
    }

    /** Dernière provenance notée, `null` si aucune. */
    fun lastOrigin(): LensOrigin? =
        prefs.getString(KEY, null)?.let { runCatching { LensOrigin.valueOf(it) }.getOrNull() }

    private companion object {
        const val PREFS = "lens_quality"
        const val KEY = "origin"
    }
}
