package dev.sam3vision.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Fil du travail sonde : un seul thread de fond pour les guetteurs voix et le
 * journal de latence. Un seul fil = même ordre déterministe que sur le main
 * (les guetteurs s'y succédaient dans l'ordre d'abonnement), sans bloquer la
 * composition ni le tampon PIXEL : chaque émission d'état (#1, #2, dessin)
 * y faisait tourner les signaux voix + lignes debug sur le main (recette
 * 03/11 : pixel p50 40 ms, p95 92 ms). Partagé (`limitedParallelism`) : pas
 * de thread dédié permanent, le pool Default l'héberge.
 */
internal val ProbeWorkDispatcher: CoroutineDispatcher =
    Dispatchers.Default.limitedParallelism(1, "travail-sonde")
