package dev.sam3vision.app.glasses

import com.meta.wearable.dat.camera.types.AudioCodec
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality

/**
 * Cadence demandée au flux des lunettes pour l'écran iSee : 24 i/s.
 *
 * Le SDK Meta DAT (mwdat-camera 1.0.x, bytecode `StreamImpl.createVideoFormat`
 * et `VideoFormat.defaultFormat`) fixe `bitRate = 750000` bps pour toutes les
 * qualités, borne `frameRate` à `coerceIn(2..30)` puis l'accroche à
 * {30, 24, 15, 7, 2}. 24 est sur la grille : demandé = tenu, sans accrochage
 * silencieux. À débit constant, chaque image de plus retire des bits aux
 * autres — mais la bascule en qualité moyenne compense : 750 kbit/s à 24 i/s
 * sur 504×896 = 0,069 bit/pixel, contre 0,054 à l'ancien 15 i/s haute
 * (720×1280). Plus de fluidité (×1,6) ET plus de bits par pixel.
 *
 * La cadence tenue est mesurée, pas supposée : recette `LUNETTES-RECETTE-TEL`
 * §3 (`parse_glasses_link.py`).
 */
internal const val GLASSES_FRAME_RATE = 24

/**
 * Essais de cadence dans l'ordre : 24 i/s puis 15 i/s, le repli prouvé.
 * `GlassesCamera` annonce le repli (`frameRateFallback`), jamais muet.
 */
internal val GLASSES_FRAME_RATE_LADDER = listOf(24, 15)

/**
 * Taux d'abandon au-delà duquel le flux des lunettes passe en qualité basse :
 * 5 % — à ce niveau le lien radio sature (jitter, freezes) et la rue plein
 * format n'arrive de toute façon plus à temps.
 */
internal const val GLASSES_LOW_DROP_RATE = 0.05

/**
 * Qualité vidéo demandée au flux : moyenne par défaut, basse quand le lien
 * sature ([dropRate] > 5 %). Pur, testé en JVM.
 */
internal fun glassesVideoQuality(dropRate: Double): VideoQuality =
    if (dropRate > GLASSES_LOW_DROP_RATE) VideoQuality.LOW else VideoQuality.MEDIUM

/**
 * Configuration du flux vidéo des lunettes pour l'écran iSee (production).
 *
 * - **Qualité moyenne** (`VideoQuality.MEDIUM` = 504×896 portrait) : SAM reçoit
 *   du 320/q55 dans tous les cas (`ISEE_FRAME_EDGE`, `ISEE_FRAME_QUALITY`) —
 *   504 suréchantillonne encore la cible 1,6×, et le meilleur ratio bits/pixel
 *   (0,069 contre 0,054 en haute à 15 i/s) réduit les macroblocs à la source.
 *   Contrepartie écran : la rue iSee (~1080 px de large) agrandit ~2,1× au
 *   lieu de ~1,5× — aperçu plus doux, analyse inchangée.
 * - **Repli bas automatique** : [dropRate] > 5 % ([GLASSES_LOW_DROP_RATE])
 *   demande `LOW` ([glassesVideoQuality]) — un lien saturé ne porte plus le
 *   format moyen, et la rue réduite à temps vaut mieux que la rue pleine en
 *   retard. Le repli s'applique à l'attache suivante (jamais de reconfiguration
 *   en cours de session : le SDK ne le permet pas sans couper le flux).
 * - **24 i/s** ([GLASSES_FRAME_RATE], [frameRate]) : sur la grille du SDK,
 *   tenu sans accrochage ; [GLASSES_FRAME_RATE_LADDER] = [24, 15], 15 = repli
 *   prouvé annoncé par `frameRateFallback`.
 * - **Vidéo compressée** : le téléphone reçoit du H.265 et le rend en direct
 *   (`GlassesLiveDecoder`), sans ré-échantillonnage intermédiaire.
 *
 * ⚠️ Le SDK n'expose ni bitrate ni résolution directe : la qualité est le
 * seul levier spatial, le débit restant fixé en interne à 750 kbit/s quel que
 * soit le réglage demandé — il n'y a pas de levier MTU/GATT côté app, le
 * transport radio est 100 % SDK Meta DAT (aucun socket ni `BluetoothGatt` dans
 * `app/` ni `core/`, vérifié par grep). Tout correctif lien est donc applicatif
 * (bits/frame, copies, verrous), jamais radio.
 *
 * Pure (le SDK s'instancie sur JVM) : prouvée par `GlassesStreamConfigTest`.
 * Le banc (`bench/GlassesDeviceSession`) garde sa propre config : outil LLM,
 * pas écran — il ne partage ni la cadence ni le besoin.
 */
internal fun glassesStreamConfiguration(
    audioCodec: AudioCodec?,
    frameRate: Int = GLASSES_FRAME_RATE,
    dropRate: Double = 0.0,
): StreamConfiguration =
    StreamConfiguration(
        audioCodec = audioCodec,
        videoQuality = glassesVideoQuality(dropRate),
        frameRate = frameRate,
        compressVideo = true,
    )
