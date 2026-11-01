# LUNETTES — recette téléphone (cadrage + latence)

> iSee sur lunettes Ray-Ban Meta + Redmi `2c6852ac`. Tout test part d'un build du
> HEAD de `origin/master`, SHA vérifié avant chaque install (`git ls-remote origin
> master` == SHA du build). Jamais d'émulateur, jamais de branche périmée. Téléphone
> sous verrou (`phone run --wait 600 --task "…" -- …`), prise/libération annoncées
> en append dans `.scratch/broadcast/BOITE-USER.md`. GPU : on ne touche à rien
> (`sam3-eu-a100` déjà déployée, aucun conteneur allumé pour ces tests).

## 1. Cibles

- **(A) Cadrage** : la rue des lunettes est nette, non déformée, sans freeze, au
  bon ratio (HIGH = 720×1280 portrait natif, 15 i/s, H.265).
- **(B) Latence lunettes → téléphone** : frame affichée en **< 100 ms** (p95,
  60 s à 15 i/s), mesurée comme ci-dessous.
- **(C) Latence vers le GPU Modal** : voir § 5 — l'aller-retour cloud a un
  plancher mesuré qui dépasse 50 ms ; on minimise l'aller et on prouve chaque ms.

## 2. Budget local (B) : ~73 ms en médiane, à valider sur matériel

| étape | poste | ordre de grandeur |
| --- | --- | --- |
| capture + encode H.265 | lunettes (opaque) | ~20 ms |
| air radio (~8 Ko/frame) | lien DAT | ~30 ms + jitter |
| réassemblage SDK → `videoStream` | SDK Meta | ~3 ms |
| `pushCopy` + `push` | téléphone | ~0,5 ms |
| Annex B + `MediaCodec` HW 720p | téléphone | ~10 ms |
| vsync + upscale GPU | affichage | ~8 ms moy (16,7 max) |
| **total nominal** | | **~73 ms** |

Queues à risque : jitter radio, vsync (0–16,7 ms), IDR manquante au démarrage.
Le handshake (lien 2–30 s, session 0,1–2 s) est one-shot, hors budget.

## 3. Mesure (B) : protocole

1. **B1 — horloges (préalable bloquant).** Logger 150 frames
   (`logcat -s GlassesChain`, ligne `flux lunettes` : `ptsUs` + `rxMs`) et estimer
   le décalage pts ↔ `elapsedRealtime` (voir `GlassesClockOffset`, min-filtre).
   Sans ça, aucun chiffre absolu n'est valable.
2. **B2 — bout en bout.** `t0` = pts à `pushCopy`, `t1` = sortie décodeur, `t2` =
   `renderedSeq` publié (`markRendered(seq)`). PASS si `t2 − t0` < 100 ms en p95
   sur 60 s. Vérifier `collectFrames=false` (zéro appel SAM/LLM pendant la mesure).
3. **B3 — budget.** Reporter les étages mesurés ici (pas dans le budget du Direct
   caméra-téléphone, `budget_latence.py`, qui est un autre chemin).
4. Seuils d'alerte : warn p95 > 70 ms, critique p95 > 100 ms ou p99 > 150 ms ;
   warn pertes > 5 %, critique > 20 % ; critique ≥ 2 reconnexions / 5 min.

Tags logcat : `GlassesChain` (pompe), `GlassesVideo` (décodeur),
`GlassesBridge` (pont sonde), `GlassesLink` (résumé 1/s).

## 4. Cadrage (A) : scénarios

- **A1 — flux simulé** (sans lunettes) : `MockGlassesTest.uneFrameArriveDuFluxSimule`
  en `connectedDebugAndroidTest` sous verrou. PASS = la frame simulée arrive et se rend.
- **A2 — HIGH tient ?** Route Bench + flux réel : lire `flux lunettes : N frames
  (WxH, …)` → WxH == 720×1280 ? `live=true` à la 1re image rendue, rue nette sans
  freeze. FAIL (freezes, décodeur muet) → repli MEDIUM, un seul endroit :
  `GlassesStreamConfig.kt`.
- **A3 — recadrage** : capture d'écran vs attendu (crop 16:9 centre 720×405 pour
  l'analyse, remplit-vue sans déformation pour la rue). PASS = pas d'étirement,
  pas de bandes anormales.

