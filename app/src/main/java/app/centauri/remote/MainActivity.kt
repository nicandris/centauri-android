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
import androidx.compose.animation.Crossfade
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
    /** Set by a notification, widget, tile or shortcut tap: where to go. */
    private var open by mutableStateOf<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) openIfAsked(intent)
        setContent {
            val dynamic by Hub.dynamicColor.collectAsStateWithLifecycle()
            CentauriTheme(dynamic) { App(open) }
        }
    }

    // Android 13+ applies the per-app language itself; before that, the one picked in Settings is applied here.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) withLanguage(newBase, Hub.language.value) else newBase)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openIfAsked(intent)
    }

    private fun openIfAsked(i: Intent) {
        destinationOf(i)?.let { open = OpenRequest(it, i.getStringExtra(EXTRA_FILE)) }
    }
}

/** One tap's request; a new instance each time, so the same destination twice still goes there. */
class OpenRequest(val to: Destination, val file: String?)

@Composable
fun App(open: OpenRequest? = null) {
    val c by Hub.client.collectAsStateWithLifecycle()
    val notify by Hub.notifications.collectAsStateWithLifecycle()
    val presets by Hub.presets.collectAsStateWithLifecycle()
    c?.let { MainScreen(it, Hub.http, notify, presets, onChangePrinter = Hub::forget, open = open) }
        ?: WelcomeScreen(onPick = Hub::pick, onDemo = Hub::startDemo)
}

private fun openAppSettings(ctx: Context) = ctx.startActivity(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
)

/** The system page for this app's notification channels (finished, paused, time left…). */
fun openNotificationSettings(ctx: Context) = ctx.startActivity(
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
)

/**
 * Asks for local-network access. After a permanent denial the system won't show the prompt
 * again, so the only way forward is the app's settings page.
 */
@Composable
private fun LocalNetworkCard(lost: Boolean = false, onGranted: () -> Unit) {
    val ctx = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) onGranted() else denied = true
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Mid-session the card replaces the whole app, so it says why.
            if (lost) Text(stringResource(R.string.network_lost), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.welcome_permission_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (denied) R.string.permission_denied_body else R.string.welcome_permission_body), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ask.launch(ACCESS_LOCAL_NETWORK) }) { Text(stringResource(R.string.welcome_permission_button)) }
                if (denied) TextButton(onClick = { openAppSettings(ctx) }) { Text(stringResource(R.string.open_settings)) }
            }
        }
    }
}

/** What [MainScreen] shows: the tabs, or one view in their place. */
private enum class Screen { Tabs, Fullscreen, Settings, About, Labs }

