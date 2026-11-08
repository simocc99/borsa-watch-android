"""Gli "attrezzi": tutto ciò che si può fare a mano dall'app, usabile anche dall'assistente AI.

Per aggiungere una capacità basta scrivere una funzione con il decoratore @tool: compare da sola
nel manuale che l'assistente riceve e nell'API del telefono. Gli attrezzi che cambiano qualcosa
(mutating=True) l'assistente può solo proporli: li esegue il server dopo il tocco di conferma.
"""
import time

from . import alerts as al
from . import market

REGISTRY = {}


class ToolError(Exception):
    """Errore comprensibile: il testo viene mostrato all'utente o passato all'assistente."""


def tool(name, desc, params=None, mutating=False, preview=None):
    def deco(fn):
        REGISTRY[name] = dict(name=name, desc=desc, params=params or {}, mutating=mutating, run=fn, preview=preview)
        return fn
    return deco


def P(type_, desc, required=True, enum=None):
    return dict(type=type_, desc=desc, required=required, enum=enum)


def validate_args(name, args):
    t = REGISTRY.get(name)
    if not t:
        raise ToolError(f"L'attrezzo «{name}» non esiste")
    args = args if isinstance(args, dict) else {}
    out = {}
    for key, spec in t["params"].items():
        v = args.get(key)
        if v is None or v == "":
            if spec["required"]:
                raise ToolError(f"Manca «{key}» ({spec['desc']})")
            continue
        try:
            if spec["type"] == "number":
                v = float(str(v).replace(",", "."))
            elif spec["type"] == "integer":
                v = int(float(str(v).replace(",", ".")))
            elif spec["type"] == "boolean":
                v = v if isinstance(v, bool) else str(v).strip().lower() in ("1", "true", "si", "sì", "yes")
            else:
                v = str(v).strip()
        except ValueError:
            raise ToolError(f"«{key}» deve essere di tipo {spec['type']}") from None
        if spec["enum"] and v not in spec["enum"]:
            raise ToolError(f"«{key}» deve essere uno tra: {', '.join(map(str, spec['enum']))}")
        out[key] = v
    return out


def is_mutating(name):
    return REGISTRY[name]["mutating"]


def run(eng, name, args):
    """Esegue un attrezzo (dopo la validazione). Usato dall'API manuale e dopo la conferma."""
    args = validate_args(name, args)
    return REGISTRY[name]["run"](eng, **args)


def preview(eng, name, args):
    """Frase di conferma per un attrezzo che modifica; controlla anche che la richiesta sia valida."""
    args = validate_args(name, args)
    t = REGISTRY[name]
    return args, (t["preview"](eng, **args) if t["preview"] else t["desc"])


def manual():
    """Il manuale degli attrezzi che si dà all'assistente."""
    lines = []
    for t in REGISTRY.values():
        ps = ", ".join(f"{k}: {s['type']}{'' if s['required'] else ' (facoltativo)'} = {s['desc']}"
                       + (f" [{'|'.join(map(str, s['enum']))}]" if s["enum"] else "") for k, s in t["params"].items())
        kind = "MODIFICA (serve conferma dell'utente)" if t["mutating"] else "SOLO LETTURA"
        lines.append(f"- {t['name']}({ps}) — {t['desc']} [{kind}]")
    return "\n".join(lines)


# ---------------------------------------------------------------- lettura
def _names(eng):
    return {t["sym"]: t["name"] for t in eng.store.tickers()}


@tool("find_ticker", "Cerca un titolo per nome e restituisce i codici Yahoo possibili.",
      {"query": P("string", "nome o parte del nome, es. Enel")})
def find_ticker(eng, query):
    try:
        return {"risultati": eng.source.search(query, 6)}
    except Exception as e:  # noqa: BLE001
        raise ToolError(f"La ricerca non ha funzionato ({e})") from None


@tool("list_tickers", "Elenca i titoli seguiti con ultimo prezzo e variazione di oggi.")
def list_tickers(eng):
    quotes = eng.store.quotes()
    rows = []
    for t in eng.store.tickers():
        q = quotes.get(t["sym"]) or {}
        rows.append(dict(sym=t["sym"], name=t["name"], last=q.get("last"), chg=q.get("chg"), cur=q.get("cur")))
    return {"titoli": rows, "totale": len(rows)}


@tool("get_quote", "Prezzo e variazione di un titolo della lista (dati letti dal server).",
      {"sym": P("string", "codice del titolo, es. ENI.MI")})
def get_quote(eng, sym):
    sym = sym.upper()
    q = eng.store.quote(sym)
    if not q or q.get("last") is None:
        raise ToolError(f"Non ho ancora un prezzo per {sym}")
    return dict(sym=sym, name=_names(eng).get(sym, sym), last=q["last"], chg=q["chg"], cur=q["cur"],
                secondi_fa=int(time.time()) - int(q["ts"] or 0))


