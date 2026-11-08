import pytest

from bwserver import alerts as al


@pytest.mark.parametrize("text,expected", [
    ("NVDA < 200", ("NVDA", "<", 200.0)),
    ("aapl>400,5", ("AAPL", ">", 400.5)),
    ("TSLA ± 5", ("TSLA", "±", 5.0)),
    ("TSLA +/- 2,5%", ("TSLA", "±", 2.5)),
    ("ENI.MI -3% in 1h", ("ENI.MI", "1h-", 3.0)),
    ("AAPL +2 un'ora", ("AAPL", "1h+", 2.0)),
])
def test_parse_rule(text, expected):
    assert al.parse_rule(text) == expected


@pytest.mark.parametrize("bad", ["", "ciao", "NVDA", "NVDA < abc"])
def test_parse_rule_errors(bad):
    with pytest.raises(ValueError):
        al.parse_rule(bad)


def test_validate_limits():
    assert al.validate("aapl", "<", 10) == ("AAPL", "<", 10.0)
    with pytest.raises(ValueError):
        al.validate("AAPL", "x", 1)
    with pytest.raises(ValueError):
        al.validate("AAPL", "±", 150)
    with pytest.raises(ValueError):
        al.validate("AAPL", "<", 0)


def test_evaluate_price_and_day_move():
    a = dict(sym="X", op="<", value=100.0)
    assert al.evaluate(a, dict(last=99.0, chg=0, cur="EUR"), [])[0]
    assert not al.evaluate(a, dict(last=101.0, chg=0, cur="EUR"), [])[0]
    a = dict(sym="X", op="±", value=5.0)
    assert al.evaluate(a, dict(last=1.0, chg=-6.0, cur="USD"), [])[0]
    assert not al.evaluate(a, dict(last=1.0, chg=4.0, cur="USD"), [])[0]


def test_evaluate_hour_move_needs_data():
    a = dict(sym="X", op="1h-", value=3.0)
    q = dict(last=95.0, chg=0, cur="EUR")
    assert not al.evaluate(a, q, [])[0]
    assert not al.evaluate(a, q, [(1000, 100.0), (1060, 95.0)])[0]            # solo 1 minuto di dati
    assert al.evaluate(a, q, [(1000, 100.0), (1000 + 1800, 95.0)])[0]         # -5% in 30 minuti
    assert not al.evaluate(a, q, [(1000, 96.0), (1000 + 1800, 95.0)])[0]      # solo -1%


def test_fires_once_then_rearms(eng):
    eng.source.override["AAPL"] = 50.0
    eng.store.add_alert("AAPL", "<", 100)
    assert eng.check_alerts() == 0                        # prezzo non ancora letto con il valore forzato
    eng.refresh(["AAPL"])
    assert [e["kind"] for e in eng.store.events()].count("alert") == 1
    eng.refresh(["AAPL"])
    assert [e["kind"] for e in eng.store.events()].count("alert") == 1      # una volta sola
    eng.source.override["AAPL"] = 150.0
    eng.refresh(["AAPL"])                                                    # rientra: si riarma
    eng.source.override["AAPL"] = 40.0
    eng.refresh(["AAPL"])
    assert [e["kind"] for e in eng.store.events()].count("alert") == 2


def test_repeat_alert(eng):
    eng.source.override["TSLA"] = 10.0
    aid = eng.store.add_alert("TSLA", "<", 100, repeat_min=5)
    eng.refresh(["TSLA"])
    n = lambda: [e["kind"] for e in eng.store.events()].count("alert")  # noqa: E731
    assert n() == 1
    eng.store.set_alert_state(aid, 1, last_fire=0)                          # finto: l'ultimo avviso è vecchio
    eng.refresh(["TSLA"])
    assert n() == 2


def test_push_sent_to_registered_phone(eng):
    eng.store.add_device("tok" * 10)
    eng.source.override["ENI.MI"] = 1.0
    eng.store.add_alert("ENI.MI", "<", 5)
    eng.refresh(["ENI.MI"])
    tokens, data = eng.push.sent[-1]
    assert tokens == ["tok" * 10] and data["kind"] == "alert" and data["sym"] == "ENI.MI"
    assert "ENI.MI" in data["body"]


def test_recap_and_facts(eng):
    f = eng.facts(2)
    assert f["count"] == 3 and len(f["best"]) == 2
    assert "Migliori" in eng.recap_text()


def test_order_and_remove(eng):
    eng.store.move_ticker("ENI.MI", "top")
    assert [t["sym"] for t in eng.store.tickers()][0] == "ENI.MI"
    eng.store.add_alert("ENI.MI", "<", 1)
    eng.store.remove_ticker("ENI.MI")
    assert not eng.store.alerts() and not eng.store.has_ticker("ENI.MI")


def test_demo_tick_closed_market_reads_once(eng):
    eng.settings.update(market_hours=True)
    eng.store.add_ticker("MSFT", "Microsoft")
    eng.tick()                       # a mercato chiuso legge solo i titoli senza prezzo
    assert eng.store.quote("MSFT")["last"] is not None
