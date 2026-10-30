package dev.sam3vision.core.speech

import dev.sam3vision.core.distance.KnownSizes

/**
 * Ordre vocal « iSee » : le mot seul bascule la session des lunettes
 * (ouverte si une paire est liée en Bluetooth, arrêtée sinon). Appelé sur
 * le texte dicté avant l'envoi au LLM, comme les autres ordres vocaux.
 *
 * Strict volontairement : **tout** l'énoncé doit être le mot, une fois
 * casse, accents, ponctuation et espaces neutralisés ([KnownSizes.canonical]).
 * Un « iSee » noyé dans une question (« demande à iSee… ») part au LLM comme
 * avant — et « ici », mot courant des questions (« qu'y a-t-il ici ? »),
 * ne déclenche jamais, malgré la ressemblance à l'oreille. Pur et testé
 * en JVM ([GlassesSessionOrderTest]).
 */
object GlassesSessionOrder {

    /**
     * Vrai si [text] dicté est l'ordre « iSee » ; `false` = le texte poursuit
     * son chemin (autres ordres, puis LLM).
     */
    fun isSessionOrder(text: String): Boolean =
        KnownSizes.canonical(text).replace(" ", "") == "isee"
}
