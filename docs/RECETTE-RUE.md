# Recette — rue : spec v4 (une prise)

Fiche spec v4 (01/11/2026) : l'intégrateur dicte une destination à ~200 m,
marche, passe les obstacles imposés, et note les phrases exactes entendues.
Historique pré-v4 : `git show 4a3c2bcf:docs/RECETTE-RUE.md`.

⚠️ **Recette non rejouée sur appareil** : ce qui suit décrit ce que le
**code** doit faire ; le comportement dans la rue reste **non mesuré**
jusqu'à la première prise.

## Ce qui est vrai dans le code (après intégration v4)

Chaîne : appui long iSee → dictée → routeur « mode … » (10 ordres, § 6 de
`docs/VOIX-DANGER.md`) → question LLM sinon → moteur Valhalla in-app
(`VoiceNavEngine` + `VoiceNavEngineDriver`, si `routeBaseUrl` renseignée)
→ manœuvres (11) et rappels (3) par la voix réseau ElevenLabs ; obstacles
(0–8, 10, 12) en **clips anglais, zéro réseau**. **Jamais deux voix en
même temps** (bus vocal : le plus petit niveau gagne, écrasé jeté).

| Étape | Observable promis | Source |
|---|---|---|
| Dictée ordre | « mode GPS I want to go to X » consommé avant le LLM, X transmis tel quel ; tout le reste = question LLM | `DictationOrders`, `IseeDictationRouter` |
| Confirmation | « Guidance started. » (clip `gps_started`) ; indisponible : « Guidance unavailable. » (`gps_unavailable`) | `IseeClipsEn` |
| Voiture en mouvement | « Car. » / « Cars. » (< 20 m, présence seule, 4 s) | Niveau 0, `car` / `cars` |
| Humain | « Person. » + cadran + distance (< 10 m, 2 s) ; ≤ 2 m : « Stop. » immédiat | Niveau 2 |
| Poteau / lampadaire / panneau | « Pole. » / « Street lamp. » / « Sign. » (< 6 m, 2 s) | Niveau 7 |
| Grillage / mur / barrière | « Fence. » / « Wall. » / « Barrier. » (< 6 m, 4 s) | Niveau 4 |
| Bordure | « Curb up. » / « Curb down. » (< 6 m, 2 s) | Niveau 5 |
| Escalier | Jalons « Stairs up/down, 4/2/1 meters. » (2 s) + marches (« Five. Steps. », 0,5 s) | Niveau 6 |
| Animal / poubelle | « Animal. » / « Trash can. » (< 6 m, 2 s) | Niveau 7 divers |
| Passage piéton | « Crosswalk ahead/left/right. » (1 fois par vue), « Aligned with the crosswalk. » (au changement), « On the crosswalk. » (1 fois) | Niveau 8 |
| Trottoir | « Sidewalk ahead/on the left/on the right. » (dérive seule, 10 s) | Niveau 10 |
| Virage + obstacle | 0 chevauchement : manœuvre (11) et obstacle ne parlent jamais l'un sur l'autre (le plus petit gagne, écrasé jeté) | `VoiceBus` |
| Traversée, préavis | « Crosswalk ahead. » (niveau 8), jamais de « Straight. » au bord | Niveau 8 |
| Traversée, mode | « Crossing mode on. » (`cross_on`), chat et GPS muets, dictée active | Niveaux 100-102 |
| Traversée, verdict | « Cross. » / « Don't cross. » (3 s, changement immédiat) | Niveau 100 |
| Traversée passée | « Crossing mode off. » (`cross_off`), le guidage reprend | Retour dehors |
| Rappel GPS | Silence ≥ 15 s : dernière consigne + distance restante (synthèse) | Niveau 3 |
| Repli | Rien à dire + silence 300 ms : « Left. » / « Right. » / « Straight. » | Niveau 12 |
| Arrivée | Une seule fois, moteur arrêté | Niveau 11 |
| Interdits | Aucun « Bus… », « Bicycle… », « Motorcycle… », « Truck… » ; aucune clé dehors ; aucune borne, arbre, portail, bateau ; aucun passage par Google Maps ; aucune voix FR hors chat/GPS | Spec v4 § 0 |

## Prérequis

- Build du HEAD attendu sur origin (`EXPECT_REF`, défaut `master`),
  APK installé sur le Redmi `2c6852ac` **via le verrou** `~/bin/phone`.
- Réglages iSee : clé ElevenLabs + voix renseignées, **URL Valhalla
  (`routeBaseUrl`) renseignée** (sans elle, pas de guidage : pas de repli
  Maps — « Guidance unavailable. »).
- Terrain : **Île-de-France** (tuiles Valhalla), GPS fixé, réseau OK (chat
  + GPS + SAM), **de jour** (la traversée ne traite que le jour).
