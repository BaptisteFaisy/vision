package dev.sam3vision.app.perms

/**
 * Permission gérée par le flux de demande : micro (dictée, navigation vocale),
 * position d'avant-plan (point de départ d'itinéraire, partage Direct) et
 * position d'arrière-plan (suivi écran éteint).
 */
enum class PermKind { MIC, LOCATION_FG, LOCATION_BG }

/**
 * Étape décidée par [nextStep] : continuer (déjà accordée), demander au
 * système, expliquer d'abord (refusée une fois), ou renvoyer vers les
 * Réglages (l'utilisateur a coché « Ne plus demander »).
 */
enum class PermStep { PROCEED, ASK, RATIONALE, SETTINGS }

/**
 * Table de décision pure (testée en JVM, sans Android) :
 *
 * - accordée → [PermStep.PROCEED] ;
 * - jamais demandée → [PermStep.ASK] (pas d'explication avant le premier
 *   dialogue système, selon les recommandations Android) ;
 * - refusée mais redemandable (`shouldShowRequestPermissionRationale`) →
 *   [PermStep.RATIONALE] ;
 * - refusée avec « Ne plus demander » → [PermStep.SETTINGS].
 */
fun nextStep(granted: Boolean, askedBefore: Boolean, showRationale: Boolean): PermStep = when {
    granted -> PermStep.PROCEED
    !askedBefore -> PermStep.ASK
    showRationale -> PermStep.RATIONALE
    else -> PermStep.SETTINGS
}

/**
 * Position d'avant-plan utilisable : fine ou approximative (coarse). Quand
 * l'utilisateur n'accorde que l'approximative, le dialogue rend `fine=false,
 * coarse=true` : refuser ce cas priverait de position pour rien.
 */
fun fgGranted(fine: Boolean, coarse: Boolean): Boolean = fine || coarse

/**
 * Règle de séquencement Android : la position d'arrière-plan ne se demande
 * qu'une fois l'avant-plan accordé — groupée ou anticipée, la demande est
 * ignorée silencieusement par le système (Android 11+).
 */
fun canAskBackground(fgGranted: Boolean): Boolean = fgGranted

/** URI `package:` de la fiche de l'app dans les Réglages système. */
fun appSettingsUri(packageName: String): String = "package:$packageName"

/** Textes du flux pour une permission : explication, repli Réglages, refus. */
data class PermCopy(
    val rationaleTitle: String,
    val rationaleMessage: String,
    val settingsMessage: String,
    val deniedMessage: String,
    val confirmLabel: String,
)

/**
 * Textes FR du flux permissions, source unique (convention : les chaînes UI
 * courantes vivent en dur dans le Kotlin, comme « J'écoute »). [MIC_DENIED]
 * reprend à l'identique le libellé historique des bandeaux de dictée.
 */
object PermTexts {
    const val MIC_DENIED = "Micro non autorisé"
    const val LOCATION_DENIED = "Position non autorisée"
    const val BG_DENIED = "Suivi en arrière-plan refusé"
    const val BG_NEEDS_FG = "Autorisez d'abord la position, puis le suivi en arrière-plan."
    const val SETTINGS_TITLE = "Permission refusée"
    const val ALLOW = "Autoriser"
    const val CONTINUE = "Continuer"
    const val OPEN_SETTINGS = "Ouvrir les réglages"
    const val LATER = "Plus tard"
}

/** Textes du flux pour [kind], jamais vides (gardé par les tests). */
fun copyFor(kind: PermKind): PermCopy = when (kind) {
    PermKind.MIC -> PermCopy(
        rationaleTitle = "Autoriser le micro",
        rationaleMessage = "iSee a besoin du micro pour écouter votre dictée et comprendre la destination.",
        settingsMessage = "Le micro est désactivé avec « Ne plus demander ». Ouvrez les Réglages pour le réactiver.",
        deniedMessage = PermTexts.MIC_DENIED,
        confirmLabel = PermTexts.ALLOW,
    )
    PermKind.LOCATION_FG -> PermCopy(
        rationaleTitle = "Autoriser la position",
        rationaleMessage = "iSee a besoin de votre position comme point de départ de l'itinéraire.",
        settingsMessage = "La position est désactivée avec « Ne plus demander ». Ouvrez les Réglages pour la réactiver.",
        deniedMessage = PermTexts.LOCATION_DENIED,
        confirmLabel = PermTexts.ALLOW,
    )
    PermKind.LOCATION_BG -> PermCopy(
        rationaleTitle = "Suivi en arrière-plan",
        rationaleMessage = "Pour partager votre position écran éteint, choisissez « Toujours autoriser » dans l'écran suivant.",
        settingsMessage = "La position en arrière-plan est désactivée avec « Ne plus demander ». Ouvrez les Réglages pour la réactiver.",
        deniedMessage = PermTexts.BG_DENIED,
        confirmLabel = PermTexts.CONTINUE,
    )
}
