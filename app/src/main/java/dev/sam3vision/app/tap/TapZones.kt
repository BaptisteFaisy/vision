package dev.sam3vision.app.tap

/**
 * Zones exclues du tap : les barres système (statut en haut, navigation — retour, accueil,
 * récents — en bas) et la marge de sécurité autour. Un tap dont la coordonnée `y` tombe
 * dans ces bandes ne doit **jamais** lancer d'enregistrement ; il est renvoyé au système.
 *
 * [viewHeightPx] est la hauteur du canevas ; les insets sont exprimés en pixels de cet
 * écran, récupérés via `WindowInsets` côté Compose.
 */
fun isExcludedTapZone(y: Float, topInsetPx: Int, bottomInsetPx: Int, viewHeightPx: Int): Boolean =
    y < topInsetPx || y > viewHeightPx - bottomInsetPx

/**
 * Largeur de la marge latérale rendue au système (gestes de retour sur les bords,
 * notamment sur les écrans larges) : un tap près du bord gauche/droit n'est pas un
 * tap « n'importe où sur l'écran ».
 */
fun isExcludedSideZone(x: Float, slopPx: Float, viewWidthPx: Int): Boolean =
    x < slopPx || x > viewWidthPx - slopPx

/**
 * Un tap court est un relâchement qui n'a ni été un appui long ni un glissement, et dont la
 * durée reste sous le seuil appui long ([longPressTimeoutMs], seuil `ViewConfiguration` par défaut).
 * La mesure est faite sur l'événement lui-même (`eventTime - downAt`), donc insensible au délai
 * double tap de `GestureDetector`.
 */
fun isShortTapRelease(
    longPressed: Boolean,
    dragged: Boolean,
    pressDurationMs: Long,
    longPressTimeoutMs: Long,
): Boolean = !longPressed && !dragged && pressDurationMs in 0L until longPressTimeoutMs
