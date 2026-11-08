"""Notifiche push verso l'app Android tramite Firebase Cloud Messaging (FCM, gratuito)."""
import json
import logging
from pathlib import Path

log = logging.getLogger("bw.push")


class NullPush:
    configured = False

    def send(self, tokens, data):
        return []


class FcmPush:
    """Manda messaggi 'solo dati' ad alta priorità: l'app li salva nella sua casella e crea la notifica."""
    configured = True
    SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

    def __init__(self, service_account_file):
        from google.oauth2 import service_account
        self.creds = service_account.Credentials.from_service_account_file(str(service_account_file), scopes=[self.SCOPE])
        self.project = json.loads(Path(service_account_file).read_text(encoding="utf-8"))["project_id"]
        self.url = f"https://fcm.googleapis.com/v1/projects/{self.project}/messages:send"

    def _headers(self):
        from google.auth.transport.requests import Request
        if not self.creds.valid:
            self.creds.refresh(Request())
        return {"Authorization": f"Bearer {self.creds.token}", "Content-Type": "application/json"}

    def send(self, tokens, data):
        """Restituisce i token non più validi (app disinstallata) da togliere dall'elenco."""
        import requests
        dead = []
        payload_data = {k: "" if v is None else str(v) for k, v in data.items()}
        for t in tokens:
            try:
                r = requests.post(self.url, headers=self._headers(), timeout=15, json={
                    "message": {"token": t, "data": payload_data, "android": {"priority": "HIGH"}}})
                if r.status_code in (400, 404) and ("UNREGISTERED" in r.text or "INVALID_ARGUMENT" in r.text):
                    dead.append(t)
                elif r.status_code >= 300:
                    log.warning("FCM %s: %s", r.status_code, r.text[:200])
            except Exception as e:  # noqa: BLE001
                log.warning("FCM errore: %s", e)
        return dead


def make_push(settings):
    f = settings["fcm_file"]
    if f and Path(f).exists():
        try:
            return FcmPush(f)
        except Exception as e:  # noqa: BLE001
            log.warning("File Firebase non valido: %s", e)
    return NullPush()
