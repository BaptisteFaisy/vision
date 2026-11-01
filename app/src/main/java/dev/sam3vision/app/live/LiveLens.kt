package dev.sam3vision.app.live

import android.content.Context
import dev.sam3vision.app.glasses.connectedGlassesStreamLens
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

    /** Focale et géométrie lues sur le capteur réel (ou mire lunettes). */
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
 *
 * [fromGlasses] = la frame mesurée vient des lunettes : le champ lunettes forcé
 * prime (aspect 9:16 du flux), sinon [glasses] (mire, `null` = pas encore
 * calibrée), sinon le profil flux du modèle détecté (`null` sans modèle
 * reconnu), sinon le repli estimé [LensSpec.Glasses] — jamais le capteur du
 * téléphone (voir le protocole mire de `docs/LUNETTES-RECETTE-TEL.md`). Par
 * défaut (téléphone), le routage est inchangé.
 */
internal fun chooseLens(
    forcedFovDeg: Float,
    sensor: LensSpec?,
    glasses: LensSpec? = null,
    fromGlasses: Boolean = false,
): ChosenLens {
    if (fromGlasses) return chooseGlassesLens(forcedFovDeg, glasses)
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

/**
 * Voie lunettes : le forcé prime quand il donne un calibrage utilisable (un
 * champ extrême sort le vertical 9:16 des bornes caméra), sinon la mire, sinon
 * le profil flux du modèle détecté, sinon le repli estimé — jamais le capteur
 * du téléphone. Le profil modèle reste un estimé ([LensOrigin.FALLBACK]) :
 * seule la mire mesure vraiment.
 */
private fun chooseGlassesLens(forcedFovDeg: Float, glasses: LensSpec?): ChosenLens {
    if (forcedFovDeg.isFinite() && forcedFovDeg in LensSpec.FOV_MIN..LensSpec.FOV_MAX) {
        LensSpec.fromGlassesSettings(forcedFovDeg).takeIf { it.usable }?.let {
            return ChosenLens(it, LensOrigin.FORCED)
        }
    }
    val usableGlasses = glasses?.takeIf { it.usable }
    if (usableGlasses != null) return ChosenLens(usableGlasses, LensOrigin.SENSOR)
    connectedGlassesStreamLens()?.let { return ChosenLens(it, LensOrigin.FALLBACK) }
    return ChosenLens(LensSpec.Glasses, LensOrigin.FALLBACK)
}

/** Même choix, sans le drapeau (voir [chooseLens]). */
internal fun chosenLens(
    forcedFovDeg: Float,
    sensor: LensSpec?,
    glasses: LensSpec? = null,
    fromGlasses: Boolean = false,
): LensSpec = chooseLens(forcedFovDeg, sensor, glasses, fromGlasses).spec

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
