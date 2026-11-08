"""Assistente AI (OpenRouter, modelli gratuiti): capisce le richieste e propone le azioni.

Principi:
- L'assistente non modifica mai nulla da solo: propone, l'utente conferma con un tocco.
- Riceve un manuale dell'ambiente (cos'è l'app, cosa può fare, regole) e lo stato attuale.
- Ogni numero nella risposta viene confrontato con i dati realmente letti; se non torna, si riprova e
  alla fine si avvisa l'utente invece di lasciar passare numeri inventati.
"""
import json
import re

from . import market
from . import tools as T

URL = "https://openrouter.ai/api/v1/chat/completions"
FALLBACK_MODELS = ["openrouter/free"]


class AIError(Exception):
    pass


SYSTEM = """Sei l'assistente di "Borsa Watch", un'app per seguire i titoli di borsa.
Parli con Giovanni: una persona non giovane ma molto lucida e brillante. Dagli del tu, con rispetto,
in italiano semplice e cordiale, senza gergo tecnico. Risposte brevi: al massimo 5 frasi.

COME FUNZIONA L'AMBIENTE
- L'app Android mostra la lista dei titoli, i grafici, gli avvisi e la casella delle notifiche.
  Il server, su un PC di casa, legge i prezzi da Yahoo Finance e controlla gli avvisi.
- Tu giri dentro il server. Non vedi internet: conosci solo quello che ti dicono gli attrezzi
  e lo STATO qui sotto. I prezzi di Yahoo possono avere qualche minuto di ritardo.
- La borsa è aperta dal lunedì al venerdì, dalle 9:00 alle 22:05, ora italiana.
- Giovanni può fare a mano nell'app tutto ciò che puoi fare tu con gli attrezzi.

REGOLE (valgono sempre)
1. Non dare consigli di investimento (comprare, vendere, tenere). Se te lo chiedono, spiega con gentilezza
   che non puoi e offri i dati che servono a decidere.
2. Non inventare prezzi, percentuali, notizie o date. Usa solo numeri ricevuti dagli attrezzi o dallo STATO.
   Se non sai, scrivi "Non lo so" e dì cosa servirebbe. Niente notizie false e niente dati falsi.
3. Non dire perché un titolo si muove, a meno che una notizia letta con get_news lo dica chiaramente:
   in quel caso cita il titolo della notizia. Altrimenti di' che il motivo non è noto.
4. Per ogni cambiamento (aggiungere o togliere titoli, avvisi, impostazioni) usa un attrezzo di tipo MODIFICA:
   Giovanni vedrà una scheda e confermerà. Non dire mai di aver già fatto la modifica: di' che l'hai proposta.
5. Per trovare un codice di un titolo usa find_ticker. Non indovinare i codici.
6. Se la richiesta non è chiara, fai UNA domanda breve invece di tirare a indovinare.

ATTREZZI DISPONIBILI
{tools}

FORMATO DELLA RISPOSTA: solo un oggetto JSON, senza altro testo:
{{"reply": "testo per Giovanni", "calls": [{{"tool": "nome_attrezzo", "args": {{"parametro": "valore"}}}}]}}
- Se ti servono dati, chiedili in "calls" (attrezzi di sola lettura): riceverai i risultati e poi risponderai.
- Se proponi una modifica, scrivi una frase breve in "reply" e metti l'attrezzo in "calls".
- Se non serve nessun attrezzo: "calls": [].

STATO ATTUALE
{state}
"""


def state_text(eng):
    n = market.now()
    tick = eng.store.tickers()
    lines = [f"Adesso: {n.strftime('%A %d/%m/%Y %H:%M')} (ora italiana). Borsa {'APERTA' if market.is_open(n) else 'CHIUSA'}.",
             f"Titoli seguiti ({len(tick)}): " + "; ".join(f"{t['sym']} = {t['name']}" for t in tick[:120])]
    alerts = eng.store.alerts()
    lines.append("Avvisi attivi: " + ("; ".join(f"n.{a['id']} {a['sym']} {a['op']} {a['value']:g}" for a in alerts) if alerts else "nessuno"))
    return "\n".join(lines)


def build_system(eng):
    return SYSTEM.format(tools=T.manual(), state=state_text(eng))


