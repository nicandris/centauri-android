package app.centauri.remote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Android 17 (API 37) gates LAN traffic behind a runtime permission for apps targeting it. */
private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
private const val API_ANDROID_17 = 37

fun needsLocalNetworkPermission(ctx: Context) =
    Build.VERSION.SDK_INT >= API_ANDROID_17 &&
        ContextCompat.checkSelfPermission(ctx, ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED

fun needsNotificationPermission(ctx: Context) =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CentauriTheme { App() } }
    }
}

@Composable
fun App() {
    val c by Hub.client.collectAsStateWithLifecycle()
    val notify by Hub.notifications.collectAsStateWithLifecycle()
    val presets by Hub.presets.collectAsStateWithLifecycle()
    c?.let { MainScreen(it, Hub.http, notify, presets, onChangePrinter = Hub::forget) }
        ?: WelcomeScreen(onPick = Hub::pick, onDemo = Hub::startDemo)
}

private fun openAppSettings(ctx: Context) = ctx.startActivity(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
)

/**
 * Asks for local-network access. After a permanent denial the system won't show the prompt
 * again, so the only way forward is the app's settings page.
 */
@Composable
private fun LocalNetworkCard(onGranted: () -> Unit) {
    val ctx = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) onGranted() else denied = true
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.welcome_permission_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (denied) R.string.permission_denied_body else R.string.welcome_permission_body), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ask.launch(ACCESS_LOCAL_NETWORK) }) { Text(stringResource(R.string.welcome_permission_button)) }
                if (denied) TextButton(onClick = { openAppSettings(ctx) }) { Text(stringResource(R.string.open_settings)) }
            }
        }
    }
}

enum class Tab(val label: Int, val icon: Int) {
    Status(R.string.tab_status, R.drawable.ic_home),
    Files(R.string.tab_files, R.drawable.ic_folder),
    History(R.string.tab_history, R.drawable.ic_history),
}

