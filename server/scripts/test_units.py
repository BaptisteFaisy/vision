#!/usr/bin/env python3
"""Tests unitaires du backend, sans GPU ni token Modal (stdlib + PIL + numpy + fastapi).

Les dépendances lourdes sont simulées par des stubs (``modal``, ``openai``) :
seule la plomberie HTTP et la logique pure sont exercées ici. Le rendu des
masques, le chargement du modèle et l'appel LLM réel restent hors de portée —
couverts par scripts/test_api.py contre le backend déployé.

    python3 scripts/test_units.py
"""

from __future__ import annotations

import asyncio
import base64
import io
import json
import os
import sys
import types
from pathlib import Path

# Quand ce fichier est lancé comme script, il EST le module « test_units » des sous-groupes
# (``scripts/tests_units/*`` font ``from test_units import check``) : sans cette ligne ils
# importeraient un second exemplaire du module, avec ses propres compteurs PASSED/FAILED,
# et leurs vérifications n'apparaîtraient nulle part.
if __name__ == "__main__":
    sys.modules.setdefault("test_units", sys.modules["__main__"])

from tests_units import latency_tests, live_fast_tests, merge_tests, weights_tests  # noqa: E402

BACKEND_DIR = Path(__file__).resolve().parent.parent / "backend"
PASSED: list[str] = []
FAILED: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    (PASSED if condition else FAILED).append(name if condition else f"{name} — {detail}")


def raises(fn, error_type) -> bool:
    try:
        fn()
    except error_type:
        return True
    except Exception:  # noqa: BLE001 - mauvaise erreur levée
        return False
    return False


def stub_module(name: str, **attrs) -> None:
    """Enregistre un module factice, ou complète un module absent de l'environnement."""
    module = types.ModuleType(name)
    for key, value in attrs.items():
        setattr(module, key, value)
    sys.modules[name] = module


def install_stubs() -> None:
    """Stubs Modal (décorateurs identité) et openai, pour importer le backend hors conteneur."""

    class _Image:
        def __getattr__(self, _name):
            return lambda *_a, **_k: self

    class _App:
        def __init__(self, *_args, **_kwargs):
            pass

        def cls(self, *_args, **_kwargs):
            return lambda target: target

    def _identity(*_args, **_kwargs):
        return lambda target: target

    stub_module(
        "modal",
        Image=_Image(),
        App=_App,
        cls=_identity,
        enter=_identity,
        asgi_app=_identity,
        method=_identity,
        # Concurrence d'entrée dans un conteneur (@modal.concurrent(max_inputs=2)).
        concurrent=_identity,
        Secret=types.SimpleNamespace(from_name=_identity),
    )
    stub_module("openai", OpenAI=types.SimpleNamespace)


def sample_png() -> str:
    from PIL import Image

    buffer = io.BytesIO()
    Image.new("RGB", (64, 40), (10, 200, 30)).save(buffer, format="PNG")
    return base64.b64encode(buffer.getvalue()).decode("ascii")


def test_imaging(imaging) -> None:
    png = sample_png()
    image = imaging.decode_image(png)
    check("decode_image: PNG", image.size == (64, 40) and image.mode == "RGB", str(image.size))
    check("decode_image: préfixe data: toléré", imaging.decode_image("data:image/png;base64," + png).size == (64, 40))
    check("decode_image: base64 invalide → ImageDecodeError",
          raises(lambda: imaging.decode_image("!!!pas du base64!!!"), imaging.ImageDecodeError))
    check("decode_image: octets non image → ImageDecodeError",
          raises(lambda: imaging.decode_image(base64.b64encode(b"pas une image").decode()),
                 imaging.ImageDecodeError))
    check("decode_image: vide → ImageDecodeError",
          raises(lambda: imaging.decode_image(""), imaging.ImageDecodeError))
    check("resize_max_edge: borne respectée",
          imaging.resize_max_edge(image, 20).size == (20, 12))
    check("resize_max_edge: jamais agrandi", imaging.resize_max_edge(image, 500).size == (64, 40))
    check("scale_factors", tuple(round(v, 3) for v in imaging.scale_factors((10, 20), (30, 30))) == (3.0, 1.5))


