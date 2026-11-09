package it.borsawatch

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Prova completa sull'emulatore: l'app vera parla con il server in modalità demo
 * (il server gira sul computer di GitHub, l'emulatore lo vede come 10.0.2.2).
 */
@OptIn(ExperimentalTestApi::class)
class E2eTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>

    private val args get() = InstrumentationRegistry.getArguments()

    /** Screenshot dello schermo salvato in /data/local/tmp (sopravvive alla disinstallazione dell'app). */
    private fun shot(name: String) {
        SystemClock.sleep(700)
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/bw_$name.png")
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    @Before fun start() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val p = Prefs(ctx)
        p.serverUrl = args.getString("bw_url") ?: "http://10.0.2.2:8765"
        p.token = args.getString("bw_token") ?: ""
        p.firstSyncDone = false
        p.lastNotified = 0
        p.inboxJson = "[]"
        p.cachedState = ""
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun stop() { scenario.close() }

    @Test fun fullFlow() {
        try { flow() } catch (t: Throwable) { shot("zz_errore"); throw t }
    }

    private fun flow() {
        // 1) la lista arriva dal server
        rule.waitUntilAtLeastOneExists(hasText("Apple"), 40_000)
        shot("01_titoli")

        // 2) dettaglio titolo + creazione avviso a mano
        rule.onAllNodesWithText("Apple")[0].performClick()
        rule.waitUntilAtLeastOneExists(hasText("Nuovo avviso", substring = true), 15_000)
        shot("02_dettaglio")
        rule.onNodeWithText("＋ Nuovo avviso").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Prezzo (es. 200)"), 10_000)
        rule.onNodeWithText("Prezzo (es. 200)").performTextInput("50")
        shot("03_nuovo_avviso")
        rule.onNodeWithText("Crea avviso").performClick()
        rule.waitUntilAtLeastOneExists(hasText("AAPL scende sotto 50"), 20_000)
        shot("04_avviso_creato")

        // 3) assistente: richiesta, scheda di conferma, conferma
        rule.onNodeWithContentDescription("Indietro").performClick()
        rule.onNodeWithText("Assistente").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Scrivi qui…"), 10_000)
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Avviso creato").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Scrivi qui…").performTextInput("Avvisami se Apple scende sotto 100")
        rule.onNodeWithContentDescription("Invia").performClick()
        shot("05a_dopo_invio")
        rule.waitUntilAtLeastOneExists(hasText("Da confermare"), 40_000)
        shot("05_assistente_proposta")
        rule.onNodeWithText("Conferma").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Fatto ✓"), 20_000)
        shot("06_assistente_fatto")

        // 4) impostazioni
        rule.onNodeWithText("Impostazioni").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Collegato ✓"), 20_000)
        shot("07_impostazioni")

        // 5) la lista mostra la campanella e l'elenco è ancora lì
        rule.onNodeWithText("Titoli").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Apple"), 10_000)
        shot("08_titoli_finale")
        check(rule.onAllNodesWithText("Tesla").fetchSemanticsNodes().isNotEmpty()) { "Tesla dovrebbe essere in lista" }
    }
}