enum class Tab(val label: Int, val icon: Int) {
    Status(R.string.tab_status, R.drawable.ic_home),
    Control(R.string.tab_control, R.drawable.ic_tune),
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
    var slow by remember { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
    var scan by remember { mutableIntStateOf(0) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var ip by rememberSaveable { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var failedIp by rememberSaveable { mutableStateOf<String?>(null) } // the typed-in IP nobody answered at
    val typed = ip.trim()

    LaunchedEffect(permitted, scan) {
        if (!permitted) return@LaunchedEffect
        found = null
        slow = false
        val hint = launch { delay(2000); slow = true }
        found = discover(broadcastTargets(ctx)) { lanDatagramSocket(Hub.lan) }
        hint.cancel()
    }
    fun connectManual() {
        if (!isIpv4(typed) || connecting) return
        connecting = true
        failedIp = null
        scope.launch {
            val p = discover(listOf(typed), untilFirst = true) { lanDatagramSocket(Hub.lan) }.firstOrNull()
            connecting = false
            if (p != null) onPick(p) else failedIp = typed
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
                    list == null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.welcome_searching), Modifier.padding(start = 12.dp))
                        }
                        if (slow) Text(
                            stringResource(R.string.welcome_still_looking), Modifier.padding(top = 8.dp),
                            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                        TextButton(onClick = { help = !help }) { Text(stringResource(R.string.welcome_help_title)) }
                        if (help) Text(
                            stringResource(R.string.welcome_help_body), Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        Text(
                            stringResource(R.string.welcome_found), Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        )
                        list.forEach { p ->
                            val item: @Composable () -> Unit = {
                                ListItem(
                                    headlineContent = { Text(p.name.ifEmpty { stringResource(R.string.default_printer_name) }) },
                                    supportingContent = { Text(p.ip) },
                                    leadingContent = { Icon(painterResource(R.drawable.ic_printer), null) },
                                    // The only one found: a clear primary Connect, still a tap away.
                                    trailingContent = if (list.size == 1) ({ Button(onClick = { onPick(p) }) { Text(stringResource(R.string.welcome_connect)) } }) else null,
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                )
                            }
                            val m = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            if (list.size == 1) Card(
                                onClick = { onPick(p) }, modifier = m,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            ) { item() }
                            else OutlinedCard(onClick = { onPick(p) }, modifier = m) { item() }
                        }
                        TextButton(onClick = { scan++ }) { Text(stringResource(R.string.welcome_search_again)) }
                    }
                }

                Spacer(Modifier.height(24.dp))
                if (!manual) {
                    TextButton(onClick = { manual = true }) { Text(stringResource(R.string.welcome_enter_ip)) }
                } else {
                    val malformed = typed.isNotEmpty() && !isIpv4(typed)
                    OutlinedTextField(
                        ip, { ip = it; failedIp = null }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.welcome_ip_label)) },
                        supportingText = {
                            Text(
                                failedIp?.let { stringResource(R.string.welcome_ip_failed, it) }
                                    ?: stringResource(if (malformed) R.string.welcome_ip_invalid else R.string.welcome_ip_hint),
                            )
                        },
                        isError = failedIp != null || malformed, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { connectManual() }),
                    )
                    Button(
                        onClick = ::connectManual, enabled = isIpv4(typed) && !connecting,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        if (connecting) Spinner(Modifier.size(18.dp), strokeWidth = 2.dp)
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

/** The app's one snackbar (in-app feedback: not sent, print started, delete failed…); see [say]. */
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/** Shows [message] without waiting for it to go away; [onAction] runs if [action] is tapped. */
fun SnackbarHostState.say(message: String, action: String? = null, onAction: () -> Unit = {}) {
    Hub.scope.launch {
        val result = showSnackbar(message, action, duration = if (action == null) SnackbarDuration.Short else SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) onAction()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    c: PrinterLink, http: OkHttpClient, notify: Boolean, presets: List<Preset>, onChangePrinter: () -> Unit, open: OpenRequest? = null,
) {
    val snack = remember { SnackbarHostState() }
    CompositionLocalProvider(LocalSnackbar provides snack) { Shell(c, http, notify, presets, onChangePrinter, open, snack) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Shell(
    c: PrinterLink, http: OkHttpClient, notify: Boolean, presets: List<Preset>, onChangePrinter: () -> Unit, open: OpenRequest?,
    snack: SnackbarHostState,
) {
    val ctx = LocalContext.current
    LifecycleStartEffect(c) {
        c.start()
        // Widgets show what the app sees, also with notifications (and so the monitor) off.
        val unfollow = followOnWidgets(ctx, c)
        onStopOrDispose { unfollow(); c.stop() }
    }
    val res = LocalResources.current
    val connected by c.connected.collectAsStateWithLifecycle()
    val st by c.status.collectAsStateWithLifecycle()
    // The last status outlives the connection; screens that act on it get none while offline.
    val live = st.takeIf { connected }
    // Give a fresh connection a few seconds before calling the printer unreachable.
    val offline by produceState(false, c, connected) {
        value = false
        if (!connected) { delay(4000); value = true }
    }
    // Still unreachable after a few reconnects: maybe it got a new address, so look for it by mainboard id.
    LaunchedEffect(offline) {
        val was = c.printer.ip
        if (offline) Hub.relocate()?.takeIf { it.ip != was }?.let { p -> snack.say(res.getString(R.string.find_moved, p.ip)) }
    }
    val printerName by c.name.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Status) }
    var screen by rememberSaveable { mutableStateOf(Screen.Tabs) }
    var notificationsFirst by rememberSaveable { mutableStateOf(false) }
    // The file a "Print again" shortcut asked to start again.
    var reprint by rememberSaveable { mutableStateOf<String?>(null) }
    // A notification, widget, tile or launcher shortcut was tapped.
    LaunchedEffect(open) {
        when (open?.to) {
            null -> {}
            Destination.CAMERA -> screen = Screen.Fullscreen
            Destination.FILES -> { tab = Tab.Files; screen = Screen.Tabs }
            Destination.STATUS, Destination.REPRINT -> { tab = Tab.Status; screen = Screen.Tabs; reprint = open.file }
        }
    }
    val labs by Hub.labs.collectAsStateWithLifecycle()
    val attrs by c.attributes.collectAsStateWithLifecycle()
    val firmware = attrs?.optString("FirmwareVersion").orEmpty().trim()
    var videoPaused by rememberSaveable { mutableStateOf(false) }
    var confirmChange by rememberSaveable { mutableStateOf(false) }
    // The permission can be revoked (or not yet granted after an upgrade) while a printer is saved.
    var networkOk by remember { mutableStateOf(c is DemoPrinter || !needsLocalNetworkPermission(ctx)) }
    LifecycleResumeEffect(c) {
        networkOk = c is DemoPrinter || !needsLocalNetworkPermission(ctx)
        onPauseOrDispose {}
    }

    // While a print runs, the monitor service keeps the connection and mirrors it in the shade.
    // Notification permission is asked the first time that's actually useful; a "no" turns the feature off,
    // and says where to turn it back on.
    // Once per screen: a reconnect while the system dialog is up must not queue a second one.
    var askedNotify by rememberSaveable { mutableStateOf(false) }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) PrintMonitorService.start(ctx)
        else {
            Hub.setNotifications(false)
            snack.say(res.getString(R.string.notify_denied), res.getString(R.string.settings)) {
                notificationsFirst = true
                screen = Screen.Settings
            }
        }
    }
    val active = st?.let { !it.idle } == true
    // Keyed on `connected` too: a monitor that gave up while the printer was offline restarts once it is back.
    LaunchedEffect(active, notify, connected) {
        when (monitorAction(notify, active, needsNotificationPermission(ctx))) {
            MonitorAction.STOP -> PrintMonitorService.stop(ctx)
            MonitorAction.NONE -> {}
            MonitorAction.ASK_PERMISSION -> if (!askedNotify) {
                askedNotify = true
                askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            MonitorAction.START -> PrintMonitorService.start(ctx)
        }
    }

    val toTabs = { screen = Screen.Tabs; notificationsFirst = false }
    Crossfade(screen to networkOk, label = "screen") { (shown, permitted) ->
        when {
            shown == Screen.Fullscreen -> Box {
                ImmersiveLandscape()
                BackHandler(onBack = toTabs)
                CameraView(c, http, fullscreen = true, paused = videoPaused, onPausedChange = { videoPaused = it }, onToggleFullscreen = toTabs)
                SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
            }
            !permitted -> Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), Alignment.Center) {
                LocalNetworkCard(lost = true, onGranted = { networkOk = true; c.reconnect() })
            }
            shown == Screen.About -> AboutScreen(firmware, onBack = { screen = Screen.Settings })
            shown == Screen.Labs -> LabsScreen(labs, firmware, onToggle = Hub::setLab, onBack = { screen = Screen.Settings })
            shown == Screen.Settings -> SettingsScreen(
                c, http, connected, notify, onBack = toTabs, onChangePrinter = { confirmChange = true },
                onLabs = { screen = Screen.Labs }, onAbout = { screen = Screen.About }, notificationsFirst = notificationsFirst,
            )
            // Tablets, foldables and landscape: navigation rail and side-by-side panes.
            else -> BoxWithConstraints {
                val wide = maxWidth >= 600.dp
                Scaffold(
                    topBar = {
                        Column {
                            TopAppBar(
                                title = {
                                    // The printer on every tab, and how it's doing.
                                    Column {
                                        Text(c.displayName(res, printerName), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            connectionLine(res, connected, st), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (offline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                actions = {
                                    // Always visible in the demo, so it isn't mistaken for (or shared as) a real printer.
                                    if (c is DemoPrinter) Box {
                                        var demoMenu by remember { mutableStateOf(false) }
                                        AssistChip(onClick = { demoMenu = true }, label = { Text(stringResource(R.string.demo_chip)) })
                                        DropdownMenu(demoMenu, { demoMenu = false }) {
                                            // Jump to a situation instead of waiting for it.
                                            DemoScenario.entries.forEach { s ->
                                                DropdownMenuItem({ Text(stringResource(s.label)) }, { demoMenu = false; c.play(s) })
                                            }
                                            HorizontalDivider()
                                            DropdownMenuItem({ Text(stringResource(R.string.demo_exit)) }, { demoMenu = false; onChangePrinter() })
                                        }
                                    }
                                    IconButton(onClick = { screen = Screen.Settings }) {
                                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
                                    }
                                },
                            )
                            // The Status tab has its own, fuller offline view.
                            if (offline && tab != Tab.Status) OfflineBanner(c)
                        }
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
                    snackbarHost = { SnackbarHost(snack) },
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
                        Crossfade(tab, Modifier.weight(1f), label = "tab") { t ->
                            when (t) {
                                Tab.Status -> StatusScreen(
                                    c, http, connected, offline, st, pad, videoPaused, { videoPaused = it },
                                    onFullscreen = { screen = Screen.Fullscreen }, onChooseFile = { tab = Tab.Files },
                                    onChangePrinter = { confirmChange = true }, labs = labs, wide = wide,
                                )
                                Tab.Control -> ControlScreen(c, live, presets, pad, onSavePreset = Hub::savePreset, onDeletePreset = Hub::deletePreset)
                                // A started print is what the user wants to watch next.
                                Tab.Files -> FilesScreen(c, http, live, pad, wide, labs, onStarted = { tab = Tab.Status })
                                Tab.History -> HistoryScreen(c, http, live, pad, wide, labs, onStarted = { tab = Tab.Status })
                            }
                        }
                    }
                }
            }
        }
    }
    reprint?.let { ReprintDialog(c, http, it, live, labs) { reprint = null } }
    if (confirmChange) AlertDialog(
        onDismissRequest = { confirmChange = false },
        title = { Text(stringResource(R.string.change_printer_title)) },
        text = { Text(stringResource(R.string.change_printer_body)) },
        confirmButton = {
            TextButton(onClick = { confirmChange = false; screen = Screen.Tabs; onChangePrinter() }) {
                Text(stringResource(R.string.menu_change_printer))
            }
        },
        dismissButton = { TextButton(onClick = { confirmChange = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A slim strip under the top bar on the other tabs while the printer is unreachable. */
@Composable
private fun OfflineBanner(c: PrinterLink) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.offline_title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = c::reconnect) { Text(stringResource(R.string.retry)) }
        }
    }
}
