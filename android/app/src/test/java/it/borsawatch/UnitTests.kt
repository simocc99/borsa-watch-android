package it.borsawatch

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FmtTest {
    @Test fun priceItalian() {
        assertEquals("1.234,56", Fmt.price(1234.5555))
        assertEquals("0,1234", Fmt.price(0.12345))
        assertEquals("–", Fmt.price(null))
    }

    @Test fun pctHasSign() {
        assertEquals("+1,50%", Fmt.pct(1.5))
        assertEquals("-2,31%", Fmt.pct(-2.309))
        assertEquals("–", Fmt.pct(null))
    }

    @Test fun normalizeUrl() {
        assertEquals("http://192.168.1.20:8765", Fmt.normalizeUrl("192.168.1.20"))
        assertEquals("http://192.168.1.20:9000", Fmt.normalizeUrl(" http://192.168.1.20:9000/ "))
        assertEquals("https://esempio.it", Fmt.normalizeUrl("https://esempio.it"))
        assertEquals("", Fmt.normalizeUrl("  "))
    }

    @Test fun agoReadable() {
        assertEquals("adesso", Fmt.ago(1000, 1010))
        assertEquals("5 min fa", Fmt.ago(1000, 1000 + 300))
        assertEquals("2 h fa", Fmt.ago(1000, 1000 + 7300))
    }
}

class ParseTest {
    private val state = """
      {"server_time":1700000000,"market_open":true,"version":"1.0.0","source":"yahoo","last_event":7,
       "tickers":[{"sym":"AAPL","name":"Apple","last":342.37,"chg":-0.54,"cur":"USD","ts":1700000000,"alerts":[3]},
                  {"sym":"XYZ","name":"Senza prezzo","last":null,"chg":null,"error":"boh","alerts":[]}],
       "alerts":[{"id":3,"sym":"AAPL","op":"<","value":300.0,"repeat_min":0,"fired":0}],
       "settings":{"ai_ready":true,"push_ready":false,"market_hours":true,"interval_alert":20,"interval_all":120}}
    """

    @Test fun snapshot() {
        val s = Parse.snapshot(JSONObject(state))
        assertEquals(2, s.tickers.size)
        assertEquals(342.37, s.tickers[0].last!!, 0.0001)
        assertNull(s.tickers[1].last)
        assertEquals(listOf(3), s.tickers[0].alerts)
        assertTrue(s.marketOpen && s.aiReady)
        assertFalse(s.pushReady)
        assertEquals("AAPL scende sotto 300", s.alerts[0].text())
    }

    @Test fun eventsRoundTrip() {
        val ev = listOf(Ev(1, 100, "alert", "AAPL", "Avviso AAPL", "è sceso", 99.5), Ev(2, 200, "market", "", "Borsa aperta", "", null))
        val back = Parse.eventsFromJson(Parse.eventsToJson(ev))
        assertEquals(ev, back)
        assertTrue(Parse.eventsFromJson("non è json").isEmpty())
    }

    @Test fun alertTexts() {
        assertEquals("TSLA si muove di oltre il 5% nella giornata", AlertRule(1, "TSLA", "±", 5.0, 0, false).text())
        assertEquals("ENI.MI cala di almeno il 2,5% in un'ora", AlertRule(1, "ENI.MI", "1h-", 2.5, 0, false).text())
    }

    @Test fun proposals() {
        val p = Parse.proposals(JSONObject("""{"proposals":[{"id":"ab12","tool":"create_alert","summary":"Creare l'avviso"}]}"""))
        assertEquals(Proposal("ab12", "create_alert", "Creare l'avviso"), p[0])
    }
}
