package dev.sam3vision.app

import dev.sam3vision.core.live.HomeModes
import dev.sam3vision.core.live.LivePrompts

/**
 * Preset maison demandé de l'extérieur : le bouton Maison y dépose l'identifiant,
 * `LiveScreen` le consomme au démarrage. Usage unique (comme [RouteRequest]) : une
 * rotation ne rejoue pas le preset, le direct garde ses concepts courants.
 */
object HomePresetRequest {
    private var pending: String? = null

    /** Le bouton Maison demande les essentiels. */
    fun pushDefault() {
        pending = HomeModes.DEFAULT.id
    }

    /** Concepts imposés (chasse SAM vocale) : prioritaires sur le preset. */
    private var custom: List<String>? = null

    /** Le direct démarrera sur [concepts] au lieu du preset (usage unique). */
    fun pushPrompts(concepts: List<String>) {
        custom = concepts
    }

    /** Concepts du preset demandé, ou les défauts rue si rien n'est demandé. */
    fun consume(): List<String> {
        custom?.let {
            custom = null
            pending = null
            return it
        }
        val id = pending
        pending = null
        return HomeModes.byId(id)?.prompts ?: LivePrompts.default
    }
}
