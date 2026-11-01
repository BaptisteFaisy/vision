package dev.sam3vision.app.isee

import dev.sam3vision.app.isee.vm.IseePicto
import dev.sam3vision.app.live.IseeLensFeeds
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.settings.Settings

/**
 * Ordre find (spec v4) : X rejoint les masques SAM (optique du téléphone si
 * présent, mire des lunettes sinon), son picto se crée à sa première
 * détection puis s'ancre sur lui.
 */
internal fun runFindOrder(
    probe: LiveProbe,
    settings: Settings,
    lensSpec: LensSpec?,
    lensFeeds: IseeLensFeeds,
    picto: IseePicto,
    label: String,
): Boolean {
    probe.ensureConcept(settings, label, lensSpec, lensFeeds::note, lensFeeds.mire())
    return picto.findObject(label)
}
