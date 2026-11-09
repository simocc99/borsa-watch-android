package it.borsawatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { BorsaTheme { BorsaRoot(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.hasExtra("open_tab")) vm.pendingTab = i.getIntExtra("open_tab", 0)
        i.getStringExtra("sym")?.takeIf { it.isNotBlank() }?.let { vm.pendingSym = it }
    }
}

private val TABS = listOf("Titoli", "Avvisi", "Assistente", "Impostazioni")

@Composable
fun BorsaRoot(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Aggiorna i prezzi ogni 10 secondi, ma solo mentre l'app è aperta (così non consuma batteria).
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refresh()
                delay(10_000)
            }
        }
    }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }
    // Apertura da una notifica
    LaunchedEffect(vm.pendingTab, vm.pendingSym) {
        vm.pendingTab?.let { tab = it; detail = null; vm.pendingTab = null }
        vm.pendingSym?.let { s ->
            if (vm.snap?.tickers?.any { it.sym == s } == true) detail = s
            vm.pendingSym = null
        }
    }
    BackHandler(enabled = detail != null) { detail = null }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.padding(bottom = if (tab == 2 && detail == null) 76.dp else 0.dp)) },
        bottomBar = {
            if (detail == null) {
                NavigationBar(containerColor = androidx.compose.ui.graphics.Color.White) {
                    val icons = listOf(Icons.Filled.Home, Icons.Filled.Notifications, Icons.Filled.Face, Icons.Filled.Settings)
                    TABS.forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i },
                            icon = {
                                if (i == 1 && vm.unread > 0) {
                                    BadgedBox(badge = { Badge { Text(vm.unread.toString()) } }) { Icon(icons[i], contentDescription = label) }
                                } else {
                                    Icon(icons[i], contentDescription = label)
                                }
                            },
                            label = { Text(label, maxLines = 1, softWrap = false, fontSize = 11.sp, letterSpacing = 0.sp, overflow = TextOverflow.Ellipsis) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = BlueSoft, selectedIconColor = BlueDeep, selectedTextColor = BlueDeep)
                        )
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val d = detail
            if (d != null) {
                TickerDetailScreen(vm, d) { detail = null }
            } else {
                when (tab) {
                    0 -> PortfolioScreen(vm, onOpen = { detail = it }, onGoSettings = { tab = 3 })
                    1 -> InboxScreen(vm, onOpen = { detail = it })
                    2 -> AssistantScreen(vm)
                    else -> SettingsScreen(vm)
                }
            }
        }
    }
}
