package it.borsawatch

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = BlueDeep)
            content()
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = BorsaApp.prefs
    var url by rememberSaveable { mutableStateOf(prefs.serverUrl) }
    var code by rememberSaveable { mutableStateOf(prefs.token) }
    var aiKey by rememberSaveable { mutableStateOf("") }
    var importing by rememberSaveable { mutableStateOf(false) }
    var importText by rememberSaveable { mutableStateOf("") }
    val snap = vm.snap

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Impostazioni", style = MaterialTheme.typography.headlineMedium, color = BlueDeep)

        Section("Collegamento al server") {
            Text(
                "Sul PC dove gira il server apri http://localhost:8765 : trovi l'indirizzo e il codice da scrivere qui sotto.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it }, singleLine = true, label = { Text("Indirizzo (es. 192.168.1.20)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = code, onValueChange = { code = it }, singleLine = true, label = { Text("Codice di abbinamento") },
                modifier = Modifier.fillMaxWidth()
            )
            BigButton(if (vm.configured) "Ricollega" else "Collega", { vm.connect(url, code) { } }, Modifier.fillMaxWidth(), enabled = !vm.busy)
            if (vm.configured) {
                Text(
                    when (vm.online) {
                        true -> "Collegato ✓"
                        false -> "Non raggiungibile: " + (vm.lastError ?: "")
                        null -> "Controllo in corso…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (vm.online == true) Up else if (vm.online == false) Down else MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { vm.disconnect() }) { Text("Scollega il server", color = Down) }
            }
        }

        if (vm.configured && snap != null) {
            Section("Assistente AI") {
                Text(
                    if (snap.aiReady) "Pronto ✓ (la chiave è già salvata sul server)"
                    else "Serve una chiave gratuita: crea un account su openrouter.ai, poi «Keys» → «Create key» e incollala qui.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = aiKey, onValueChange = { aiKey = it }, singleLine = true, label = { Text("Chiave OpenRouter (sk-or-…)") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                )
                BigButton("Salva chiave", { vm.saveServerSettings("openrouter_key" to aiKey.trim()); aiKey = "" }, Modifier.fillMaxWidth(), enabled = aiKey.isNotBlank())
            }

            Section("Come legge i prezzi il server") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Solo negli orari di borsa (lun-ven 9:00-22:05)", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = snap.marketHoursOnly, onCheckedChange = { vm.saveServerSettings("market_hours" to it) })
                }
                Stepper("Titoli con avviso: ogni", snap.intervalAlert, 10, 600, 10) { vm.saveServerSettings("interval_alert" to it) }
                Stepper("Tutti gli altri: ogni", snap.intervalAll, 30, 3600, 30) { vm.saveServerSettings("interval_all" to it) }
                BigButton("Rileggi i prezzi adesso", { vm.refreshServerNow() }, Modifier.fillMaxWidth(), filled = false, enabled = !vm.busy)
            }

            Section("La tua lista") {
                BigButton("Importa una lista (incolla)", { importing = true }, Modifier.fillMaxWidth(), filled = false)
                BigButton("Esporta in CSV e condividi", {
                    scope.launch {
                        val csv = vm.exportCsv()
                        if (csv.isNotBlank()) {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_SUBJECT, "Borsa Watch - lista titoli")
                                putExtra(Intent.EXTRA_TEXT, csv)
                            }
                            ctx.startActivity(Intent.createChooser(send, "Condividi la lista").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }, Modifier.fillMaxWidth(), filled = false)
            }
        }

        Section("Notifiche e batteria") {
            Text(
                if (snap?.pushReady == true) "Notifiche push attive: arrivano subito, anche fuori casa."
                else "Senza le notifiche push, gli avvisi arrivano controllando il server ogni 15 minuti (con il Wi-Fi di casa o la VPN).",
                style = MaterialTheme.typography.bodyMedium
            )
            if (Build.VERSION.SDK_INT >= 33) {
                BigButton("Consenti le notifiche", { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }, Modifier.fillMaxWidth(), filled = false)
            }
            BigButton("Invia una notifica di prova", {
                Notifier.show(ctx, Ev(System.currentTimeMillis() % 1_000_000, System.currentTimeMillis() / 1000, "alert", "", "Prova di Borsa Watch", "Se leggi questo, le notifiche funzionano.", null))
            }, Modifier.fillMaxWidth(), filled = false)
            BigButton("Non risparmiare batteria per l'app", {
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            }, Modifier.fillMaxWidth(), filled = false)
            Text("Nella schermata che si apre: Batteria → Nessuna restrizione.", style = MaterialTheme.typography.bodySmall)
        }

        Section("Informazioni") {
            Text("App 1.0 · server ${snap?.version ?: "–"} · prezzi: ${snap?.source ?: "–"}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "I prezzi arrivano da Yahoo Finance e possono avere qualche minuto di ritardo. Uso personale: non sono consigli di investimento.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (importing) {
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text("Importa una lista") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Un titolo per riga:  CODICE  # Nome\nad esempio  ENI.MI # Eni", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(value = importText, onValueChange = { importText = it }, minLines = 6, maxLines = 10, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.importText(importText); importText = ""; importing = false }, enabled = importText.isNotBlank()) { Text("Importa") }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("Annulla") } }
        )
    }
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, step: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label $value secondi", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = { onChange((value - step).coerceAtLeast(min)) }, enabled = value > min) { Text("−", style = MaterialTheme.typography.titleLarge) }
        TextButton(onClick = { onChange((value + step).coerceAtMost(max)) }, enabled = value < max) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}
