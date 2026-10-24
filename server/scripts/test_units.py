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


async def asgi_call(app, method: str, path: str, body: bytes) -> tuple[int, dict]:
    """Requête factice contre la vraie app ASGI, sans httpx ni TestClient."""
    scope = {"type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1", "method": method,
             "path": path, "raw_path": path.encode(), "query_string": b"",
             "headers": [(b"content-type", b"application/json")], "client": ("test", 1),
             "server": ("test", 80), "root_path": "", "scheme": "http"}
    sent: list[dict] = []

    async def receive():
        return {"type": "http.request", "body": body, "more_body": False}

    async def send(message):
        sent.append(message)

    await app(scope, receive, send)
    status = next((m["status"] for m in sent if m["type"] == "http.response.start"), 0)
    payload = b"".join(m.get("body", b"") for m in sent if m["type"] == "http.response.body")
    return status, json.loads(payload.decode("utf-8") or "null")


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


RUNNERS = (
    ("imaging", "imaging", test_imaging),
    ("masques", "imaging", test_masks),
    ("segmentation (logique)", "segmentation", test_segmentation_logic),
    ("llm (logique)", "llm", test_llm_logic),
    ("modal_app (helpers)", "modal_app", test_modal_helpers),
    ("contrat HTTP (app FastAPI)", "modal_app", test_http_contract),
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
