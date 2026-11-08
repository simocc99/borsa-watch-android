package it.borsawatch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

private val SUGGESTIONS = listOf(
    "Fammi il riepilogo di oggi",
    "Quali titoli salgono di più?",
    "Avvisami se Apple scende sotto 100",
    "Aggiungi Enel alla lista"
)

@Composable
fun AssistantScreen(vm: MainViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val ready = vm.snap?.aiReady == true
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(vm.chat.size, vm.chatBusy) {
        if (vm.chat.isNotEmpty()) listState.animateScrollToItem(vm.chat.size)
    }

    fun send(text: String) {
        vm.sendChat(text)
        input = ""
        keyboard?.hide()
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Text("Assistente", style = MaterialTheme.typography.headlineMedium, color = BlueDeep, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
        Text(
            "Dimmi cosa vuoi fare, ti preparo la modifica e tu confermi con un tocco.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        if (!vm.configured) {
            Banner("Prima collega il server dalle Impostazioni.", Modifier.padding(16.dp), warn = true)
        } else if (!ready) {
            Banner(
                "L'assistente non è ancora attivo: manca la chiave gratuita di OpenRouter. La inserisci nelle Impostazioni (sezione Assistente).",
                Modifier.padding(16.dp), warn = true
            )
        }
        LazyColumn(
            state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(vm.chat) { index, m -> Bubble(vm, index, m) }
            item {
                if (vm.chatBusy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("  Sto pensando…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (vm.chat.isEmpty() && vm.configured) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Prova a chiedere:", style = MaterialTheme.typography.bodyMedium)
                        SUGGESTIONS.forEach { s ->
                            AssistChip(onClick = { send(s) }, label = { Text(s, style = MaterialTheme.typography.bodyMedium) })
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Scrivi qui…") }, shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
                maxLines = 3
            )
            IconButton(onClick = { send(input) }, enabled = input.isNotBlank() && !vm.chatBusy) {
                Icon(Icons.Filled.Send, contentDescription = "Invia", tint = Blue)
            }
        }
    }
}

@Composable
private fun Bubble(vm: MainViewModel, index: Int, m: ChatMsg) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.fromUser) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = if (m.fromUser) Blue else Color.White,
            contentColor = if (m.fromUser) Color.White else MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(18.dp), shadowElevation = if (m.fromUser) 0.dp else 1.dp,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(m.text, style = MaterialTheme.typography.bodyLarge)
                if (!m.verified) {
                    Spacer(Modifier.height(4.dp))
                    Text("Alcuni numeri non sono stati verificati.", style = MaterialTheme.typography.bodySmall, color = Warn)
                }
            }
        }
        m.proposals.forEach { p -> ProposalCard(vm, index, p) }
    }
}

@Composable
private fun ProposalCard(vm: MainViewModel, index: Int, p: Proposal) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BlueSoft), shape = RoundedCornerShape(16.dp),
        modifier = Modifier.widthIn(max = 340.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Da confermare", style = MaterialTheme.typography.bodySmall, color = BlueDeep)
            Text(p.summary, style = MaterialTheme.typography.titleMedium)
            when (p.state) {
                "pending", "error" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton("Conferma", { vm.answerProposal(index, p.id, true) }, Modifier.weight(1f))
                    BigButton("Annulla", { vm.answerProposal(index, p.id, false) }, Modifier.weight(1f), filled = false)
                }
                "done" -> Text("Fatto ✓", style = MaterialTheme.typography.titleMedium, color = Up)
                "rejected" -> Text("Annullato", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Suppress("unused")
private val unusedBox: @Composable () -> Unit = { Box(Modifier.background(Color.Transparent)) }
