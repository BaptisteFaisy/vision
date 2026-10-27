"""Knobs d'environnement et nom du GPU : purs, sans stack ML (ni torch ni PIL).

Extraits de ``fast_core.py`` (garde taille < 50 lignes/fonction, < 500/fichier) :
``fast_core`` les ré-exporte, donc ``from fast_core import clamp_int, ...`` et les
tests unitaires ne changent pas.
"""

_TRUTHY = ("1", "true", "yes", "on")


def env_bool(raw, default: bool = False) -> bool:
    """Knob d'environnement ou de requête : absent/"" -> default, sinon 1|true|yes|on."""
    if raw is None or str(raw).strip() == "":
        return default
    return str(raw).strip().lower() in _TRUTHY


def env_int(raw, default=None):
    """Entier d'environnement optionnel ; None si absent ou non numérique."""
    try:
        return int(str(raw).strip()) if raw not in (None, "") else default
    except ValueError:
        return default


def clamp_int(value, default: int, low: int, high: int) -> int:
    """Ramener un knob optionnel dans [low, high] ; absent ou non numérique -> default."""
    if value is None:
        return default
    try:
        number = int(value)
    except (TypeError, ValueError):
        return default
    return max(low, min(high, number))


def gpu_name() -> str:
    """Nom du GPU réellement alloué, relevé une fois (nvidia-smi, secours torch)."""
    import subprocess

    try:
        result = subprocess.run(["nvidia-smi", "--query-gpu=name", "--format=csv,noheader"],
                                capture_output=True, text=True, timeout=15)
        if result.returncode == 0 and result.stdout.strip():
            return result.stdout.strip().splitlines()[0].strip()
    except (OSError, subprocess.SubprocessError):
        pass
    try:
        import torch

        if torch.cuda.is_available():
            return torch.cuda.get_device_name(0)
    except Exception:  # noqa: BLE001 — /health ne doit jamais tomber pour un nom de GPU
        pass
    return "unknown"
