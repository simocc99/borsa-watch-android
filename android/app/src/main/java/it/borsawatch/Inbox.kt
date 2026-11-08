package it.borsawatch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun InboxScreen(vm: MainViewModel, onOpen: (String) -> Unit) {
    val list = vm.events.sortedByDescending { it.id }
    val known = vm.snap?.tickers?.map { it.sym }?.toSet() ?: emptySet()
    LaunchedEffect(vm.events.size) { vm.markInboxSeen() }

    Column(Modifier.fillMaxSize()) {
        Text("Avvisi ricevuti", style = MaterialTheme.typography.headlineMedium, color = BlueDeep, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
        if (list.isEmpty()) {
            EmptyState(
                "Ancora nessun avviso",
                "Quando un titolo supera una soglia che hai scelto, l'avviso compare qui e ti arriva anche la notifica sul telefono."
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { e ->
                    val isAlert = e.kind == "alert"
                    Card(
                        onClick = { if (e.sym.isNotBlank() && e.sym in known) onOpen(e.sym) },
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                            Icon(
                                if (isAlert) Icons.Filled.Notifications else Icons.Filled.Info, contentDescription = null,
                                tint = if (isAlert) Blue else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.title, style = MaterialTheme.typography.titleMedium)
                                if (e.body.isNotBlank()) Text(e.body, style = MaterialTheme.typography.bodyLarge)
                                Text(Fmt.ago(e.ts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
