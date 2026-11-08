"""Impostazioni del server: cartella dati e file settings.json."""
import json
import os
import secrets
import threading
from pathlib import Path

VERSION = "1.0.0"

DEFAULTS = {
    "token": "",                 # codice di abbinamento con il telefono (generato al primo avvio)
    "port": 8765,
    "interval_alert": 20,        # secondi: titoli che hanno un avviso
    "interval_all": 120,         # secondi: tutti gli altri titoli
    "market_hours": True,        # legge solo lun-ven 9:00-22:05 (ora italiana)
    "openrouter_key": "",        # chiave per l'assistente AI (gratis su openrouter.ai)
    "model": "openrouter/free",
    "fcm_file": "",              # file JSON dell'account di servizio Firebase (notifiche push)
}

_lock = threading.Lock()


def data_dir() -> Path:
    env = os.environ.get("BW_DATA")
    if env:
        p = Path(env)
    elif os.name == "nt":
        p = Path(os.environ.get("APPDATA", str(Path.home()))) / "BorsaWatch"
    else:
        p = Path.home() / ".borsawatch"
    p.mkdir(parents=True, exist_ok=True)
    return p


class Settings:
    """Legge e salva settings.json. Le chiavi sconosciute vengono conservate."""

    def __init__(self, folder: Path):
        self.path = folder / "settings.json"
        self.folder = folder
        self.values = dict(DEFAULTS)
        try:
            self.values.update(json.loads(self.path.read_text(encoding="utf-8-sig")))
        except (OSError, ValueError):
            pass
        if not self.values.get("token"):
            self.values["token"] = secrets.token_hex(5)  # 10 caratteri, facili da copiare
            self.save()

    def __getitem__(self, key):
        return self.values.get(key, DEFAULTS.get(key))

    def get(self, key, default=None):
        return self.values.get(key, default)

    def update(self, **kw):
        with _lock:
            self.values.update(kw)
            self.save()

    def save(self):
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.values, indent=2, ensure_ascii=False), encoding="utf-8")
        os.replace(tmp, self.path)

    def public(self) -> dict:
        """Impostazioni che l'app può vedere (mai la chiave né il token)."""
        return {
            "interval_alert": self["interval_alert"], "interval_all": self["interval_all"],
            "market_hours": self["market_hours"], "model": self["model"],
            "ai_ready": bool(self["openrouter_key"]), "push_ready": bool(self["fcm_file"]),
        }
