"""Modal backend for sam3-vision: SAM 3.1 segmentation + vision LLM.

HTTP contract (docs/SPEC.md): GET /health, POST /segment, /analyze, /ask.

Only ``modal`` is imported at module level: torch, transformers, PIL, fastapi
and openai are imported inside the container-side functions, so this module
stays importable locally (py_compile, ``import modal_app``) without a GPU stack.
"""

# NOTE: no `from __future__ import annotations` here — FastAPI resolves the
# request-model annotations (imported locally inside `web()`) at runtime and
# would otherwise see unresolved strings and turn the body into query params.
import asyncio
import threading
from pathlib import Path

import modal

APP_NAME = "sam3-vision"
LLM_SECRET_NAME = "sam3-llm"
GPU_TYPE = "A10G"
MODEL_LABEL = "sam3.1"
BACKEND_DIR = Path(__file__).resolve().parent

image = (
    modal.Image.debian_slim(python_version="3.12")
    .pip_install_from_requirements(str(BACKEND_DIR / "requirements.txt"))
    # .env() is a build step: it must run *before* add_local_* (Modal rule).
    .env({"PYTHONPATH": "/root:/root/backend_src"})
    .add_local_dir(str(BACKEND_DIR), remote_path="/root/backend_src")
)

app = modal.App(APP_NAME)


def _labels(objects: list[dict]) -> list[str]:
    """Distinct labels, ordered by first appearance (objects are score-sorted)."""
    labels: list[str] = []
    for obj in objects:
        if obj["label"] not in labels:
            labels.append(obj["label"])
    return labels


@app.cls(
    image=image,
    gpu=GPU_TYPE,
    secrets=[modal.Secret.from_name(LLM_SECRET_NAME)],
    timeout=600,
    startup_timeout=900,
    scaledown_window=300,
)
class Sam3Service:
    """Warm container: model loaded once, HTTP routes served from the same process."""

    @modal.enter()
    def load(self) -> None:
        """Load SAM 3.1 + processor exactly once per container."""
        from segmentation import load_model

        self.model, self.processor, self.device, self.dtype = load_model()
        self._gpu_lock = threading.Lock()

    # ---- blocking pipelines, always executed from a worker thread ----

    def _segment_sync(self, image_b64: str, prompts) -> dict:
        """Decode the image, then run SAM 3.1 (serialised on the GPU lock)."""
        from imaging import decode_image
        from segmentation import run_segmentation

        image = decode_image(image_b64)
        with self._gpu_lock:
            return run_segmentation(self.model, self.processor, self.device, self.dtype, image, prompts)

    def _analyze_sync(self, image_b64: str) -> dict:
        """Segment with the default concepts, then describe the photo with the LLM."""
        from imaging import decode_image, to_llm_data_url
        from llm import describe_image, settings_from_env
        from segmentation import run_segmentation

        image = decode_image(image_b64)
        with self._gpu_lock:
            result = run_segmentation(self.model, self.processor, self.device, self.dtype, image, None)
        description, tags = describe_image(to_llm_data_url(image), _labels(result["objects"]), settings_from_env())
        return {"description": description, "tags": tags, "objects": result["objects"]}

    def _ask_sync(self, image_b64: str, question: str, history: list[dict]) -> dict:
        """Answer a question about the photo, with the client chat history."""
        from imaging import decode_image, to_llm_data_url
        from llm import answer_question, settings_from_env

        data_url = to_llm_data_url(decode_image(image_b64))
        return {"answer": answer_question(data_url, question, history, settings_from_env())}

    @modal.asgi_app()
    def web(self):
        """FastAPI application exposing the four SPEC routes."""
        from fastapi import FastAPI, HTTPException
        from fastapi.exceptions import RequestValidationError
        from fastapi.responses import JSONResponse
        from imaging import ImageDecodeError
        from schemas import AnalyzeRequest, AskRequest, SegmentRequest
        from segmentation import PromptError, device_label

        api = FastAPI(title=APP_NAME, version="1.0.0")

        async def call(pipeline, *args):
            """Run a blocking pipeline in a thread, mapping errors to HTTP codes."""
            try:
                return await asyncio.to_thread(pipeline, *args)
            except (ImageDecodeError, PromptError) as exc:
                raise HTTPException(status_code=400, detail=str(exc)) from exc
            except Exception as exc:
                raise HTTPException(status_code=500, detail=f"internal error: {exc}") from exc

        @api.exception_handler(RequestValidationError)
        async def invalid_body(_request, exc):
            return JSONResponse(status_code=400, content={"detail": f"invalid request body: {exc.errors()}"})

        @api.get("/health")
        async def health():
            return {"ok": True, "model": MODEL_LABEL, "device": device_label()}

        @api.post("/segment")
        async def segment(request: SegmentRequest):
            return await call(self._segment_sync, request.image_b64, request.prompts)

        @api.post("/analyze")
        async def analyze(request: AnalyzeRequest):
            return await call(self._analyze_sync, request.image_b64)

        @api.post("/ask")
        async def ask(request: AskRequest):
            history = [message.model_dump() for message in request.history or []]
            return await call(self._ask_sync, request.image_b64, request.question, history)

        return api