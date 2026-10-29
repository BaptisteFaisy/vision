# Carte OSM du guidage — source, repli, batterie, CI, recette

Référence (français) de la carte affichée pendant le guidage vocal in-app
(Valhalla, piéton, Île-de-France). Le contrat technique de la navigation
reste `docs/NAVIGATION.md`, le fonctionnel vocal `docs/NAVIGATION-VOCALE.md`
— ce document n'y touche pas, il les cite.

**Périmètre** : fond de carte MapLibre + tracé pendant
`VoiceNavUiState.Guiding`, affiché sur l'accueil iSee sous la caméra
(`IseeGuidanceBlock`, qui remplace la boule pendant le guidage).
Hors périmètre : géocodage (§7.1 de `NAVIGATION-VOCALE.md`), itinéraire
Valhalla, carte plein écran, mode avion avec guidage neuf (impossible :
ni Valhalla ni BAN ne répondent — § Limites de `NAVIGATION.md`).

## 1. Source de tuiles retenue

**Tuiles raster OSM France (style osmfr, libellés français), HTTPS direct,
sans clé** (`guidemap/GuideMapStyle.kt`) :

```
https://{a,b,c}.tile.openstreetmap.fr/osmfr/{z}/{x}/{y}.png
```

Rendue par **MapLibre Native** (`org.maplibre.gl:android-sdk:13.7.1`,
Maven Central, BSD-2, `MapView` classique dans `AndroidView`) :
`app/.../guidemap/GuidanceMapView.kt` charge un style JSON local (source
raster unique, zoom natif 20) et y dessine tracé, arrivée, position +
précision, passages piétons, feux et pastilles de manœuvres
(`core/.../nav/GuidanceMapData.kt`, pur JVM).

Pourquoi pas CARTO : depuis 2026, `basemaps.cartocdn.com` exige une clé
d'API (`carto.com/basemaps/apikey`, paramètre `key=`) — sans clé, le CDN
ne renvoie qu'un filigrane « API KEY REQUIRED ». Une clé = un secret à
gérer pour un gain nul. Écarté ; réévaluer si le volume dépasse la
politesse OSM-FR.

Pourquoi pas de tuiles vectorielles autohébergées : un serveur à opérer
pour une carte de 170 dp — disproportionné.

## 2. Politique d'usage et attribution

Données © les contributeurs OpenStreetMap (**ODbL**, attribution
obligatoire), fond : OpenStreetMap France (CC BY-SA). Mentions dans le
style (`attribution`) et en surimpression permanente bas-gauche
(« © OpenStreetMap contributors »).

**Règles appliquées** : URL exacte en HTTPS ; tuiles de la zone visible
uniquement (pas de pré-chargement, pas de scraping, pas de pré-cache
d'itinéraire) ; cache disque du SDK ; zoom borné (natif 20, client 22).

## 3. Repli hors-ligne : tuiles en échec → schéma MiniRouteMap

Le schéma existe et ne dépend d'aucun réseau pour son dessin :
`MiniRouteMap` dessine sur `Canvas` le tracé, l'arrivée, la position, le
cercle de précision et les feux, nord en haut. Seule la liste des feux
vient du réseau (`fetchSignals`), déjà repliée : échec = liste vide +
« 0 feu », nouvelle tentative après 60 s (`SIGNALS_RETRY_MS`).

### 3.1 Déclencheur exact

`GuidanceMapCard` (`guidemap/GuideMapLive.kt`) bascule sur le schéma dès
que le SDK signale l'échec du chargement (`OnDidFailLoadingMapListener` :
style ou tuiles injoignables — timeout, HTTP ≥ 400, DNS, TLS, mode
avion). Le repli dure jusqu'à la sortie d'écran : rouvrir l'écran
réessaie les tuiles. Pas de boucle de rechargement en tâche de fond.

### 3.2 UX exacte du repli

- **Aucun écran d'erreur, aucun blocage** : le guidage vocal continue,
  l'accueil garde son instruction, son reste de trajet et son bouton
  « Arrêter le guidage ».
