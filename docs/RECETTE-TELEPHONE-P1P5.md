# Recette téléphone P1–P5 — runbook d'exécution

Acceptation appareil des 5 prompts intégrés sur `master`
(intégration `4023874`, HEAD recetté `892b9fb`).

> **État : PRÉPARÉ le 30/10/2026, non exécuté.** Le Redmi `2c6852ac` a été
> vu en début de session puis s'est déconnecté avant l'installation ; seul le Samsung
> `RF8T11NBMCV` restait en ligne, hors protocole (toutes les fiches ciblent
> le Redmi) → non touché. **Zéro verdict ci-dessous :** chaque checklist est
> vierge, à remplir par l'exécutant.

## 0. Build de référence

- SHA : `892b9fb6f0a1883c8d7fe4b4c1525948b1ef1992` (`origin/master` au
  moment du build ; la branche a été rebasée sur `161991c` pour le push,
  le build recetté reste `892b9fb`).
- `versionName = 1.0.664-892b9fb`, `versionCode = 1793364671`
  (corroboré : `git log %ct` = `aapt dump badging`, arbre propre au build).
- APK : `app/build/outputs/apk/debug/app-debug.apk` (209 463 435 o),
  bâti le 30/10/2026 à 14:52 par `.\gradlew.bat assembleDebug --offline`.
- Rebuild à l'identique : `git checkout 892b9fb6f0a1883c8d7fe4b4c1525948b1ef1992`
  puis la même commande ; contrôler par
  `aapt dump badging … | findstr versionName`.
- `bash scripts/test_scripts.sh` : **128/128 OK** (dont hygiène de
  `verify-street.sh`, § 1, et phrases E2E rue, § 8).

## 1. Pré-requis communs

- Redmi `2c6852ac` en ligne (`adb devices` → `device`), câble,
  autorisation RSA acceptée à l'écran.
