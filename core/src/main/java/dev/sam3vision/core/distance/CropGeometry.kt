package dev.sam3vision.core.distance

/**
 * Géométrie des recadrages centrés, et leur effet sur le calibrage optique.
 *
 * Deux chemins lunettes recadrent le flux portrait avant analyse : la voix LLM
 * (16:9 centre, `cropTo16x9` côté `app`) et la sonde SAM (photo de la rue
 * « remplit la vue », `bitmapSourceCrop` côté `app`). Les deux déléguent ici :
 * une seule définition du rectangle, une seule du remappage FOV — prouvable
 * sur JVM par `CropGeometryTest`, sans `Bitmap` ni SDK Meta.
 *
 * Règle d'optique : un crop ne change pas la focale en pixels (mêmes pixels),
 * il change les champs (moins de pixels de chaque côté) et le centre optique
 * (décalé de l'origine du crop). La spec post-crop est native dans
 * l'orientation du crop ([LensSpec.sensorLandscape]) : un crop paysage d'un
 * flux portrait porte son FOV horizontal en [LensSpec.horizontalFovDeg], et
 * [LensSpec.focalPx] suit alors les réductions d'échelle comme avant.
 */
object CropGeometry {

    /** Fenêtre de recadrage, en pixels, origine en haut à gauche (comme `CropRect` côté `app`). */
    data class Window(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * Recadrage centré au ratio [aspectW]:[aspectH] (`16:9` → `16, 9`).
     *
     * Même contrat que `cropTo16x9` côté `app` (qui délègue ici) : `null` quand
     * l'image est déjà au ratio exact (rien à couper), quand une dimension est
     * inexploitable, ou quand le côté coupé tomberait à zéro (troncature
     * entière, pas d'arrondi). Comparaison sans flottant (`Long`, pas de
     * dépassement).
     */
    fun centerCropToAspect(srcW: Int, srcH: Int, aspectW: Int, aspectH: Int): Window? {
        if (srcW <= 0 || srcH <= 0 || aspectW <= 0 || aspectH <= 0) return null
        val wide = srcW.toLong() * aspectH
        val tall = srcH.toLong() * aspectW
        if (wide == tall) return null
        if (wide < tall) {
            val cropped = (srcW.toLong() * aspectH / aspectW).toInt()
            if (cropped < 1) return null
            return Window(0, (srcH - cropped) / 2, srcW, cropped)
        }
        val cropped = (srcH.toLong() * aspectW / aspectH).toInt()
        if (cropped < 1) return null
        return Window((srcW - cropped) / 2, 0, cropped, srcH)
    }

    /**
     * Recadrage source « remplit la vue » : le rectangle centré de la source
     * ([srcW]×[srcH]) qui a exactement le ratio de la destination
     * ([dstW]×[dstH]).
     *
     * Même contrat que `bitmapSourceCrop` côté `app` (qui délègue ici) :
     * jamais `null` — dégénéré → source entière, et un ratio extrême garde au
     * moins un pixel (`coerceIn`, pas de recadrage vide).
     */
    fun fillViewCrop(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Window {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return Window(0, 0, srcW, srcH)
        val wide = srcW.toLong() * dstH
        val tall = dstW.toLong() * srcH
        if (wide == tall) return Window(0, 0, srcW, srcH)
        if (wide > tall) {
            val w = (srcH.toLong() * dstW / dstH).toInt().coerceIn(1, srcW)
            return Window((srcW - w) / 2, 0, w, srcH)
        }
        val h = (srcW.toLong() * dstH / dstW).toInt().coerceIn(1, srcH)
        return Window(0, (srcH - h) / 2, srcW, h)
    }

    /**
     * Champ (degrés) porté par [pixels] à focale [focalPx] : `2·atan(px / 2f)`.
     * C'est le « FOV horizontal effectif après crop » quand [pixels] est la
     * largeur du crop. `0f` si la focale est inexploitable (jamais un `NaN`).
     */
    fun fovDegForPixels(focalPx: Float, pixels: Int): Float {
        if (!focalPx.isFinite() || focalPx <= 0f || pixels <= 0) return 0f
        return Math.toDegrees(2.0 * kotlin.math.atan(pixels / 2.0 / focalPx)).toFloat()
    }

    /**
     * Calibrage de l'image croppée ([crop] dans une source [srcW]×[srcH] de
     * calibrage [spec]) : focale px inchangée, champs resserrés, centre optique
     * remappé, orientation native du crop.
     *
     * `null` quand on ne peut pas conclure (spec inutilisable, crop hors
     * source ou vide, champ post-crop hors bornes [LensSpec], centre optique
     * hors crop) — l'appelant garde alors son ancien calibrage
     * (`DistanceMeter.setLens` ignore `null`). La distorsion est propagée
     * ([propagatedDistortion] : renormalisée à la demi-diagonale du crop,
     * exacte — le recentrage ne casse rien, `r` part du centre optique
     * remappé) ; `null` sans mesure mire, comme avant.
     */
    fun afterCrop(spec: LensSpec, srcW: Int, srcH: Int, crop: Window): LensSpec? {
        if (!spec.usable || srcW <= 0 || srcH <= 0) return null
        if (crop.width <= 0 || crop.height <= 0 || crop.x < 0 || crop.y < 0) return null
        if (crop.x + crop.width > srcW || crop.y + crop.height > srcH) return null
        val focalPx = spec.focalPx(srcW, srcH)
        if (focalPx <= 0f) return null
        val horizontal = fovDegForPixels(focalPx, crop.width)
        val vertical = fovDegForPixels(focalPx, crop.height)
        if (horizontal !in LensSpec.FOV_MIN..LensSpec.FOV_MAX) return null
        if (vertical !in LensSpec.FOV_MIN..LensSpec.FOV_MAX) return null
        val (cx, cy) = spec.principalPx(srcW, srcH)
        val principalX = remappedPrincipal(cx, crop.x, crop.width)
        val principalY = remappedPrincipal(cy, crop.y, crop.height)
        if (principalX !in 0f..1f || principalY !in 0f..1f) return null
        return LensSpec(
            horizontal,
            vertical,
            sensorLandscape = crop.width >= crop.height,
            principalXFrac = principalX,
            principalYFrac = principalY,
            distortion = propagatedDistortion(spec.distortion, srcW, srcH, crop.width, crop.height),
        )
    }

    /**
     * Distorsion [d] (mesurée sur la source [srcW]×[srcH]) renormalisée au
     * repère du crop [cropW]×[cropH] : `k1' = k1·(n'/n)²`,
     * `k2' = k2·(n'/n)⁴` (`n`, `n'` = demi-diagonales via [Distortion.normPx]).
     * Exacte quel que soit le recentrage : `r` part du centre optique, lui-même
     * remappé par [afterCrop], et la translation conserve les distances —
     * redresser puis cropper = cropper puis redresser (pinné à 0,01 px).
     * `null` sans mesure (pas de distorsion inventée) ; coeffs nuls mesurés
     * propagés non-nuls (mesuré sans distorsion ≠ non mesuré).
     */
    private fun propagatedDistortion(
        d: RadialDistortion?,
        srcW: Int,
        srcH: Int,
        cropW: Int,
        cropH: Int,
    ): RadialDistortion? {
        if (d == null) return null
        val source = Distortion.normPx(srcW, srcH)
        val cropped = Distortion.normPx(cropW, cropH)
        if (source <= 0f || cropped <= 0f) return null
        val r2 = (cropped / source).let { it * it }
        return RadialDistortion(d.k1 * r2, d.k2 * r2 * r2)
    }

    /**
     * Boîte `[x1, y1, x2, y2]` (convention [Detection]) exprimée dans la source
     * → mêmes coins dans le repère du [crop] (mapping portrait → paysage pour
     * les détections). Identité si la boîte est inexploitable.
     */
    fun mapBoxToCrop(box: List<Float>, crop: Window): List<Float> {
        if (box.size < 4) return box
        return listOf(box[0] - crop.x, box[1] - crop.y, box[2] - crop.x, box[3] - crop.y)
    }

    /**
     * Inverse de [mapBoxToCrop] : boîte du repère du [crop] → repère source.
     * Identité si la boîte est inexploitable.
     */
    fun mapBoxToSource(box: List<Float>, crop: Window): List<Float> {
        if (box.size < 4) return box
        return listOf(box[0] + crop.x, box[1] + crop.y, box[2] + crop.x, box[3] + crop.y)
    }

    /** Centre optique (px source) → fraction dans le crop (`0,5` si le crop fait 1 px). */
    private fun remappedPrincipal(centerPx: Float, cropOrigin: Int, cropSize: Int): Float {
        if (cropSize <= 1) return 0.5f
        return (centerPx - cropOrigin) / (cropSize - 1)
    }
}
