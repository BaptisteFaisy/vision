"""Banc SAM 3.1 *vidéo* (tracker multiplex officiel) sur la GPU Modal demandée.

  cd backend && PROBE_GPU=A100-40GB modal run probe_video_tracker.py

Le checkpoint `sam3.1_multiplex.pt` est lu dans le bucket public du user
(`Baptiste12312/sam3.1-bucket`) via `download_bucket_files`, puis propagé frame
par frame sur la vidéo fournie. Mesures : détection sur l'image invitée, latence
par frame propagée, fps en régime établi (hors 1re frame), pic VRAM. Plusieurs
runs et prompts sont supportés pour valider une carte.
"""
import json
import os
import pathlib
import time

import modal

REQ = str(pathlib.Path(__file__).resolve().parent / "requirements.txt")
image = (
    modal.Image.debian_slim(python_version="3.12")
    .env({"PYTHONPATH": "/root"})
    .pip_install_from_requirements(REQ)
    .apt_install("git")  # après la couche pip : le cache de la couche torch reste valide
    .pip_install(
        "git+https://github.com/facebookresearch/sam3.git",
        "einops",
        "pycocotools",
        "hydra-core",
        "opencv-python-headless",
        "setuptools<80",
    )
)

GPU = os.environ.get("PROBE_GPU", "H100")
BUCKET = "Baptiste12312/sam3.1-bucket"
CKPT_DIR = "/root/ckpts"
CKPT_FILES = ["sam3.1_multiplex.pt", "config.json"]  # le BPE vient de sam3/assets, pas du bucket
MUX = int(os.environ.get("PROBE_MUX", "16"))        # objets par bucket multiplex (figé par le ckpt)
MAXOBJ = int(os.environ.get("PROBE_MAXOBJ", "16"))  # total objets suivis (figé par le ckpt)
COMPILE = os.environ.get("PROBE_COMPILE", "0") == "1"
PROMPTS = os.environ.get("PROBE_PROMPTS", "person").split(",")
REPEATS = int(os.environ.get("PROBE_REPEATS", "1"))
TAG = os.environ.get("PROBE_TAG", f"{GPU}-c{int(COMPILE)}-p{len(PROMPTS)}-r{REPEATS}")

app = modal.App(f"sam3-tracker-{TAG}")


def fetch_checkpoint() -> float:
    """Télécharge le checkpoint depuis le bucket public ; renvoie la durée (0 si déjà là)."""
    import os

    from huggingface_hub import download_bucket_files

    os.makedirs(CKPT_DIR, exist_ok=True)
    target = os.path.join(CKPT_DIR, "sam3.1_multiplex.pt")
    if os.path.exists(target):
        return 0.0
    start = time.time()
    download_bucket_files(BUCKET, files=[(f, os.path.join(CKPT_DIR, f)) for f in CKPT_FILES])
    return round(time.time() - start, 1)


def patch_start_session() -> None:
    """Contourne un bug du paquet sam3 : `init_state()` du modèle multiplex ne prend pas
    `offload_state_to_cpu`, que Sam3BasePredictor.start_session passe quand même.
    On filtre les kwargs non supportés ; le reste du chemin (prompt, propagation) est intact.
    """
    import inspect
    import time
    import uuid

    from sam3.model.sam3_base_predictor import Sam3BasePredictor

    def start_session(self, resource_path, session_id=None, offload_video_to_cpu=False,
                      offload_state_to_cpu=False):
        params = inspect.signature(self.model.init_state).parameters
        kwargs = {"resource_path": resource_path}
        for name, value in (
            ("offload_video_to_cpu", offload_video_to_cpu),
            ("offload_state_to_cpu", offload_state_to_cpu),
            ("async_loading_frames", getattr(self, "async_loading_frames", None)),
        ):
            if name in params and value is not None:
                kwargs[name] = value
        state = self.model.init_state(**kwargs)
        sid = session_id or str(uuid.uuid4())
        self._all_inference_states[sid] = {
            "state": state, "session_id": sid, "start_time": time.time(), "last_use_time": time.time(),
        }
        return {"session_id": sid}

    Sam3BasePredictor.start_session = start_session


