package dev.sam3vision.app.live

import android.content.Context
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.distance.RadialDistortion
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Mire lunettes persistée : le LensSpec mesuré sur damier (protocole
 * `docs/LUNETTES-RECETTE-TEL.md` § 6) — l'étage SENSOR de la voie lunettes.
 * Prefs propres, comme CalibrationStore (codec Settings intouché).
 */
class GlassesMireStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Persiste la mire résolue par le solveur. */
    fun save(spec: LensSpec, atMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putFloat(KEY_H, spec.horizontalFovDeg)
            .putFloat(KEY_V, spec.verticalFovDeg)
            .putFloat(KEY_CX, spec.principalXFrac)
            .putFloat(KEY_CY, spec.principalYFrac)
            .putBoolean(KEY_HAS_DIST, spec.distortion != null)
            .putFloat(KEY_K1, spec.distortion?.k1 ?: 0f)
            .putFloat(KEY_K2, spec.distortion?.k2 ?: 0f)
            .putLong(KEY_AT, atMs)
            .apply()
        mireVersion.value += 1
    }

    /** Dernière mire persistée, null si jamais posée, effacée ou illisible. */
    fun lastMire(): LensSpec? = mireLensOf(
        MireFields(
            hFovDeg = prefs.getFloat(KEY_H, Float.NaN),
            vFovDeg = prefs.getFloat(KEY_V, Float.NaN),
            cx = prefs.getFloat(KEY_CX, Float.NaN),
            cy = prefs.getFloat(KEY_CY, Float.NaN),
            hasDistortion = prefs.getBoolean(KEY_HAS_DIST, false),
            k1 = prefs.getFloat(KEY_K1, Float.NaN),
            k2 = prefs.getFloat(KEY_K2, Float.NaN),
            atMs = prefs.getLong(KEY_AT, 0L),
        ),
    )

    /** Oublie la mire (retour au repli estimé). */
    fun clear() {
        prefs.edit()
            .remove(KEY_H).remove(KEY_V).remove(KEY_CX).remove(KEY_CY)
            .remove(KEY_HAS_DIST).remove(KEY_K1).remove(KEY_K2).remove(KEY_AT)
            .apply()
        mireVersion.value += 1
    }

    /**
     * Âge de la mire à [nowMs], null si aucune : l'appelant alerte au lieu
     * de mesurer en silence sur un repli.
     */
    fun mireAgeMs(nowMs: Long): Long? {
        if (lastMire() == null) return null
        val age = nowMs - prefs.getLong(KEY_AT, 0L)
        return if (age >= 0L) age else null
    }

    companion object {
        /**
         * Version bumpée à chaque save/clear, toutes instances (l'écran
         * Réglages et la sonde tiennent des stores distincts) : la bascule
         * `observeGlassesLive` s'y réabonne et repose le stack sans attendre
         * un on/off du direct.
         */
        val mireVersion = MutableStateFlow(0L)

        private const val PREFS = "glasses_mire"
        const val KEY_H = "hFovDeg"
        const val KEY_V = "vFovDeg"
        const val KEY_CX = "principalX"
        const val KEY_CY = "principalY"
        const val KEY_HAS_DIST = "hasDistortion"
        const val KEY_K1 = "k1"
        const val KEY_K2 = "k2"
        const val KEY_AT = "miredAtMs"
    }
}

/** Champs d'une mire, lus des prefs ou du bloc collé, avant validation. */
internal data class MireFields(
    val hFovDeg: Float,
    val vFovDeg: Float,
    val cx: Float,
    val cy: Float,
    val hasDistortion: Boolean,
    val k1: Float,
    val k2: Float,
    val atMs: Long,
)

/**
 * Valide des champs mire en LensSpec, null si un champ cloche. Pur, testé en JVM.
 * Le flux lunettes est natif portrait (720×1280) : sensorLandscape vaut false.
 */
internal fun mireLensOf(fields: MireFields): LensSpec? {
    if (fields.hFovDeg !in LensSpec.FOV_MIN..LensSpec.FOV_MAX) return null
    if (fields.vFovDeg !in LensSpec.FOV_MIN..LensSpec.FOV_MAX) return null
    if (!fields.cx.isFinite() || fields.cx !in 0f..1f) return null
    if (!fields.cy.isFinite() || fields.cy !in 0f..1f) return null
    if (!fields.k1.isFinite() || !fields.k2.isFinite()) return null
    if (fields.atMs <= 0L) return null
    val spec = LensSpec(
        fields.hFovDeg, fields.vFovDeg, sensorLandscape = false, fields.cx, fields.cy,
        if (fields.hasDistortion) RadialDistortion(fields.k1, fields.k2) else null,
    )
    return spec.takeIf { it.usable }
}

/** Première ligne du bloc que crache le solveur (`tools/solve_glasses_mire.py`). */
internal const val MIRE_BLOCK_HEADER = "mire v1"

/**
 * Lit le bloc collé du solveur, null si incomplet ou illisible. Pur, testé en JVM.
 * Le contrôle des bornes appartient à [mireLensOf], pas au parsing.
 */
internal fun parseMireBlock(text: String): MireFields? {
    val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    if (lines.firstOrNull() != MIRE_BLOCK_HEADER) return null
    val values = lines.drop(1)
        .filter { it.contains('=') }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    return MireFields(
        hFovDeg = values["hFovDeg"]?.toFloatOrNull() ?: return null,
        vFovDeg = values["vFovDeg"]?.toFloatOrNull() ?: return null,
        cx = values["cx"]?.toFloatOrNull() ?: return null,
        cy = values["cy"]?.toFloatOrNull() ?: return null,
        hasDistortion = values["hasDistortion"]?.toBooleanStrictOrNull() ?: return null,
        k1 = values["k1"]?.toFloatOrNull() ?: return null,
        k2 = values["k2"]?.toFloatOrNull() ?: return null,
        atMs = values["atMs"]?.toLongOrNull() ?: return null,
    )
}

/**
 * Calibrages persistés de la sonde : provenance optique + mire lunettes.
 * Un seul champ au ViewModel (plafond 500 lignes) pour les deux stores.
 */
internal class IseeLensFeeds(context: Context) {
    private val quality = LensQualityStore(context)
    private val mire = GlassesMireStore(context)

    /** Mémorise la provenance du calibrage courant. */
    fun note(origin: LensOrigin) = quality.note(origin)

    /** Dernière mire lunettes, null si jamais posée ou illisible. */
    fun mire(): LensSpec? = mire.lastMire()
}
