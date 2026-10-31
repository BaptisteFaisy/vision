package dev.sam3vision.app.isee

import dev.sam3vision.core.local.LumaPlane
import dev.sam3vision.core.mask.GrayMask
import dev.sam3vision.core.stairs.StairDetector
import dev.sam3vision.core.stairs.StairRoi
import dev.sam3vision.core.stairs.StairWatcher

/**
 * Dernier compte de marches vu par la caméra, pour la voix escaliers.
 *
 * Le téléphone offre le plan de luminance de chaque frame convertie
 * ([offerLuma], fil de l'analyse : le Canny y tourne, ~26 000 pixels), les
 * lunettes offrent les pixels de leur photo ([offerPixels], même plan
 * rééchantillonné) et le guetteur escaliers (`observeStairs`) relit le compte
 * frais à chaque réponse SAM — une seule écriture d'entier transite entre les
 * fils, jamais une image. Frais, le compte nourrit le compte à rebours de la
 * montée (« Plus que N marches. ») ; périmé (caméra coupée, repli bitmap sans
 * luminance), il vaut 0 et l'annonce reste standard.
 *
 * Le comptage est calibré sur la reconnaissance SAM : le masque exact de
 * l'escalier ([setMaskPlane], posé par la publication à chaque réponse) borne
 * au pixel près ; sans masque, la ROI ([setRoi], sa boîte, posée par le
 * guetteur) borne au rectangle ; sans rien, le balayage couvre l'image
 * entière, comme avant. Dans tous les cas, plus de stores vénitiens hors
 * escalier dès que SAM le reconnaît.
 */
internal object StairCannyCount {

    /** Fraîcheur d'un compte : au-delà, la caméra est coupée ou figée. */
    const val FRESH_MS = 1_500L

    @Volatile
    private var count: Int = 0

    @Volatile
    private var countedAtMs: Long = 0L

    /** ROI SAM en cours (`null` = image entière) : posée par le guetteur, lue par l'analyse. */
    @Volatile
    private var roi: StairRoi? = null

    /**
     * ROI du comptage (boîte SAM normalisée de l'escalier) ; `null` l'efface
     * (plus d'escalier localisé : le balayage couvre l'image entière).
     * S'applique aux frames suivantes, pas au compte déjà lu.
     */
    fun setRoi(roi: StairRoi?) {
        this.roi = roi
    }

    /** Plan du masque SAM en cours (`null` = repli boîte) : posé par la publication. */
    @Volatile
    private var maskPlane: ByteArray? = null

    /**
     * Plan du masque de l'escalier ([LumaPlane.SIZE] octets, `1` = escalier) ;
     * `null` l'efface (repli boîte). Prime sur [setRoi], même contrat de fil.
     */
    fun setMaskPlane(plane: ByteArray?) {
        maskPlane = plane
    }

    /**
     * Compte les marches vues par les lunettes : [pixels] est la photo du pont
     * (ARGB_8888, déjà redressée), rééchantillonnée au plan [LumaPlane] au plus
     * proche voisin. Dimensions incohérentes = ignorées, comme un plan incomplet.
     */
    fun offerPixels(pixels: IntArray, width: Int, height: Int) {
        val luma = lumaPlaneFromPixels(pixels, width, height) ?: return
        count = cannyLineCount(luma, roi, maskPlane)
        countedAtMs = System.currentTimeMillis()
    }

    /**
     * Compte les marches de [luma] (plan [LumaPlane] de l'analyseur, déjà
     * tourné). Plan incomplet = ignoré (le compte précédent vieillit, puis
     * expire — il ne bascule jamais à « zéro marche » sur une frame douteuse).
     */
    fun offerLuma(luma: ByteArray) {
        if (luma.size != LumaPlane.SIZE) return
        count = cannyLineCount(luma, roi, maskPlane)
        countedAtMs = System.currentTimeMillis()
    }

    /**
     * Marches vues sur la dernière frame fraîche, 0 sinon : 0 = non compté,
     * pas « zéro marche » (convention [StairWatcher]).
     */
    fun lineCount(nowMs: Long = System.currentTimeMillis()): Int =
        count.takeIf { nowMs - countedAtMs <= FRESH_MS } ?: 0

    /** Âge du dernier compte en ms (`null` = jamais compté) : diagnostic du direct. */
    fun countAgeMs(nowMs: Long = System.currentTimeMillis()): Long? =
        (nowMs - countedAtMs).takeIf { countedAtMs > 0L }

    /** Nouvelle séance : ni compte, ni ROI, ni masque d'avant (tout repart de zéro). */
    fun clear() {
        count = 0
        countedAtMs = 0L
        roi = null
        maskPlane = null
    }
}

/**
 * Pixels ARGB_8888 ([width]×[height]) → plan [LumaPlane], au plus proche voisin
 * (miroir de l'échantillonneur NV21 : mêmes bornes, même ordre) ; `null` si les
 * dimensions sont incohérentes. Pur JVM, testé sans appareil.
 */
internal fun lumaPlaneFromPixels(pixels: IntArray, width: Int, height: Int): ByteArray? {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return null
    val out = ByteArray(LumaPlane.SIZE)
    var index = 0
    for (y in 0 until LumaPlane.HEIGHT) {
        val row = (y.toLong() * height / LumaPlane.HEIGHT).toInt() * width
        for (x in 0 until LumaPlane.WIDTH) {
            val pixel = pixels[row + (x.toLong() * width / LumaPlane.WIDTH).toInt()]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            out[index++] = ((299 * r + 587 * g + 114 * b) / 1000).toByte()
        }
    }
    return out
}

/**
 * Marches comptées dans [luma] (plan [LumaPlane] complet) : dans [maskPlane]
 * quand il est posé (masque SAM exact, prime), sinon dans [roi] (sa boîte),
 * sinon dans l'image entière. Le détecteur Canny pur ([StairDetector]), sans
 * exception sur le chemin du direct.
 */
internal fun cannyLineCount(luma: ByteArray, roi: StairRoi? = null, maskPlane: ByteArray? = null): Int {
    if (luma.size != LumaPlane.SIZE) return 0
    return runCatching {
        StairDetector.detect(
            GrayMask(LumaPlane.WIDTH, LumaPlane.HEIGHT, luma), roi = roi, maskPlane = maskPlane,
        ).lineCount
    }.getOrDefault(0)
}
