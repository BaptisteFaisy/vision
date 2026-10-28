# PERF — latence mesurée (relevé antérieur, hors de cet arbre ; backend déployé, GPU A10G)

`POST /segment` vers le backend déployé avec `/tmp/street.png` (2048×824, ~2,4 Mo de base64), chronos aller-retour depuis la machine locale, réseau inclus.

## À chaud

| Cas | 3 mesures | médian | réponse |
|---|---|---|---|
| `/segment` `prompts:null` (17 objets) | 1,93 / 1,96 / 1,95 s | **1,95 s** | 61 901 o |
| `/segment` 2 prompts (6 objets) | 1,39 / 1,60 / 2,03 s | **1,60 s** | 10 097 o |
| `/health` | 0,30 / 0,35 / 0,30 s | **0,30 s** | 46 o |
| 400 validation (avant / après décodage) | 0,30 / 0,32 / 0,33 — 1,01 / 1,03 / 1,08 s | **0,30 / 1,00 s** | < 400 o |

Ce n'est pas le réseau qui coûte : le décodage PNG de l'entrée fait ~1 s (payé même si la requête
échoue ensuite), et l'encodeur vision ne passe **qu'une fois** pour les N prompts (`vision_embeds`
réutilisé) — 8 concepts → 2 concepts ne fait gagner que ~0,3 s. Le volume vient des masques (~3,6 Ko).

## Froid (premier appel après scaledown)

Après 450 s d'inactivité (`scaledown_window=300`) : `/health` **35,8 s** — montée du conteneur **et**
chargement de `unsloth/sam3.1` dans `@modal.enter()`, payés même pour une sonde de vie ; puis
`/segment` **5,7 s** (contexte CUDA froid) et retour à ~1,9 s. Un deuxième essai après 567 s
d'inactivité n'a donné **aucun** cold start (1,93 s) : conteneur maintenu vivant (trafic tiers sur
l'URL, ou horloge de scaledown plus lente que la fenêtre) — non reproductible, à vérifier en console.

## Conséquences

- Timeout client SPEC = 60 s : `/segment` à froid (35,8 + 5,7) passe, `/analyze` à froid peut mordre dedans → garder « Réessayer », pas gonfler le timeout.
- Les « 60-120 s » de cold start du README sont pessimistes (**~36 s** mesurés) ; et un conteneur qui
  ne descend pas facture le GPU en continu (~1,10 $/h) — le non-scaling observé mérite un œil.
