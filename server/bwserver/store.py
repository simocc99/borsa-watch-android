"""Archivio SQLite: titoli, avvisi, ultimi prezzi, storico, casella avvisi, telefoni, proposte."""
import json
import sqlite3
import threading
import time
import uuid

SCHEMA = """
CREATE TABLE IF NOT EXISTS tickers(sym TEXT PRIMARY KEY, name TEXT, pos INTEGER);
CREATE TABLE IF NOT EXISTS alerts(
    id INTEGER PRIMARY KEY AUTOINCREMENT, sym TEXT, op TEXT, value REAL,
    repeat_min INTEGER DEFAULT 0, fired INTEGER DEFAULT 0, last_fire INTEGER DEFAULT 0, created INTEGER);
CREATE TABLE IF NOT EXISTS quotes(
    sym TEXT PRIMARY KEY, last REAL, chg REAL, open REAL, high REAL, low REAL, vol REAL,
    cur TEXT, ts INTEGER, error TEXT);
CREATE TABLE IF NOT EXISTS history(sym TEXT, ts INTEGER, price REAL);
CREATE INDEX IF NOT EXISTS hist_idx ON history(sym, ts);
CREATE TABLE IF NOT EXISTS events(
    id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, kind TEXT, sym TEXT, title TEXT, body TEXT, price REAL);
CREATE TABLE IF NOT EXISTS devices(token TEXT PRIMARY KEY, ts INTEGER);
CREATE TABLE IF NOT EXISTS proposals(
    id TEXT PRIMARY KEY, ts INTEGER, tool TEXT, args TEXT, summary TEXT, status TEXT);
CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT);
"""

HISTORY_STEP = 60            # un punto al minuto per titolo
HISTORY_KEEP = 7 * 86400     # si tengono 7 giorni