def test_masks(imaging) -> None:
    import numpy as np

    mask = np.zeros((4, 6), dtype=np.uint8)
    mask[1:3, 2:5] = 1
    encoded = imaging.encode_mask_png_b64(mask, (6, 4))
    raw = base64.b64decode(encoded)
    from PIL import Image

    decoded = Image.open(io.BytesIO(raw))
    check("mask PNG: mode niveaux de gris 8 bits", decoded.mode == "L", decoded.mode)
    check("mask PNG: taille exacte", decoded.size == (6, 4), str(decoded.size))
    values = sorted(set(decoded.getdata()))
    check("mask PNG: 0/255 uniquement", values == [0, 255], str(values))
    resized = imaging.mask_to_uint8(mask, (12, 8))
    check("mask: remise à l'échelle nearest", resized.shape == (8, 12) and resized.max() == 255)


def test_rle(imaging) -> None:
    """Codec RLE : miroir du décodeur Kotlin (MaskRle.kt + maskGrid), octet par octet."""
    import numpy as np

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
    import numpy as np

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


def test_segmentation_logic(segmentation) -> None:
    check("prompts null géré en amont (normalize lève)",
          raises(lambda: segmentation.normalize_prompts(None), segmentation.PromptError))
    check("prompts nettoyés et dédupliqués",
          segmentation.normalize_prompts([" voiture ", "voiture", "piéton"]) == ["voiture", "piéton"])
    check("prompts vides → PromptError",
          raises(lambda: segmentation.normalize_prompts([]), segmentation.PromptError))
    check("prompts que vides → PromptError",
          raises(lambda: segmentation.normalize_prompts(["", "   "]), segmentation.PromptError))
    check("prompts > MAX → PromptError",
          raises(lambda: segmentation.normalize_prompts([f"c{i}" for i in range(segmentation.MAX_PROMPTS + 1)]),
                 segmentation.PromptError))
    check("prompt trop long → PromptError",
          raises(lambda: segmentation.normalize_prompts(["x" * (segmentation.MAX_PROMPT_CHARS + 1)]),
                 segmentation.PromptError))
    check("concept FR → EN", segmentation.model_concept("une voiture") == "car")
    check("concept inconnu passes through", segmentation.model_concept("kayak") == "kayak")
    box = segmentation._clamp_box([-10, 5, 9999, 20], 2.0, 2.0, (100, 40))
    check("box bornée dans l'image", box == [0.0, 10.0, 100.0, 40.0], str(box))


def test_llm_logic(llm) -> None:
    check("extract_json: objet simple", llm.extract_json('{"description": "a", "tags": ["x"]}')["tags"] == ["x"])
    check("extract_json: fence markdown", llm.extract_json('```json\n{"description": "a"}\n```') is not None)
    check("extract_json: texte sans JSON → None", llm.extract_json("aucun json") is None)
    check("extract_json: liste → None", llm.extract_json("[1,2]") is None)
    check("label_hint vide", llm.label_hint([]) == "aucun objet segmenté")
    history = [{"role": "user", "content": "q"}, {"role": "system", "content": "x"}, {"role": "user", "content": " "}]
    check("history filtrée (rôles + vides)", llm._history_messages(history) == [{"role": "user", "content": "q"}])
    check("history tronquée", len(llm._history_messages([{"role": "user", "content": "q"}] * 40)) == llm.MAX_HISTORY_MESSAGES)
    message = llm.user_message("data:image/jpeg;base64,AAA", "question")
    check("user_message multimodal", message["role"] == "user" and len(message["content"]) == 2)
    os.environ.pop("LLM_API_KEY", None)
    check("settings sans clé → LLMError", raises(llm.settings_from_env, llm.LLMError))


def test_modal_helpers(modal_app) -> None:
    objects = [{"label": "car"}, {"label": "person"}, {"label": "car"}]
    check("_labels dédupliqué ordonné", modal_app._labels(objects) == ["car", "person"])
    big = "A" * 2_000_000
    errors = [{"type": "model_attributes_type", "loc": ("body",), "msg": "Input should be a valid dictionary",
               "input": [big]}]
    detail = modal_app._validation_detail(errors)
    check("_validation_detail: pas d'echo du payload", big[:64] not in detail, f"len={len(detail)}")
    check("_validation_detail: borné", len(detail) <= modal_app.MAX_DETAIL_CHARS, str(len(detail)))
    many = [{"loc": ("body", f"f{i}"), "msg": "Field required"} for i in range(20)]
    check("_validation_detail: nombre d'erreurs borné", modal_app._validation_detail(many).count("Field required") <= 8)


