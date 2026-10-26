package dev.sam3vision.app

import android.util.Size
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy

/**
 * Taille visée pour le flux d'**aperçu** (px) : plein capteur 1080p.
 *
 * Mesuré sur le téléphone (capture 1220×2712, carte ≈ 1000 px de large) : l'aperçu de
 * la carte iSee était un aplat de couleur d'environ 3 px, soit une source de ~320 px
 * étirée ~3×. Cause : la `Preview` n'avait aucun `ResolutionSelector` propre et CameraX
 * la faisait descendre dans la famille de résolution de l'analyse.
 */
val PREVIEW_TARGET_SIZE = Size(1920, 1080)

/**
 * Sélecteur de résolution de l'**aperçu**, indépendant du flux d'analyse.
 *
 * L'analyse garde sa propre cible, plus petite et réglée pour la latence (320 px dans
 * iSee, 512 px dans le direct) : c'est elle qui part au modèle. L'aperçu, lui, ne sert
 * qu'à l'œil — il n'a aucune raison de partager la résolution de l'analyse.
 *
 * `FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER` : la taille la plus proche **au-dessus** de
 * la cible si le capteur la propose, sinon la plus proche en dessous — tous les appareils
 * n'offrent pas 1920×1080 pile, et l'aperçu ne doit pas retomber sur 320 px pour autant.
 */
fun previewResolutionSelector(): ResolutionSelector = ResolutionSelector.Builder()
    .setResolutionStrategy(
        ResolutionStrategy(PREVIEW_TARGET_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
    )
    .build()