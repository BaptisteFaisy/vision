package dev.sam3vision.core.stairs

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.LivePrompts

/**
 * Concept rampe d'escalier : ce que SAM doit suivre pour signaler une main
 * courante (« Rampe, à 2 heures. »).
 *
 * Un seul prompt, en anglais (la forme que SAM segmente le mieux) : 13
 * concepts par défaut sur [LivePrompts.MAX] (18), donc 5 slots libres côté
 * rue/extérieur (11 côté maison) — « handrail » (8 lettres, ≤ 32) y tient.
 * Le français reste reconnu côté client ([isHandrail]) : un label « rampe »
 * compte comme un « handrail ».
 *
 * Ce fichier ne touche pas à [LivePrompts] (défauts du direct, hors
 * périmètre) : l'intégrateur ajoute « handrail » par le champ « Follow » ou
 * instruction du propriétaire des prompts. [promptsFor] reste disponible
 * pour composer une base (miroir de `StairConcepts.promptsFor` côté
 * traversée).
 *
 * Distance : volontairement **absente** de [KnownSizes] — une rampe
 * segmentée va du barreau au garde-corps d'étage, aucune hauteur typique
 * n'est défendable. La distance d'une rampe vient de la branche profondeur
 * (`DepthReadings`, médiane agnostique au label) ou du plan de sol — jamais
 * du sténopé.
 */
object HandrailConcepts {

    /** Le concept rampe : un seul prompt, « handrail ». */
    val PROMPTS: List<String> = listOf("handrail")

    private val KEYS = setOf(
        "handrail", "railing", "banister", "bannister", "grab bar", "grab rail",
        "rampe", "main courante", "garde corps", "barre d appui",
    )

    /**
     * Vrai si [label] désigne une rampe (casse, accents, pluriel simple
     * neutralisés). « marche » seul n'est **pas** reconnu : ambigu avec la
     * marche à pied — seule la rampe nommée compte.
     */
    fun isHandrail(label: String): Boolean {
        val key = KnownSizes.canonical(label)
        if (key.isEmpty()) return false
        if (key in KEYS) return true
        return key.endsWith("s") && key.length > 3 && key.dropLast(1) in KEYS
    }

    /**
     * Prompts rampe : « handrail » d'abord, [base] ensuite, bornés à
     * [LivePrompts.MAX] — miroir de `StairConcepts.promptsFor` côté traversée.
     */
    fun promptsFor(base: List<String> = emptyList()): List<String> =
        LivePrompts.normalize(PROMPTS + base)
}
