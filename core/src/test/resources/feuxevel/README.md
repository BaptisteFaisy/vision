# Éval caisson JVM (`CaissonEvalTest`)

`RECALL-v1.json` pine les verdicts de la chaîne détection→verdict sur 12 cas
(caissons synthétiques + 3 crops réels embarqués par référence) et le rappel
petits objets. Aucune image ici : les cas `real-*` relisent `feuxfr/` et
`feuxfr2/` (voir leurs `ATTRIBUTION.md`, Panoramax IGN, Licence Ouverte 2.0).

Règle : un changement de la porte qui modifie un verdict bump `version`
(visible en diff), jamais d'ajustement silencieux du doré.