class Store:
    def __init__(self, path=":memory:"):
        self.db = sqlite3.connect(str(path), check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.lock = threading.RLock()
        with self.lock:
            self.db.executescript(SCHEMA)
            self.db.commit()

    # ---- utilità ----
    def _all(self, sql, args=()):
        with self.lock:
            return [dict(r) for r in self.db.execute(sql, args).fetchall()]

    def _one(self, sql, args=()):
        rows = self._all(sql, args)
        return rows[0] if rows else None

    def _run(self, sql, args=()):
        with self.lock:
            cur = self.db.execute(sql, args)
            self.db.commit()
            return cur

    # ---- titoli ----
    def tickers(self):
        return self._all("SELECT sym, name, pos FROM tickers ORDER BY pos, sym")

    def has_ticker(self, sym):
        return self._one("SELECT 1 AS x FROM tickers WHERE sym=?", (sym.upper(),)) is not None

    def add_ticker(self, sym, name=""):
        sym = sym.strip().upper()
        if not sym:
            return False
        with self.lock:
            if self.has_ticker(sym):
                return False
            pos = (self._one("SELECT MAX(pos) AS m FROM tickers") or {}).get("m")
            self.db.execute("INSERT INTO tickers VALUES(?,?,?)", (sym, name.strip() or sym, (pos if pos is not None else -1) + 1))
            self.db.commit()
        return True

    def remove_ticker(self, sym):
        sym = sym.upper()
        with self.lock:
            n = self.db.execute("DELETE FROM tickers WHERE sym=?", (sym,)).rowcount
            self.db.execute("DELETE FROM alerts WHERE sym=?", (sym,))
            self.db.execute("DELETE FROM quotes WHERE sym=?", (sym,))
            self.db.execute("DELETE FROM history WHERE sym=?", (sym,))
            self.db.commit()
        return n > 0

    def move_ticker(self, sym, where):
        """where: 'top', 'up', 'down' o 'bottom'."""
        sym = sym.upper()
        order = [t["sym"] for t in self.tickers()]
        if sym not in order:
            return False
        i = order.index(sym)
        order.pop(i)
        j = {"top": 0, "up": max(i - 1, 0), "down": min(i + 1, len(order)), "bottom": len(order)}.get(where, i)
        order.insert(j, sym)
        self._save_order(order)
        return True

    def sort_tickers(self):
        """Mette tutta la lista in ordine alfabetico per nome (l'ordine di prima si perde)."""
        order = [t["sym"] for t in sorted(self.tickers(), key=lambda t: (t["name"] or t["sym"]).lower())]
        self._save_order(order)
        return len(order)

    def _save_order(self, order):
        with self.lock:
            for pos, s in enumerate(order):
                self.db.execute("UPDATE tickers SET pos=? WHERE sym=?", (pos, s))
            self.db.commit()

    # ---- avvisi ----
    def alerts(self):
        return self._all("SELECT * FROM alerts ORDER BY sym, id")

    def add_alert(self, sym, op, value, repeat_min=0):
        cur = self._run("INSERT INTO alerts(sym,op,value,repeat_min,created) VALUES(?,?,?,?,?)",
                        (sym.upper(), op, float(value), int(repeat_min or 0), int(time.time())))
        return cur.lastrowid

    def remove_alert(self, alert_id):
        return self._run("DELETE FROM alerts WHERE id=?", (int(alert_id),)).rowcount > 0

    def set_alert_state(self, alert_id, fired, last_fire=None):
        if last_fire is None:
            self._run("UPDATE alerts SET fired=? WHERE id=?", (int(fired), alert_id))
        else:
            self._run("UPDATE alerts SET fired=?, last_fire=? WHERE id=?", (int(fired), int(last_fire), alert_id))

    # ---- prezzi ----
    def save_quote(self, q, ts=None):
        ts = int(ts or time.time())
        sym = q["sym"].upper()
        if q.get("error"):
            # si tiene l'ultimo prezzo buono e si annota solo l'errore
            self._run("INSERT INTO quotes(sym,error,ts) VALUES(?,?,?) ON CONFLICT(sym) DO UPDATE SET error=excluded.error",
                      (sym, str(q["error"])[:200], ts))
            return
        self._run("INSERT OR REPLACE INTO quotes VALUES(?,?,?,?,?,?,?,?,?,NULL)",
                  (sym, q["last"], q.get("chg"), q.get("open"), q.get("high"), q.get("low"), q.get("vol"), q.get("cur"), ts))
        last = self._one("SELECT MAX(ts) AS t FROM history WHERE sym=?", (sym,))
        if not last or not last["t"] or ts - last["t"] >= HISTORY_STEP:
            self._run("INSERT INTO history VALUES(?,?,?)", (sym, ts, q["last"]))

    def quotes(self):
        return {r["sym"]: r for r in self._all("SELECT * FROM quotes")}

    def quote(self, sym):
        return self._one("SELECT * FROM quotes WHERE sym=?", (sym.upper(),))

    def history(self, sym, since):
        return self._all("SELECT ts, price FROM history WHERE sym=? AND ts>=? ORDER BY ts", (sym.upper(), int(since)))

    def prune_history(self):
        self._run("DELETE FROM history WHERE ts<?", (int(time.time()) - HISTORY_KEEP,))

    # ---- casella avvisi ----
    def add_event(self, kind, title, body="", sym="", price=None):
        cur = self._run("INSERT INTO events(ts,kind,sym,title,body,price) VALUES(?,?,?,?,?,?)",
                        (int(time.time()), kind, sym, title, body, price))
        return cur.lastrowid

    def events(self, after=0, limit=200):
        rows = self._all("SELECT * FROM events WHERE id>? ORDER BY id DESC LIMIT ?", (int(after), int(limit)))
        return list(reversed(rows))

    def last_event_id(self):
        return (self._one("SELECT MAX(id) AS m FROM events") or {}).get("m") or 0

    # ---- telefoni ----
    def add_device(self, token):
        self._run("INSERT OR REPLACE INTO devices VALUES(?,?)", (token, int(time.time())))

    def devices(self):
        return [r["token"] for r in self._all("SELECT token FROM devices")]

    def remove_device(self, token):
        self._run("DELETE FROM devices WHERE token=?", (token,))

    # ---- proposte dell'assistente ----
    def add_proposal(self, tool, args, summary):
        pid = uuid.uuid4().hex[:10]
        self._run("INSERT INTO proposals VALUES(?,?,?,?,?,?)",
                  (pid, int(time.time()), tool, json.dumps(args, ensure_ascii=False), summary, "pending"))
        return pid

    def proposal(self, pid):
        r = self._one("SELECT * FROM proposals WHERE id=?", (pid,))
        if r:
            r["args"] = json.loads(r["args"])
        return r

    def set_proposal_status(self, pid, status):
        self._run("UPDATE proposals SET status=? WHERE id=?", (status, pid))

    # ---- valori vari ----
    def get(self, k, default=None):
        r = self._one("SELECT v FROM kv WHERE k=?", (k,))
        return r["v"] if r else default

    def set(self, k, v):
        self._run("INSERT OR REPLACE INTO kv VALUES(?,?)", (k, str(v)))
