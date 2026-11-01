package dev.sam3vision.app.isee

import dev.sam3vision.app.live.MotionLog

/**
 * Remet à zéro les globaux de la **séance** qu'une publication écrit : pistes de
 * personnes ([ProbeTracks]), plan de marches ([StairCannyCount]), pompe fond
 * ([ProbeBackgroundMotion]) et journal de mouvement ([MotionLog]).
 *
 * À appeler dans un `@After` dès qu'un test lance de vraies frames : ces objets
 * sont uniques pour la JVM de test, donc un test qui publie sans nettoyer salit
 * la suite suivante. Vécu le 01/11 (PR #919) : `ProbeStairsPriorityTest` ne
 * parlait plus après `LiveProbePublishConcurrencyTest` — sa personne passait
 * pour une piste déjà prévenue, et la CI tombait sur une classe que la PR
 * n'avait pas touchée.
 *
 * La règle est tenue par `tools/check-test-globals.py` (appelé par
 * `scripts/test_scripts.sh`, donc par la CI) : un fichier de test qui publie via
 * `probe.` doit appeler `begin()` (qui nettoie aussi) ou cette fonction.
 */
internal fun seanceVide() {
    globalsNouvelleSeance()
    ProbeBackgroundMotion.reset()
    MotionLog.reset()
}
