package it.borsawatch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PortfolioScreen(vm: MainViewModel, onOpen: (String) -> Unit, onGoSettings: () -> Unit) {
    val snap = vm.snap
    var filter by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }

    val all = snap?.tickers ?: emptyList()
    val shown = remember(all, filter, query) {
        var l = all
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            l = l.filter { it.name.lowercase().contains(q) || it.sym.lowercase().contains(q) }
        }
        when (filter) {
            "best" -> l.filter { it.chg != null }.sortedByDescending { it.chg }
            "worst" -> l.filter { it.chg != null }.sortedBy { it.chg }
            "alerts" -> l.filter { it.alerts.isNotEmpty() }
            else -> l
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 110.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { SummaryCard(vm) }
            if (!vm.configured) {
                item {
                    Banner("Per vedere i tuoi titoli collega l'app al server: apri le Impostazioni e scrivi indirizzo e codice che trovi sul PC.")
                    Spacer(Modifier.height(10.dp))
                    BigButton("Vai alle Impostazioni", onGoSettings, Modifier.fillMaxWidth())
                }
            } else {
                item { TopFlop(all, onOpen) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("all" to "Tutti", "best" to "Salgono", "worst" to "Scendono", "alerts" to "Con avviso").forEach { (k, label) ->
                            FilterChip(
                                selected = filter == k, onClick = { filter = k },
                                label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BlueSoft)
                            )
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Cerca nella tua lista") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Cancella") }
                        },
                        singleLine = true, shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                    )
                }
                if (all.isEmpty()) {
                    item {
                        EmptyState(
                            "La lista è vuota",
                            "Premi «Aggiungi titolo» per cercarne uno per nome, oppure importa la tua lista dalle Impostazioni."
                        )
                    }
                } else if (shown.isEmpty()) {
                    item { EmptyState("Nessun titolo", "Nessun titolo corrisponde a questa ricerca.") }
                }
                items(shown, key = { it.sym }) { t -> TickerCard(t, onClick = { onOpen(t.sym) }) }
            }
        }
        if (vm.configured) {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = Blue, contentColor = Color.White,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Aggiungi titolo", style = MaterialTheme.typography.labelLarge) }
            )
        }
    }
    if (adding) AddTickerDialog(vm, onDismiss = { adding = false })
}

@Composable
private fun SummaryCard(vm: MainViewModel) {
    val snap = vm.snap
    Surface(shape = RoundedCornerShape(22.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.background(Brush.linearGradient(listOf(Blue, Color(0xFF3B82F6)))).padding(20.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("I tuoi titoli", style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.weight(1f))
                    if (vm.busy) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    } else if (vm.configured) {
                        IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = "Aggiorna", tint = Color.White) }
                    }
                }
                val n = snap?.tickers?.size ?: 0
                val market = if (snap == null) "" else if (snap.marketOpen) "Borsa aperta" else "Borsa chiusa"
                Text(
                    if (vm.configured) "$n titoli" + (if (market.isNotEmpty()) "  ·  $market" else "") else "Non ancora collegato",
                    style = MaterialTheme.typography.bodyLarge, color = Color.White
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(vm.online)
                    Spacer(Modifier.width(8.dp))
                    val newest = snap?.tickers?.mapNotNull { it.ts }?.maxOrNull()
                    Text(
                        when {
                            !vm.configured -> "Collega il server dalle Impostazioni"
                            vm.online == false -> "Server non raggiungibile" + (if (newest != null) " · ultimi dati alle ${Fmt.time(newest)}" else "")
                            newest != null -> "Aggiornato alle ${Fmt.time(newest)}"
                            else -> "In attesa dei primi prezzi…"
                        },
                        style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE6EEFF)
                    )
                }
            }
        }
    }
}

@Composable
private fun TopFlop(all: List<Tick>, onOpen: (String) -> Unit) {
    val withChg = all.filter { it.chg != null }
    val best = withChg.maxByOrNull { it.chg!! }
    val worst = withChg.minByOrNull { it.chg!! }
    if (best == null || worst == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MiniCard("▲ Sale di più", best, UpSoft, Up, Modifier.weight(1f)) { onOpen(best.sym) }
        MiniCard("▼ Scende di più", worst, DownSoft, Down, Modifier.weight(1f)) { onOpen(worst.sym) }
    }
}

@Composable
private fun MiniCard(label: String, t: Tick, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(color = bg, shape = RoundedCornerShape(16.dp), modifier = modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = fg)
            Text(t.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(Fmt.pct(t.chg), style = MaterialTheme.typography.titleMedium, color = fg)
        }
    }
}

@Composable
fun AddTickerDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun doSearch() {
        if (q.isBlank()) return
        scope.launch {
            searching = true
            hits = vm.search(q.trim())
            searched = true
            searching = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aggiungi un titolo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    label = { Text("Nome o codice (es. Enel)") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { doSearch() }),
                    modifier = Modifier.fillMaxWidth()
                )
                BigButton("Cerca", { doSearch() }, Modifier.fillMaxWidth(), enabled = !searching)
                if (searching) CircularProgressIndicator(Modifier.size(24.dp))
                if (searched && hits.isEmpty() && !searching) Text("Nessun risultato. Prova con un altro nome.")
                LazyColumn(Modifier.height(240.dp)) {
                    items(hits, key = { it.sym }) { h ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                vm.addTicker(h.sym, h.name.ifBlank { h.sym })
                                onDismiss()
                            }.padding(vertical = 10.dp)
                        ) {
                            Text(h.name.ifBlank { h.sym }, style = MaterialTheme.typography.titleMedium)
                            Text(h.sym + (if (h.exchange.isNotBlank()) "  ·  " + h.exchange else ""), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } }
    )
    LaunchedEffect(Unit) { delay(0) }
}
