"""Mémoïsation des entrées texte DETR par concepts (stdlib seule, sans torch).

Tokeniser + monter sur GPU coûte ~23 ms par frame (mesuré en prod, constant) pour un
texte qui ne change pas en séance : les concepts sont fixés au démarrage du direct et
le tokenizer est déterministe. Ce cache rend les tenseurs déjà prêts sur hit — le poste
``tokenize_ms`` tombe alors à ~0. Miss = construction identique à l'historique.

Sûreté : clé ``(tuple(concepts), device)``, FIFO bornée (8 entrées, quelques Ko), lectures
seules en aval (forward en ``inference_mode``, recopie graphe) — un hit rend exactement
ce qu'un miss reconstruirait. Fils unique par conteneur pour le modèle : pas de verrou
(le GIL rend get/set atomiques, un double calcul est bénin et identique).
"""

_TOKEN_CACHE_MAX = 8
_token_cache: dict = {}


def tokenize_cached(processor, concepts, device):
    """``processor(text=concepts).to(device)``, mémoïsé (voir module)."""
    key = (tuple(concepts), str(device))
    hit = _token_cache.get(key)
    if hit is not None:
        return hit
    inputs = {name: value.to(device)
              for name, value in processor(text=list(concepts),
                                           return_tensors="pt").items()}
    if len(_token_cache) >= _TOKEN_CACHE_MAX:
        _token_cache.pop(next(iter(_token_cache)))
    _token_cache[key] = inputs
    return inputs


def cache_clear() -> None:
    """Vide le cache (tests, changement de modèle)."""
    _token_cache.clear()