/** First run: explain, get local-network access if needed, then find the printer automatically. */
@Composable
fun WelcomeScreen(onPick: (Printer) -> Unit, onDemo: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var permitted by remember { mutableStateOf(!needsLocalNetworkPermission(ctx)) }
    // Coming back from the app's settings page after granting it there.
    LifecycleResumeEffect(Unit) {
        permitted = !needsLocalNetworkPermission(ctx)
        onPauseOrDispose {}
    }
    var found by remember { mutableStateOf<List<Printer>?>(null) } // null while searching
    var scan by remember { mutableIntStateOf(0) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var ip by rememberSaveable { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var manualFailed by remember { mutableStateOf(false) }

    LaunchedEffect(permitted, scan) {
        if (!permitted) return@LaunchedEffect
        found = null
        found = discover(broadcastTargets(ctx))
    }
    fun connectManual() {
        connecting = true
        manualFailed = false
        scope.launch {
            val p = discover(listOf(ip.trim())).firstOrNull()
            connecting = false
            if (p != null) onPick(p) else manualFailed = true
        }
    }

    // The demo offer and disclaimer stay pinned to the bottom; everything above scrolls.
    Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))
            Box(
                Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_printer), null, Modifier.size(52.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.welcome_body), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))

            if (!permitted) {
                LocalNetworkCard(onGranted = { permitted = true })
            } else {
                val list = found
                when {
                    list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.welcome_searching), Modifier.padding(start = 12.dp))
                    }
                    list.isEmpty() -> {
                        Text(stringResource(R.string.welcome_none), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.welcome_none_body), Modifier.padding(top = 4.dp),
                            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(onClick = { scan++ }, Modifier.padding(top = 16.dp)) {
                            Text(stringResource(R.string.welcome_search_again))
                        }
                    }
                    else -> {
                        Text(
                            stringResource(R.string.welcome_found), Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        )
                        list.forEach { p ->
                            OutlinedCard(onClick = { onPick(p) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                ListItem(
                                    headlineContent = { Text(p.name.ifEmpty { stringResource(R.string.default_printer_name) }) },
                                    supportingContent = { Text(p.ip) },
                                    leadingContent = { Icon(painterResource(R.drawable.ic_printer), null) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                )
                            }
                        }
                        TextButton(onClick = { scan++ }) { Text(stringResource(R.string.welcome_search_again)) }
                    }
                }

                Spacer(Modifier.height(24.dp))
                if (!manual) {
                    TextButton(onClick = { manual = true }) { Text(stringResource(R.string.welcome_enter_ip)) }
                } else {
                    OutlinedTextField(
                        ip, { ip = it; manualFailed = false }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.welcome_ip_label)) },
                        supportingText = { Text(stringResource(if (manualFailed) R.string.welcome_none else R.string.welcome_ip_hint)) },
                        isError = manualFailed, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (ip.isNotBlank()) connectManual() }),
                    )
                    Button(
                        onClick = ::connectManual, enabled = ip.isNotBlank() && !connecting,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        if (connecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.welcome_connect))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.demo_prompt), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onDemo, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.demo_button)) }
            Text(
                stringResource(R.string.unofficial_note), Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(c: PrinterLink, http: OkHttpClient, notify: Boolean, presets: List<Preset>, onChangePrinter: () -> Unit) {
    LifecycleStartEffect(c) {
        c.start()
        onStopOrDispose { c.stop() }
    }
    val ctx = LocalContext.current
    val connected by c.connected.collectAsStateWithLifecycle()
    val st by c.status.collectAsStateWithLifecycle()
    val printerName by c.name.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Status) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var labs by rememberSaveable { mutableStateOf(false) }
    var videoPaused by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmChange by rememberSaveable { mutableStateOf(false) }
    // The permission can be revoked (or not yet granted after an upgrade) while a printer is saved.
    var networkOk by remember { mutableStateOf(c is DemoPrinter || !needsLocalNetworkPermission(ctx)) }
    LifecycleResumeEffect(c) {
        networkOk = c is DemoPrinter || !needsLocalNetworkPermission(ctx)
        onPauseOrDispose {}
    }

    // While a print runs, the monitor service keeps the connection and mirrors it in the shade.
    // Notification permission is asked the first time that's actually useful; a "no" turns the feature off.
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) PrintMonitorService.start(ctx) else Hub.setNotifications(false)
    }
    val active = st?.let { !it.idle } == true
    // Keyed on `connected` too: a monitor that gave up while the printer was offline restarts once it is back.
    LaunchedEffect(active, notify, connected) {
        when (monitorAction(notify, active, needsNotificationPermission(ctx))) {
            MonitorAction.STOP -> PrintMonitorService.stop(ctx)
            MonitorAction.NONE -> {}
            MonitorAction.ASK_PERMISSION -> askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
            MonitorAction.START -> PrintMonitorService.start(ctx)
        }
    }

    when {
        fullscreen -> {
            ImmersiveLandscape()
            BackHandler { fullscreen = false }
            CameraView(c, http, fullscreen = true, paused = videoPaused, onPausedChange = { videoPaused = it }) { fullscreen = false }
        }
        !networkOk -> Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), Alignment.Center) {
            LocalNetworkCard(onGranted = { networkOk = true; c.reconnect() })
        }
        about -> AboutScreen(onBack = { about = false })
        labs -> {
            val on by Hub.labs.collectAsStateWithLifecycle()
            LabsScreen(on, onToggle = Hub::setLab, onBack = { labs = false })
        }
        settings -> SettingsScreen(
            c, st, connected, notify, presets, onSavePreset = Hub::savePreset, onDeletePreset = Hub::deletePreset,
            onBack = { settings = false }, onChangePrinter = { confirmChange = true },
        )
        // Tablets, foldables and landscape: navigation rail and side-by-side panes.
        else -> BoxWithConstraints {
            val wide = maxWidth >= 600.dp
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(if (tab == Tab.Status) c.displayName(LocalResources.current, printerName) else stringResource(tab.label))
                        },
                        actions = {
                            IconButton(onClick = { menu = true }) {
                                Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_options))
                            }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text(stringResource(R.string.menu_printer_settings)) }, { menu = false; settings = true })
                                DropdownMenuItem({ Text(stringResource(R.string.menu_labs)) }, { menu = false; labs = true })
                                DropdownMenuItem({ Text(stringResource(R.string.menu_change_printer)) }, { menu = false; confirmChange = true })
                                DropdownMenuItem({ Text(stringResource(R.string.menu_about)) }, { menu = false; about = true })
                            }
                        },
                    )
                },
                bottomBar = {
                    if (!wide) NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t, onClick = { tab = t },
                                icon = { Icon(painterResource(t.icon), null) }, label = { Text(stringResource(t.label)) },
                            )
                        }
                    }
                },
            ) { pad ->
                Row {
                    if (wide) NavigationRail(Modifier.padding(pad)) {
                        Tab.entries.forEach { t ->
                            NavigationRailItem(
                                selected = tab == t, onClick = { tab = t },
                                icon = { Icon(painterResource(t.icon), null) }, label = { Text(stringResource(t.label)) },
                            )
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            Tab.Status -> StatusScreen(
                                c, http, connected, st, pad, videoPaused, { videoPaused = it },
                                onFullscreen = { fullscreen = true }, onChooseFile = { tab = Tab.Files }, wide = wide,
                            )
                            Tab.Files -> FilesScreen(c, http, st, pad, wide)
                            Tab.History -> HistoryScreen(c, http, st, pad, wide)
                        }
                    }
                }
            }
        }
    }
    if (confirmChange) AlertDialog(
        onDismissRequest = { confirmChange = false },
        title = { Text(stringResource(R.string.change_printer_title)) },
        text = { Text(stringResource(R.string.change_printer_body)) },
        confirmButton = {
            TextButton(onClick = { confirmChange = false; settings = false; onChangePrinter() }) {
                Text(stringResource(R.string.menu_change_printer))
            }
        },
        dismissButton = { TextButton(onClick = { confirmChange = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
