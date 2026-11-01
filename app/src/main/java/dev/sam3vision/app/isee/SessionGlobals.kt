package dev.sam3vision.app.isee

/**
 * Début de séance : les globaux de la séance repartent vides (pistes de
 * personnes, plan de marches). Appelé par `LiveProbe.begin()` en plus de
 * `ProbeHazards.reset()` côté ViewModel, pour que la sonde seule — et donc ses
 * tests — parte d'une séance vide.
 *
 * Sans ça, une frame publiée par un test restait dans le tracker global et la
 * suite suivante se taisait : le 01/11 (PR #919), `ProbeStairsPriorityTest` ne
 * parlait plus après le test de concurrence, et la CI tombait sur une classe
 * que la PR n'avait pas touchée. La règle côté tests est tenue par
 * `tools/check-test-globals.py`.
 */
internal fun globalsNouvelleSeance() {
    ProbeTracks.reset()
    StairCannyCount.clear()
}
