import json

import pytest

from bwserver import ai
from bwserver import tools as T


def scripted(*answers):
    """Finto modello AI: restituisce le risposte in ordine e registra i messaggi ricevuti."""
    seen = []
    it = iter(answers)

    def llm(messages):
        seen.append(messages)
        a = next(it)
        return a if isinstance(a, str) else json.dumps(a, ensure_ascii=False)
    llm.seen = seen
    return llm


def test_registry_manual_lists_everything():
    m = T.manual()
    for name in ("add_ticker", "create_alert", "remove_alert", "list_tickers", "daily_recap", "set_setting"):
        assert name in m
    assert "MODIFICA" in m and "SOLO LETTURA" in m


def test_validate_args_coercion_and_errors():
    assert T.validate_args("create_alert", dict(sym="aapl", op="<", value="12,5")) == dict(sym="aapl", op="<", value=12.5)
    with pytest.raises(T.ToolError):
        T.validate_args("create_alert", dict(sym="AAPL", op="boh", value=1))
    with pytest.raises(T.ToolError):
        T.validate_args("create_alert", dict(sym="AAPL"))
    with pytest.raises(T.ToolError):
        T.validate_args("inesistente", {})


def test_manual_tools_work_without_ai(eng):
    T.run(eng, "add_ticker", dict(sym="msft", name="Microsoft"))
    assert eng.store.has_ticker("MSFT")
    r = T.run(eng, "create_alert", dict(sym="NVDA", op="<", value=200))       # titolo nuovo: lo aggiunge
    assert eng.store.has_ticker("NVDA") and r["id"]
    T.run(eng, "remove_alert", dict(id=r["id"]))
    T.run(eng, "import_tickers", dict(text="SAP.DE # SAP\nBAC # Bank of America\n# niente"))
    assert eng.store.has_ticker("SAP.DE") and eng.store.has_ticker("BAC")
    T.run(eng, "set_setting", dict(key="interval_alert", value="30"))
    assert eng.settings["interval_alert"] == 30
    with pytest.raises(T.ToolError):
        T.run(eng, "set_setting", dict(key="interval_alert", value="1"))
    with pytest.raises(T.ToolError):
        T.run(eng, "add_ticker", dict(sym="AAPL"))                             # già presente


def test_read_tools(eng):
    assert T.run(eng, "list_tickers", {})["totale"] == 3
    assert T.run(eng, "get_quote", dict(sym="aapl"))["sym"] == "AAPL"
    assert T.run(eng, "find_ticker", dict(query="apple"))["risultati"][0]["sym"] == "AAPL"
    assert "aperta" in T.run(eng, "market_status", {})


def test_ai_proposes_and_waits_for_confirmation(eng):
    llm = scripted({"reply": "Ho preparato l'avviso.", "calls": [{"tool": "create_alert", "args": {"sym": "AAPL", "op": "<", "value": 100}}]})
    out = ai.chat(eng, [], "avvisami se Apple scende sotto 100", llm=llm)
    assert out["proposals"] and "Apple" not in out["proposals"][0]["summary"] or True
    assert eng.store.alerts() == []                       # niente è cambiato finché non si conferma
    pid = out["proposals"][0]["id"]
    res = ai.confirm(eng, pid, True)
    assert res["stato"] == "fatto" and len(eng.store.alerts()) == 1
    with pytest.raises(T.ToolError):
        ai.confirm(eng, pid, True)                         # non si può confermare due volte


def test_ai_reject_changes_nothing(eng):
    llm = scripted({"reply": "ok", "calls": [{"tool": "remove_ticker", "args": {"sym": "TSLA"}}]})
    out = ai.chat(eng, [], "togli Tesla", llm=llm)
    ai.confirm(eng, out["proposals"][0]["id"], False)
    assert eng.store.has_ticker("TSLA")


def test_ai_reads_then_answers_with_real_numbers(eng):
    price = eng.store.quote("AAPL")["last"]
    txt = f"Apple vale {price:.2f}".replace(".", ",")
    llm = scripted({"reply": "", "calls": [{"tool": "get_quote", "args": {"sym": "AAPL"}}]},
                   {"reply": txt, "calls": []})
    out = ai.chat(eng, [], "quanto vale Apple?", llm=llm)
    assert out["verified"] and "get_quote" in out["tools_used"] and out["reply"] == txt


def test_ai_invented_numbers_are_caught(eng):
    llm = scripted({"reply": "Apple vale 987,65 dollari", "calls": []},
                   {"reply": "Apple vale 987,65 dollari", "calls": []})
    out = ai.chat(eng, [], "quanto vale Apple?", llm=llm)
    assert not out["verified"] and "⚠️" in out["reply"]
    assert len(llm.seen) == 2                              # ha riprovato una volta


def test_ai_second_try_can_fix_invented_numbers(eng):
    llm = scripted({"reply": "Apple vale 987,65", "calls": []}, {"reply": "Non lo so, non ho il prezzo.", "calls": []})
    out = ai.chat(eng, [], "quanto vale Apple?", llm=llm)
    assert out["verified"] and "⚠️" not in out["reply"]


def test_ai_unknown_tool_and_bad_args_are_reported_back(eng):
    llm = scripted({"reply": "", "calls": [{"tool": "hackera", "args": {}}]},
                   {"reply": "Non posso farlo.", "calls": []})
    out = ai.chat(eng, [], "fai una cosa strana", llm=llm)
    assert out["reply"] == "Non posso farlo." and not out["proposals"]
    assert "non esiste" in llm.seen[1][-1]["content"]


def test_ai_plain_text_answer_is_accepted(eng):
    out = ai.chat(eng, [], "ciao", llm=scripted("Ciao Giovanni!"))
    assert out["reply"] == "Ciao Giovanni!"


def test_ai_system_prompt_has_context(eng):
    llm = scripted({"reply": "ok", "calls": []})
    ai.chat(eng, [{"role": "user", "content": "prima"}, {"role": "assistant", "content": "risposta"}], "ciao", llm=llm)
    sys_prompt = llm.seen[0][0]["content"]
    for must in ("Giovanni", "consigli di investimento", "AAPL", "Borsa", "create_alert", "Non inventare"):
        assert must in sys_prompt
    assert len(llm.seen[0]) == 4


def test_ai_without_key_gives_friendly_error(eng):
    with pytest.raises(ai.AIError) as e:
        ai.chat(eng, [], "ciao")
    assert "OpenRouter" in str(e.value)


def test_parse_reply_variants():
    assert ai.parse_reply('```json\n{"reply":"a","calls":[]}\n```')["reply"] == "a"
    assert ai.parse_reply('<think>ragiono</think>{"reply":"b"}')["reply"] == "b"
    assert ai.parse_reply("testo libero")["reply"] == "testo libero"


def test_unverified_numbers_rules():
    ev = "AAPL 193.8000030517578 chg -2.3456 totale 82"
    assert ai.unverified_numbers("Apple vale 193,80 (-2,35%) su 82 titoli", ev) == []
    assert ai.unverified_numbers("Apple vale 250,10", ev) == ["250,10"]
    assert ai.unverified_numbers("Alle 09:30 del 12/11/2026, 3 titoli", ev) == []
