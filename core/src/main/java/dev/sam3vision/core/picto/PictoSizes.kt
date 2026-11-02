package dev.sam3vision.core.picto

/**
 * Panel des tailles d'affichage des pictogrammes : cinq tailles fixes,
 * carrées, en fraction de la largeur de la carte caméra — jamais la taille
 * du rectangle reconnu, qui varie avec la distance et le cadreur.
 *
 * Référentiel écran 9:16 portrait (1080×1920, la carte fait pleine largeur) :
 *
 * | taille | largeur écran | px @1080 | hauteur écran |
 * |--------|-------------|----------|---------------|
 * | XS     | 14 %        | 151 px   | 7,9 %         |
 * | S      | 20 %        | 216 px   | 11,3 %        |
 * | M      | 27 %        | 292 px   | 15,2 %        |
 * | L      | 35 %        | 378 px   | 19,7 %        |
 * | XL     | 45 %        | 486 px   | 25,3 %        |
 *
 * [DEFAULT] (M) : lisible d'un coup d'œil sans manger la scène, et deux
 * pictos M tiennent côte à côte sur la largeur. Pur et testé en JVM.
 */
enum class PictoSize(val fraction: Float) {
    XS(0.14f),
    S(0.20f),
    M(0.27f),
    L(0.35f),
    XL(0.45f),
    ;

    companion object {
        /** Taille affichée : M pour tous les pictos, sans exception. */
        val DEFAULT: PictoSize = M
    }
}

/**
 * [PictoHit] ramené à une taille d'affichage fixe : même centre, carré de
 * côté [size] (fraction de la largeur de la carte), borné à la carte.
 * [cardAspect] est le ratio largeur/hauteur de la carte (9:16 → 0,5625) ;
 * invalide, il retombe sur 9:16. Pur et testé en JVM.
 */
fun PictoHit.withFixedSize(size: PictoSize = PictoSize.DEFAULT, cardAspect: Float): PictoHit {
    val aspect = if (cardAspect.isFinite() && cardAspect > 0f) cardAspect else 9f / 16f
    val w = size.fraction.coerceIn(0f, 1f)
    val h = (size.fraction * aspect).coerceIn(0f, 1f)
    val cx = x + this.w / 2f
    val cy = y + this.h / 2f
    return copy(
        x = (cx - w / 2f).coerceIn(0f, (1f - w).coerceAtLeast(0f)),
        y = (cy - h / 2f).coerceIn(0f, (1f - h).coerceAtLeast(0f)),
        w = w,
        h = h,
    )
}