def run_session(predictor, video_path: str, prompts: list[str]) -> dict:
    """Une session complète : start → prompts sur la frame 0 → propagation avant chronométrée."""
    import numpy as np
    import torch

    session_id = predictor.handle_request({"type": "start_session", "resource_path": video_path})["session_id"]
    obj_ids, probs, prompt_s = [], [], 0.0
    for text in prompts:
        start = time.time()
        out = predictor.handle_request(
            {"type": "add_prompt", "session_id": session_id, "frame_index": 0, "text": text}
        )["outputs"]
        prompt_s += time.time() - start
        if out.get("out_obj_ids") is not None:
            obj_ids += [int(i) for i in out["out_obj_ids"].flatten().tolist()]
            probs += [round(float(p), 3) for p in (out["out_probs"].flatten().tolist() if out.get("out_probs") is not None else [])]
    torch.cuda.synchronize()
    torch.cuda.reset_peak_memory_stats()
    stamps, frames, last = [time.time()], 0, None
    for item in predictor.handle_stream_request(
        {"type": "propagate_in_video", "session_id": session_id, "propagation_direction": "forward"}
    ):
        frames += 1
        last = item
        stamps.append(time.time())
    torch.cuda.synchronize()
    steps = np.diff(stamps)
    steady = float(steps[1:].mean()) if len(steps) > 1 else float(steps.mean())
    result = {
        "prompt_s": round(prompt_s, 2),
        "objects": len(set(obj_ids)),
        "obj_ids": sorted(set(obj_ids)),
        "probs": probs[:8],
        "frames": frames,
        "total_s": round(float(steps.sum()), 2),
        "first_step_ms": round(float(steps[0]) * 1e3, 1),
        "steady_ms": round(steady * 1e3, 1),
        "steady_fps": round(1 / steady, 2),
        "mean_fps": round(frames / float(steps.sum()), 2),
        "p95_ms": round(float(np.percentile(steps, 95)) * 1e3, 1),
        "median_ms": round(float(np.median(steps)) * 1e3, 1),
        "slow_steps": int((steps > 0.5).sum()),
        "vram_peak_gb": round(float(torch.cuda.max_memory_allocated()) / 1e9, 2),
    }
    if isinstance(last, dict):
        inner = last.get("outputs", last)
        masks = inner.get("out_binary_masks")
        ids = inner.get("out_obj_ids")
        result["last_masks"] = list(masks.shape) if masks is not None and hasattr(masks, "shape") else None
        result["last_objs"] = int(len(ids.flatten().tolist())) if ids is not None else None
    predictor.handle_request({"type": "close_session", "session_id": session_id})
    return result


@app.function(image=image, gpu=GPU, timeout=3000, cpu=8, memory=32768)
def tracker_bench(video_bytes: bytes, mux: int = MUX, maxobj: int = MAXOBJ,
                  compile_model: bool = COMPILE, prompts: tuple = tuple(PROMPTS),
                  repeats: int = REPEATS) -> dict:
    import torch

    from sam3.model_builder import build_sam3_multiplex_video_predictor

    patch_start_session()
    name, cap = torch.cuda.get_device_name(0), torch.cuda.get_device_capability()
    report = {"device": f"{name} sm_{cap[0]}{cap[1]}", "tag": TAG, "compile": compile_model,
              "prompts": list(prompts), "mux": mux, "max_objects": maxobj,
              "download_s": fetch_checkpoint()}
    video_path = "/root/clip.mp4"
    pathlib.Path(video_path).write_bytes(video_bytes)
    start = time.time()
    predictor = build_sam3_multiplex_video_predictor(
        checkpoint_path=os.path.join(CKPT_DIR, "sam3.1_multiplex.pt"),
        multiplex_count=mux,
        max_num_objects=maxobj,
        use_fa3=False,
        compile=compile_model,
        warm_up=compile_model,
    )
    report["build_s"] = round(time.time() - start, 1)
    report["vram_weights_gb"] = round(float(torch.cuda.max_memory_allocated()) / 1e9, 2)
    report["runs"] = [run_session(predictor, video_path, list(prompts)) for _ in range(repeats)]
    free, total = torch.cuda.mem_get_info()
    report["vram_occupée_gb"] = round((total - free) / 1e9, 2)
    report["vram_carte_gb"] = round(total / 1e9, 1)
    print("RESULT " + json.dumps(report))
    return report


@app.local_entrypoint()
def main(video: str = "/tmp/clip_1080p_300.mp4"):
    raw = pathlib.Path(video).read_bytes()
    print(f"vidéo {len(raw) // 1024} Ko depuis {video} → tracker sur {GPU} (tag {TAG})")
    print(json.dumps(tracker_bench.remote(raw, MUX, MAXOBJ, COMPILE, tuple(PROMPTS), REPEATS), indent=2))
