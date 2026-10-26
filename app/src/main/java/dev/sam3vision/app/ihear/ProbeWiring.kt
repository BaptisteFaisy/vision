package dev.sam3vision.app.ihear

import dev.sam3vision.app.BuildConfig
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.resolveSamEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Câblage de la sonde SAM de la page caméra : réglages, voix, fin d'enregistrement.
 *
 * Isolé du ViewModel pour que celui-ci reste sous la limite de 500 lignes : la
 * logique est la même, elle vit simplement à côté.
 */

/**
 * Applique les Réglages à la sonde : endpoint résolu (saisie manuelle > région
 * EU/US > URL du build), annonce vocale des objets et de leur distance, focale de
 * la caméra, distances on/off, plafond d'objets.
 */
internal fun LiveProbe.configureFrom(settings: Settings) {
    configure(
        baseUrl = settings.resolveSamEndpoint(BuildConfig.SAM_BASE_URL, BuildConfig.SAM_US_BASE_URL),
        announce = settings.announcePositions,
        lens = LensSpec.fromSettings(settings.cameraFovDeg),
        distancesOn = settings.distanceOverlayEnabled,
        maxObjects = settings.objectsOnScreen,
        language = settings.speechLanguage,
    )
}

/**
 * Chaque nouvelle phrase de la sonde est confiée à la voix (une seule fois par
 * scène). [playerIdle] vrai = aucune bulle du chat en cours de lecture.
 */
internal fun LiveProbe.observeSpoken(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
) {
    scope.launch {
        state.collect { st ->
            val phrase = st.spoken ?: return@collect
            voice.onPhrase(phrase, playerIdle())
        }
    }
}