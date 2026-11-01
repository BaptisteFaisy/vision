package dev.sam3vision.app.glasses

/**
 * Politique du bandeau d'enregistrement, sortie de [GlassesPolicy] (garde 500
 * lignes) : que dire à chaque état du SDK, en tenant compte du bandeau affiché.
 * Pur — pas d'Android, pas de SDK DAT — donc testé en JVM.
 */

/**
 * Ce que la **perte** d'enregistrement doit dire, et quand se taire : rien au premier état
 * (au démarrage, `wasRegistered` est faux), rien quand c'est l'utilisateur qui s'est
 * désenregistré (`wanted`), sinon l'avertissement. Pur, donc testé en JVM.
 */
fun registrationLostMessage(
    wasRegistered: Boolean,
    registered: Boolean,
    wanted: Boolean,
): GlassesMessage? = when {
    !wasRegistered || registered -> null
    wanted -> null
    else -> GlassesMessages.registrationLost()
}

/**
 * Ce que le bandeau doit dire après un état d'enregistrement, en tenant compte
 * du bandeau déjà affiché : sans lui, le message « accept, then come back here »
 * survivait à la confirmation et cohabitait avec la ligne « registered » —
 * l'écran se contredisait. Pur, donc testé en JVM.
 */
fun registrationStateMessage(
    wasRegistered: Boolean,
    registered: Boolean,
    wanted: Boolean,
    current: GlassesMessage?,
): GlassesMessage? = when {
    registered && current == GlassesMessages.registrationStarted() -> GlassesMessages.registrationConfirmed()
    registered && !wasRegistered && current == null -> GlassesMessages.registrationConfirmed()
    !registered -> registrationLostMessage(wasRegistered, registered, wanted) ?: current
    else -> current
}
