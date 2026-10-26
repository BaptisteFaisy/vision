package dev.sam3vision.app

import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.resolveSamEndpoint

/**
 * Endpoint SAM réellement appelé par l'app : saisie manuelle > preset de la région
 * (mode EU ou **mode US**) > URL injectée au build pour cette région.
 *
 * Une seule carte tourne à la fois côté Modal : ici on choisit seulement *laquelle*
 * des deux apps déployées on interroge.
 */
fun Settings.samEndpoint(): String =
    resolveSamEndpoint(buildEu = BuildConfig.SAM_BASE_URL, buildUs = BuildConfig.SAM_US_BASE_URL)

/** Vrai si un endpoint SAM est disponible (les presets de région en fournissent un). */
fun Settings.samConfigured(): Boolean = samEndpoint().isNotBlank()