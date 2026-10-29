package dev.sam3vision.core.stairs

import dev.sam3vision.core.distance.KnownSizes

/** Sens d'un escalier : montant, descendant, ou inconnu (rien ne le dit). */
enum class StairDirection { Up, Down, Unknown }

/**
 * Concepts labels qui désignent un **escalier** (voie SAM, miroir du détecteur
 * Canny [StairDetector] qui voit les marches sans les nommer).
 *
 * Le sens vient des **mots du libellé** — SAM répète le concept suivi :
 * « stairs going up », « escalier descendant »… Un simple « stairs » /
 * « escalier » ne dit pas le sens ([StairDirection.Unknown]) : on annonce
 * l'escalier sans sens plutôt qu'un sens deviné — un sens faux devant un vrai
 * escalier ferait tomber.
 *
 * Les libellés sont comparés sous leur forme canonique (casse, accents),
 * mot à mot : « Stairs going up » vaut « stairs going up ».
 */
object StairConcepts {

    /** Formes canoniques du mot « escalier » (voir [KnownSizes.canonical]). */
    private val STAIRS = setOf(
        "stair", "stairs", "staircase", "staircases", "steps", "upstairs", "downstairs",
        "escalier", "escaliers", "marche", "marches",
    )

    /** Mots du libellé qui disent la montée. */
    private val UP = setOf(
        "up", "upstairs", "upward", "upwards", "ascending",
        "monte", "montee", "montant", "montante", "montants",
    )

    /** Mots du libellé qui disent la descente. */
    private val DOWN = setOf(
        "down", "downstairs", "downward", "downwards", "descending",
        "descend", "descendre", "descente", "descendant", "descendante",
    )

    /** Vrai si [label] désigne un escalier (avec ou sans sens). */
    fun isStairs(label: String): Boolean = words(label).any { it in STAIRS }

    /** Sens dit par [label] ; [StairDirection.Unknown] quand rien ne le dit. */
    fun direction(label: String): StairDirection {
        val found = words(label)
        if (found.none { it in STAIRS }) return StairDirection.Unknown
        if (found.any { it in UP }) return StairDirection.Up
        if (found.any { it in DOWN }) return StairDirection.Down
        return StairDirection.Unknown
    }

    /** Mots canoniques non vides du libellé. */
    private fun words(label: String): List<String> =
        KnownSizes.canonical(label).split(' ').filter { it.isNotEmpty() }
}
