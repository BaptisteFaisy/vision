# ISEE-DESIGN-PORTRAIT — le design obligatoire de l'écran iSee

> **Statut : obligatoire depuis le 2026-10-27 (décision user).** Toute
> régression listée ci-dessous est un **bug bloquant**. Tout changement
> volontaire de ce design exige une **demande explicite du user** — jamais
> d'initiative d'agent. Les garde-fous : `tools/check-isee-design.py` (CI,
> job `ci`) et les tests JVM `IseePortraitTest` (`:core`) + `IseeSceneTest`
> (`:app`), qui figent les valeurs.

## 1. Ce que l'écran montre

- **Fond blanc**, plein écran. En-tête « iSee » + pastille d'état, en blanc
  sur la rue quand la caméra est ouverte, en encre sinon ; bandeau blanc
  derrière les boutons système du bas.
- **Une seule boule de verre** au liquide marbré (`IseePortraitOrb`) : reflet
  de boîte à lumière, irisation au bord, liseré. Shader AGSL sur API 33+,
  repli en dégradés en dessous. **Aucun pixel sur la boule, aucun satellite.**
- **Caméra = la rue en haut**, sans cadre ni carte (`IseePortraitCamera`) :
  elle occupe 58 % du haut (`PICTURE_H`) et **se fond dans le blanc** sur
  ses 34 % du bas (`FADE`, courbe `pictureAlpha`). À l'ouverture elle arrive
  **floue et agrandie** (×1,05, flou max 24) puis devient nette (`focusPull`).
  Elle reste visible **tant qu'une capture est vivante** (`captureLive`),
  quel que soit l'état (tenir le micro sous vidéo ne la cache pas).
- **La boule respire** (respiration + ombre de contact). Au repos : centrée à
  40 % de la hauteur (`REST_Y`), rayon 33,5 % de la largeur (`REST_R`).
  Caméra ouverte : elle rétrécit et **se pose sur la jointure** de la rue
  (`CAM_Y` 55,5 %, `CAM_R` 20 %).
- **Seule la phrase en cours**, en gros, **sous la boule** (`IseeSpokenLine`,
  4 lignes max + ellipsis, label YOU/ISEE au-dessus). Elle **s'allume mot à
  mot** : mot en cours dans la couleur de qui parle, mots dits en noir, mots
  à venir en gris très clair (`DIM` = encre à 18 %). Hors parole : tout noir.
- **Le user parle (écoute)** : liquide **bleu**. **iSee parle** : liquide
  **ambre** + **couronne de 120 barres ambre** autour de la boule. Pastille :
  LISTENING bleu, SPEAKING ambre, SEEING / THINKING / MEMORY / READY sinon.

## 2. Palette Amber (figée, `IseePortrait`)

| Rôle | Constante | Valeur |
|---|---|---|
| User | `YOU` / `YOU_INK` | `0xFF0B5FFF` / `0xFF0A50D8` |
| iSee | `SEE` / `SEE_INK` | `0xFFF5A300` / `0xFFC98200` |
| Encre / secondaire | `INK` / `SUB` | `0xFF0B1220` / `0xFF6A7486` |
| Mots à venir | `DIM` | `0x2E0B1220` |
| Liquide repos | `IDLE` | bleu → cyan → ambre clair → ambre → crème |
| Liquide user | `PAL_YOU` | 5 bleus |
| Liquide iSee | `PAL_SEE` | 5 ambres |

## 3. Où vit le code

- **Logique pure, `:core`** : `core/…/iseedesign/IseePortrait.kt` —
  géométrie (`portraitGeometry`), fondu (`pictureAlpha`), mise au point
  (`focusPull`), voix (`voiceEnvelope`, `wordsSpoken`), palettes. Testé sur
  JVM, aucune dépendance Android.
- **UI fine, `:app`** : `app/…/isee/IseeScenePane.kt` (assemblage),
  `IseePortraitOrb.kt` (boule), `IseePortraitCamera.kt` (rue + fondu),
  `IseeSpokenLine.kt` (phrase mot à mot). Le contrat `iseeSceneLayers`
  (caméra vivante → carte caméra) est conservé ; le paramètre `dissolving`
  n'est gardé que pour le contrat — **le design n'a plus de dissolution
  en pixels**.

## 4. Quarantaine : l'ancien design ne revient pas

Fichiers legacy, **hors du chemin de production** : `IseeSceneArt.kt`
(satellites, bulles), `IseeScenePixels.kt` (grille de pixels),
`preview/IseePreviews.kt` (aperçus Android Studio seuls). Symboles legacy,
définis dans `core/…/iseedesign/IseeScene.kt` : `satellitePositions`,
`sampleGrid`, `pixelTrip`, `bubbleLookIndex`, `BubbleLook`, `textPages`.

Règle : ces symboles ne sont utilisables que par les fichiers legacy et les
tests — **jamais par le chemin de production** (`IseeScenePane` et ce qu'il
assemble). Le garde `tools/check-isee-design.py` refuse toute autre
référence ; il exige aussi le câblage Portrait dans `IseeScenePane`
(`portraitGeometry`, `rememberOrbPainter`, `SpokenLine`, `PortraitCamera`).

## 5. Règle pour les agents

Ne pas réintroduire satellites, grille de pixels, dissolution en pixels ni
texte multi-pages ; ne pas modifier palettes, géométrie ni constantes
(`REST_*`, `CAM_*`, `PICTURE_H`, `FADE`) sans demande explicite du user ;
tout nouvel écran iSee reprend ce design. En cas de doute : le code
`IseePortrait.kt` + ses tests font foi, pas la maquette d'origine.

## 6. Boule gelée : le rendu ne change plus sans le user

Le code qui dessine la boule est figé à l'octet près :
`tools/orb-frozen-baseline.json` garde les empreintes de `IseePortraitOrb.kt`
(entier : shader AGSL, repli dégradés, peintre) et des 5 unités d'animation de
`IseeScenePane.kt` (`rememberSceneTime`, `OrbLayer`, `orbLookOf`,
`drawOrbShadow`, `drawVoiceCrown`). Le garde `tools/check-isee-design.py` (CI,
job `ci`) refuse toute dérive ; en complément, `IseePortraitTest` fige
l'enveloppe de voix échantillon par échantillon et `IseeSceneTest` le mapping
scène → look.

Changement volontaire (demande explicite du user uniquement) : modifier le
code, régénérer avec `python3 tools/freeze-orb-baseline.py`, relire le diff
(baseline et code voyagent ensemble), déclarer les deux fichiers dans
`## Scope`.
