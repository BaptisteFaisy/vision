# Démo iSee avec les lunettes Ray-Ban Meta — pas à pas

> Relevé du 29/10/2026. Chaque étape dit **son objectif** : si l'objectif n'est pas atteint,
> ne pas passer à la suivante, l'erreur vient de là.

## Ce qu'il faut sur la table

- le téléphone, avec **iSee** installé ;
- les lunettes **chargées** et appairées à l'app **Meta AI** (pas seulement au Bluetooth) ;
- une pièce avec de la lumière : les lunettes filment mal dans le noir.

La vidéo ne s'écrit **jamais** dans les lunettes : elle arrive sur le téléphone au fur et à
mesure, dans **`Movies/iSee/`**. Les lunettes ne peuvent donc pas « se remplir ».

## Étape 1 — Installer la bonne version d'iSee

**Objectif : avoir les boutons d'autorisation caméra et micro.** Ils n'existent pas dans les
versions précédentes ; sans eux, la caméra des lunettes reste refusée.

Installer l'APK du jour, puis ouvrir iSee une fois.

- Android refuse la mise à jour (« application non installée ») ? L'ancienne iSee vient d'une
  autre signature : **désinstaller l'ancienne**, puis installer. Les réglages d'iSee sont
  chiffrés : ils seront à ressaisir (clés, langue, rôle).

## Étape 2 — Vérifier que Meta AI connaît les lunettes

**Objectif : des lunettes connectées côté Meta AI.** iSee ne peut parler qu'à une paire que Meta
AI voit déjà.

Ouvrir l'app **Meta AI** → section lunettes : la paire est là, connectée, batterie suffisante.
Si elle n'y est pas, l'appairer maintenant — iSee ne peut rien y faire.

## Étape 3 — Ouvrir le bloc lunettes d'iSee

**Objectif : lire l'état, et voir les cinq boutons.**

iSee → **Réglages** → première section, carte blanche **« Lunettes Ray-Ban Meta »**
(section `META GLASSES`). On doit lire quatre lignes d'état et voir, dans cet ordre :

1. **Autoriser la caméra (Meta AI)**
2. **Autoriser le micro (Meta AI)**
3. **Enregistrer auprès de Meta AI** (ou « Ré-enregistrer… »)
4. **Démarrer la session**
5. **Prendre une photo**

Si les boutons 1 et 2 manquent : mauvais APK, revenir à l'étape 1.

## Étape 4 — « Enregistrer auprès de Meta AI »

**Objectif : les lunettes savent qu'iSee existe.** Tant que ce n'est pas fait, elles refusent
tout.

Appuyer → Meta AI s'ouvre et demande de confirmer → accepter → **revenir dans iSee**. La ligne
d'état ne doit plus dire « pas enregistré » (le bouton devient « Ré-enregistrer auprès de Meta AI »).

## Étape 5 — « Autoriser la caméra (Meta AI) »

**Objectif : débloquer l'image.** C'est le seul chemin : dans l'app Meta AI, la fiche d'iSee
n'expose que « Déconnecter », et les réglages Android ne connaissent pas ces lunettes.

Appuyer → Meta AI demande la caméra → accepter → revenir. Recommencer si Meta AI revient sans
avoir posé la question.

## Étape 6 — « Autoriser le micro (Meta AI) »

**Objectif : avoir le son dans la vidéo.** Sans cette autorisation la vidéo s'enregistre
**muette** (et le message de fin le dit), avec cette autorisation le son des lunettes est dans
le même fichier MP4 que l'image.

Appuyer → accepter → revenir.

## Étape 7 — Démarrer la session

**Objectif : l'image des lunettes arrive sur le téléphone, et l'enregistrement démarre.**

Appuyer « Démarrer la session ». Attendre que le bouton devienne « Arrêter » (état `STARTED`).
La ligne d'état doit afficher **`Vidéo : enregistrement → Movies/iSee/isee-lunettes-…`**.

- « branches refermées » : déplier les lunettes ;
- « lunettes trop chaudes » : les laisser refroidir deux minutes ;
- « batterie trop basse » : charger ;
- pas de ligne « Vidéo » : l'enregistrement n'a pas pris, arrêter et redémarrer la session.

## Étape 8 — Faire la démo

**Objectif : la matière de la vidéo.** Parler normalement (le son est déjà capté), marcher,
montrer ce qui doit être vu.

Les gestes, pendant la session :

| Geste sur les lunettes | Ce qui se passe |
|---|---|
| appui court | photo → écran d'analyse (masques + description) |
| appui long | photo → question dans le chat, sur le téléphone |
| double appui | rien n'est lancé, message « Mode Direct : pas encore porté sur lunettes » |

La voix d'iSee sort **dans les lunettes** quand la session est ouverte, et sur le téléphone
sinon. Le double appui émet d'abord un appui court (bug connu du SDK) : le code attend 350 ms
avant d'analyser, pour ne pas déclencher une analyse par erreur.

## Étape 9 — Arrêter la session

**Objectif : fermer proprement le fichier pour qu'il apparaisse dans la galerie.**

Appuyer « Arrêter ». Le message de fin dit **« avec le son »** ou **« sans son »** : s'il dit
sans son, le micro a été refusé (étape 6).

## Étape 10 — Vérifier la vidéo

**Objectif : être sûr, avant de ranger le matériel, que la démo est bien enregistrée.**

Galerie → **`Movies/iSee`** → le fichier `isee-lunettes-AAAAMMJJ-HHMMSS.mp4` :

- il **se lit droit** : cadre portrait 504×896, exactement ce que les lunettes voient ;
- le **son** est audible ;
- il se lit en entier, sans coupure au milieu.

Un fichier absent ou à moitié écrit est retiré de la galerie par l'app : dans ce cas, l'étape 7
n'a pas abouti (voir ses messages).

## Étape 11 — Le 16:9, au montage

**Objectif : savoir à l'avance à quoi ressemblera le format final.**

Les lunettes filment en **portrait natif** et le SDK n'offre aucun réglage de format
(`StreamConfiguration` : codec audio, qualité, cadence, compression — rien d'autre). Le fichier
est donc portrait. Pour un rendu 16:9, deux choix au montage, sans jamais coucher l'image :

- poser le portrait dans un cadre 16:9 (fond neutre de chaque côté) ;
- ou faire un panoramique vertical dans une bande 16:9 (on garde 32 % de la hauteur, on perd le
  reste : à réserver aux plans où le sujet est au centre).

## Si la démo doit être refaite

Chaque session crée **un nouveau fichier daté** : rien à effacer entre deux prises. Une prise
d'1h30 occupe environ **0,5 à 1,4 Go** (estimation, non mesurée) — vérifier l'espace libre du
téléphone avant.
