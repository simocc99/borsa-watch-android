"""API HTTP per l'app Android e pagina di stato locale.

Tutte le rotte /api/* (tranne /api/ping) richiedono l'intestazione  Authorization: Bearer <codice>.
La pagina http://localhost:PORTA/ mostra il codice di abbinamento, ma solo a chi è sul PC stesso.
"""
import html
import json
import socket
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from . import ai, market
from . import tools as T
from .config import VERSION


def local_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        ips.update(i[4][0] for i in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET))
    except OSError:
        pass
    return sorted(i for i in ips if not i.startswith("127."))


def state(eng):
    quotes = eng.store.quotes()
    alerts = eng.store.alerts()
    rows = []
    for t in eng.store.tickers():
        q = quotes.get(t["sym"]) or {}
        rows.append(dict(sym=t["sym"], name=t["name"], last=q.get("last"), chg=q.get("chg"), open=q.get("open"),
                         high=q.get("high"), low=q.get("low"), vol=q.get("vol"), cur=q.get("cur"), ts=q.get("ts"),
                         error=q.get("error") if q.get("last") is None else None,
                         alerts=[a["id"] for a in alerts if a["sym"] == t["sym"]]))
    return dict(server_time=int(time.time()), market_open=market.is_open(), version=VERSION,
                source=eng.source.name, tickers=rows, alerts=alerts, last_event=eng.store.last_event_id(),
                settings=eng.settings.public())


