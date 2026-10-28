package dev.sam3vision.core.traverse

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.LivePrompts

/**
 * Concept escalier : ce que SAM doit suivre pour signaler des marches.
 *
 * Un seul prompt, en anglais (la forme que SAM segmente le mieux), câblé dans
 * les défauts du direct (dangers extérieurs, ordre user du 28/10).
 * Le français reste reconnu côté client ([isStairs]) : un label « escalier »
 * compte comme un « stairs ».
 *
 * [promptsFor] reste disponible pour composer une base traversée ou un
 * guidage obstacles (miroir de [VehicleConcepts.promptsFor]).
 *
 * Distance : volontairement **absente** de [KnownSizes] — un escalier
 * segmenté entier mesure d'un mètre à plusieurs étages, aucune hauteur
 * typique n'est défendable (la marche de 0,17 m ne vaut que pour une marche
 * isolée, jamais produite par ce concept). La distance d'un escalier vient
 * de la branche profondeur (`DepthReadings`, médiane agnostique au label
 * calibrée par les ancres sténopé de la frame) ou du plan de sol si la pose
 * est réglée — jamais du sténopé.
 */
object StairConcepts {

    /** Le concept escalier : un seul prompt, « stairs ». */
    val PROMPTS: List<String> = listOf("stairs")

    private val KEYS = setOf(
        "stairs", "stair", "staircase", "stairway", "steps", "step",
        "escalier",
    )

    /**
     * Vrai si [label] désigne un escalier (casse, accents, pluriel simple
     * neutralisés). « marche » seul n'est **pas** reconnu : ambigu avec la
     * marche à pied — seul « escalier » (et l'anglais) compte côté FR.
     */
    fun isStairs(label: String): Boolean {
        val key = KnownSizes.canonical(label)
        if (key.isEmpty()) return false
        if (key in KEYS) return true
        return key.endsWith("s") && key.length > 3 && key.dropLast(1) in KEYS
    }

    /**
     * Prompts escalier : « stairs » d'abord, [base] ensuite, bornés à
     * [LivePrompts.MAX] — miroir de [VehicleConcepts.promptsFor].
     */
    fun promptsFor(base: List<String> = emptyList()): List<String> =
        LivePrompts.normalize(PROMPTS + base)
}
