package it.borsawatch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val ALERT_TYPES = listOf(
    "<" to "Scende sotto un prezzo",
    ">" to "Sale sopra un prezzo",
    "±" to "Si muove di oltre un % nella giornata",
    "1h-" to "Cala di almeno un % in un'ora",
    "1h+" to "Sale di almeno un % in un'ora"
)

@Composable
fun TickerDetailScreen(vm: MainViewModel, sym: String, onBack: () -> Unit) {
    val snap = vm.snap
    val t = snap?.tickers?.firstOrNull { it.sym == sym }
    val alerts = snap?.alerts?.filter { it.sym == sym } ?: emptyList()
    var days by remember { mutableStateOf(1) }
    var points by remember { mutableStateOf<List<Pair<Long, Double>>>(emptyList()) }
    var news by remember { mutableStateOf<List<String>?>(null) }
    var newAlert by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sym, days) { points = vm.chart(sym, days) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Indietro") }
            Column(Modifier.weight(1f)) {
                Text(t?.name ?: sym, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text(sym, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (t == null) {
                Banner("Questo titolo non è più nella lista.")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Fmt.price(t.last) + (if (t.cur.isNullOrBlank()) "" else " " + t.cur),
                        style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f)
                    )
                    ChangeChip(t.chg)
                }
                if (t.last == null && t.error != null) Banner("Per ora non riesco a leggere il prezzo di questo titolo.", warn = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to "Oggi", 5 to "5 giorni").forEach { (d, label) ->
                        FilterChip(
                            selected = days == d, onClick = { days = d },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BlueSoft)
                        )
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    PriceChart(points, Modifier.padding(12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatBox("Apertura", Fmt.price(t.open), Modifier.weight(1f))
                    StatBox("Massimo", Fmt.price(t.high), Modifier.weight(1f))
                    StatBox("Minimo", Fmt.price(t.low), Modifier.weight(1f))
                }

                SectionTitle("Avvisi su questo titolo")
                if (alerts.isEmpty()) {
                    Text("Nessun avviso. Creane uno per essere avvisato con una notifica.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                alerts.forEach { a ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Notifications, contentDescription = null, tint = Blue)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.text(), style = MaterialTheme.typography.bodyLarge)
                                if (a.repeatMin > 0) Text("Si ripete ogni ${a.repeatMin} minuti", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { vm.removeAlert(a.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Togli avviso") }
                        }
                    }
                }
                BigButton("＋ Nuovo avviso", { newAlert = true }, Modifier.fillMaxWidth())

                SectionTitle("Notizie")
                val n = news
                if (n == null) {
                    BigButton("Mostra le ultime notizie", { scope.launch { news = vm.news(sym) } }, Modifier.fillMaxWidth(), filled = false)
                } else if (n.isEmpty()) {
                    Text("Yahoo non ha notizie per questo titolo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    n.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    Text("Titoli presi da Yahoo Finance, senza interpretazioni.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                SectionTitle("Posizione nella lista")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton("In cima", { vm.moveTicker(sym, "top") }, Modifier.weight(1f), filled = false)
                    BigButton("Su", { vm.moveTicker(sym, "up") }, Modifier.weight(1f), filled = false)
                    BigButton("Giù", { vm.moveTicker(sym, "down") }, Modifier.weight(1f), filled = false)
                }
                TextButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Togli dalla lista", color = Down, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (newAlert) NewAlertDialog(sym, onDismiss = { newAlert = false }) { op, value, rep ->
        vm.createAlert(sym, op, value, rep)
        newAlert = false
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Togliere dalla lista?") },
            text = { Text("${t?.name ?: sym} sparirà dalla lista insieme ai suoi avvisi.") },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; vm.removeTicker(sym); onBack() }) { Text("Sì, togli", color = Down) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Annulla") } }
        )
    }
}

@Composable
fun NewAlertDialog(sym: String, onDismiss: () -> Unit, onCreate: (String, Double, Int) -> Unit) {
    var op by remember { mutableStateOf("<") }
    var value by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf(false) }
    val number = value.replace(',', '.').toDoubleOrNull()
    val isPct = op != "<" && op != ">"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuovo avviso su $sym") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ALERT_TYPES.forEach { (k, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = op == k, role = Role.RadioButton, onClick = { op = k }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = op == k, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
                OutlinedTextField(
                    value = value, onValueChange = { value = it }, singleLine = true,
                    label = { Text(if (isPct) "Percentuale (es. 5)" else "Prezzo (es. 200)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = repeat, onCheckedChange = { repeat = it })
                    Text("Ripeti ogni 30 minuti finché resta vero", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(op, number ?: return@TextButton, if (repeat) 30 else 0) }, enabled = number != null && number > 0) {
                Text("Crea avviso")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}
