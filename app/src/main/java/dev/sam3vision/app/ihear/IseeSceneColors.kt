package dev.sam3vision.app.ihear

import androidx.compose.ui.graphics.Color

/** Palette de la vidéo de design : fond dégradé, wordmark, pills d'état,
 * gros texte, pill d'instructions et dots de pagination. Couleurs mesurées au
 * pixel sur les frames de la vidéo (1080×2400). */
object IseeSceneColors {
    /** Fond en dégradé vertical : clair en haut, légèrement plus sombre vers le bas. */
    val bgTop = Color(0xFFF2F3F8)
    val bgLower = Color(0xFFD6DBE8)
    val bgBottom = Color(0xFFCBCFDE)

    val wordmark = Color(0xFF0A1220)
    /** Gros texte : iSee et ses souvenirs en quasi-noir, le user en bleu. */
    val headingDark = Color(0xFF060B18)
    val headingUser = Color(0xFF0663FD)

    /** Libellé YOU / ISEE au-dessus du gros texte. */
    val label = Color(0xFF575F6F)

    // Pills d'état : clair inactif, bleuThinking, violet voix, rouille mémoire,
    // bleu écoute, clair terminé.
    val pillQuietBg = Color(0xFFFAFBFE)
    val pillQuietText = Color(0xFF57606E)
    val pillActiveBg = Color(0xFF0663FD)
    val pillVoiceBg = Color(0xFF9133E8)
    val pillListenBg = Color(0xFF2E79FF)
    val pillDoneBg = Color(0xFFFCFCFC)
    val pillDoneText = Color(0xFF535E73)
    val pillMemoryBg = Color(0xFFB7491E)

    /** Pill d'instructions du bas : mots forts sombres, mots de service gris. */
    val instructBg = Color(0xFFF8F9FC)
    val instructStrong = Color(0xFF070C19)
    val instructGray = Color(0xFFC6CBD8)

    /** Dots de pagination bas-droite, et ticks bleus d'écoute autour de la bulle. */
    val dotActive = Color(0xFF2E79FF)
    val dotInactive = Color(0xFFE4E8F0)
    val ticks = Color(0xFF2E79FF)

    /** Carte caméra : fond photo sombre, puce REC bleue. */
    val cameraFrame = Color(0xFFFFFFFF)
    val cameraChip = Color(0xFF0663FD)
}