async def asgi_call(app, method: str, path: str, body: bytes,
                    headers_out: dict | None = None,
                    content_type: str = "application/json",
                    query_string: bytes = b"") -> tuple[int, dict]:
    """Requête factice contre la vraie app ASGI, sans httpx ni TestClient.

    ``headers_out`` (optionnel) reçoit les en-têtes de la réponse en minuscules : c'est
    ainsi qu'on vérifie un en-tête additif comme ``Server-Timing`` sans changer le retour.
    ``content_type``/``query_string`` servent l'entrée binaire (``octet-stream`` + knobs).
    """
    scope = {"type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1", "method": method,
             "path": path, "raw_path": path.encode(), "query_string": query_string,
             "headers": [(b"content-type", content_type.encode())], "client": ("test", 1),
             "server": ("test", 80), "root_path": "", "scheme": "http"}
    sent: list[dict] = []

    async def receive():
        return {"type": "http.request", "body": body, "more_body": False}

    async def send(message):
        sent.append(message)

    await app(scope, receive, send)
    started = next((m for m in sent if m["type"] == "http.response.start"), {})
    if headers_out is not None:
        headers_out.update({key.decode("latin-1").lower(): value.decode("latin-1")
                            for key, value in started.get("headers", [])})
    payload = b"".join(m.get("body", b"") for m in sent if m["type"] == "http.response.body")
    return started.get("status", 0), json.loads(payload.decode("utf-8") or "null")


def test_http_contract(modal_app) -> None:
    service = modal_app.Sam3Service()
    service._gpu_lock = __import__("threading").Lock()
    api = service.web()
    ok_body = {"width": 64, "height": 40, "objects": []}
    png = sample_png()
    cases = (
        ("/segment image_b64 absent", "/segment", b'{"prompts": null}', 400),
        ("/segment JSON malformé", "/segment", b'{"image_b64": "abcdefgh", broken', 400),
        ("/segment corps non-dict", "/segment", json.dumps([png]).encode(), 400),
        ("/segment prompts non-liste", "/segment", json.dumps({"image_b64": png, "prompts": "car"}).encode(), 400),
        ("/segment base64 invalide", "/segment", json.dumps({"image_b64": "!!!pas du base64!!!",
                                                            "prompts": None}).encode(), 400),
        ("/segment GET", "/segment", b"", 405),
        ("/ask question vide", "/ask", json.dumps({"image_b64": png, "question": ""}).encode(), 400),
        ("/ask role invalide", "/ask", json.dumps({"image_b64": png, "question": "q",
                                                  "history": [{"role": "system", "content": "x"}]}).encode(), 400),
        ("/analyze image_b64 absent", "/analyze", b"{}", 400),
        ("/inconnue", "/frobnicate", b"{}", 404),
    )
    for name, path, body, expected in cases:
        status, payload = asyncio.run(asgi_call(api, "GET" if name.endswith("GET") else "POST",
                                               path, body or b"{}"))
        check(name, status == expected, f"{status} au lieu de {expected}")
        check(f"{name} → detail string", isinstance(payload.get("detail"), str), str(payload)[:120])
    huge = asyncio.run(asgi_call(api, "POST", "/segment", json.dumps([png * 20]).encode()))
    check("/segment echo borné (< 1 Ko)", len(json.dumps(huge[1])) < 1024, f"{len(json.dumps(huge[1]))} octets")

    service._segment_sync = lambda *args: ok_body
    status, payload = asyncio.run(asgi_call(api, "POST", "/segment",
                                            json.dumps({"image_b64": png, "prompts": None}).encode()))
    check("/segment OK 200 + payload SPEC", status == 200 and payload == ok_body, str(payload)[:120])

    def boom(*_args):
        raise RuntimeError("gpu exploded")

    service._analyze_sync = boom
    status, payload = asyncio.run(asgi_call(api, "POST", "/analyze", json.dumps({"image_b64": png}).encode()))
    check("/analyze erreur interne → 500", status == 500 and "gpu exploded" in payload.get("detail", ""), str(status))


