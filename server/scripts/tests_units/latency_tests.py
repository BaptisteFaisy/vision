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
    line = timing.format_boot_cpu("import_torch", 1200.5, 1200.5)
    check("timing: boot_cpu format exact",
          line == "boot_cpu: stage=import_torch ms=1200.5 total_ms=1200.5", line)
    check("timing: boot_cpu préfixe constant",
          timing.format_boot_cpu("total", 14800.0, 14800.0).startswith(
              timing.BOOT_CPU_PREFIX + " "),
          timing.format_boot_cpu("total", 14800.0, 14800.0))


def test_max_objects_ceiling(segmentation) -> None:
    """Plafond serveur à 32 : ``finalize`` coupe à 32, les meilleurs scores gardés."""
    from test_units import check

    check("MAX_OBJECTS = 32 (demande user 29/10)", segmentation.MAX_OBJECTS == 32,
          str(segmentation.MAX_OBJECTS))
    many = [{"label": f"objet{i}", "score": 1.0 - i / 100.0, "box": [0.0, 0.0, 8.0, 8.0],
             "mask": None} for i in range(40)]
    capped = segmentation.finalize(many, (8, 8), segmentation.MAX_OBJECTS, "none")
    check("finalize: 40 entrées → 32 objets", len(capped["objects"]) == 32,
          str(len(capped["objects"])))
    check("finalize: les 32 meilleurs scores gardés",
          [item["label"] for item in capped["objects"]] == [f"objet{i}" for i in range(32)])
    _crowd_cap(segmentation, check)


def _crowd_cap(segmentation, check) -> None:
    """Plafond client 16 (patch foule 01/11) : 16 objets passent, le 17e est tronqué.

    En foule, 10 personnes ; 8 objets = la moitié de la scène perdue. Le 17e tombé
    est le moins bien classé : ``finalize`` trie au score seul, donc c'est bien le
    dernier arrivé du tri qui saute, jamais un mieux classé.
    """
    crowd = [{"label": f"personne{i}", "score": 0.9 - i / 100.0,
              "box": [0.0, 0.0, 8.0, 8.0], "mask": None} for i in range(17)]
    sixteen = segmentation.finalize(crowd[:16], (8, 8), 16, "none")
    check("foule : 16 objets passent au plafond client",
          len(sixteen["objects"]) == 16, str(len(sixteen["objects"])))
    trimmed = segmentation.finalize(crowd, (8, 8), 16, "none")
    check("foule : le 17e objet est tronqué",
          len(trimmed["objects"]) == 16, str(len(trimmed["objects"])))
    labels = [item["label"] for item in trimmed["objects"]]
    check("foule : le 17e tombé est le moins bien classé",
          "personne16" not in labels and "personne15" in labels, str(labels[-3:]))


def test_live_clamp_ceiling(live_app) -> None:
    """Knob ``max_objects`` : 32 passe, au-delà le plafond serveur s'applique, défaut 16."""
    from test_units import check

    cap = __import__("segmentation").MAX_OBJECTS
    low, default = live_app.MAX_OBJECTS_MIN, live_app.MAX_OBJECTS_DEFAULT
    check("_clamp: 32 passe (plafond serveur)", live_app._clamp(32, default, low, cap) == 32)
    check("_clamp: au-delà → plafond serveur", live_app._clamp(99, default, low, cap) == cap)
    check("défaut /live (16 : 17 prompts + slots gardés)", default == 16)

