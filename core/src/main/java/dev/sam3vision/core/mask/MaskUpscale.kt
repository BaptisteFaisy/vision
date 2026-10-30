package dev.sam3vision.core.mask

/**
 * Plafond d'allocation d'un masque reçu : 4 Mpx couvrent 2048 × 2048, très au-dessus des
 * 640 × 360 du direct comme des 256 px de la grille réduite. Au-delà, le masque est refusé
 * **avant** toute allocation : un en-tête hostile ne fait pas réserver des mégaoctets.
 */
const val MASK_MAX_PIXELS = 4 * 1024 * 1024

/** Pixels d'une grille `width × height`, `null` si elle est nulle ou au-delà de [MASK_MAX_PIXELS]. */
fun maskGridSize(width: Int, height: Int): Int? {
    if (width <= 0 || height <= 0) return null
    val size = width.toLong() * height.toLong()
    return if (size <= MASK_MAX_PIXELS) size.toInt() else null
}

/**
 * Grille du masque annoncée par `mask_scale` : ce champ porte le **ratio grille/frame**
 * (256 px de côté pour une frame de 512 ⇒ 0,5), pas une taille en pixels — le client en
 * déduit les deux dimensions, seul moyen de relire des runs RLE. `null` si le ratio est
 * absurde (non fini, ≤ 0 ou > 4) ou si la grille qui en découle est inutilisable.
 */
fun maskGrid(frameWidth: Int, frameHeight: Int, scale: Float): Pair<Int, Int>? {
    if (!scale.isFinite() || scale <= 0f || scale > 4f) return null
    val width = Math.round(frameWidth * scale)
    val height = Math.round(frameHeight * scale)
    return if (maskGridSize(width, height) == null) null else width to height
}

/**
 * Agrandit [mask] à `targetWidth × targetHeight` par **plus proche voisin centré** : le
 * centre du pixel cible pointe le pixel source sous-jacent, sans décalage cumulé. C'est le
 * seul agrandissement du client (le codec envoie ses masques sur la grille réduite, il faut
 * les ramener à la frame), et il ne décale rien : un masque déjà à la bonne taille est
 * rendu **tel quel**. `null` si la cible est inutilisable.
 */
fun upscaleNearest(mask: GrayMask, targetWidth: Int, targetHeight: Int): GrayMask? {
    val total = maskGridSize(targetWidth, targetHeight) ?: return null
    if (mask.width == targetWidth && mask.height == targetHeight) return mask
    val xMap = axisMap(targetWidth, mask.width)
    val yMap = axisMap(targetHeight, mask.height)
    val pixels = ByteArray(total)
    for (y in 0 until targetHeight) {
        val sourceRow = yMap[y] * mask.width
        val targetRow = y * targetWidth
        for (x in 0 until targetWidth) {
            pixels[targetRow + x] = mask.pixels[sourceRow + xMap[x]]
        }
    }
    return GrayMask(targetWidth, targetHeight, pixels)
}

/**
 * Table d'un axe : l'index source de chaque pixel cible, calculé **une fois par axe**
 * (`targetWidth + targetHeight` divisions) au lieu d'une fois par pixel
 * (`targetWidth × targetHeight`) : la boucle interne ne fait plus que des lectures.
 */
private fun axisMap(target: Int, source: Int): IntArray =
    IntArray(target) { sourceIndex(it, target, source) }

/** Index source du centre du pixel [index] d'un axe de [target] pixels, borné à [source]. */
private fun sourceIndex(index: Int, target: Int, source: Int): Int =
    (((index + 0.5) * source) / target).toInt().coerceIn(0, source - 1)

/**
 * Masque ramené à la taille de la frame : `256 × 103` de grille pour une frame de
 * `512 × 206` redevient `512 × 206` (blocs 2 × 2). Un masque déjà à la bonne taille, ou une
 * frame de taille inconnue, rend le masque inchangé.
 *
 * Conséquence assumée : l'agrandissement n'est pas exact au pixel. La métrique d'erreur du
 * calque local se calcule donc **après** lui, sur la grille réellement décodée.
 */
fun upscaleToFrame(mask: GrayMask, frameWidth: Int, frameHeight: Int): GrayMask =
    upscaleNearest(mask, frameWidth, frameHeight) ?: mask