@tool("list_alerts", "Elenca gli avvisi attivi.")
def list_alerts(eng):
    return {"avvisi": [dict(id=a["id"], sym=a["sym"], regola=al.describe(a["sym"], a["op"], a["value"]),
                            ripeti_ogni_minuti=a["repeat_min"] or None) for a in eng.store.alerts()]}


@tool("top_flop", "I titoli che oggi salgono e scendono di più.",
      {"n": P("integer", "quanti per lato (predefinito 5)", required=False)})
def top_flop(eng, n=5):
    f = eng.facts(max(1, min(int(n), 20)))
    return {"migliori": f["best"], "peggiori": f["worst"]}


@tool("daily_recap", "Dati certi per fare un breve riepilogo della giornata: migliori, peggiori, avvisi scattati.")
def daily_recap(eng):
    f = eng.facts(3)
    f["borsa_aperta"] = market.is_open()
    return f


@tool("get_news", "Ultimi titoli di notizie di Yahoo su un titolo (solo titoli, senza interpretazioni).",
      {"sym": P("string", "codice del titolo")})
def get_news(eng, sym):
    try:
        items = eng.source.news(sym.upper(), 5)
    except Exception as e:  # noqa: BLE001
        raise ToolError(f"Non riesco a leggere le notizie ({e})") from None
    return {"notizie": items} if items else {"notizie": [], "nota": "Yahoo non ha notizie per questo titolo"}


@tool("market_status", "Dice se la borsa (lun-ven 9:00-22:05, ora italiana) è aperta adesso.")
def market_status(eng):
    n = market.now()
    return dict(adesso=n.strftime("%A %d/%m/%Y %H:%M"), aperta=market.is_open(n),
                letture_solo_in_orario=bool(eng.settings["market_hours"]))


@tool("refresh_now", "Rilegge subito i prezzi di tutti i titoli.")
def refresh_now(eng):
    ok = eng.refresh()
    return dict(letti=ok, totale=len(eng.store.tickers()))


# ---------------------------------------------------------------- modifiche
def _pv_add(eng, sym, name=None):
    sym = sym.upper()
    if eng.store.has_ticker(sym):
        raise ToolError(f"{sym} è già nella lista")
    q = eng.source.quote(sym, name or "")
    if q.get("error"):
        raise ToolError(f"Non trovo prezzi per «{sym}»: controlla il codice")
    return f"Aggiungere {name or q.get('name') or sym} ({sym}) alla lista"


@tool("add_ticker", "Aggiunge un titolo alla lista.",
      {"sym": P("string", "codice Yahoo, es. ENEL.MI"), "name": P("string", "nome da mostrare", required=False)},
      mutating=True, preview=_pv_add)
def add_ticker(eng, sym, name=None):
    sym = sym.upper()
    if not eng.store.add_ticker(sym, name or ""):
        raise ToolError(f"{sym} è già nella lista")
    eng.refresh([sym])
    return {"ok": True, "sym": sym}


def _pv_remove(eng, sym):
    if not eng.store.has_ticker(sym):
        raise ToolError(f"{sym.upper()} non è nella lista")
    return f"Togliere {_names(eng).get(sym.upper(), sym.upper())} ({sym.upper()}) dalla lista, con i suoi avvisi"


@tool("remove_ticker", "Toglie un titolo dalla lista (e i suoi avvisi).",
      {"sym": P("string", "codice del titolo")}, mutating=True, preview=_pv_remove)
def remove_ticker(eng, sym):
    if not eng.store.remove_ticker(sym):
        raise ToolError(f"{sym.upper()} non è nella lista")
    return {"ok": True}


def _pv_move(eng, sym, where):
    if not eng.store.has_ticker(sym):
        raise ToolError(f"{sym.upper()} non è nella lista")
    return {"top": "Mettere in cima", "up": "Spostare su", "down": "Spostare giù"}[where] + f" {sym.upper()}"


@tool("move_ticker", "Cambia l'ordine di un titolo nella lista.",
      {"sym": P("string", "codice del titolo"), "where": P("string", "dove", enum=["top", "up", "down"])},
      mutating=True, preview=_pv_move)
def move_ticker(eng, sym, where):
    if not eng.store.move_ticker(sym, where):
        raise ToolError(f"{sym.upper()} non è nella lista")
    return {"ok": True}


def _valid_alert(sym, op, value):
    try:
        return al.validate(sym, op, value)
    except ValueError as e:
        raise ToolError(str(e)) from None


