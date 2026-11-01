# LATENCE-M1-VECTEURS — contrat WS binaire, décisions + vecteurs partagés

Source unique exécutable : `server/scripts/tests_units/m1_ws_vectors.json`
(version 1, 16 vecteurs). Ce document en est la lecture humaine : mêmes
trames, mêmes attentes. En cas de divergence, le JSON fait foi.

Exécution : `python3 server/scripts/test_units.py` (suite « WS binaire M1
(vecteurs) », zéro GPU) côté serveur ; `:core:testDebugUnitTest --tests
dev.sam3vision.core.net.M1VectorsTest` côté client (l'encodeur doit
reproduire les 4 trames `kotlin` à l'octet près).

Contrat (`docs/LATENCE-M1-TRANSPORT-BINAIRE.md` §2) : un message binaire =
`uint32 N` + en-tête JSON UTF-8 de N octets (champs de `LiveWsRequest`
**sans** `image_b64`) + JPEG brut. Les vecteurs utilisent le JPEG
conventionnel `ffd8010203` (5 octets, cf. `WsLiveBinaryTest`) : le contrat
ne valide pas son contenu, seul l'étage de décodage le lit.

## 1. Décisions

- **Ordre des octets : big-endian.** N=91 s'écrit `00 00 00 5b`, jamais
  `5b 00 00 00` (vecteur `valid-minimal`, vérifié des deux côtés).
- **N compte des octets UTF-8, pas des caractères.** `valid-utf8-octets` :
  `piéton` (é = `c3 a9`, 2 octets) → N=86 pour 85 caractères.
- **Borne 4 Ko incluse.** N=4096 accepté (`valid-boundary-4096`),
  N=4097 refusé avant toute lecture (`err-n-too-large`).
- **Refus : 400, connexion maintenue, `id` repris quand il est lisible.**
  En-tête illisible ou `id` absent → `id: -1` (même `UNKNOWN_ID` que le
  texte) ; en-tête valide mais JPEG vide/indécodable → `id` de l'en-tête.
- **`id` texte conservé.** `"id":"7"` (chaîne numérique) est accepté avec
  `id=7` (`ok-id-text-numeric`, coercition pydantic lax, comme le texte) ;
  `"id":"abc"` → 400 `id=-1`. Le client n'émet que des entiers.
- **Champs inconnus ignorés, jamais de 400** (`ok-unknown-field`, `roi`) :
  les knobs futurs (M2/M3) transitent sans casser les vieux serveurs.
- **Réponse inchangée** : message texte JSON, forme de `POST /live` +
  `id` de l'en-tête ; erreur `{"id":…,"error":{"status":400,…}}`.
- **Repli texte.** Le client n'émet du binaire que si le serveur déployé
  le lit (flag, activation après le deploy groupé M2/M3). Sur refus
  `{"id":-1,"error":…}`, il renvoie la frame en JSON **avec le même `id`**,
  puis la session reste en texte (cf. `WsLiveBinaryTest`, déjà couvert).

## 2. Trames valides (le client doit les reproduire à l'octet près)

`valid-minimal` — N=91, `id=7`, `prompts=["person","door"]`,
`work_edge=640`, `max_objects=4`, `mask_encoding="bits"` :

```text
header : {"id":7,"prompts":["person","door"],"work_edge":640,"max_objects":4,"mask_encoding":"bits"}
frame  : 0000005b7b226964223a372c2270726f6d707473223a5b22706572736f6e222c22646f6f72225d2c22776f72
         6b5f65646765223a3634302c226d61785f6f626a65637473223a342c226d61736b5f656e636f64696e67223a
         2262697473227dffd8010203
attend : ok, id=7, jpeg=ffd8010203
```

`valid-null-prompts-none` — N=78, `prompts=null`, `mask_encoding="none"`
(sans `mask_format`/`mask_edge) :

```text
header : {"id":9,"prompts":null,"work_edge":640,"max_objects":4,"mask_encoding":"none"}
frame  : 0000004e7b226964223a392c2270726f6d707473223a6e756c6c2c22776f726b5f65646765223a3634302c
         226d61785f6f626a65637473223a342c226d61736b5f656e636f64696e67223a226e6f6e65227dffd8010203
attend : ok, id=9, jpeg=ffd8010203
```

`valid-utf8-octets` — N=86 octets (85 caractères), `prompts=["piéton"]` :

```text
header : {"id":11,"prompts":["piéton"],"work_edge":640,"max_objects":4,"mask_encoding":"bits"}
frame  : 000000567b226964223a31312c2270726f6d707473223a5b227069c3a9746f6e225d2c22776f726b5f656467
         65223a3634302c226d61785f6f626a65637473223a342c226d61736b5f656e636f64696e67223a2262697473
         227dffd8010203
attend : ok, id=11, jpeg=ffd8010203
```

`valid-full-knobs` — N=133, `+ "mask_format":"rle","mask_edge":256,
"with_depth":true` (ordre exact de l'encodeur) :

```text
header : {"id":12,"prompts":null,"work_edge":640,"max_objects":4,"mask_encoding":"bits","mask_format":"rle",
          "mask_edge":256,"with_depth":true}
frame  : 000000857b226964223a31322c2270726f6d707473223a6e756c6c2c22776f726b5f65646765223a3634302c
         226d61785f6f626a65637473223a342c226d61736b5f656e636f64696e67223a2262697473222c226d61736b
         5f666f726d6174223a22726c65222c226d61736b5f65646765223a3235362c22776974685f6465707468223a
         747275657dffd8010203
attend : ok, id=12, jpeg=ffd8010203
```

Autres valides (serveur seul, non émis par le client) : `valid-boundary-4096`
(N=4096, `{"id":21}` + espaces, → ok id=21), `ok-id-text-numeric`
(`0000000a7b226964223a2237227dffd8010203`, `{"id":"7"}` → ok id=7),
`ok-unknown-field`
(`0000001a7b226964223a392c22726f69223a5b302c302c31302c31305d7dffd8010203`,
`{"id":9,"roi":[0,0,10,10]}` → ok id=9).

## 3. Trames refusées (400, connexion maintenue)

| vecteur | trame hex | attendu |
|---|---|---|
| `err-n-zero` | `00000000ffd8010203` | id=-1, `invalid binary header` |
| `err-n-too-large` | `000010017b226964223a377dffd8010203` | id=-1, `binary header too large` |
| `err-json-invalid` | `000000057b6f6f7073ffd8010203` (`{oops`) | id=-1, `invalid binary header` |
| `err-json-non-object` | `000000055b312c325dffd8010203` (`[1,2]`) | id=-1, `…expected a JSON object` |
| `err-id-missing` | `000000117b2270726f6d707473223a5b2261225d7dffd8010203` | id=-1, `invalid request body` |
| `err-id-text-nonnumeric` | `0000000c7b226964223a22616263227dffd8010203` (`{"id":"abc"}`) | id=-1, `invalid request body` |
| `err-jpeg-empty` | `000000097b226964223a31337d` (`{"id":13}`, sans JPEG) | **id=13**, `empty JPEG payload` |
| `err-truncated-nolen` | `0000` (2 octets) | id=-1, `truncated binary frame` |
| `err-truncated-header` | `000000647b226964223a377d` (N=100, message court) | id=-1, `truncated binary frame` |

## 4. Réponses (sortie texte JSON, inchangée)

```json
{"id": -1, "error": {"status": 400, "detail": "binary header too large: 4097B > 4096B"}}
{"id": 13, "error": {"status": 400, "detail": "empty JPEG payload in binary frame"}}
```

Succès : exactement `POST /live` + `id` repris de l'en-tête
(`{"id":7,"width":…,"height":…,"elapsed_ms":…,"objects":[…]}`).