class Handler(BaseHTTPRequestHandler):
    server_version = "BorsaWatch/" + VERSION
    eng = None            # impostato da make_server

    def log_message(self, *a):  # silenzio nel terminale
        pass

    # ---- risposte ----
    def _send(self, code, obj, ctype="application/json"):
        body = obj if isinstance(obj, bytes) else (obj if isinstance(obj, str) else json.dumps(obj, ensure_ascii=False, default=str)).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype + "; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if not n:
            return {}
        raw = self.rfile.read(min(n, 2_000_000))
        ctype = self.headers.get("Content-Type", "")
        if "json" in ctype:
            try:
                return json.loads(raw.decode("utf-8"))
            except ValueError:
                return {}
        return {k: v[0] for k, v in parse_qs(raw.decode("utf-8")).items()}

    def _authorized(self):
        return self.headers.get("Authorization", "") == f"Bearer {self.eng.settings['token']}"

    def _is_local(self):
        return self.client_address[0] in ("127.0.0.1", "::1")

    # ---- instradamento ----
    def do_GET(self):
        self._route("GET")

    def do_POST(self):
        self._route("POST")

    def do_DELETE(self):
        self._route("DELETE")

    def _route(self, method):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        path = u.path.rstrip("/") or "/"
        try:
            if path == "/":
                return self._home(method)
            if path == "/api/ping":
                return self._send(200, dict(app="Borsa Watch", version=VERSION, auth=self._authorized(), time=int(time.time())))
            if not path.startswith("/api/"):
                return self._send(404, dict(error="non trovato"))
            if not self._authorized():
                return self._send(401, dict(error="Codice di abbinamento sbagliato"))
            out = self._api(method, path[5:], q)
            return self._send(200, out)
        except T.ToolError as e:
            return self._send(400, dict(error=str(e)))
        except ai.AIError as e:
            return self._send(503, dict(error=str(e)))
        except Exception as e:  # noqa: BLE001
            return self._send(500, dict(error=f"Errore del server: {e}"))

    # ---- API ----
    def _api(self, method, route, q):
        eng, store = self.eng, self.eng.store
        body = self._body() if method in ("POST", "DELETE") else {}
        if method == "GET":
            if route == "state":
                return state(eng)
            if route == "events":
                return dict(events=store.events(int(q.get("after") or 0)))
            if route == "search":
                return T.run(eng, "find_ticker", dict(query=q.get("q", "")))
            if route == "chart":
                sym, days = q.get("sym", "").upper(), int(q.get("days") or 1)
                pts = [dict(ts=h["ts"], price=h["price"]) for h in store.history(sym, time.time() - days * 86400)]
                if len(pts) < 8:
                    try:
                        pts = eng.source.chart(sym, days)
                    except Exception:  # noqa: BLE001
                        pass
                return dict(sym=sym, points=pts)
            if route == "news":
                return T.run(eng, "get_news", dict(sym=q.get("sym", "")))
            if route == "recap":
                return dict(text=eng.recap_text(), facts=eng.facts(5))
            if route == "export":
                return dict(csv=self._csv())
            if route == "tools":
                return dict(tools=[dict(name=t["name"], desc=t["desc"], mutating=t["mutating"]) for t in T.REGISTRY.values()])
        elif method == "POST":
            if route == "device":
                token = str(body.get("token") or "").strip()
                if len(token) < 20:
                    raise T.ToolError("Codice del telefono non valido")
                store.add_device(token)
                return dict(ok=True)
            if route == "refresh":
                return T.run(eng, "refresh_now", {})
            if route == "ai/chat":
                return ai.chat(eng, body.get("history") or [], str(body.get("text") or "").strip())
            if route == "ai/confirm":
                return ai.confirm(eng, str(body.get("id") or ""), bool(body.get("accept", True)))
            if route == "settings":
                allowed = {k: body[k] for k in ("openrouter_key", "model") if k in body}
                if allowed:
                    eng.settings.update(**allowed)
                for k in ("market_hours", "interval_alert", "interval_all"):
                    if k in body:
                        T.run(eng, "set_setting", dict(key=k, value=body[k]))
                return dict(ok=True, settings=eng.settings.public())
            # attrezzi che modificano, usati dai pulsanti manuali dell'app
            manual = {"tickers": "add_ticker", "tickers/move": "move_ticker", "alerts": "create_alert", "import": "import_tickers"}
            if route in manual:
                return T.run(eng, manual[route], body)
        elif method == "DELETE":
            if route.startswith("tickers/"):
                return T.run(eng, "remove_ticker", dict(sym=route.split("/", 1)[1]))
            if route.startswith("alerts/"):
                return T.run(eng, "remove_alert", dict(id=route.split("/", 1)[1]))
        raise T.ToolError("Richiesta sconosciuta")

    def _csv(self):
        rows = ["Ticker;Nome;Ultimo;Variazione %;Valuta"]
        quotes = self.eng.store.quotes()
        for t in self.eng.store.tickers():
            q = quotes.get(t["sym"]) or {}
            f = lambda v: "" if v is None else str(round(v, 4)).replace(".", ",")   # noqa: E731
            rows.append(";".join([t["sym"], t["name"].replace(";", ","), f(q.get("last")), f(q.get("chg")), q.get("cur") or ""]))
        return "\n".join(rows)

    # ---- pagina di stato sul PC ----
    def _home(self, method):
        eng = self.eng
        if method == "POST" and self._is_local():
            b = self._body()
            upd = {}
            if b.get("openrouter_key", "").strip():
                upd["openrouter_key"] = b["openrouter_key"].strip()
            if b.get("fcm_json", "").strip():
                try:
                    data = json.loads(b["fcm_json"])
                    assert data.get("project_id") and data.get("private_key")
                    path = eng.settings.folder / "fcm.json"
                    path.write_text(json.dumps(data), encoding="utf-8")
                    upd["fcm_file"] = str(path)
                except (ValueError, AssertionError):
                    return self._send(400, "Il file Firebase non è valido.", "text/plain")
            if upd:
                eng.settings.update(**upd)
                if "fcm_file" in upd:
                    from .push import make_push
                    eng.push = make_push(eng.settings)
            self.send_response(303)
            self.send_header("Location", "/")
            self.end_headers()
            return
        if not self._is_local():
            return self._send(200, "<h2>Borsa Watch</h2><p>Il server è attivo. Apri l'app sul telefono.</p>", "text/html")
        s, port = eng.settings, eng.settings["port"]
        urls = "".join(f"<li><code>http://{ip}:{port}</code></li>" for ip in local_ips()) or "<li>(nessuna rete trovata)</li>"
        page = f"""<!doctype html><html lang="it"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Borsa Watch - server</title>
<style>body{{font-family:Segoe UI,Arial,sans-serif;background:#f4f8ff;color:#12263f;margin:0}}
main{{max-width:760px;margin:0 auto;padding:24px}}h1{{color:#0b5cd5}}
.card{{background:#fff;border-radius:14px;padding:18px 22px;margin:14px 0;box-shadow:0 2px 10px #0b5cd51a}}
code{{background:#e8f0fe;padding:3px 8px;border-radius:6px;font-size:1.15em}}
.ok{{color:#0a7a3d;font-weight:600}}.no{{color:#b45309;font-weight:600}}
textarea,input{{width:100%;box-sizing:border-box;padding:8px;border:1px solid #b7c9e8;border-radius:8px}}
button{{background:#0b5cd5;color:#fff;border:0;border-radius:8px;padding:10px 18px;font-size:1em;margin-top:8px}}</style>
<main><h1>Borsa Watch — server</h1>
<div class="card"><b>Per collegare il telefono</b> apri l'app, vai in Impostazioni e scrivi:
<p>Indirizzo del server (uno di questi, telefono e PC sulla stessa rete Wi-Fi):</p><ul>{urls}</ul>
<p>Codice di abbinamento: <code>{html.escape(s['token'])}</code></p></div>
<div class="card"><b>Stato</b><br>Titoli seguiti: {len(eng.store.tickers())} · Avvisi: {len(eng.store.alerts())} · Telefoni registrati: {len(eng.store.devices())}<br>
Sorgente prezzi: {eng.source.name} · Borsa {'aperta' if market.is_open() else 'chiusa'}<br>
Assistente AI: <span class="{'ok' if s['openrouter_key'] else 'no'}">{'pronto' if s['openrouter_key'] else 'manca la chiave'}</span> ·
Notifiche push: <span class="{'ok' if s['fcm_file'] else 'no'}">{'pronte' if s['fcm_file'] else 'non configurate (funzionano comunque sul Wi-Fi di casa)'}</span></div>
<form class="card" method="post"><b>Assistente AI</b> — chiave gratuita di openrouter.ai<br>
<input name="openrouter_key" placeholder="sk-or-..." autocomplete="off"><button>Salva chiave</button></form>
<form class="card" method="post"><b>Notifiche push (facoltativo)</b> — incolla il contenuto del file JSON dell'account di servizio Firebase<br>
<textarea name="fcm_json" rows="4" placeholder='{{"type": "service_account", ...}}'></textarea><button>Salva</button></form>
</main></html>"""
        return self._send(200, page, "text/html")


def make_server(eng, host="0.0.0.0", port=None):
    handler = type("BoundHandler", (Handler,), {"eng": eng})
    srv = ThreadingHTTPServer((host, port if port is not None else eng.settings["port"]), handler)
    srv.daemon_threads = True
    return srv
