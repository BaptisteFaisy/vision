package dev.sam3vision.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Palette sombre de l'app (cohérente avec l'ancienne app Expo). */
/**
 * Palette claire, réservée à la page Settings (fond blanc, texte noir).
 * Le reste de l'app garde [AppColors] (thème sombre) : rien d'autre ne change.
 */
object SettingsLight {
    val background = Color.White
    val text = Color.Black
    val muted = Color(0xFF5B6472)
    val border = Color(0xFFE2E8F0)
    val primary = Color(0xFF1D4ED8)
}

object AppColors {
    val background = Color(0xFF0B0D12)
    val surface = Color(0xFF151A23)
    val surfaceHigh = Color(0xFF1D2430)
    val text = Color(0xFFEDF1F7)
    val textMuted = Color(0xFF98A2B3)
    val primary = Color(0xFF4F8EF7)
    val success = Color(0xFF3ED598)
    val danger = Color(0xFFF2555A)
    val warning = Color(0xFFF5C451)
}

/**
 * Couleur d'overlay par objet (rotation). Les 6 premières reprises de l'app Expo,
 * étendues pour ne pas collisionner sur les scènes chargées.
 */
val MASK_COLORS: List<Color> = listOf(
    AppColors.primary,
    Color(0xFFF2555A),
    Color(0xFF3ED598),
    Color(0xFFB07CF0),
    Color(0xFFF5C451),
    Color(0xFF35C8D4),
    Color(0xFFFF8A4C),
    Color(0xFF7EE081),
)

/** Opacité de base des masques (0-255 pour `colorizeMask`). */
const val MASK_ALPHA = 115

fun maskColorAt(index: Int): Color = MASK_COLORS[index % MASK_COLORS.size]

/** Seuil de bascule clair/sombre (success ≈ 0,51 ; primary ≈ 0,28 ; danger ≈ 0,26). */
private const val CONTENT_ON_THRESHOLD = 0.45f

/**
 * Encre lisible sur un fond [color] : sombre sur fond clair (warning, success),
 * claire sinon. Un seul endroit décide — bandeaux, boutons et pastilles partagent
 * la même règle au lieu de coder « blanc sauf… » chacun de leur côté.
 */
fun contentOn(color: Color): Color =
    if (color.luminance() > CONTENT_ON_THRESHOLD) AppColors.background else AppColors.text
