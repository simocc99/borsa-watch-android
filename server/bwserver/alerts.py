"""Regole di avviso: scrittura, lettura e controllo."""
import re
import time

# Operatori: "<" sotto un prezzo, ">" sopra un prezzo, "±" movimento del giorno in %,
# "1h-" calo in % nell'ultima ora, "1h+" rialzo in % nell'ultima ora.
OPS = ("<", ">", "±", "1h-", "1h+")

_RULE = re.compile(
    r"^\s*(?P<sym>[\w.\^=\-]+)\s*(?:(?P<op><|>|±|\+/-|\+-)\s*(?P<v1>[\d.,]+)\s*%?"
    r"|(?P<sign>[+-])\s*(?P<v2>[\d.,]+)\s*%?\s*(?:in|nell'|/)?\s*(?:1\s*h|un'?\s*ora|ora))\s*$", re.I)


def _num(s):
    return float(s.replace(",", "."))


def parse_rule(text):
    """'NVDA < 200', 'TSLA ± 5', 'AAPL -3% in 1h' -> (sym, op, value). ValueError se non si capisce."""
    m = _RULE.match(text or "")
    if not m:
        raise ValueError(f"Non capisco la regola: {text!r}")
    sym = m.group("sym").upper()
    if m.group("op"):
        op = "±" if m.group("op") in ("±", "+/-", "+-") else m.group("op")
        return sym, op, _num(m.group("v1"))
    return sym, "1h-" if m.group("sign") == "-" else "1h+", _num(m.group("v2"))


def describe(sym, op, value):
    v = f"{value:g}".replace(".", ",")
    return {
        "<": f"{sym} scende sotto {v}",
        ">": f"{sym} sale sopra {v}",
        "±": f"{sym} si muove di oltre il {v}% nella giornata",
        "1h-": f"{sym} cala di almeno il {v}% in un'ora",
        "1h+": f"{sym} sale di almeno il {v}% in un'ora",
    }[op]


def validate(sym, op, value):
    if op not in OPS:
        raise ValueError(f"Tipo di avviso sconosciuto: {op}")
    if not sym or not str(sym).strip():
        raise ValueError("Manca il titolo")
    value = float(value)
    if value <= 0:
        raise ValueError("Il valore deve essere maggiore di zero")
    if op in ("±", "1h-", "1h+") and value > 100:
        raise ValueError("Una variazione in % non può superare 100")
    return str(sym).strip().upper(), op, value


def evaluate(alert, quote, history):
    """Restituisce (condizione_vera, testo). history = [(ts, prezzo)] dal più vecchio."""
    if not quote or quote.get("last") is None:
        return False, ""
    last, op, val = quote["last"], alert["op"], alert["value"]
    cur = quote.get("cur") or ""
    p = f"{last:,.2f}".replace(",", "X").replace(".", ",").replace("X", ".")
    if op == "<" and last < val:
        return True, f"{alert['sym']} è sceso a {p} {cur} (soglia {val:g})"
    if op == ">" and last > val:
        return True, f"{alert['sym']} è salito a {p} {cur} (soglia {val:g})"
    if op == "±" and quote.get("chg") is not None and abs(quote["chg"]) >= val:
        return True, f"{alert['sym']} oggi {quote['chg']:+.2f}% (ora {p} {cur})"
    if op in ("1h-", "1h+") and history:
        now = history[-1][0]
        window = [(t, x) for t, x in history if t >= now - 3600]
        if window and now - window[0][0] >= 300:      # servono almeno 5 minuti di dati
            ref = window[0][1]
            change = (last - ref) / ref * 100 if ref else 0
            minutes = round((now - window[0][0]) / 60)
            if op == "1h-" and change <= -val:
                return True, f"{alert['sym']} è calato del {abs(change):.2f}% in {minutes} minuti (ora {p} {cur})"
            if op == "1h+" and change >= val:
                return True, f"{alert['sym']} è salito del {change:.2f}% in {minutes} minuti (ora {p} {cur})"
    return False, ""


def should_fire(alert, cond, now=None):
    """Una volta sola finché la condizione resta vera; con repeat_min si ripete dopo quei minuti."""
    now = now or time.time()
    if not cond:
        return False
    if not alert["fired"]:
        return True
    return bool(alert["repeat_min"]) and now - alert["last_fire"] >= alert["repeat_min"] * 60