def _pv_alert(eng, sym, op, value, repeat_min=0):
    sym, op, value = _valid_alert(sym, op, value)
    extra = "" if eng.store.has_ticker(sym) else f" (e aggiungo {sym} alla lista)"
    rep = f", ripetuto ogni {int(repeat_min)} minuti" if repeat_min else ""
    return f"Creare l'avviso: {al.describe(sym, op, value)}{rep}{extra}"


@tool("create_alert", "Crea un avviso. op: '<' sotto un prezzo, '>' sopra un prezzo, '±' movimento del giorno in %, "
      "'1h-' calo in % nell'ultima ora, '1h+' rialzo in % nell'ultima ora.",
      {"sym": P("string", "codice del titolo"), "op": P("string", "tipo", enum=list(al.OPS)),
       "value": P("number", "soglia (prezzo oppure %)"),
       "repeat_min": P("integer", "se indicato, ripete l'avviso ogni N minuti finché resta vero", required=False)},
      mutating=True, preview=_pv_alert)
def create_alert(eng, sym, op, value, repeat_min=0):
    sym, op, value = _valid_alert(sym, op, value)
    if not eng.store.has_ticker(sym):
        _pv_add(eng, sym)             # controlla che il codice esista
        eng.store.add_ticker(sym)
        eng.refresh([sym])
    aid = eng.store.add_alert(sym, op, value, repeat_min)
    eng.check_alerts([sym])
    return {"ok": True, "id": aid, "regola": al.describe(sym, op, value)}


def _pv_rm_alert(eng, id):
    a = next((x for x in eng.store.alerts() if x["id"] == id), None)
    if not a:
        raise ToolError(f"Non esiste un avviso numero {id}")
    return f"Togliere l'avviso: {al.describe(a['sym'], a['op'], a['value'])}"


@tool("remove_alert", "Toglie un avviso (usa list_alerts per conoscere il numero).",
      {"id": P("integer", "numero dell'avviso")}, mutating=True, preview=_pv_rm_alert)
def remove_alert(eng, id):
    if not eng.store.remove_alert(id):
        raise ToolError(f"Non esiste un avviso numero {id}")
    return {"ok": True}


def parse_ticker_lines(text):
    out = []
    for line in (text or "").splitlines():
        sym, _, name = line.partition("#")
        if sym.strip():
            out.append((sym.strip().upper().split()[0], name.strip()))
    return out


def _pv_import(eng, text):
    rows = parse_ticker_lines(text)
    if not rows:
        raise ToolError("Non ho trovato nessun titolo nel testo")
    new = [s for s, _ in rows if not eng.store.has_ticker(s)]
    return f"Importare {len(rows)} titoli ({len(new)} nuovi)"


@tool("import_tickers", "Importa molti titoli: una riga ciascuno, nel formato  CODICE  # Nome.",
      {"text": P("string", "elenco, una riga per titolo")}, mutating=True, preview=_pv_import)
def import_tickers(eng, text):
    added = sum(1 for s, n in parse_ticker_lines(text) if eng.store.add_ticker(s, n))
    return {"ok": True, "aggiunti": added}


SETTINGS_ALLOWED = {"market_hours": ("boolean", None), "interval_alert": ("integer", (10, 600)),
                    "interval_all": ("integer", (30, 3600))}


def _check_setting(key, value):
    if key not in SETTINGS_ALLOWED:
        raise ToolError(f"Impostazione sconosciuta: {key}. Valide: {', '.join(SETTINGS_ALLOWED)}")
    kind, rng = SETTINGS_ALLOWED[key]
    if kind == "boolean":
        return str(value).strip().lower() in ("1", "true", "si", "sì", "yes")
    try:
        v = int(float(str(value).replace(",", ".")))
    except ValueError:
        raise ToolError("Il valore deve essere un numero") from None
    if not rng[0] <= v <= rng[1]:
        raise ToolError(f"Il valore deve essere tra {rng[0]} e {rng[1]}")
    return v


def _pv_setting(eng, key, value):
    v = _check_setting(key, value)
    nice = {"market_hours": "Leggere solo negli orari di borsa", "interval_alert": "Secondi tra le letture dei titoli con avviso",
            "interval_all": "Secondi tra le letture degli altri titoli"}[key]
    return f"{nice}: {'sì' if v is True else 'no' if v is False else v}"


@tool("set_setting", "Cambia un'impostazione del server: market_hours (true/false), interval_alert (10-600 secondi), "
      "interval_all (30-3600 secondi).",
      {"key": P("string", "nome", enum=list(SETTINGS_ALLOWED)), "value": P("string", "nuovo valore")},
      mutating=True, preview=_pv_setting)
def set_setting(eng, key, value):
    eng.settings.update(**{key: _check_setting(key, value)})
    return {"ok": True}
