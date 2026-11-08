import json
import threading
import urllib.error
import urllib.request

import pytest

from bwserver.api import make_server


@pytest.fixture
def api(eng):
    srv = make_server(eng, "127.0.0.1", 0)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{srv.server_address[1]}"
    token = eng.settings["token"]

    def call(method, path, body=None, auth=True):
        req = urllib.request.Request(base + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if auth else {})})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode())
    call.eng, call.base = eng, base
    yield call
    srv.shutdown()


def test_ping_is_open_but_data_is_not(api):
    assert api("GET", "/api/ping", auth=False)[0] == 200
    assert api("GET", "/api/state", auth=False)[0] == 401


def test_state_shape(api):
    code, s = api("GET", "/api/state")
    assert code == 200 and len(s["tickers"]) == 3
    t = s["tickers"][0]
    assert {"sym", "name", "last", "chg", "alerts"} <= set(t)
    assert s["settings"]["ai_ready"] is False and "token" not in json.dumps(s)


def test_manual_flow_add_alert_events_delete(api):
    assert api("POST", "/api/tickers", {"sym": "MSFT", "name": "Microsoft"})[0] == 200
    api.eng.source.override["MSFT"] = 1.0
    code, r = api("POST", "/api/alerts", {"sym": "MSFT", "op": "<", "value": 5})
    assert code == 200
    api("POST", "/api/refresh", {})
    ev = api("GET", "/api/events?after=0")[1]["events"]
    assert any(e["kind"] == "alert" and e["sym"] == "MSFT" for e in ev)
    assert api("DELETE", f"/api/alerts/{r['id']}")[0] == 200
    assert api("DELETE", "/api/tickers/MSFT")[0] == 200
    assert api("DELETE", "/api/tickers/MSFT")[0] == 400


def test_errors_are_json_with_italian_message(api):
    code, r = api("POST", "/api/alerts", {"sym": "AAPL", "op": "<", "value": -3})
    assert code == 400 and "error" in r


def test_search_chart_recap_export_move(api):
    assert api("GET", "/api/search?q=tesla")[1]["risultati"][0]["sym"] == "TSLA"
    assert len(api("GET", "/api/chart?sym=AAPL&days=1")[1]["points"]) > 5
    assert "Migliori" in api("GET", "/api/recap")[1]["text"]
    assert api("GET", "/api/export")[1]["csv"].startswith("Ticker;Nome")
    assert api("POST", "/api/tickers/move", {"sym": "ENI.MI", "where": "top"})[0] == 200
    assert api("GET", "/api/state")[1]["tickers"][0]["sym"] == "ENI.MI"


def test_device_registration_and_settings(api):
    assert api("POST", "/api/device", {"token": "x"})[0] == 400
    assert api("POST", "/api/device", {"token": "t" * 40})[0] == 200
    assert api.eng.store.devices() == ["t" * 40]
    code, r = api("POST", "/api/settings", {"openrouter_key": "sk-or-test", "interval_alert": 45})
    assert code == 200 and r["settings"]["ai_ready"] and api.eng.settings["interval_alert"] == 45


def test_ai_endpoints(api, monkeypatch):
    from bwserver import ai
    answers = iter([json.dumps({"reply": "Preparato.", "calls": [{"tool": "add_ticker", "args": {"sym": "MSFT"}}]})])
    monkeypatch.setattr(ai, "openrouter_llm", lambda s: (lambda m: next(answers)))
    code, r = api("POST", "/api/ai/chat", {"text": "aggiungi Microsoft", "history": []})
    assert code == 200 and r["proposals"][0]["tool"] == "add_ticker"
    assert not api.eng.store.has_ticker("MSFT")
    code, r2 = api("POST", "/api/ai/confirm", {"id": r["proposals"][0]["id"], "accept": True})
    assert code == 200 and api.eng.store.has_ticker("MSFT")


def test_ai_missing_key_is_503(api):
    code, r = api("POST", "/api/ai/chat", {"text": "ciao"})
    assert code == 503 and "OpenRouter" in r["error"]


def test_local_home_shows_pairing_code(api):
    html = urllib.request.urlopen(api.base + "/").read().decode()
    assert api.eng.settings["token"] in html and "Borsa Watch" in html


def test_demo_fake_assistant_end_to_end(api):
    api.eng.settings.values["fake_ai"] = True
    code, r = api("POST", "/api/ai/chat", {"text": "Avvisami se Apple scende sotto 100", "history": []})
    assert code == 200 and r["proposals"] and r["proposals"][0]["tool"] == "create_alert", r
    code, r2 = api("POST", "/api/ai/confirm", {"id": r["proposals"][0]["id"], "accept": True})
    assert code == 200 and api.eng.store.alerts()