# ------------------------------------------------------------------ OpenRouter
def openrouter_llm(settings):
    import requests

    def call(messages):
        key = settings["openrouter_key"]
        if not key:
            raise AIError("Per usare l'assistente serve la chiave gratuita di OpenRouter: la trovi nella guida.")
        models = []
        for m in [settings["model"]] + FALLBACK_MODELS:
            if m and m not in models:
                models.append(m)
        last = "nessuna risposta"
        for model in models:
            for json_mode in (True, False):
                body = {"model": model, "messages": messages, "temperature": 0.2}
                if json_mode:
                    body["response_format"] = {"type": "json_object"}
                try:
                    r = requests.post(URL, timeout=90, json=body, headers={
                        "Authorization": f"Bearer {key}", "X-Title": "Borsa Watch", "HTTP-Referer": "https://github.com/simocc99/borsa-watch-android"})
                except requests.RequestException as e:
                    last = f"rete: {e}"
                    break
                if r.status_code == 200:
                    try:
                        content = r.json()["choices"][0]["message"]["content"]
                    except (KeyError, IndexError, ValueError):
                        content = ""
                    if content and content.strip():
                        return content
                    last = "risposta vuota"
                elif r.status_code in (401, 403):
                    raise AIError("La chiave OpenRouter non è valida: controllala nelle impostazioni del server.")
                else:
                    last = f"errore {r.status_code}"
                    if r.status_code != 400:
                        break              # 429/5xx: si passa al modello successivo
        raise AIError(f"L'assistente non è disponibile in questo momento ({last}). Riprova tra poco.")
    return call


# ------------------------------------------------------------------ lettura della risposta
def parse_reply(text):
    text = re.sub(r"<think>.*?</think>", "", text or "", flags=re.S).strip()
    a, b = text.find("{"), text.rfind("}")
    if a >= 0 and b > a:
        try:
            d = json.loads(text[a:b + 1])
            if isinstance(d, dict):
                calls = d.get("calls") if isinstance(d.get("calls"), list) else []
                return {"reply": str(d.get("reply") or "").strip(),
                        "calls": [c for c in calls if isinstance(c, dict) and c.get("tool")]}
        except ValueError:
            pass
    return {"reply": text, "calls": []}


# ------------------------------------------------------------------ controllo dei numeri
_NUM = re.compile(r"\d+(?:[.,]\d+)*")
_TIME = re.compile(r"\b\d{1,2}[:.]\d{2}\b|\b\d{1,2}/\d{1,2}(?:/\d{2,4})?\b")


def _to_float(s):
    s = s.strip(".,")
    if "," in s and "." in s:                         # 1.234,56 oppure 1,234.56
        s = s.replace(".", "").replace(",", ".") if s.rfind(",") > s.rfind(".") else s.replace(",", "")
    elif "," in s:
        s = s.replace(",", ".")
    elif s.count(".") > 1:
        s = s.replace(".", "")
    try:
        return float(s)
    except ValueError:
        return None


def numbers_in(text):
    return [(m.group(), _to_float(m.group())) for m in _NUM.finditer(_TIME.sub(" ", text))]


def unverified_numbers(reply, evidence_text):
    """Numeri della risposta che non compaiono nei dati reali (con tolleranza sugli arrotondamenti)."""
    ev = [abs(v) for _, v in numbers_in(evidence_text) if v is not None]
    bad = []
    for raw, v in numbers_in(reply):
        if v is None or (v == int(v) and abs(v) <= 10) or 2000 <= v <= 2100:
            continue
        dec = len(raw.replace(",", ".").split(".")[1]) if re.search(r"[.,]\d+$", raw) and len(re.split(r"[.,]", raw)[-1]) <= 2 else 0
        tol = 0.5 * 10 ** (-dec) + 1e-9
        if not any(abs(abs(v) - e) <= tol or abs(abs(v) - round(e, dec)) <= 1e-9 for e in ev):
            bad.append(raw)
    return bad


# ------------------------------------------------------------------ conversazione
def fake_llm(eng):
    """Assistente finto, solo per la modalità demo e per le prove automatiche dell'app."""
    def call(messages):
        text = messages[-1]["content"].lower()
        if "avvis" in text:
            return json.dumps({"reply": "Ho preparato l'avviso: controllalo e conferma.",
                               "calls": [{"tool": "create_alert", "args": {"sym": "AAPL", "op": "<", "value": 100}}]})
        return json.dumps({"reply": "Sono l'assistente di prova. Chiedimi di creare un avviso.", "calls": []})
    return call