- Le schéma `MiniRouteMap` s'affiche **tel quel** dans le même cadre
  170 dp : tracé + arrivée + position + feux (ou « 0 feu » si les feux
  aussi sont en échec réseau).
- La mention d'attribution OSM disparaît avec les tuiles (elle crédite
  le fond affiché) ; aucun texte « hors-ligne » n'est ajouté —
  l'utilisateur malvoyant suit la voix, pas le fond.
- Si l'itinéraire lui-même est perdu (pas seulement les tuiles), c'est
  le repli moteur qui s'applique, inchangé : Maps piéton + retour au
  repos (`NAVIGATION.md`, « Si le moteur ne peut pas démarrer »).

### 3.3 Ce qui reste affiché (récapitulatif)

| Couche | Tuiles OK | Tuiles KO |
|---|---|---|
| Instruction + reste | oui | oui (inchangé) |
| Carte (tracé, arrivée, position, passages, feux, manœuvres) | fond OSM + surcouches | schéma seul (inchangé) |
| Attribution « © OpenStreetMap contributors » | oui | **non** (avec les tuiles) |
| Bouton « Arrêter le guidage » + micro d'appui long | oui | oui (inchangé) |

## 4. Comportement carte (sans scintillement)

- Le statique (tracé, pastilles) n'est redessiné que quand il change ;
  à chaque tick (1 Hz), seule la position est rafraîchie.
- La caméra ne bouge que si la cible a bougé de plus de 10 m ou si le
  zoom change — le suivi ne tremble pas et n'arrache pas le doigt.
- Zoom déduit de l'étendue du tracé (miroir de l'aspect-fit de
  `RouteMapProjection`, clamp 2..19), cible = position sinon centre du
  tracé, nord en haut. Sans tracé, rien n'est composé (comme la
  mini-carte).
- Limite connue v1 : `upcoming` vaut `false` partout — le moteur
  n'expose pas l'index de la prochaine manœuvre (`GuidanceUpdate` ne
  porte ni index ni identifiant), donc aucune pastille n'est surlignée.
  Les pastilles portent le libellé (« Tournez à droite sur rue de
  Rivoli ») ; l'allumage viendra avec le moteur.

## 4 bis. Pas de carte sans destination (retiré le 29/10/2026)

