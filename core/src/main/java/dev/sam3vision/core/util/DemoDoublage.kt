package dev.sam3vision.core.util

/**
 * Utilitaire **jetable** : sert à vérifier que l'agent de test corrige un vrai
 * rouge de CI (branche de test, jamais mergée).
 */
object DemoDoublage {

    /** Double [x]. */
    fun doubler(x: Int): Int = x + x + 1
}
