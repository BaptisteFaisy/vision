package dev.sam3vision.app.isee.vm

import dev.sam3vision.core.picto.PictoHit

/** Où en est le repère : repos, photo, ou génération réseau. */
enum class PictoPhase { Idle, Shooting, Generating }

/**
 * L'état picto de l'écran : la phase, les objets reconnus sur la vidéo
 * (overlay temps réel) et le nombre de repères armés.
 */
data class PictoUiState(
    val phase: PictoPhase = PictoPhase.Idle,
    val hits: List<PictoHit> = emptyList(),
    val armed: Int = 0,
)
