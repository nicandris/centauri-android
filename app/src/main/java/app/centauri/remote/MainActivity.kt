package app.centauri.remote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            // Follow the phone: light/dark from the system, Material You colours on Android 12+.
            val scheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App() {
    val c by Hub.client.collectAsState()
    c?.let { MainScreen(it, Hub.http) } ?: SetupScreen()
}

enum class Tab(val label: String, val icon: Int) {
    Status("Status", R.drawable.ic_home),
    Files("Files", R.drawable.ic_folder),
    History("History", R.drawable.ic_history),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen() {
    var ip by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Printer>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { TopAppBar(title = { Text("Add printer") }) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Finds Centauri Carbon printers on this network. If none show up, enter the printer's IP address.",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                ip, { ip = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = { Text("IP address (optional)") }, singleLine = true,
            )
            Button(
                onClick = {
                    scope.launch {
                        searching = true
                        found = runCatching { discover(ip.trim().ifEmpty { "255.255.255.255" }) }.getOrDefault(emptyList())
                        searching = false
                        searched = true
                    }
                },
                enabled = !searching,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) { Text("Search") }
            when {
                searching -> Box(Modifier.padding(16.dp)) { CircularProgressIndicator() }
                searched && found.isEmpty() -> Text(
                    "No printer answered. Check that the phone is on the same Wi-Fi, or enter the IP.",
                    Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error,
                )
            }
            found.forEach { p ->
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = { Text(p.ip) },
                    leadingContent = { Icon(painterResource(R.drawable.ic_printer), null) },
                    modifier = Modifier.clickable { Hub.pick(p) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(c: SdcpClient, http: OkHttpClient) {
    LifecycleStartEffect(c) {
        c.start()
        onStopOrDispose { c.stop() }
    }
    val ctx = LocalContext.current
    val connected by c.connected.collectAsState()
    val st by c.status.collectAsState()
    val notify by Hub.notifications.collectAsState()

    // While a print runs, the monitor service keeps the connection and mirrors it in the shade.
    val active = st?.let { !it.idle } == true
    LaunchedEffect(active, notify) {
        if (active && notify) PrintMonitorService.start(ctx)
        if (!notify) PrintMonitorService.stop(ctx)
    }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(notify) {
        if (notify && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    var tab by rememberSaveable { mutableStateOf(Tab.Status) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }

    if (fullscreen) {
        ImmersiveLandscape()
        BackHandler { fullscreen = false }
        CameraView(c, http, fullscreen = true) { fullscreen = false }
        return
    }
    if (settings) {
        SettingsScreen(c, st, connected, onBack = { settings = false }, onChangePrinter = Hub::forget)
        return
    }

    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (tab == Tab.Status) c.printer.name.ifEmpty { "Centauri Carbon" } else tab.label) },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_vert), "More options") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Printer settings") }, { menu = false; settings = true })
                        DropdownMenuItem({ Text("Change printer") }, { menu = false; Hub.forget() })
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Icon(painterResource(t.icon), null) }, label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        when (tab) {
            Tab.Status -> StatusScreen(c, http, connected, st, pad,
                onFullscreen = { fullscreen = true }, onChooseFile = { tab = Tab.Files })
            Tab.Files -> FilesScreen(c, http, st, pad)
            Tab.History -> HistoryScreen(c, http, pad)
        }
    }
}
