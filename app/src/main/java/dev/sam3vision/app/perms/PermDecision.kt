package dev.sam3vision.app.perms

/**
 * Règles et textes de permission qui n'ont besoin d'aucun objet Android (testables en JVM).
 *
 * L'app demande ses quatre permissions runtime **d'un seul geste** au premier lancement
 * (`FirstRunPermissions.kt`) : le flux par permission (explication, « ne plus demander »,
 * renvoi vers les Réglages) n'a plus d'appelant et a été retiré le 29/10/2026, avec sa table
 * de décision `nextStep`/`PermStep`/`PermKind`/`PermCopy`. Ce qui reste ici est lu par du code
 * de production — ne rien y ajouter qui ne le soit pas.
 */

/**
 * Règle de séquencement Android : la position d'arrière-plan ne se demande qu'une fois
 * l'avant-plan accordé — groupée ou anticipée, la demande est ignorée silencieusement par
 * le système (Android 11+). Lue par `MainActivity` au premier lancement.
 */
fun canAskBackground(fgGranted: Boolean): Boolean = fgGranted

/** Textes FR du refus de permission, source unique (les chaînes UI vivent en dur dans le Kotlin). */
object PermTexts {
    const val MIC_DENIED = "Microphone not allowed"
}
