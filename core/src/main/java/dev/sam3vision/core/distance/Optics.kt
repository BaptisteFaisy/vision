package dev.sam3vision.core.distance

/**
 * Focale en pixels (`f_px`) déduite des caractéristiques **brutes** du capteur
 * Camera2, celles que `Camera2CameraInfo` rend côté app : focale (mm), taille
 * physique du tableau de pixels (mm), zone active (px) et taille de ce tableau (px).
 *
 * Pourquoi ce calcul vit dans `:core` : le recadrage du capteur est le seul endroit
 * où `f_px` peut se tromper d'un facteur constant (`SENSOR_INFO_PHYSICAL_SIZE`
 * décrit le tableau **complet**, pas la zone active), et c'est du calcul pur — il se
 * teste sans téléphone. `app/…/CameraLens.kt` ne fait plus que lire les
 * caractéristiques et appeler [Optics.lensSpec].
 *
 * **Une seule focale dans le dépôt** : ces fonctions rendent un [LensSpec], et c'est
 * [LensSpec.focalPx] qui la convertit en pixels pour chaque image analysée. Aucune
 * troisième formule (le piège des « focales à trois valeurs » des runs `vld`).
 */
object Optics {

    /** Focales (mm) plausibles pour un objectif de téléphone, ultra grand-angle à téléobjectif. */
    const val FOCAL_MM_MIN = 0.5f
    const val FOCAL_MM_MAX = 60f

    /** Côtés physiques (mm) plausibles d'un capteur de téléphone. */
    const val SENSOR_MM_MIN = 0.5f
    const val SENSOR_MM_MAX = 60f

    /** Zone **active** du capteur en millimètres, après recadrage du tableau de pixels. */
    data class ActiveSensor(val widthMm: Float, val heightMm: Float)

    /**
     * Zone active en millimètres. La taille physique déclarée par le driver couvre le
     * tableau complet ([arrayWidthPx]×[arrayHeightPx]) ; une caméra qui ne lit qu'une
     * partie de ce tableau (couloir optique, mode recadré) déclare une zone active plus
     * petite ([activeWidthPx]×[activeHeightPx]) : la taille physique correspondante est
     * mise à l'échelle, sinon `f_px` serait sous-estimée d'autant.
     *
     * `null` si un côté est nul ou négatif, si la zone active dépasse le tableau, ou si
     * la taille physique sort des bornes d'un capteur de téléphone.
     */
    fun activeSensor(
        physicalWidthMm: Float,
        physicalHeightMm: Float,
        activeWidthPx: Int,
        activeHeightPx: Int,
        arrayWidthPx: Int,
        arrayHeightPx: Int,
    ): ActiveSensor? {
        if (physicalWidthMm !in SENSOR_MM_MIN..SENSOR_MM_MAX) return null
        if (physicalHeightMm !in SENSOR_MM_MIN..SENSOR_MM_MAX) return null
        if (activeWidthPx <= 0 || activeHeightPx <= 0) return null
        if (arrayWidthPx <= 0 || arrayHeightPx <= 0) return null
        if (activeWidthPx > arrayWidthPx || activeHeightPx > arrayHeightPx) return null
        return ActiveSensor(
            physicalWidthMm * activeWidthPx / arrayWidthPx,
            physicalHeightMm * activeHeightPx / arrayHeightPx,
        )
    }

    /**
     * Calibrage du capteur, `null` si une entrée est absente ou hors bornes, ou si les
     * angles qui en sortent ne sont pas plausibles ([LensSpec.usable], 15–130°) — un
     * calibrage faux vaut moins que le repli Réglages puis [LensSpec.Default] que font
     * déjà les appelants (`DistanceMeter.setLens` n'accepte que les specs `usable`).
     */
    fun lensSpec(focalMm: Float, sensor: ActiveSensor?): LensSpec? {
        if (sensor == null) return null
        if (focalMm !in FOCAL_MM_MIN..FOCAL_MM_MAX) return null
        val spec = LensSpec.fromSensor(focalMm, sensor.widthMm, sensor.heightMm) ?: return null
        return if (spec.usable) spec else null
    }

    /** Raccourci : les caractéristiques Camera2 brutes → un calibrage (voir [activeSensor]). */
    fun lensSpec(
        focalMm: Float,
        physicalWidthMm: Float,
        physicalHeightMm: Float,
        activeWidthPx: Int,
        activeHeightPx: Int,
        arrayWidthPx: Int,
        arrayHeightPx: Int,
    ): LensSpec? = lensSpec(
        focalMm,
        activeSensor(
            physicalWidthMm, physicalHeightMm,
            activeWidthPx, activeHeightPx, arrayWidthPx, arrayHeightPx,
        ),
    )

    /**
     * Calibrage depuis `LENS_INTRINSIC_CALIBRATION` (`[fxPx], [fyPx], [cxPx], [cyPx]`,
     * en pixels de la zone active `[activeWidthPx]`×`[activeHeightPx]`) : focale **et**
     * centre optique mesurés par le driver, plus justes que la focale (mm) + centre
     * supposé. `null` si une entrée est incohérente ou si les angles sortent de
     * [LensSpec.usable] — l'appelant retombe alors sur [lensSpec] (mm), puis les Réglages.
     * Peu de drivers exposent cette clé : c'est une voie bonus, jamais exigée.
     */
    fun lensSpecFromIntrinsics(
        fxPx: Float,
        fyPx: Float,
        cxPx: Float,
        cyPx: Float,
        activeWidthPx: Int,
        activeHeightPx: Int,
    ): LensSpec? {
        val spec = LensSpec.fromFocalPx(fxPx, fyPx, cxPx, cyPx, activeWidthPx, activeHeightPx)
            ?: return null
        return if (spec.usable) spec else null
    }

    /**
     * `f_px` d'une image `[imageWidth]`×`[imageHeight]` pour les caractéristiques brutes
     * d'un capteur : le chiffre que réclame le sténopé. `0f` (jamais un `NaN`) quand le
     * capteur ne permet pas de conclure — l'appelant n'affiche alors aucune distance.
     */
    fun focalPx(
        focalMm: Float,
        physicalWidthMm: Float,
        physicalHeightMm: Float,
        activeWidthPx: Int,
        activeHeightPx: Int,
        arrayWidthPx: Int,
        arrayHeightPx: Int,
        imageWidth: Int,
        imageHeight: Int,
    ): Float {
        val spec = lensSpec(
            focalMm,
            physicalWidthMm, physicalHeightMm,
            activeWidthPx, activeHeightPx, arrayWidthPx, arrayHeightPx,
        ) ?: return 0f
        return spec.focalPx(imageWidth, imageHeight)
    }
}