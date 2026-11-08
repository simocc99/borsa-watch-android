"""Sorgenti dei prezzi: Yahoo Finance (vero) e demo (finto, per le prove)."""
import hashlib
import math
import random
import time
from concurrent.futures import ThreadPoolExecutor


class YahooSource:
    name = "yahoo"

    def quote(self, sym, name=""):
        import yfinance as yf
        err = ""
        for _ in range(2):  # Yahoo ogni tanto risponde male una volta sola
            try:
                fi = yf.Ticker(sym).fast_info
                last, prev = fi["last_price"], fi["previous_close"]
                if last is None:
                    raise ValueError("nessun prezzo")
                return dict(sym=sym, name=name, last=float(last), open=fi["open"], high=fi["day_high"],
                            low=fi["day_low"], vol=fi["last_volume"], cur=fi["currency"],
                            chg=(last - prev) / prev * 100 if prev else None)
            except Exception as e:  # noqa: BLE001
                err = str(e) or type(e).__name__
                time.sleep(1.5)
        return dict(sym=sym, name=name, error=err)

    def search(self, query, limit=8):
        import yfinance as yf
        found = yf.Search(query, max_results=limit).quotes
        return [dict(sym=q["symbol"], name=q.get("shortname") or q.get("longname") or "", exchange=q.get("exchDisp") or "")
                for q in found if q.get("symbol") and q.get("quoteType") not in ("FUTURE", "OPTION")]

    def news(self, sym, limit=5):
        import yfinance as yf
        out = []
        for n in (yf.Ticker(sym).news or [])[:limit]:
            c = n.get("content") or n
            title = c.get("title")
            if title:
                out.append(dict(title=title, publisher=(c.get("provider") or {}).get("displayName") or c.get("publisher") or "",
                                date=c.get("pubDate") or c.get("providerPublishTime") or ""))
        return out

    def chart(self, sym, days=1):
        import yfinance as yf
        period, interval = ("1d", "5m") if days <= 1 else ("5d", "30m")
        h = yf.Ticker(sym).history(period=period, interval=interval)
        return [dict(ts=int(t.timestamp()), price=float(p)) for t, p in zip(h.index, h["Close"]) if p == p]

    def many(self, items, workers=3):
        with ThreadPoolExecutor(max_workers=workers) as ex:
            return list(ex.map(lambda it: self.quote(*it), items))


class DemoSource:
    """Prezzi inventati ma credibili e ripetibili: servono a provare app e server senza Yahoo."""
    name = "demo"
    CATALOG = {"AAPL": "Apple", "TSLA": "Tesla", "NVDA": "Nvidia", "ENI.MI": "Eni", "ENEL.MI": "Enel",
               "ISP.MI": "Intesa Sanpaolo", "UCG.MI": "UniCredit", "MSFT": "Microsoft", "SAP.DE": "SAP",
               "AIR.PA": "Airbus", "BAC": "Bank of America", "CCL": "Carnival"}

    def __init__(self):
        self.t0 = time.time()
        self.override = {}  # sym -> prezzo forzato (per i test degli avvisi)

    def _base(self, sym):
        return 20 + int(hashlib.md5(sym.encode()).hexdigest()[:6], 16) % 400

    def quote(self, sym, name=""):
        base = self._base(sym)
        t = (time.time() - self.t0) / 30
        phase = int(hashlib.md5(sym.encode()).hexdigest()[6:10], 16)
        last = self.override.get(sym) or round(base * (1 + 0.02 * math.sin(t + phase)), 2)
        prev = base
        return dict(sym=sym, name=name or self.CATALOG.get(sym, sym), last=last, open=prev, high=last * 1.01,
                    low=last * 0.99, vol=100000, cur="EUR" if sym.endswith((".MI", ".DE", ".PA")) else "USD",
                    chg=(last - prev) / prev * 100)

    def search(self, query, limit=8):
        q = query.lower()
        res = [dict(sym=s, name=n, exchange="Demo") for s, n in self.CATALOG.items() if q in s.lower() or q in n.lower()]
        return res[:limit]

    def news(self, sym, limit=5):
        return [dict(title=f"Notizia di prova su {sym}", publisher="Demo", date="")]

    def chart(self, sym, days=1):
        rnd = random.Random(sym)
        base, now = self._base(sym), int(time.time())
        return [dict(ts=now - (78 - i) * 300, price=round(base * (1 + 0.01 * math.sin(i / 8) + rnd.uniform(-.002, .002)), 2))
                for i in range(79)]

    def many(self, items, workers=3):
        return [self.quote(*it) for it in items]