def test_rle(imaging) -> None:
    """Codec RLE : miroir du décodeur Kotlin (MaskRle.kt + maskGrid), octet par octet."""
    from test_units import check, raises

    mask = np.zeros((4, 6), dtype=np.uint8)
    mask[1:3, 2:5] = 1
    encoded = imaging.encode_mask_rle_b64(mask, (6, 4))
    raw = base64.b64decode(encoded)
    # 4x6 : ligne0 = 6 zéros, lignes1-2 = 2 zéros + 3 uns + 1 zéro, ligne3 = 6 zéros.
    # Runs : 8,3,3,3,7 → varints 1 octet chacun.
    check("RLE: runs exacts", list(raw) == [8, 3, 3, 3, 7], str(list(raw)))
    check("RLE: golden base64", encoded == "CAMDAwc=",
          encoded)
    back = imaging.decode_mask_rle_b64(encoded, (6, 4))
    check("RLE: roundtrip sans perte",
          back.shape == (4, 6) and (back == (mask * 255).astype(np.uint8)).all())
    leading = np.ones((1, 4), dtype=np.uint8)
    check("RLE: masque commençant par 1 → run 0 initial",
          list(base64.b64decode(imaging.encode_mask_rle_b64(leading, (4, 1)))) == [0, 4])
    long = np.zeros((1, 300), dtype=np.uint8)
    check("RLE: run > 127 → varint 2 octets",
          list(base64.b64decode(imaging.encode_mask_rle_b64(long, (300, 1)))) == [0xAC, 0x02])
    rng = np.random.default_rng(7)
    alea_ok = True
    for shape in ((1, 1), (1, 4), (4, 1), (4, 6), (103, 256), (256, 103)):
        tirage = (rng.random(shape) < 0.3).astype(np.uint8)
        retour = imaging.decode_mask_rle_b64(
            imaging.encode_mask_rle_b64(tirage, (shape[1], shape[0])), (shape[1], shape[0]))
        alea_ok = alea_ok and (retour == tirage * 255).all()
    check("RLE: roundtrip aléatoire six géométries", alea_ok)
    damier = (np.indices((8, 8)).sum(axis=0) % 2).astype(np.uint8)
    brut = list(base64.b64decode(imaging.encode_mask_rle_b64(damier, (8, 8))))
    check("RLE: damier 8x8 = 57 runs (50×1, 7×2 aux jonctions)",
          len(brut) == 57 and brut.count(1) == 50 and brut.count(2) == 7)
    grid, scale = imaging.rle_grid((512, 1138), 256)
    expected_scale = 256 / 1138
    gw = int(np.floor(np.float32(512) * np.float32(expected_scale) + np.float32(0.5)))
    gh = int(np.floor(np.float32(1138) * np.float32(expected_scale) + np.float32(0.5)))
    check("RLE: grille = calcul client float32", grid == (gw, gh), f"{grid} vs {(gw, gh)}")
    check("RLE: mask_scale = edge/long", abs(scale - expected_scale) < 1e-12, str(scale))
    check("RLE: edge borné, jamais rejeté",
          imaging.rle_grid((512, 512), 99999)[0][0] <= 1024
          and imaging.rle_grid((512, 512), 0)[0] == imaging.rle_grid((512, 512), 256)[0])
    check("RLE: total inexact refusé",
          raises(lambda: imaging.decode_mask_rle_b64(
              base64.b64encode(bytes([2, 2])).decode(), (6, 4)), imaging.MaskFormatError))


def test_rle_finalize(segmentation) -> None:
    """finalize(mask_format=rle) : grille réduite + mask_scale par objet."""
    from test_units import check

    mask = np.zeros((8, 8), dtype=np.uint8)
    mask[2:6, 2:6] = 1
    entries = [{"label": "person", "score": 0.9, "box": [0.0, 0.0, 8.0, 8.0], "mask": mask}]
    result = segmentation.finalize(entries, (512, 512), 4, "png",
                                   mask_format="rle", mask_edge=256)
    check("finalize RLE: encodage appliqué", result["mask_encoding"] == "rle",
          str(result.get("mask_encoding")))
    obj = result["objects"][0]
    check("finalize RLE: mask_scale = 0,5", abs(obj["mask_scale"] - 0.5) < 1e-12,
          str(obj.get("mask_scale")))
    check("finalize RLE: masque non vide", isinstance(obj["mask_b64"], str) and len(obj["mask_b64"]) > 8)
    legacy = segmentation.finalize(entries, (512, 512), 4, "png")
    check("finalize sans knob: chemin historique", legacy["mask_encoding"] == "png"
          and "mask_scale" not in legacy["objects"][0])
    unknown = segmentation.finalize(entries, (512, 512), 4, "png", mask_format="poly")
    check("finalize knob inconnu: ignoré, pas d'erreur", unknown["mask_encoding"] == "png")
