# Pictogrammes Meta Muse (`muse-image-1.0`) — contrat client

Fiche relue dans le **code de cet arbre** le 2026-10-28 (branche `picto-reperes`).
Le contrat décrit celui du **code** (`core/net/MetaImageClient.kt`) : la
documentation publique de Meta couvre les endpoints, pas ce chemin exact —
les constats marqués « non mesuré » restent à vérifier sur le téléphone.

La photo d'un objet filmé devient son pictogramme noir sur jaune
(`core/picto/PictoPrompt.kt`), relu par l'aidant, puis reconnu sur la vidéo
(`core/picto/PictoMatcher.kt`, local, sans réseau). La géométrie vient de la
photo, jamais d'une invention : le modèle ne fait que redessiner.

## Contrat HTTP

```
POST {asrBaseUrl}/images/edits           (MetaImageClient.kt)
Authorization: Bearer <clé Meta>
User-Agent: sam3-vision-android/1.0
Content-Type: multipart/form-data; boundary=----sam3vision<uuid sans tirets>

champs texte : model | prompt | size | n=1 | response_format=b64_json | output_format=png
fichier      : name="image"; filename="photo.jpg", image/jpeg, <octets JPEG 768 px>
```

- **Base URL et clé** : les mêmes que la dictée Meta (`Settings.asrBaseUrl` =
  `https://api.meta.ai/v1`, `Settings.asrApiKey`) — aucun réglage nouveau.
  Clé vide ⇒ échec **local**, sans réseau.
- **Modèle** : `muse-image-1.0` (`core/picto/PictoPrompt.kt:IMAGE_MODEL`).
- **Taille** : `1024x1024` — sur ces endpoints `size` ne règle que le rapport
  d'aspect, pas les pixels exacts ; le PNG est affiché remis à l'échelle.
- **Multipart** : assemblé par [MetaMultipart] (partagé avec l'ASR) — OkHttp
  `MultipartBody` ajoute un `Content-Length` par part, que Meta refuse en 400.
- **Réponse** : JSON `{created, data:[{b64_json}]}` (`extractImageB64`,
  `core/net/Json.kt`) ; une réponse `url` seule n'est pas prise en charge
  (`BadResponse` explicite, pas de second appel silencieux).
- **Coût/latence (doc Meta, non mesuré ici)** : ~0,01 $ et ~10 s par image.

## Erreurs : ce que voit l'utilisateur

| Cas | `ApiError` | Ligne de statut |
|---|---|---|
| 401, 403 | `InvalidKey` | `Picto: clé invalide (401)` / `accès refusé (403)` |
| 402 | `PaymentRequired` | `Picto: paiement requis (402) : …` |
| 429 | `RateLimited` | `Picto: limite atteinte (429)` |
| image absente / base64 invalide | `BadResponse` | `Picto: réponse inattendue : …` |
| délai / réseau | `Timeout` / `Network` | `Picto: backend injoignable : …` |

Le compte Meta renvoyait 402 sur tout appel d'inférence (voir `META-ASR.md`,
non re-vérifié) : tant que la facturation n'est pas configurée côté console
(acte personnel, jamais un agent), la génération remonte ce 402 tel quel.

## Où ça vit dans le code

| Rôle | Fichier |
|---|---|
| Client HTTP Meta Image | `core/.../net/MetaImageClient.kt` (+ `PictoImageClient` dans `Clients.kt`) |
| Multipart partagé ASR+image | `core/.../net/MetaMultipart.kt` |
| Extraction `b64_json` | `extractImageB64` (`core/.../net/Json.kt`) |
| Consigne + modèle + taille | `core/.../picto/PictoPrompt.kt` |
| Gabarit + corrélation NCC | `core/.../picto/Picto.kt`, `PictoMatcher.kt` |
| Orchestration écran | `app/.../isee/vm/IseePicto.kt` (+ état dans `IseeState.kt`) |
| Magasin persistant | `app/.../isee/PictoStore.kt` (`filesDir/pictos`, hors purge) |
| Pont Bitmap (frames, photo) | `app/.../isee/PictoFrames.kt` |
| Bouton, overlay, relecture | `app/.../isee/PictoLayer.kt`, `PictoDialog.kt`, `IseePortraitCamera.kt` |

## Suivis (hors V1)

- Annonce vocale à la première reconnaissance (l'overlay seul ne parle pas).
- Expiration des marques (1,5 s sans passage) contre le scintillement.
- Suppression/renommage d'un repère depuis les Réglages.
