"""Tests du portage latence, côté serveur : codec « bits », négociation, cap, dédup, chronos.

Chaque fonction reçoit le module à exercer (convention de ``RUNNERS`` dans
``scripts/test_units.py``) et n'utilise que stdlib + numpy + PIL : aucun GPU, aucun token
Modal. Les cas sont ceux du rapport ``impl2/rapport-23.md``.
"""

from __future__ import annotations

import base64
import struct

import numpy as np


def _mask(height: int, width: int, rows=(1,)) -> np.ndarray:
    """Masque binaire (h, w) avec les lignes ``rows`` à 1."""
    mask = np.zeros((height, width), dtype=np.uint8)
    for row in rows:
        if 0 <= row < height:
            mask[row, :] = 1
    return mask


def test_mask_bits(imaging) -> None:
    """Round-trip du codec « M1 » : 1 bit/pixel + zlib, sans perte, en-tête little-endian."""
    from test_units import check, raises

    cases = (("1x1", 1, 1), ("largeur non multiple de 8", 7, 13), ("haut", 3, 17),
             ("plein", 6, 4), ("vide", 5, 9))
    for name, height, width in cases:
        rows = (0,) if name in ("plein", "vide") else (height // 2,)
        mask = _mask(height, width, rows)
        if name == "vide":
            mask[:] = 0
        if name == "plein":
            mask[:] = 1
        encoded = imaging.encode_mask_bits_b64(mask, (width, height))
        decoded = imaging.decode_mask_bits_b64(encoded)
        expected = imaging.mask_to_uint8(mask, (width, height))
        check(f"bits {name}: aller-retour identique",
              decoded.shape == expected.shape and bool(np.array_equal(decoded, expected)),
              f"{decoded.shape} != {expected.shape}")

    mask = _mask(8, 16, (2, 5))
    raw = base64.b64decode(imaging.encode_mask_bits_b64(mask, (16, 8)))
    magic, width, height = struct.unpack("<2sHH", raw[:6])
    check("bits: en-tête M1 <largeur, hauteur> little-endian",
          (magic, width, height) == (b"M1", 16, 8), f"{magic!r} {width}x{height}")
    check("bits: corps zlib (décompressable)",
          len(imaging.zlib.decompress(raw[6:])) == 8 * 2, "corps inattendu")
    check("bits: plus compact que le PNG",
          len(raw) < len(base64.b64decode(imaging.encode_mask_png_b64(mask, (16, 8)))))

    check("bits: base64 invalide → MaskFormatError",
          raises(lambda: imaging.decode_mask_bits_b64("!!!pas du base64!!!"),
                 imaging.MaskFormatError))
    check("bits: charge trop courte → MaskFormatError",
          raises(lambda: imaging.decode_mask_bits_b64(base64.b64encode(b"M1").decode()),
                 imaging.MaskFormatError))
    bad_magic = base64.b64encode(struct.pack("<2sHH", b"X9", 4, 4) + b"zz").decode()
    check("bits: magic inconnu → MaskFormatError",
          raises(lambda: imaging.decode_mask_bits_b64(bad_magic), imaging.MaskFormatError))
    bad_zlib = base64.b64encode(struct.pack("<2sHH", b"M1", 4, 4) + b"pas zlib").decode()
    check("bits: flux zlib invalide → MaskFormatError",
          raises(lambda: imaging.decode_mask_bits_b64(bad_zlib), imaging.MaskFormatError))


def test_mask_negotiation(imaging) -> None:
    """``mask_encoding`` : défaut ``png`` (contrat historique), ``bits``/``none`` sur demande."""
    from test_units import check, raises

    mask = _mask(4, 6, (1,))
    size = (6, 4)
    check("mask_encoding: absent → png", imaging.check_mask_encoding(None) == "png")
    check("mask_encoding: vide → png", imaging.check_mask_encoding("") == "png")
    check("mask_encoding: casse ignorée", imaging.check_mask_encoding(" BITS ") == "bits")
    check("mask_encoding: inconnu → MaskEncodingError",
          raises(lambda: imaging.check_mask_encoding("jpeg"), imaging.MaskEncodingError))
    check("encode_mask_b64(png) == encode_mask_png_b64",
          imaging.encode_mask_b64(mask, size, "png") == imaging.encode_mask_png_b64(mask, size),
          "octets PNG différents du contrat historique")
    check("encode_mask_b64(none) → masque vide", imaging.encode_mask_b64(mask, size, "none") == "")
    bits = imaging.encode_mask_b64(mask, size, "bits")
    check("encode_mask_b64(bits) → décode en M1",
          bool(np.array_equal(imaging.decode_mask_bits_b64(bits), imaging.mask_to_uint8(mask, size))))


def test_finalize_cap(segmentation) -> None:
    """Cap ``max_objects`` AVANT encodage : liste finale identique, moins de masques encodés."""
    from test_units import check

    encoded: list[str] = []
    original = segmentation.encode_mask_b64

    def counting(mask, size, encoding):
        encoded.append(encoding)
        return original(mask, size, encoding)

    segmentation.encode_mask_b64 = counting
    try:
        entries = [
            {"label": f"l{index}", "score": round(0.99 - index * 0.01, 4),
             "box": [0.0, 0.0, 1.0, 1.0], "mask": _mask(8, 8, (index % 8,))}
            for index in range(20)]
        result = segmentation.finalize(entries, (8, 8), 5, "png")
    finally:
        segmentation.encode_mask_b64 = original

    naive = sorted(entries, key=lambda item: item["score"], reverse=True)[:5]
    check("finalize: 5 masques encodés pour 5 objets renvoyés", len(encoded) == 5, str(len(encoded)))
    check("finalize: sélection et ordre identiques au tri complet",
          [item["label"] for item in result["objects"]] == [item["label"] for item in naive])
    check("finalize: masque du 1er objet décodable",
          bool(naive[0]["mask"].any()) and result["objects"][0]["mask_b64"] != "")
    check("finalize: mask_encoding appliqué renvoyé", result["mask_encoding"] == "png")
    check("finalize: none ⇒ aucun masque encodé",
          segmentation.finalize(entries, (8, 8), 3, "none")["objects"][0]["mask_b64"] == "")


def test_top_indices(segmentation) -> None:
    """Le cap par concept ne change pas le top global (égalités comprises)."""
    from test_units import check

    scores = [0.5, 0.9, 0.5, 0.1, 0.9, 0.3]
    check("_top_indices: cap 2 = ordre de détection à égalité",
          segmentation._top_indices(np.array(scores), 2) == [1, 4],
          str(segmentation._top_indices(np.array(scores), 2)))
    check("_top_indices: cap 0 → vide", segmentation._top_indices(np.array(scores), 0) == [])


def test_alias_dedup(segmentation) -> None:
    """Dédup FR/EN sur le concept modèle : deux libellés, une seule passe DETR."""
    from test_units import check, raises

    check("alias: chaise + chair → un seul prompt",
          segmentation.normalize_prompts(["chaise", "chair"]) == ["chaise"])
    check("alias: label = le premier saisi",
          segmentation.normalize_prompts(["chair", "chaise", "une chaise"]) == ["chair"])
    check("alias: casse/article dédupliqués",
          segmentation.normalize_prompts(["Voiture", "la voiture", "car"]) == ["Voiture"])
    check("alias: concepts distincts conservés",
          segmentation.normalize_prompts(["chaise", "table"]) == ["chaise", "table"])
    check("alias: prompt inconnu normalisé pour le modèle",
          segmentation.model_concept("  Mon Kayak ") == "mon kayak")
    check("alias: dédup des inconnus sur la casse",
          segmentation.normalize_prompts(["Kayak", "kayak"]) == ["Kayak"])
    check("alias: mur/escalier → wall/stairs",
          segmentation.model_concept("mur") == "wall"
          and segmentation.model_concept("les escaliers") == "stairs")
    check("alias: extérieur (barrière, rambarde, poteau, trottoir)",
          segmentation.model_concept("barrière") == "barrier"
          and segmentation.model_concept("une rambarde") == "railing"
          and segmentation.model_concept("POTEAUX") == "pole"
          and segmentation.model_concept("trottoir") == "sidewalk")
    check("alias: accents optionnels (barriere, fenetre, passage pieton)",
          segmentation.model_concept("barriere") == "barrier"
          and segmentation.model_concept("fenetre") == "window"
          and segmentation.model_concept("passage pieton") == "crosswalk")
    check("alias: dédup accentué/non accentué",
          segmentation.normalize_prompts(["barrière", "barriere"]) == ["barrière"])
    check("alias: dédup mur/wall et escalier/stairs",
          segmentation.normalize_prompts(["mur", "wall"]) == ["mur"]
          and segmentation.normalize_prompts(["escalier", "stairs"]) == ["escalier"])
    check("alias: mur + escalier distincts conservés",
          segmentation.normalize_prompts(["mur", "escalier"]) == ["mur", "escalier"])
    check("alias: plafond MAX_PROMPTS toujours appliqué",
          raises(lambda: segmentation.normalize_prompts([f"c{i}" for i in range(segmentation.MAX_PROMPTS + 1)]),
                 segmentation.PromptError))


def test_timing(timing) -> None:
    """Chronos par étage : cumul, chronomètre de bloc et en-tête ``Server-Timing``."""
    import time as _time

    from test_units import check

    timers: dict = {}
    check("timing: add_ms cumule", timing.add_ms(timers, "vision_ms", 1.25) == 1.2
          and timing.add_ms(timers, "vision_ms", 1.25) == 2.5, str(timers))
    with timing.Stage(timers, "queue_ms"):
        _time.sleep(0.01)
    check("timing: Stage mesure ≥ 10 ms", timers.get("queue_ms", 0) >= 10.0, str(timers))
    check("timing: en-tête ordonné",
          timing.format_server_timing({"preprocess_ms": 1.2, "vision_ms": 31.0})
          == "preprocess_ms;dur=1.2, vision_ms;dur=31.0",
          timing.format_server_timing({"preprocess_ms": 1.2, "vision_ms": 31.0}))
    check("timing: carte vide → pas d'en-tête", timing.format_server_timing({}) == "")
    check("timing: None → pas d'en-tête", timing.format_server_timing(None) == "")
    check("timing: valeur non numérique ignorée",
          timing.format_server_timing({"a_ms": "x", "b_ms": 2}) == "b_ms;dur=2.0")
    leaked = False
    try:
        with timing.Stage(timers, "detr_ms"):
            raise RuntimeError("boum")
    except RuntimeError:
        leaked = True
    check("timing: Stage ne masque pas l'exception et mesure quand même",
          leaked and "detr_ms" in timers, str(timers))