- Parcours repéré (~200 m) : une **voiture qui bouge**, un **humain** (dont
  un passage à ≤ 2 m), un **poteau**, un **mur ou grillage**, une
  **bordure** (montée + descente), un **escalier** (jalons 4/2/1 audibles),
  un **passage piéton sur l'itinéraire**, un **virage** avant l'arrivée, et
  15 s de marche sans obstacle (rappel GPS) + 300 ms de silence (repli).

## Recette (une prise, ~15 min)

1. **Dictée ordre.** Appui long ≥ 300 ms, dicter « mode GPS I want to go
   to X » (X = adresse à ~200 m). Attendu : « Guidance started. », pas de
   crash, pas de question LLM. Contre-essai : « je veux aller à X » (sans
   « mode ») → question LLM, pas de guidage.
2. **Voiture.** Une voiture bouge à < 20 m. Attendu : « Car. » (ou « Cars. »
   si plusieurs), sans cadran ni distance, pas plus d'une fois toutes les
   4 s. Une voiture garée ne dit rien.
3. **Humain.** Approcher une personne. Attendu : « Person. » + heure du
   cadran + distance, pas plus d'une fois toutes les 2 s. À ≤ 2 m :
   « Stop. » immédiat, même si les 2 s ne sont pas écoulées.
4. **Mobilier.** Poteau, lampadaire, panneau, grillage, mur, barrière à
   < 6 m. Attendu : le nom seul (« Pole. », « Wall. », …), 2 s (poteaux et
   mobilier) / 4 s (grillages, murs, barrières).
5. **Bordures.** Montée puis descente. Attendu : « Curb up. » /
   « Curb down. », 2 s.
6. **Escalier.** Marcher vers un escalier. Attendu : jalons à 4 m, 2 m,
   1 m (« Stairs up, 4 meters. », …) + nombre de marches (« Five. Steps. »).
7. **Passage piéton.** Attendu : annonce 1 fois par vue, alignement au
   changement seul, arrivée 1 fois. Noter tout doublon.
8. **Virage.** Attendu : manœuvre parlée (synthèse) ; **0 chevauchement**
   avec un obstacle (coupe franche, jamais deux voix). Capture écran.
9. **Traversée.** Dire « mode crossing on ». Attendu : « Crossing mode on. »,
   verdict (« Cross. » / « Don't cross. », 3 s, changement immédiat),
   humains (101) puis bordures (102) après le verdict, **chat et GPS muets**.
   Passé : « mode crossing off », retour au libre. Capture écran.
10. **Rappel GPS.** 15 s de marche sans annonce. Attendu : dernière consigne
    répétée + distance restante (synthèse).
11. **Repli.** 300 ms de silence sans rien à dire. Attendu : « Left. » /
    « Right. » / « Straight. » (clips), une fois.
12. **Arrivée.** Marcher jusqu'à X. Attendu : arrivée une seule fois ;
    retour iSee : écran d'accueil, pas de crash.

Chaque point : SHA du build, heure, phrase exacte entendue + texte écran.
Tout écart au tableau part en **issue GitHub**, pas en commentaire.

## Checklist vierge

| # | Observable | SHA | Heure | Verdict |
|---|---|---|---|---|
| 1 | « Guidance started. » après « mode GPS… » ; sans « mode » → LLM | | | |
| 2 | « Car. » / « Cars. », présence seule, 4 s ; garée = silence | | | |
| 3 | « Person. » + cadran + distance, 2 s ; ≤ 2 m → « Stop. » immédiat | | | |
| 4 | « Pole./Lamp./Sign. » 2 s ; « Fence./Wall./Barrier. » 4 s | | | |
| 5 | « Curb up./down. », 2 s | | | |
| 6 | Jalons 4/2/1 + marches dites | | | |
| 7 | Passage : 1 fois par vue / au changement / 1 fois, sans doublon | | | |
| 8 | Manœuvre + 0 chevauchement obstacle | | | |
| 9 | « Crossing mode on. », verdict 3 s + immédiat, chat/GPS muets | | | |
| 10 | Rappel 15 s : consigne + distance restante | | | |
| 11 | Repli 300 ms : Left/Right/Straight, une fois | | | |
| 12 | Arrivée une seule fois, pas de crash | | | |
| 13 | Interdits : 0 bus/vélo/camion/moto, 0 clés dehors, 0 Maps, 0 FR hors chat/GPS | | | |

## Première prise

**Non rejouée** : pas d'appareil piloté d'ici. La checklist ci-dessus est
prête ; la première prise l'archive (procès-verbal + captures) et remplit
chaque verdict — aucun n'est pré-rempli ici.

## Non mesuré (à ne pas vendre comme vérifié)

- La recette n'a pas été rejouée sur appareil : non mesuré.
- Phrases réellement entendues dans la rue, dans le bon ordre, sans
  chevauchement : non mesuré (exige le téléphone + la marche).
- Jalons escaliers 4/2/1 audibles aux bonnes distances : non mesuré
  (profondeur à valider dehors).
- Repli 300 ms : non mesuré — et **non implémenté** (constante seule,
  voir `docs/REVUE-V4.md` R-12) : la recette échouera sur ce point
  jusqu'à son implémentation.
