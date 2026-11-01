package dev.sam3vision.core.distance

import kotlin.math.atan
import kotlin.math.tan

/**
 * Calibrage optique de la caméra, en **champs de vision** (degrés) du capteur.
 *
 * Le sténopé n'a besoin que d'une chose : la focale **en pixels** de l'image
 * analysée. Elle se déduit du champ de vision de l'axe qui porte la *largeur* de
 * cette image — le direct redresse chaque frame, une image portrait livre donc
 * en largeur l'axe *vertical* du capteur ([sensorLandscape] dit dans quel sens
 * le capteur est natif).
 *
 * Le **centre optique** ([principalXFrac], [principalYFrac], en fractions
 * d'image) n'est pas toujours le centre géométrique : le driver le donne via
 * `LENS_INTRINSIC_CALIBRATION` quand il le connaît. Le plan de sol et le nuage
 * de points le lisent ([principalPx]) ; le sténopé, qui ne voit que des
 * hauteurs, n'en a pas besoin.
 *
 * La **distorsion radiale** ([distortion], mesurée sur mire, `null` sans mesure)
 * redresse les boîtes avant toute mesure ([Distortion]) : le sténopé et le plan
 * de sol travaillent alors sur des rayons droits.
 */
data class LensSpec(
    val horizontalFovDeg: Float,
    val verticalFovDeg: Float,
    val sensorLandscape: Boolean = true,
    /** Centre optique horizontal, en fraction de largeur (0,5 = centré). */
    val principalXFrac: Float = 0.5f,
    /** Centre optique vertical, en fraction de hauteur (0,5 = centré). */
    val principalYFrac: Float = 0.5f,
    /**
     * Distorsion radiale mesurée sur mire, `null` **sans mesure** (le redressement
     * est alors l'identité : sténopé et plan de sol lisent des boîtes brutes,
     * biais de quelques pixels aux bords sur grand-angle). À ne pas confondre
     * avec des coeffs nuls (objectif *mesuré* sans distorsion).
     */
    val distortion: RadialDistortion? = null,
) {

    /** Vrai quand une mesure mire est posée (`false` = `distortion == null`, boîtes lues brutes). */
    val hasMeasuredDistortion: Boolean
        get() = distortion != null

    /** Vrai si les deux angles, le centre optique et la distorsion sont plausibles. */
    val usable: Boolean
        get() = horizontalFovDeg in FOV_MIN..FOV_MAX && verticalFovDeg in FOV_MIN..FOV_MAX &&
            principalXFrac.isFinite() && principalYFrac.isFinite() &&
            principalXFrac in 0f..1f && principalYFrac in 0f..1f &&
            (distortion == null || (distortion.k1.isFinite() && distortion.k2.isFinite()))

    /** Champ de vision porté par la largeur d'une image `[width]`×`[height]`. */
    fun widthFovDeg(width: Int, height: Int): Float {
        val imageLandscape = width >= height
        return if (imageLandscape == sensorLandscape) horizontalFovDeg else verticalFovDeg
    }

    /**
     * Focale en pixels pour une image `[width]`×`[height]` : `largeur / 2·tan(fov/2)`.
     * `0f` si le calibrage est inutilisable (angle nul, champ hors bornes, image vide).
     */
    fun focalPx(width: Int, height: Int): Float {
        if (!usable || width <= 0 || height <= 0) return 0f
        val halfTangent = tan(Math.toRadians(widthFovDeg(width, height) / 2.0))
        return if (halfTangent <= 1e-6) 0f else (width / 2.0 / halfTangent).toFloat()
    }

    /**
     * Centre optique en pixels d'une image `[width]`×`[height]`, au sens des
     * centres de pixels (`0,5` → `(size − 1) / 2`, comme `CameraIntrinsics`).
     * Les fractions se conservent par mise à l'échelle : une frame réduite
     * garde le même centre relatif que le capteur.
     */
    fun principalPx(width: Int, height: Int): Pair<Float, Float> =
        Pair(principalXFrac * (width - 1), principalYFrac * (height - 1))

    companion object {
        /** En deçà, ce n'est plus une caméra (téléobjectif extrême). */
        const val FOV_MIN = 15f

        /** Au-delà, c'est un fish-eye : la projection sténopé n'y tient plus. */
        const val FOV_MAX = 130f

        /** Grand-angle de téléphone typique, capteur 4:3. */
        const val DEFAULT_H_FOV_DEG = 67f

        /** Repli quand le capteur ne dit rien et que l'utilisateur n'a rien réglé. */
        val Default: LensSpec = fromHorizontalFov(DEFAULT_H_FOV_DEG)

        /** Champ horizontal du flux lunettes 9:16, déduit des 100° diagonaux constructeur. */
        const val GLASSES_H_FOV_DEG = 60.6f

        /** Rapport largeur/hauteur du flux des lunettes (720×1280 portrait). */
        const val GLASSES_ASPECT = 9f / 16f

        /**
         * Repli lunettes (≈ 60,6° × 92,1°, capteur natif portrait) : la diagonale
         * de 100° annoncée par le constructeur, ramenée au 9:16 du flux. Estimation
         * en attendant la mire (protocole `docs/LUNETTES-RECETTE-TEL.md` § 6) : le
         * flux peut être recadré ou stabilisé — le réglage l'affine, la mire le
         * remplacera.
         */
        val Glasses: LensSpec = fromHorizontalFov(GLASSES_H_FOV_DEG, GLASSES_ASPECT)
            .copy(sensorLandscape = false)

        /**
         * Calibrage déduit d'un seul angle horizontal (réglage utilisateur), pour un
         * capteur d'aspect [aspect] (4:3 par défaut) : le champ vertical suit la même
         * focale que l'horizontal.
         */
        fun fromHorizontalFov(deg: Float, aspect: Float = 4f / 3f): LensSpec {
            val safeAspect = if (aspect > 0f) aspect else 4f / 3f
            val halfTangent = tan(Math.toRadians(deg / 2.0)) / safeAspect
            val vertical = Math.toDegrees(2.0 * atan(halfTangent)).toFloat()
            return LensSpec(deg, vertical)
        }

        /**
         * Calibrage des Réglages : le champ horizontal forcé par l'utilisateur prime
         * (borné caméra, sinon ignoré), sinon le grand-angle de repli. Même règle que
         * le choix capteur côté app : forcé valide, sinon capteur réel, sinon repli 67°.
         */
        fun fromSettings(forcedFovDeg: Float): LensSpec =
            if (forcedFovDeg in FOV_MIN..FOV_MAX) fromHorizontalFov(forcedFovDeg) else Default

        /**
         * Calibrage lunettes des Réglages : le champ horizontal forcé prime (borné
         * caméra, aspect 9:16 du flux), sinon le repli [Glasses]. Miroir de
         * [fromSettings], qui suppose un capteur téléphone 4:3.
         */
        fun fromGlassesSettings(forcedFovDeg: Float): LensSpec =
            if (forcedFovDeg in FOV_MIN..FOV_MAX) fromHorizontalFov(forcedFovDeg, GLASSES_ASPECT)
                .copy(sensorLandscape = false)
            else Glasses

        /** Calibrage à partir d'une focale (mm) et d'un capteur (mm), `null` si incohérent. */
        fun fromSensor(focalMm: Float, sensorWidthMm: Float, sensorHeightMm: Float): LensSpec? {
            if (focalMm <= 0f || sensorWidthMm <= 0f || sensorHeightMm <= 0f) return null
            val horizontal = Math.toDegrees(2.0 * atan(sensorWidthMm / 2.0 / focalMm)).toFloat()
            val vertical = Math.toDegrees(2.0 * atan(sensorHeightMm / 2.0 / focalMm)).toFloat()
            return LensSpec(horizontal, vertical, sensorLandscape = sensorWidthMm >= sensorHeightMm)
        }

        /**
         * Calibrage mesuré sur mire : focale (mm) et capteur (mm) relevés pour une
         * image `[imageWidth]`×`[imageHeight]`. Délègue à [fromSensor] en supposant
         * que la **zone active couvre toute l'image** (pas de recadrage capteur :
         * les millimètres du tableau valent pour l'image entière) — sinon passer
         * par [Optics.activeSensor]. `null` si une entrée est incohérente.
         * La distorsion mesurée en même temps se pose ensuite par `.copy(distortion = …)`.
         */
        fun fromMeasured(
            focalMm: Float,
            sensorWidthMm: Float,
            sensorHeightMm: Float,
            imageWidth: Int,
            imageHeight: Int,
        ): LensSpec? {
            if (imageWidth <= 0 || imageHeight <= 0) return null
            return fromSensor(focalMm, sensorWidthMm, sensorHeightMm)
        }

        /**
         * Calibrage depuis une focale **en pixels** (`fx`, `fy`) et un centre optique
         * (`cx`, `cy`), dans les coordonnées de la zone active
         * `[refWidthPx]`×`[refHeightPx]` — le contenu de `LENS_INTRINSIC_CALIBRATION`.
         * `null` si une entrée est incohérente ou si le centre sort du capteur.
         * Le tour est exact à taille de référence : [focalPx] et [principalPx]
         * y retrouvent les entrées (aux échelles réduites, ~0,1 px d'écart sur
         * le centre, la focale scalant en `W/2` et le centre en `(W−1)/2`).
         */
        fun fromFocalPx(
            fxPx: Float,
            fyPx: Float,
            cxPx: Float,
            cyPx: Float,
            refWidthPx: Int,
            refHeightPx: Int,
        ): LensSpec? {
            if (!fxPx.isFinite() || !fyPx.isFinite() || fxPx <= 0f || fyPx <= 0f) return null
            if (!cxPx.isFinite() || !cyPx.isFinite()) return null
            if (refWidthPx <= 1 || refHeightPx <= 1) return null
            if (cxPx < 0f || cxPx > refWidthPx - 1 || cyPx < 0f || cyPx > refHeightPx - 1) return null
            val horizontal = Math.toDegrees(2.0 * atan(refWidthPx / 2.0 / fxPx)).toFloat()
            val vertical = Math.toDegrees(2.0 * atan(refHeightPx / 2.0 / fyPx)).toFloat()
            return LensSpec(
                horizontal,
                vertical,
                sensorLandscape = refWidthPx >= refHeightPx,
                principalXFrac = cxPx / (refWidthPx - 1),
                principalYFrac = cyPx / (refHeightPx - 1),
            )
        }

        /**
         * Demi-tangente du champ [hfovDeg] (degrés, borné caméra) : le facteur
         * qui projette l'image en mètres (`mètres = normalisé × 2 × profondeur
         * × tan(fov/2)`). Un seul endroit pour les trois projections (trait du
         * couloir, décalage chiffré, fenêtre d'évitement).
         */
        fun halfTangent(hfovDeg: Float): Float {
            val fov = hfovDeg.coerceIn(FOV_MIN, FOV_MAX)
            return tan(Math.toRadians(fov / 2.0)).toFloat()
        }
    }
}