# P6-5 — Note de décision : repli quand les lunettes décrochent

> Statut : **instruction, pas une décision produit**. P6 reste **backlog** :
> aucun code, aucune bascule câblée par cette note.
> Rédigée le 2026-10-30 depuis `origin/master` (`892b9fb`), par lecture du
> code. Les valeurs d'hystérésis proposées sont des défauts à valider en
> P6-0 (S2) puis en recette de coupure réelle.

Parent : `docs/LUNETTES-META-CADRAGE.md` (§2 point 5, §5 point ouvert
n° 1, §4 étape P6-5).

## 1. Signaux disponibles (déjà dans le code)

| Signal | Source | Sens |
|---|---|---|
| `DeviceSessionState.STOPPED` / `STOPPING` → phase `STOPPED` | `GlassesController.onSessionState` (`:299-308`), collecteur par session | session morte (paire retirée, BT perdu, fermée par les lunettes) ; une session `STOPPED` n'est plus une session (`allowsConnect`) |
| `DeviceSessionError` (18 valeurs) | `created.errors`, phrases une par une (`sessionErrorMessage`) | cause fine : `DEVICE_DISCONNECTED`, `SESSION_ENDED_BY_DEVICE`, `THERMAL_*`, `BATTERY_CRITICAL`, `PEAK_POWER_SHUTDOWN`… |
| `StreamError` (8 valeurs) | `attached.stream.errorStream` (`GlassesCamera.watch`) | cause flux : `HINGE_CLOSED` (branches refermées), `THERMAL_HOT`, `BATTERY_LOW`, `PEAK_POWER_LIMIT`, `TIMEOUT`… |
| `PAUSED` (paire reposée) | affiché « en cours » (`docs/META-GLASSES.md` §3 ter) | trou de guidage potentiel, aujourd'hui muet |
| `GlassesVideoLink.live` / `streaming` | passerelle frames | `live` = image rendue ; `streaming` = flux ouvert même avant la première image |
| `GlassesLinkGate` (30 s) | attente du lien `CONNECTED` | précédent de reconnexion bornée avec message (`waitingForLink`, `noLinkedDevice`) |
| `GlassesMicLink.detach()` | fin de session | la dictée retombe **déjà** sur le micro du téléphone — le repli micro existe, le repli vue non |

## 2. Options

### A. Pause annoncée (recommandée en poche)

À la perte du flux : annoncer « lunettes déconnectées, guidage en
pause », arrêter d'envoyer des frames à SAM (`stop()` + `/release`,
garde-fou inchangé — `LiveProbeStop.kt`), tenter une reconnexion
bornée (§4), reprendre seul si ça revient.

### B. Bascule caméra du téléphone, si et seulement si l'app est visible

Écran rallumé / app au premier plan : la caméra du téléphone peut voir,
donc elle reprend. À noter : le code le fait **déjà par construction**
quand `live` retombe — `wantsFrame` téléphone rend faux seulement si
`GlassesVideoLink.live` est vrai (`IseeVisionCard.kt:352-355`). La
bascule « visible » n'est donc pas un mécanisme à inventer, c'est le
comportement actuel à préserver.

### C. Bascule caméra du téléphone en poche (rejetée)

Une caméra en poche filme du noir : envoyer ces frames à SAM, c'est de
la facturation pour du mensonge (« jamais de rue noire » est la règle
de `GlassesVideoLink.markRendered`). **Interdit**, même en option.

### D. Arrêt complet + notification (variante de A)

Comme A, mais sans reconnexion automatique : l'utilisateur relance.
À garder comme palier de dégradation si les tentatives de reconnexion
s'avèrent coûteuses (mesure P6-0 §8 : arrêt auto si session perdue
> 60 s).

## 3. Recommandation : A en poche, B si visible, jamais C

- **En poche / écran éteint** (cas nominal du P6) : **pause annoncée**
  (A), reconnexion bornée, reprise seule. La voix porte l'annonce : en
  poche, il n'y a pas d'écran à lire — le bandeau Réglages ne suffit
  pas (déjà signalé comme trou, `docs/META-GLASSES.md` §7).
- **App visible** : **bascule téléphone** (B), qui est le comportement
  actuel à ne pas casser.
- **Jamais C.** Si le produit veut un repli « qui voit » en poche, il
  n'existe pas : c'est la décision à acter, pas un point à rouvrir.

Phrases proposées (motif `GlassesPolicy`, gravité portée avec le texte) :

- perte : « Lunettes déconnectées, guidage en pause. » (+ cause courte
  quand le SDK la donne : « branches refermées », « lunettes chaudes »,
  « batterie des lunettes basse » — phrases déjà écrites dans
  `streamErrorMessage` / `sessionErrorMessage`) ;
- reprise lunettes : « Lunettes reconnectées, guidage repris. » ;
- bascule visible : « Guidage repris sur le téléphone. » ;
- abandon (D) : « Guidage arrêté : relance la session dans Réglages. »

## 4. Hystérésis proposée (défauts, produit tranche)

- Annonce de perte : dès `STOPPED` avéré **ou** 10 s sans frame décodable
  (un `PAUSED` bref ne doit pas couper la voix à chaque paire reposée).
- Reconnexion : 3 tentatives sur 90 s (précédent : `GlassesLinkGate`
  30 s), puis pause longue + notification (palier D).
- Reprise : 3 s de flux décodable continu avant d'annoncer le retour
  (anti-ping-pong).
- Comportement annoncé en < 30 s dans tous les cas (seuil N repris du
  cadrage §4 P6-5, à confirmer par la mesure S2).

## 5. Ce qui bloque la tranche P6-5

1. **P6-0, scénario S2** : la session survit-elle au verrouillage ?
   Quel délai de reprise ? Ces deux constats dimensionnent l'hystérésis.
2. **Décision produit** : phrases (§3) et valeurs (§4) actées ; sort du
   palier D (reconnexion bornée puis arrêt, ou arrêt direct).
3. **Politique `PAUSED`** : paire reposée = trou muet ou annonce
   (« guidage en pause, remets les lunettes ») ? Le `PAUSED` affiché
   « en cours » aujourd'hui ne convient pas au guidage poche.
4. **Détecteur** (design P6-2) : qui écoute — le service (P6-3) ou le
   contrôleur relayé ? Les points d'écoute existent (`sessionWatch`,
   `errorStream`), leur hôte en poche non.
5. **Garde-fou facturation** : `stop()` + `/release` sur chaque sortie
   (session perdue, budget épuisé, arrêt manuel) — règle rappelée au
   cadrage §5 point 3, à tester en JVM (motif `GlassesPolicyTest`).

## 6. Critères de décision (le repli est tranché quand)

- [ ] Produit a acté : phrases, valeurs d'hystérésis, palier D oui/non,
      politique `PAUSED`.
- [ ] S2 livré : survie session + délai reprise mesurés.
- [ ] Grille de recette de coupure réelle écrite (paire éteinte, hors
      portée, branches refermées, batterie basse, `PAUSED`) : chaque
      cas → comportement annoncé en < N s, **aucune frame noire
      facturée**, reprise sans redémarrage de l'app.
- [ ] Recette sur coupure réelle exigée (jamais mock seul — cadrage §4).

## 7. Non tranché par cette note

Le service de premier plan (`P6-3-SERVICE-PREMIER-PLAN.md`),
l'arbitrage micro (`MicBus`, P6-4), la pompe headless (P6-1).