def chat(eng, history, text, llm=None, max_rounds=3):
    """Una domanda di Giovanni -> {reply, proposals, tools_used, verified}."""
    if llm is None:
        llm = fake_llm(eng) if eng.settings.get("fake_ai") else openrouter_llm(eng.settings)
    system = build_system(eng)
    msgs = [{"role": "system", "content": system}]
    for h in (history or [])[-8:]:
        if isinstance(h, dict) and h.get("role") in ("user", "assistant") and str(h.get("content") or "").strip():
            msgs.append({"role": h["role"], "content": str(h["content"])[:2000]})
    msgs.append({"role": "user", "content": text})

    evidence = [system, text]
    used, proposals = [], []
    retried = False
    reply = ""
    for round_no in range(max_rounds + 1):
        raw = llm(msgs)
        data = parse_reply(raw)
        reply = data["reply"]
        feedback = []
        for c in data["calls"][:6]:
            name, args = str(c.get("tool")), c.get("args") or {}
            try:
                if name not in T.REGISTRY:
                    raise T.ToolError(f"L'attrezzo «{name}» non esiste")
                if T.is_mutating(name):
                    clean, summary = T.preview(eng, name, args)
                    if not any(p["tool"] == name and p["args"] == clean for p in proposals):
                        proposals.append(dict(tool=name, args=clean, summary=summary))
                    feedback.append({"attrezzo": name, "stato": "proposta pronta, in attesa di conferma di Giovanni"})
                else:
                    res = T.run(eng, name, args)
                    used.append(name)
                    feedback.append({"attrezzo": name, "args": args, "risultato": res})
            except T.ToolError as e:
                feedback.append({"attrezzo": name, "errore": str(e)})
            except Exception as e:  # noqa: BLE001
                feedback.append({"attrezzo": name, "errore": f"errore interno: {e}"})
        needs_more = any("risultato" in f or "errore" in f for f in feedback)
        if needs_more and round_no < max_rounds:
            blob = json.dumps(feedback, ensure_ascii=False, default=str)
            evidence.append(blob)
            msgs += [{"role": "assistant", "content": raw},
                     {"role": "user", "content": "RISULTATI DEGLI ATTREZZI (dati certi):\n" + blob +
                      "\nOra rispondi a Giovanni, sempre nel formato JSON. Usa solo questi dati."}]
            continue
        bad = unverified_numbers(reply, "\n".join(evidence)) if reply else []
        if bad and not retried:
            retried = True
            msgs += [{"role": "assistant", "content": raw},
                     {"role": "user", "content": f"ATTENZIONE: nella risposta ci sono numeri che non risultano dai dati: {', '.join(bad)}. "
                      "Riscrivi usando SOLO numeri presenti nei dati, oppure di' che non lo sai. Formato JSON."}]
            continue
        break

    verified = not (reply and unverified_numbers(reply, "\n".join(evidence)))
    if not verified:
        reply += "\n\n⚠️ Attenzione: alcuni numeri di questa risposta non risultano dai dati letti. Controllali nell'elenco."
    if not reply:
        reply = "Ho preparato la modifica qui sotto: controllala e conferma." if proposals else "Non ho capito bene, puoi dirlo in un altro modo?"
    saved = [dict(p, id=eng.store.add_proposal(p["tool"], p["args"], p["summary"])) for p in proposals]
    return dict(reply=reply, proposals=saved, tools_used=sorted(set(used)), verified=verified)


def confirm(eng, pid, accept=True):
    p = eng.store.proposal(pid)
    if not p:
        raise T.ToolError("Questa proposta non esiste più")
    if p["status"] != "pending":
        raise T.ToolError("Questa proposta è già stata gestita")
    if not accept:
        eng.store.set_proposal_status(pid, "rejected")
        return {"ok": True, "stato": "annullata"}
    res = T.run(eng, p["tool"], p["args"])
    eng.store.set_proposal_status(pid, "done")
    return {"ok": True, "stato": "fatto", "risultato": res, "riepilogo": p["summary"]}