def _live_service(live_app):
    """Instance Sam3Live + app FastAPI, hors GPU (stub torch, verrou factice)."""
    stub_module("torch", cuda=types.SimpleNamespace(
        is_available=lambda: True, get_device_name=lambda _i: "NVIDIA A100 80GB PCIe"))
    service = live_app.Sam3Live()
    service._gpu_lock = __import__("threading").Lock()
    service.gpu_name = "NVIDIA A100 80GB PCIe"
    return service, service.web()


def test_live_contract(live_app) -> None:
    """/live (docs/LIVE-SPEC.md) : bornes des knobs, /health enrichi, erreurs 400 bornées."""
    png = sample_png()
    service, api = _live_service(live_app)

    check("_clamp: absent → défaut", live_app._clamp(None, 768, 256, 1024) == 768)
    check("_clamp: sous-borne → borne basse", live_app._clamp(10, 768, 256, 1024) == 256)
    check("_clamp: sur-borne → borne haute", live_app._clamp(99_999, 768, 256, 1024) == 1024)
    check("_clamp: valeur parasite → défaut", live_app._clamp("xyz", 12, 1, 24) == 12)

    status, payload = asyncio.run(asgi_call(api, "GET", "/health", b""))
    check("/health: ok + model + device + gpu",
          status == 200 and payload.get("ok") is True and payload.get("model") == "sam3.1"
          and payload.get("device") == "cuda" and "A100" in payload.get("gpu", ""), str(payload)[:120])

    cases = (
        ("/live image_b64 absent", "/live", b'{"prompts": null}', 400),
        ("/live base64 invalide", "/live", json.dumps({"image_b64": "!!!pas du base64!!!"}).encode(), 400),
        ("/live prompts non-liste", "/live", json.dumps({"image_b64": png, "prompts": "car"}).encode(), 400),
        ("/live corps = liste", "/live", json.dumps([png]).encode(), 400),
        ("/live GET", "/live", b"", 405),
        ("/segment image_b64 absent", "/segment", b'{"prompts": null}', 400),
    )
    for name, path, body, expected in cases:
        status, payload = asyncio.run(asgi_call(api, "GET" if name.endswith("GET") else "POST", path, body))
        check(name, status == expected, f"{status} au lieu de {expected}")
        check(f"{name} → detail string borné",
              isinstance(payload.get("detail"), str) and len(payload["detail"]) <= 300, str(payload)[:120])


def test_live_pipeline(live_app) -> None:
    """/live : echo borné, transmission des knobs, 500 interne borné."""
    png = sample_png()
    service, api = _live_service(live_app)

    huge = asyncio.run(asgi_call(api, "POST", "/live", json.dumps([png * 20]).encode()))
    check("/live echo borné (< 1 Ko)", len(json.dumps(huge[1])) < 1024, f"{len(json.dumps(huge[1]))} octets")

    captured = {}

    def fake_live(image_b64, prompts, max_objects, work_edge):
        captured.update(prompts=prompts, max_objects=max_objects, work_edge=work_edge)
        return {"width": 64, "height": 40, "elapsed_ms": 12, "objects": []}

    service._live_sync = fake_live
    status, payload = asyncio.run(asgi_call(api, "POST", "/live", json.dumps(
        {"image_b64": png, "prompts": ["person"], "max_objects": 5, "work_edge": 640}).encode()))
    check("/live OK 200 + charge utile LIVE-SPEC",
          status == 200 and set(payload) == {"width", "height", "elapsed_ms", "objects"}, str(payload)[:120])
    check("/live knobs transmis au pipeline",
          captured == {"prompts": ["person"], "max_objects": 5, "work_edge": 640}, str(captured))

    def boom(*_args):
        raise RuntimeError("gpu exploded")

    service._live_sync = boom
    status, payload = asyncio.run(asgi_call(api, "POST", "/live", json.dumps({"image_b64": png}).encode()))
    check("/live erreur interne → 500 borné",
          status == 500 and len(payload.get("detail", "")) <= 300, str(payload)[:120])