## 5. Vers le GPU Modal : analyse 50 ms (honnête)

Chaîne d'envoi lunettes → GPU : radio (~30 ms) → collecte → JPEG 320 q55
(~6 Ko) → WebSocket déjà ouvert → réseau → ingress Modal EU → file → inférence.

Constats mesurés (docs `LATENCE-PROD`, `LIVE-PERF`, `LATENCE-BUDGET`) :

- plancher aller-retour cloud seul : **123,7 ms** (avant tout client) ;
- réseau vers Modal EU : **~200 ms** d'aller-retour ;
- inférence GPU rapide : ~52–125 ms (`fast 82-125ms`, tokenize 23,4 → 0).

**Verdict : un aller-retour lunettes → GPU → réponse en < 50 ms est impossible
sur le chemin cloud actuel** — le réseau EU seul dépasse le budget. Ce que cette
branche livre pour s'en approcher au plus près :

- pont lunettes → sonde événementiel (**−500 ms** par envoi, fini le sondage) ;
- sonde bitmap sautée sur H.265 Annex B (**−1 à 3 ms** par frame, −45 ms/s de CPU) ;
- accueil poussé avant le muxeur (une cale d'enregistrement ne retarde plus l'image) ;
- horodatage arrivée + `renderedSeq` + `GlassesLinkStats` (p50/p95/p99) : chaque
  ms du chemin est mesurable, les seuils du § 3 deviennent des verdicts ;
- envoi binaire (`binaryUpload`, −33 % vs base64) et WebSocket persistant
  (−31 ms vs POST) : déjà en place, à activer/vérifier sur ce chemin.

Pour de vraies décisions < 50 ms, la seule voie est la boucle locale sur le
téléphone (primitifs embarqués, sans GPU) ; le GPU reste le chemin lent/async
(masques SAM lourds, LLM). Un modèle embarqué (ExecuTorch/NNAPI) est un chantier
séparé, hors de cette branche.

## 6. Calibrage optique sur mire (outillage livré, prise terrain restante)

Le SDK n'expose ni FOV ni distorsion. En attendant la mire, la voie lunettes
mesure sur un repli estimé (`LensSpec.Glasses`, affinable par `glassesFovDeg`)
— routage, store et solveur sont livrés, reste la prise terrain :

1. Imprimer : `python3 tools/solve_glasses_mire.py gen-mire --corners 9x6 --square-mm 25 --out mire.svg`
   — 100 %, vérifier la case au réglet.
2. Photographier : ≥ 3 photos du damier via le flux HIGH (720×1280), angles
   variés (face, penché ±30°, coins et centre du cadre couverts).
3. Résoudre : `uv run --with opencv-python-headless --with numpy tools/solve_glasses_mire.py solve --photos DIR --corners 9x6 --square-mm 25 --out mire.json`
   — exige pixels carrés (±2 %), sinon rephotographier ; viser rms < 1 px.
4. Coller : le bloc `mire v1` dans Réglages → Distances → Mire solver block
   → Save mire (persisté `GlassesMireStore`, étage SENSOR de la voie lunettes).
5. Recette : distance sténopé sur objet connu à ±10 % (3 distances).

Conventions : k1/k2 OpenCV (norme focale) convertis en norme demi-diagonale
(`opencv_to_distortion`, testé en CI) ; capteur natif portrait ; tangentielle
nulle (le modèle embarqué ne porte que k1/k2 radiaux).

## 7. Grille PASS/FAIL (une ligne par scénario, à remplir sur matériel)

| id | scénario | attendu | mesuré | verdict |
| --- | --- | --- | --- | --- |
| A1 | flux simulé | frame rendue | | |
| A2 | HIGH 720×1280 | rue nette, live=true | WxH = | |
| A3 | recadrage | pas de déformation | | |
| B1 | horloges | offset stable, drift < 1 ms/min | | |
| B2 | e2e p95 60 s | < 100 ms | p50/p95/p99 = | |
| B3 | budget | étages reportés § 2 | | |
| C | envoi GPU | −500 ms vs master (poll supprimé) | t_envoi = | |

`collectFrames` reste à `false` par défaut pendant A/B (pas de GPU) ; le passer à
`true` uniquement pour C, sur une session courte, derrière la carte existante.