- Annoncer prise/libération du téléphone selon la convention locale de la
  machine (`BOITE-USER.md`, cité par les fiches, n'est pas dans le dépôt).
- Toutes les commandes téléphone de ce runbook passent par
  `adb -s 2c6852ac` (sur r630/Linux, préférer le verrou `~/bin/phone`,
  règle `AGENTS.md` § 10, absent de ce poste Windows — noter l'adaptation
  au procès-verbal).
- Règle PV : à chaque item, noter **SHA du build + heure + observable**
  (phrase exacte entendue + texte écran). Tout écart part en
  **issue GitHub**, pas en commentaire.

Installation (une seule fois, avant P1) :

```powershell
adb -s 2c6852ac install -r app\build\outputs\apk\debug\app-debug.apk
adb -s 2c6852ac shell dumpsys package dev.sam3vision.app | Select-String versionName
# attendu : versionName=1.0.664-892b9fb
adb -s 2c6852ac shell pm grant dev.sam3vision.app android.permission.CAMERA
adb -s 2c6852ac shell pm grant dev.sam3vision.app android.permission.RECORD_AUDIO
adb -s 2c6852ac shell pm grant dev.sam3vision.app android.permission.ACCESS_FINE_LOCATION
adb -s 2c6852ac shell am force-stop dev.sam3vision.app
adb -s 2c6852ac shell am start -n dev.sam3vision.app/.MainActivity
```

Réglages iSee : clé ElevenLabs + voix renseignées, bascule « Guider vers
la destination confirmée » sur oui, bascule « Guider tout droit (caméra
iSee) » activée, **URL Valhalla (`routeBaseUrl`) renseignée**, Google Maps
installé et connecté.

Terrain P1/P4/P5 : **Île-de-France** (tuiles Valhalla), GPS fixé, réseau OK,
**de jour**.

Commandes de capture (à réutiliser à chaque protocole) :

```powershell
adb -s 2c6852ac logcat -c
adb -s 2c6852ac logcat -v time sam3vision:I DepthLive:I *:S
adb -s 2c6852ac shell logcat -d -b crash | Select-String dev.sam3vision.app
adb -s 2c6852ac shell uiautomator dump /sdcard/isee_ui.xml
adb -s 2c6852ac pull /sdcard/isee_ui.xml pv-Px-ui.xml
adb -s 2c6852ac shell screencap -p /sdcard/isee_Px.png
adb -s 2c6852ac pull /sdcard/isee_Px.png pv-Px.png
```

## 2. Ordre d'exécution

P1 → P2 → P3 → P4 → P5, sans réinstaller entre les protocoles (le kill de
P2 est volontaire, pas une réinstallation). Remettre l'interrupteur monde
sur « dehors » (défaut) en fin de P2.

## 3. P1 — Arbitre vocal, à l'oreille (`d375bb0`)

Chaîne : `VoiceNavSpeaker.isSpeaking` → `VoiceNavEngineDriver.isSpeaking`
→ `IseeVoiceNav.isRouteSpeaking` → `IseeViewModel.isRouteSpeaking` →
`ProximityVoice(routeSpeaking)`. Règle : obstacle **non urgent** jugé
pendant une consigne route → tu (mémorisé, **pas de rattrapage tardif**) ;
danger **urgent** → coupe toujours (`player.stop()`) ; **jamais deux voix**.

1. Lancer un guidage actif (destination à > 100 m, cf P4), marcher.
2. Pendant une consigne route entendue (« Continuez dans cette
   direction… » / « Tournez à … »), croiser un obstacle **non urgent**
   (personne à quelques mètres dans le couloir). Attendu : la consigne va
   au bout, l'obstacle ne parle pas. Noter phrase + heure.
3. Provoquer un danger **urgent** au contact (≤ 0,6 m,
   « obstacle, arrêtez-vous. ») pendant une consigne. Attendu : coupe
   franche, une seule voix. Noter phrase + heure.
4. Sur tout le trajet : vérifier que deux voix ne parlent jamais l'une sur
   l'autre (file ou coupe franche).

Checklist vierge P1 :

| # | Observable | SHA build | Heure | Verdict |
|---|---|---|---|---|
| 1 | Consigne route entendue entière, phrase exacte : … | | | |
| 2 | Obstacle non urgent tu pendant la consigne (pas de rattrapage) | | | |
| 3 | Danger urgent coupe la consigne, phrase exacte : … | | | |
| 4 | Jamais deux voix en même temps sur le trajet | | | |

## 4. P2 — Mondes dedans/dehors + kill + conceptsSent (`5505ea4`)

Réglages, section « Monde — dehors / dedans », interrupteur
« Dehors : rue + GPS (éteint = dedans) », aide
« Dedans : objets d'intérieur, pas de GPS. ». Dehors (défaut) : les 12
dangers rue (`LivePrompts.default`) ; dedans : les 7 objets d'intérieur
(`person, door, chair, table, stairs, wall, window`). Persisté
(`outdoor_mode`, prefs chiffrées `sam3vision_secure_settings`) : **la
bascule survit au kill**. Portée 4 m inchangée dans les deux mondes.

1. Ouvrir Réglages, noter l'état initial de l'interrupteur (dump UI :
   `checked=true/false` sur le nœud « Dehors : rue + GPS »).
2. Basculer sur « dedans » (éteindre), revenir à iSee, attendre des
   frames ; noter la headline SAM + le logcat (`sam3vision` :
   « première analyse : … »).
3. Kill : `am force-stop dev.sam3vision.app`, relancer, retourner dans
   Réglages : l'interrupteur est toujours éteint (dump UI) → persistance
   OK. Les prefs étant chiffrées, le dump UI est l'observable de
   persistance (pas de `grep` possible via `run-as`).
4. Rebasculer « dehors », kill + relance, vérifier allumé (dump UI).
5. ⚠️ Point d'attention lu dans le code, à confirmer sur appareil : la
   ligne « suivis (N) : … » (`conceptsSent`, `liveProbeDetails`) n'est
   affichée nulle part sur iSee (panneau dense = headline + erreurs
   seules, `LiveDebugPanel.kt:43-44`) et n'est écrite dans aucun logcat
   (`core/` pur JVM sans `Log`, aucun `Log` des concepts dans `app/`) →
   `conceptsSent` n'est pas vérifiable stricto sensu. Si confirmé,
   ouvrir une issue (afficher ou journaliser les concepts envoyés).

Checklist vierge P2 :

| # | Observable | SHA build | Heure | Verdict |
|---|---|---|---|---|
| 1 | État initial interrupteur « Dehors : rue + GPS » (dump UI) : … | | | |
| 2 | Monde « dedans » actif (headline + logcat « première analyse ») : … | | | |
| 3 | Après kill + relance : interrupteur toujours éteint (dump UI) | | | |
| 4 | Après re-bascule + kill + relance : interrupteur allumé (dump UI) | | | |
| 5 | `conceptsSent` observable (ligne « suivis » ou log) : … (sinon issue) | | | |

## 5. P3 — Calibration 20 panneaux (`9a7ecc7`, `VOIX-DANGER.md` § 15)

Filtre tête : `SignConcepts.isHighSign`, seuils **provisoires**
`HEAD_TOP = 0,22` / `HEAD_RANGE_M = 4,0 m` ; panneau au sténopé 2,20 m ;
distance inconnue ou non finie ⇒ panneau gardé (prudence). But : calibrer
les deux seuils sur corpus réel.

1. Preset Extérieur (suit « sign »), téléphone tenu à hauteur de poitrine,
   tangage neutre. Pour chaque détection panneau, relever : `centerY`
   normalisé (0 = haut de l'image), `meters` (sténopé ou profondeur),
   annoncé ou non (phrase exacte + heure).
2. **Corpus A** : 10 panneaux à hauteur d'homme (affiches basses, panneaux
   d'info, pub à hauteur des yeux). Attendu : 100 % annoncés à < 3 m.
3. **Corpus B** : 10 panneaux routiers ou pubs hautes (enseignes de
   vitrine, mâts, 4×3). Attendu : 0 annonce (0 bavardage).
4. Remplir les deux tables `VOIX-DANGER.md` § 15 (lieu, panneau, `centerY`,
   `meters`, annoncé ?), dater le relevé. **Aucun seuil ne change sans son
   corpus** (réglage + tests de frontière ensuite, hors recette).

Checklist vierge P3 :

| # | Observable | SHA build | Heure | Verdict |
|---|---|---|---|---|
| A1-A10 | Corpus A rempli (10 lignes, lieu + centerY + meters + annoncé) | | | |
| A | 100 % des A annoncés à < 3 m | | | |
| B1-B10 | Corpus B rempli (10 lignes, lieu + centerY + meters + annoncé) | | | |
| B | 0 annonce sur les B (0 bavardage) | | | |

## 6. P4 — Dictée destination > 100 m, wording virage (`32b11dd`, `NAVIGATION.md`)

Contrat : `TURN_NOW_M = 25 m` ; au-delà, préavis directionnel
« Continuez dans cette direction, rue X. » **dit une seule fois** ; en
deçà ou sans distance, ordre sec « Tournez à gauche, rue X. »
**sans distance** ; aucun virage franc ne contient « dans X mètres ».
Inchangés (distances conservées) : demi-tour (« Faites demi-tour dans X »),
giratoire (« Au rond-point, suivez la route dans X »),
traversée (« Passage protégé dans X »), arrivée (« Vous êtes arrivé à X »).

Prérequis : `routeBaseUrl` renseignée (proxy Valhalla), GPS actif, parcours
avec un virage franc à **plus de 100 m** du départ. Noter SHA, heure et
phrases entendues.

1. Dicter une destination dont l'itinéraire commence par plus de 100 m
   puis un virage franc nommé (rue X). Attendu : une seule fois
   « Continuez dans cette direction, rue X. », puis silence jusqu'au
   carrefour (aucune répétition à distance intermédiaire).
2. Marcher jusqu'au carrefour. Attendu : « Tournez à gauche, rue X. »
   (ou droite), **sans distance**, audible avant d'avoir à tourner.
3. Pendant tout le trajet : vérifier qu'aucun virage franc entendu ne
   contient « dans X mètres ».
4. Demi-tour, giratoire, traversée, arrivée : vérifier les phrases
   inchangées du contrat ci-dessus (distances conservées).

Checklist vierge P4 :

| # | Observable | SHA build | Heure | Verdict |
|---|---|---|---|---|
| 1 | Préavis unique « Continuez dans cette direction, rue X. », heure : … | | | |
| 2 | Ordre sec sans distance au carrefour, phrase exacte : … | | | |
| 3 | Aucun « dans X mètres » sur virage franc du trajet | | | |
| 4 | Phrases inchangées (demi-tour / giratoire / traversée / arrivée) : … | | | |

## 7. P5 — Rue : `verify-street.sh` + `RECETTE-RUE.md` (`5df0ff1`)

Renvoyé tel quel (pas de duplication) :

- Script : `scripts/verify-street.sh` — interactif, **~15 min, une prise** :
  dictée d'une destination à ~200 m, obstacles (poteau, poubelle/benne,
  panneau bas, passage piéton, animal si possible), virage, traversée auto
  au rappel proche, arrivée. Il capture l'observable horodaté (phrases
  exactes, écran, crash) et rend la checklist remplie + `shot-*.png`.
- Fiche : `docs/RECETTE-RUE.md` — tableau des observables promis par le
  code (§ Ce qui est vrai), prérequis (IdF, GPS, réseau, jour, parcours
  ~200 m repéré), recette en 9 étapes, **checklist vierge (16 lignes)**.

Notes poste Windows : lancer sous Git Bash
(`"C:\Program Files\Git\bin\bash.exe" scripts/verify-street.sh`) ;
`EXPECT_REF=master` (défaut) ; `APK=app/build/outputs/apk/debug/app-debug.apk`
(défaut OK) ; verrou `~/bin/phone` absent ici → `PHONE_BIN` adapté ou `adb
-s 2c6852ac` direct (noter l'adaptation au PV). Recette interactive :
terminal requis (marche + voix + Entrée à chaque étape).

## 8. Clôture

- Chaque checklist remplie (observable + SHA + heure + verdict), PV +
  captures archivés, **une issue GitHub par écart constaté**.
- Fin de P2 : interrupteur monde remis sur « dehors » (défaut).
- Ce runbook ne pré-remplit aucun verdict : tout résultat vient de
  l'appareil, jamais du code lu.
