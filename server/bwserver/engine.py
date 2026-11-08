"""Il cuore del server: legge i prezzi, controlla gli avvisi, manda le notifiche."""
import logging
import time

from . import alerts as al
from . import market

log = logging.getLogger("bw.engine")


class Engine:
    def __init__(self, settings, store, source, push):
        self.settings, self.store, self.source, self.push = settings, store, source, push
        self.last_run = {}          # sym -> ultimo istante di lettura
        self.busy = False

    # ---------- notifiche ----------
    def notify(self, kind, title, body="", sym="", price=None):
        eid = self.store.add_event(kind, title, body, sym, price)
        devices = self.store.devices()
        if devices:
            data = dict(id=eid, kind=kind, sym=sym, title=title, body=body, price=price, ts=int(time.time()))
            for dead in self.push.send(devices, data):
                self.store.remove_device(dead)
        return eid

    # ---------- letture ----------
    def refresh(self, syms=None):
        """Legge i prezzi (tutti o solo quelli indicati), li salva e controlla gli avvisi."""
        names = {t["sym"]: t["name"] for t in self.store.tickers()}
        syms = [s for s in (syms or names) if s in names]
        if not syms:
            return 0
        self.busy = True
        try:
            results = self.source.many([(s, names[s]) for s in syms])
        finally:
            self.busy = False
        now = time.time()
        ok = 0
        for q in results:
            self.store.save_quote(q)
            self.last_run[q["sym"]] = now
            ok += 0 if q.get("error") else 1
        self.check_alerts(syms)
        return ok

    def check_alerts(self, syms=None):
        now = time.time()
        fired = 0
        for a in self.store.alerts():
            if syms is not None and a["sym"] not in syms:
                continue
            q = self.store.quote(a["sym"])
            hist = [(h["ts"], h["price"]) for h in self.store.history(a["sym"], now - 3700)] if a["op"].startswith("1h") else []
            cond, text = al.evaluate(a, q, hist)
            if al.should_fire(a, cond, now):
                self.notify("alert", f"Avviso {a['sym']}", text, a["sym"], q["last"])
                self.store.set_alert_state(a["id"], 1, now)
                fired += 1
            elif not cond and a["fired"]:
                self.store.set_alert_state(a["id"], 0)   # la condizione è rientrata: l'avviso si riarma
        return fired

    # ---------- riepiloghi ----------
    def facts(self, n=5):
        """Dati certi per riepiloghi e assistente: migliori, peggiori, avvisi di oggi."""
        names = {t["sym"]: t["name"] for t in self.store.tickers()}
        rows = [dict(sym=s, name=names[s], last=q["last"], chg=q["chg"], cur=q["cur"])
                for s, q in self.store.quotes().items() if s in names and q.get("last") is not None and q.get("chg") is not None]
        rows.sort(key=lambda r: r["chg"], reverse=True)
        today0 = int(time.mktime(market.now().replace(hour=0, minute=0, second=0, microsecond=0).timetuple()))
        todays = [e for e in self.store.events(0, 500) if e["kind"] == "alert" and e["ts"] >= today0]
        return dict(best=rows[:n], worst=list(reversed(rows[-n:])) if len(rows) > n else list(reversed(rows)),
                    count=len(rows), alerts_today=[dict(sym=e["sym"], text=e["body"]) for e in todays])

    def recap_text(self):
        f = self.facts(3)
        if not f["count"]:
            return "Non ho ancora letto nessun prezzo."
        fmt = lambda r: f"{r['name']} {r['chg']:+.2f}%"   # noqa: E731
        txt = "Migliori: " + ", ".join(fmt(r) for r in f["best"]) + ". Peggiori: " + ", ".join(fmt(r) for r in f["worst"]) + "."
        if f["alerts_today"]:
            txt += f" Avvisi scattati oggi: {len(f['alerts_today'])}."
        return txt

    def housekeeping(self):
        today = market.now().strftime("%Y-%m-%d")
        if market.is_open() and self.store.get("open_sent") != today:
            self.store.set("open_sent", today)
            self.notify("market", "Borsa aperta", f"Sto controllando {len(self.store.tickers())} titoli.")
        if market.after_close() and self.store.get("open_sent") == today and self.store.get("close_sent") != today:
            self.store.set("close_sent", today)
            self.notify("market", "Borsa chiusa", self.recap_text())
        if int(time.time()) % 3600 < 5:
            self.store.prune_history()

    # ---------- ciclo ----------
    def due(self, now=None):
        now = now or time.time()
        alert_syms = {a["sym"] for a in self.store.alerts()}
        out = []
        for t in self.store.tickers():
            every = self.settings["interval_alert"] if t["sym"] in alert_syms else self.settings["interval_all"]
            if now - self.last_run.get(t["sym"], 0) >= every:
                out.append(t["sym"])
        return out

    def tick(self):
        self.housekeeping()
        if self.settings["market_hours"] and not market.is_open():
            # a mercato chiuso si legge una sola volta, per avere dei prezzi da mostrare
            quotes, now = self.store.quotes(), time.time()
            todo = [t["sym"] for t in self.store.tickers()
                    if (quotes.get(t["sym"]) or {}).get("last") is None and now - self.last_run.get(t["sym"], 0) > 600]
        else:
            todo = self.due()
        if todo:
            self.refresh(todo)

    def run(self, stop):
        while not stop.is_set():
            try:
                self.tick()
            except Exception:  # noqa: BLE001
                log.exception("errore nel ciclo")
            stop.wait(3)
