package dev.sam3vision.app.isee

/**
 * Politique de frame SAM de la page caméra : 480 px envoyés, jamais agrandis.
 * Le capteur est demandé en 640×480 et le direct aidant prend la même géométrie :
 * un seul pack NV21 sert alors les deux (voir Nv21JpegEncoder).
 *
 * Le 2026-10-26, la conversion d'une frame coûtait 12-42 ms côté téléphone parce que
 * l'on demandait 320 px au capteur qui livrait 640×480, redimensionné ensuite pour rien
 * (et en tournant avant de réduire : la rotation coûte le nombre de pixels). On demande
 * donc désormais la taille qu'on envoie, et la règle est verrouillée par des tests.
 *
 * Pure et sans Android : c'est ce qui la rend testable en JVM.
 */
object FramePolicy {

    /** Côté long de l'image envoyée à SAM (px). */
    const val EDGE = 480

    /** Qualité JPEG de l'image envoyée (65 : invisible à l'œil sur du 480 px). */
    const val QUALITY = 65
}