Un mode navigation sans destination a existé (bande permanente en bas de
l'écran iSee) : retiré sur ordre user — la carte n'apparaît que pendant le
guidage, avec un tracé. Voir `NAVIGATION-VOCALE.md` §1 bis.

## 5. Notes batterie et réseau

- **Surface bornée** : la carte fait 150 dp de haut sur l'accueil
  (`IseeGuidanceBlock`) — quelques tuiles 256 px par zone, pas une carte
  plein écran. Zoom borné (natif 20) : chaque niveau × 4 tuiles.
- **Pas de pré-chargement** : tuiles de la zone visible uniquement. Le
  polling à 1 Hz (`ROUTE_MAP_POLL_MS`) ne relit que l'itinéraire et la
  position (locaux), jamais des tuiles.
- **Cache disque** : une zone déjà guidée ne retélécharge rien. Ordre
  de grandeur non mesuré ici : une tuile 256 px pèse quelques dizaines
  de Kio ; un guidage = quelques dizaines de tuiles.
- **GPS inchangé** : le guidage utilise déjà la position ; la carte
  n'ajoute aucun capteur.
- **Mode avion en cours de guidage** : consignes vocales système seules
  (hors-ligne), schéma affiché, tuiles masquées (§3) — pas de boucle de
  rechargement.

## 6. Permissions

- `INTERNET` : **présente** — les tuiles HTTPS partent par elle, aucune
  déclaration à ajouter.
- Localisation : présente — la carte n'en demande pas de nouvelle.
- Stockage : aucun (cache dans le stockage privé de l'app, sans
  permission depuis Android 10) ; `network_security.xml` inchangé
  (tuiles en HTTPS).

## 7. Impact CI

- **Poids** (mesuré le 29/10/2026, APK debug universel r630) :
  `libmaplibre.so` ≈ 13,0 Mo (arm64-v8a), 9,6 Mo (armeabi-v7a),
  13,4 Mo (x86), 13,6 Mo (x86_64) — ≈ 49,7 Mo au total sur les
  4 ABI (`.so` stockés non compressés, standard). Par appareil
  (1 ABI) : ≈ 13 Mo.
- **Budget 5 min** : inchangé. Le job `ci` (PR) ne compile pas le
  natif (natif dans `ci-native.yml`, hors PR, `timeout-minutes: 10`
  justifié — `CI-QUEUE.md`).
- **Déclaration** (AGENTS.md §3.4) : `org.maplibre.gl:android-sdk:13.7.1`
  déclarée dans `app/DEPS.txt` **avant** usage.
- **Gardes** : aucun garde neuf ; fichiers sous les plafonds.

## 8. Recette téléphone (après merge, sur master)

Préalables (règles ISEE-TEST-MASTER-SEUL + téléphone) : build du HEAD de
`origin/master` (`git ls-remote origin master` = SHA de l'APK), Redmi
`2c6852ac`, `ROUTE_BASE_URL` renseignée, occupation via `phone run`,
annonce prise/libération dans `BOITE-USER.md`. Destination IdF
(guidage Valhalla piéton uniquement).

1. Dicter « je veux aller à la gare de Lyon » → confirmer « Démarrer »
   → retour à l'accueil : la carte OSM sous la caméra (rues + tracé
   bleu par-dessus) **et** l'instruction en cours. **Capture 1** :
   guidage + tuiles.
2. Vérifier l'attribution « © OpenStreetMap contributors » visible en
   bas à gauche de la carte. **Capture 2** : zoom sur l'attribution.
3. Marcher : la position suit sans scintillement, la caméra ne tremble
   pas. **Capture 3** : suivi en mouvement.
4. Passer en mode avion en cours de guidage → le schéma seul reste
   (tracé, position, « 0 feu » si les feux tombent aussi), la voix
   système continue. **Capture 4** : schéma seul, sans tuiles.
5. S'écarter de l'itinéraire → recalcul moteur ; la carte suit la
   nouvelle zone, pas de flash d'erreur.
6. « Arrête le guidage » → retour accueil iSee, voix « Guidage arrêté »,
   carte démontée (pas de téléchargement en tâche de fond — vérifier au
   compteur réseau).

Chaque point : SHA du build, heure, observable (motif §4 de
`NAVIGATION-VOCALE.md`).

## 9. Versions épinglées

| Élément | Version | Preuve |
|---|---|---|
| MapLibre (`android-sdk`) | **13.7.1** (Maven Central, BSD-2) | métadonnées repo1.maven.org, relevé du 29/10/2026 |
| Tuiles | `https://{a,b,c}.tile.openstreetmap.fr/osmfr/{z}/{x}/{y}.png` | tuile z12 vérifiée 200 le 29/10/2026 |
| Licence des données | ODbL + attribution « © OpenStreetMap contributors » | `openstreetmap.org/copyright` |
| Gradle / AGP / Kotlin / SDK | 8.14.1 / 8.11.1 / 2.2.21 / compile+target 36, minSdk 29 | `NOTES.md`, inchangé |

## 10. Interdits

- Ne pas coder l'URL des tuiles en dur hors `GuideMapStyle` (constantes
  groupées, bascule centralisée).
- Ne pas pré-charger, pré-cacher ni scraper des tuiles (§2 + §5).
- Ne pas bloquer ou bruiter le guidage quand les tuiles échouent (§3.2).
- Ne pas ajouter de permission de localisation ou de stockage pour la
  carte (§6).
- Ne pas tester depuis une branche en retard sur `master` ni un APK au
  SHA inconnu (§8).
- Ne pas louer une deuxième carte GPU pour tester (règle « une seule GPU »).