RUNNERS = (
    ("imaging", "imaging", test_imaging),
    ("masques", "imaging", test_masks),
    ("masques : codec RLE (miroir Kotlin)", "imaging", test_rle),
    ("masques : finalize RLE + mask_scale", "segmentation", test_rle_finalize),
    ("segmentation (logique)", "segmentation", test_segmentation_logic),
    ("llm (logique)", "llm", test_llm_logic),
    ("modal_app (helpers)", "modal_app", test_modal_helpers),
    ("contrat HTTP (app FastAPI)", "modal_app", test_http_contract),
    ("live_app (contrat /live)", "live_app", test_live_contract),
    ("live_app (pipeline /live)", "live_app", test_live_pipeline),
    # Portage latence (mission 23) — codecs, cap, dédup, chronos, conteneur de prod.
    ("latence : codec bits", "imaging", latency_tests.test_mask_bits),
    ("latence : négociation mask_encoding", "imaging", latency_tests.test_mask_negotiation),
    ("latence : cap max_objects avant encodage", "segmentation", latency_tests.test_finalize_cap),
    ("latence : tri/cap par concept", "segmentation", latency_tests.test_top_indices),
    ("latence : dédup alias FR/EN", "segmentation", latency_tests.test_alias_dedup),
    ("latence : détection → objets", "segmentation", live_fast_tests.test_detection_entries),
    ("chronos : postes et Server-Timing", "timing", latency_tests.test_timing),
    ("chronos : levier FAST_TIMING", "fast_core", live_fast_tests.test_timing_gate),
    ("latence : équivalence du cap par concept", "segmentation", live_fast_tests.test_cap_equivalence),
    ("live_fast : ordre des étages / verrou", "fast_core", live_fast_tests.test_run_frame_order),
    ("live_fast : chemin sans verrou", "fast_core", live_fast_tests.test_run_frame_no_lock),
    ("live_fast : contention du verrou GPU", "fast_core", live_fast_tests.test_gpu_contention),
    ("live_fast : frame de bout en bout (bits/none/png)", "fast_core",
     live_fast_tests.test_run_frame_end_to_end),
    ("live_fast : contrat POST /live", "live_app_fast", live_fast_tests.test_live_fast_contract),
    ("live_fast : entrée binaire /live", "live_app_fast", live_fast_tests.test_live_fast_binary),
    ("live_fast : contrat /live/ws", "live_app_fast", live_fast_tests.test_live_fast_ws),
    # Apport du 20:30 (poids embarqués + snapshot mémoire) préservé par la fusion.
    ("poids : comportement historique sans levier", "fast_core", weights_tests.test_weights_default),
    ("poids : lecture locale + local_files_only", "fast_core", weights_tests.test_weights_local),
    ("poids : edge injecté dans la config", "fast_core", weights_tests.test_weights_edge),
    ("poids : image (téléchargement au build, snapshot)", "live_app_fast",
     weights_tests.test_weights_image),
    ("déploiement : leviers du script", "timing", weights_tests.test_deploy_script),
    ("fusion : contrat de load_cpu", "fast_core", merge_tests.test_load_cpu),
    ("fusion : conteneur (snapshot + latence)", "live_app_fast", merge_tests.test_fusion_live_app),
    # Portage live_fast (déployé) : poids locaux + snapshot + double phase.
    ("live_fast : poids locaux (module déployé)", "timing", weights_tests.test_live_fast_weights),
    ("live_fast : image (poids + snapshot déployés)", "timing", weights_tests.test_live_fast_image),
    ("live_fast : split snap CPU / GPU", "timing", weights_tests.test_live_fast_split),
    ("graphe : pas de faute CUDGraph", "timing", weights_tests.test_no_cudagraph_typo),
    ("concepts : table déployée synchronisée", "timing", weights_tests.test_concepts_sync),
)


def main() -> int:
    sys.path.insert(0, str(BACKEND_DIR))
    install_stubs()
    for title, module_name, runner in RUNNERS:
        try:
            module = __import__(module_name)
            runner(module)
        except Exception as exc:  # noqa: BLE001 - un groupe ne doit pas arrêter les autres
            FAILED.append(f"{title} — exception {type(exc).__name__}: {exc}")
    print(f"Tests unitaires backend : {len(PASSED)} OK, {len(FAILED)} KO")
    for line in FAILED:
        print(f"  KO {line}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
