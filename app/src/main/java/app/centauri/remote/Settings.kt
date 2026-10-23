package app.centauri.remote

import android.Manifest
import android.graphics.Bitmap
import android.os.LocaleList
import android.app.LocaleManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import okhttp3.OkHttpClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Which settings dialog is open. */
private sealed interface SettingsDialog {
    data class Temperature(
        val label: Int, val current: Int, val actual: Double, val max: Int, val nozzle: Boolean, val set: (Int) -> Boolean,
    ) : SettingsDialog
    data class Speed(val current: Int) : SettingsDialog
    data object Home : SettingsDialog
    data object Jog : SettingsDialog
    data class ApplyPreset(val preset: Preset) : SettingsDialog
    data object SavePreset : SettingsDialog
    data object RenamePrinter : SettingsDialog
}

/**
 * The app and printer settings behind the top bar's gear: the printer's name and details, change
 * printer, notifications, Labs and About. Live controls are on the Control tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    c: PrinterLink, http: OkHttpClient, connected: Boolean, notify: Boolean, onBack: () -> Unit, onChangePrinter: () -> Unit,
    onLabs: () -> Unit, onAbout: () -> Unit, notificationsFirst: Boolean = false,
) {
    var notificationsOpen by rememberSaveable { mutableStateOf(notificationsFirst) }
    if (notificationsOpen) return NotificationSettingsScreen(notify, onBack = { notificationsOpen = false })
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val res = LocalResources.current
    val printerName by c.name.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    // The printer's web UI logo tells stock Elegoo firmware from OpenCentauri at a glance; nothing shown if it has none.
    val logo by produceState<Bitmap?>(null, c) { if (c !is DemoPrinter) value = printerLogo(http, c.printer.ip) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).readableWidth().verticalScroll(rememberScrollState())) {
            Section(R.string.section_printer)
            ListItem(
                headlineContent = { Text(c.displayName(res, printerName)) },
                trailingContent = { Icon(painterResource(R.drawable.ic_edit), null) },
                modifier = Modifier.clickable(
                    enabled = connected, onClickLabel = stringResource(R.string.rename_printer), role = Role.Button,
                ) { dialog = SettingsDialog.RenamePrinter },
                supportingContent = {
                    Text(
                        if (c is DemoPrinter) stringResource(R.string.demo_subtitle)
                        else stringResource(R.string.ip_state, c.printer.ip, stringResource(if (connected) R.string.connected else R.string.not_connected)),
                    )
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.menu_change_printer)) },
                modifier = Modifier.clickable(onClick = onChangePrinter),
            )
            val attrs by c.attributes.collectAsStateWithLifecycle()
            val clock by c.clock.collectAsStateWithLifecycle()
            val clockLabel = stringResource(R.string.detail_clock)
            val details = remember(attrs) { attrs?.let { printerDetails(ctx, it) }.orEmpty() } +
                listOfNotNull(clock?.let { clockLabel to printerClock(ctx, it) })
            if (details.isNotEmpty() || logo != null) {
                Section(R.string.section_details)
                // On a neutral box so a white or transparent logo shows in both themes.
                logo?.let {
                    Box(
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(8.dp),
                    ) { Image(it.asImageBitmap(), stringResource(R.string.logo_desc), Modifier.heightIn(max = 64.dp)) }
                }
                SelectionContainer {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        details.forEach { (label, value) ->
                            Row {
                                Text(
                                    label, Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(value, Modifier.weight(3f), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            Section(R.string.section_app)
            ListItem(
                headlineContent = { Text(stringResource(R.string.section_notifications)) },
                supportingContent = { Text(stringResource(if (notify) R.string.notifications_row_body else R.string.notifications_off)) },
                modifier = Modifier.clickable(role = Role.Button) { notificationsOpen = true },
            )
            val dynamicColor by Hub.dynamicColor.collectAsStateWithLifecycle()
            SwitchRow(
                stringResource(R.string.dynamic_color_title), stringResource(R.string.dynamic_color_body), dynamicColor,
                onChange = Hub::setDynamicColor,
            )
            LanguageRow()
            ListItem(
                headlineContent = { Text(stringResource(R.string.menu_labs)) },
                supportingContent = { Text(stringResource(R.string.labs_row_body)) },
                modifier = Modifier.clickable(role = Role.Button, onClick = onLabs),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.menu_about)) },
                modifier = Modifier.clickable(role = Role.Button, onClick = onAbout),
            )
        }
    }
    SettingsDialogs(c, null, emptyList(), dialog, onSavePreset = {}) { dialog = null }
}

/** Settings-style pages on a tablet: one readable column, at most 640 dp wide, centred. */
internal fun Modifier.readableWidth() = wrapContentWidth().widthIn(max = 640.dp).fillMaxWidth()

/** The language names, each written in itself; matches [LANGUAGES]. */
private val LANGUAGE_NAMES = listOf(R.string.language_system, R.string.language_en, R.string.language_nl, R.string.language_el)

/** In-app language: the system's per-app language, which it keeps (and shows in its own settings). */
@Composable
private fun LanguageRow() {
    val ctx = LocalContext.current
    val config = LocalConfiguration.current
    val current = remember(config) { languageChoice(ctx.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()) }
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(stringResource(R.string.language_title)) },
            supportingContent = { Text(stringResource(LANGUAGE_NAMES[LANGUAGES.indexOf(current)])) },
            modifier = Modifier.clickable(role = Role.Button) { open = true },
        )
        DropdownMenu(open, { open = false }) {
            LANGUAGES.forEachIndexed { i, tag ->
                DropdownMenuItem(
                    { Text(stringResource(LANGUAGE_NAMES[i])) },
                    {
                        open = false
                        ctx.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
                    },
                    Modifier.semantics { selected = tag == current },
                    trailingIcon = if (tag == current) ({ Icon(painterResource(R.drawable.ic_check_circle), null) }) else null,
                )
            }
        }
    }
}

/** The Control tab: presets, temperatures, speed, fans, light and motion, while the printer is connected. */
@Composable
fun ControlScreen(
    c: PrinterLink, st: Status?, presets: List<Preset>, pad: PaddingValues,
    onSavePreset: (Preset) -> Unit, onDeletePreset: (Preset) -> Unit,
) {
    val command = rememberCommand()
    val snack = LocalSnackbar.current
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    Column(Modifier.fillMaxSize().padding(pad).readableWidth().verticalScroll(rememberScrollState())) {
        if (st == null) {
            Text(
                stringResource(R.string.blocked_offline), Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Section(R.string.section_presets)
        val res = LocalResources.current
        builtInPresets(res).forEach { p ->
            PresetRow(p, res, onApply = { dialog = SettingsDialog.ApplyPreset(p) })
        }
        presets.forEach { p ->
            PresetRow(
                p, res, onApply = { dialog = SettingsDialog.ApplyPreset(p) },
                // Presets are local and cheap: delete at once, with Undo instead of a confirm.
                onDelete = {
                    onDeletePreset(p)
                    snack.say(res.getString(R.string.preset_deleted, p.name), res.getString(R.string.undo)) { onSavePreset(p) }
                },
            )
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.preset_save_current)) },
            supportingContent = { Text(stringResource(R.string.preset_save_current_body)) },
            leadingContent = { Icon(painterResource(R.drawable.ic_add), null) },
            modifier = Modifier.clickable { dialog = SettingsDialog.SavePreset },
        )

        Section(R.string.section_temperature)
        TempRow(R.string.nozzle_target, st.nozzleTarget) { dialog = nozzleDialog(c, st) }
        TempRow(R.string.bed_target, st.bedTarget) { dialog = bedDialog(c, st) }

        Section(R.string.section_print)
        ListItem(
            headlineContent = { Text(stringResource(R.string.print_speed)) },
            supportingContent = { Text(speedLabel(res, st.print.speedPct)) },
            modifier = Modifier.clickable { dialog = SettingsDialog.Speed(st.print.speedPct) },
        )
        SwitchRow(stringResource(R.string.chamber_light), null, st.light) { command(c.setLight(it)) }

        Section(R.string.section_fans)
        Fans(c, st)

        Section(R.string.section_motion)
        val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        ListItem(
            headlineContent = { Text(stringResource(R.string.home_axes)) },
            supportingContent = {
                Text(stringResource(if (st.idle) R.string.home_axes_body else R.string.home_axes_idle_only))
            },
            colors = if (st.idle) ListItemDefaults.colors()
            else ListItemDefaults.colors(headlineColor = dim, supportingColor = dim),
            modifier = Modifier.clickable(enabled = st.idle) { dialog = SettingsDialog.Home },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.move_axes)) },
            supportingContent = {
                Text(stringResource(if (st.idle) R.string.move_axes_body else R.string.home_axes_idle_only))
            },
            colors = if (st.idle) ListItemDefaults.colors()
            else ListItemDefaults.colors(headlineColor = dim, supportingColor = dim),
            modifier = Modifier.clickable(enabled = st.idle) { dialog = SettingsDialog.Jog },
        )
    }
    SettingsDialogs(c, st, presets, dialog, onSavePreset) { dialog = null }
}

private fun nozzleDialog(c: PrinterLink, st: Status) =
    SettingsDialog.Temperature(R.string.nozzle_target, st.nozzleTarget.toInt(), st.nozzle, 320, nozzle = true, c::setNozzle)

private fun bedDialog(c: PrinterLink, st: Status) =
    SettingsDialog.Temperature(R.string.bed_target, st.bedTarget.toInt(), st.bed, 110, nozzle = false, c::setBed)

/** The nozzle or bed target dialog, also opened by tapping a temperature on the Status screen. */
@Composable
fun TargetTempDialog(c: PrinterLink, st: Status, nozzle: Boolean, onDismiss: () -> Unit) =
    SettingsDialogs(c, st, emptyList(), if (nozzle) nozzleDialog(c, st) else bedDialog(c, st), {}, onDismiss)

/** The print speed choice, also opened from the speed chip on the Status screen. */
@Composable
fun SpeedTargetDialog(c: PrinterLink, st: Status, onDismiss: () -> Unit) =
    SettingsDialogs(c, st, emptyList(), SettingsDialog.Speed(st.print.speedPct), {}, onDismiss)

@Composable
private fun SettingsDialogs(
    c: PrinterLink, st: Status?, presets: List<Preset>, dialog: SettingsDialog?,
    onSavePreset: (Preset) -> Unit, onDismiss: () -> Unit,
) {
    val command = rememberCommand()
    val snack = LocalSnackbar.current
    val res = LocalResources.current
    val printerName by c.name.collectAsStateWithLifecycle()
    when (val d = dialog) {
        null -> {}
        is SettingsDialog.Temperature -> TempDialog(d.copy(set = { v -> d.set(v).also(command) }), onDismiss)
        is SettingsDialog.Speed -> SpeedDialog(d.current, { command(c.setSpeed(it)) }, onDismiss)
        SettingsDialog.Home -> HomeDialog({ command(c.home(it)) }, onDismiss)
        SettingsDialog.Jog -> JogDialog({ axis, mm -> command(c.jog(axis, mm)) }, { command(c.home(it)) }, idle = st?.idle == true, onDismiss)
        is SettingsDialog.ApplyPreset -> {
            // ABS temperatures on a running PLA print are a real foot-gun.
            val clash = presetClash(d.preset, st)
            ConfirmDialog(
                title = stringResource(R.string.preset_apply_title, d.preset.name),
                body = d.preset.summary(res) + clash?.let { "\n\n" + res.getString(R.string.preset_clash, it, d.preset.name) }.orEmpty(),
                confirm = stringResource(R.string.preset_apply),
                onConfirm = { command(c.apply(d.preset)) },
                destructive = clash != null,
                onDismiss = onDismiss,
            )
        }
        SettingsDialog.RenamePrinter -> TextInputDialog(
            title = stringResource(R.string.rename_printer), label = stringResource(R.string.printer_name),
            initial = c.displayName(res, printerName), confirm = stringResource(R.string.rename),
            onConfirm = { name ->
                // Process-wide, so the outcome is reported even if Settings is left mid-rename.
                Hub.scope.launch {
                    val error = try {
                        if (c.renamePrinter(name)) null else res.getString(R.string.change_refused)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: PrinterException) {
                        res.getString(e.messageRes)
                    }
                    error?.let { snack.say(it) }
                }
            },
            onDismiss = onDismiss,
        )
        SettingsDialog.SavePreset -> st?.let { status ->
            SavePresetDialog(presets, onSave = { onSavePreset(presetFrom(it, status)) }, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun PresetRow(p: Preset, res: android.content.res.Resources, onApply: () -> Unit, onDelete: (() -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(p.name) },
        supportingContent = { Text(p.summary(res)) },
        trailingContent = onDelete?.let {
            { IconButton(onClick = it) { Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.preset_delete)) } }
        },
        modifier = Modifier.clickable(onClick = onApply),
    )
}

@Composable
internal fun ConfirmDialog(
    title: String, body: String, confirm: String, onConfirm: () -> Unit, destructive: Boolean = false, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(); onDismiss() },
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.textButtonColors(),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A one-field dialog (renaming the printer). */
@Composable
internal fun TextInputDialog(
    title: String, label: String, initial: String, confirm: String,
    onConfirm: (String) -> Unit, onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val trimmed = text.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it.take(60) }, label = { Text(label) }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = trimmed.isNotEmpty() && trimmed != initial, onClick = { onConfirm(trimmed); onDismiss() }) {
                Text(confirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Names the current settings; reusing a name replaces that preset (and says so). */
@Composable
private fun SavePresetDialog(existing: List<Preset>, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val suggested = stringResource(R.string.preset_default_name, existing.size + 1)
    var name by remember { mutableStateOf(suggested) }
    val trimmed = name.trim()
    val replaces = existing.any { it.name.equals(trimmed, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preset_save_current)) },
        text = {
            OutlinedTextField(
                name, { name = it.take(40) },
                label = { Text(stringResource(R.string.preset_name)) },
                supportingText = { Text(stringResource(if (replaces) R.string.preset_replaces else R.string.preset_save_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = trimmed.isNotEmpty(), onClick = { onSave(trimmed); onDismiss() }) {
                Text(stringResource(R.string.preset_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Every notification option: the master switch, one per kind of notification, and the system's page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationSettingsScreen(notify: Boolean, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val res = LocalResources.current
    val notices by Hub.notices.collectAsStateWithLifecycle()
    val remindBefore by Hub.remindBefore.collectAsStateWithLifecycle()
    val clockWarning by Hub.clockWarning.collectAsStateWithLifecycle()
    var remindMenu by remember { mutableStateOf(false) }
    // Turning notifications on is the moment to ask for the permission; a "no" keeps them off.
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Hub.setNotifications(it) }
    val setNotify = { on: Boolean ->
        if (on && needsNotificationPermission(ctx)) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        else Hub.setNotifications(on)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.section_notifications)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back)) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).readableWidth().verticalScroll(rememberScrollState())) {
            SwitchRow(stringResource(R.string.notify_all), stringResource(R.string.notify_body), notify, onChange = setNotify)
            Section(R.string.section_notification_types)
            Notice.entries.forEach { n ->
                val body = when (n) {
                    Notice.TIME_LEFT -> stringResource(R.string.time_left_body)
                    Notice.FIRST_LAYER -> stringResource(R.string.first_layer_body)
                    else -> null
                }
                SwitchRow(stringResource(n.label), body, n in notices, enabled = notify) { Hub.setNotice(n, it) }
                if (n == Notice.TIME_LEFT) Box {
                    val enabled = notify && Notice.TIME_LEFT in notices
                    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.remind_title)) },
                        supportingContent = { Text(res.getString(R.string.remind_minutes, remindBefore)) },
                        colors = if (enabled) ListItemDefaults.colors() else ListItemDefaults.colors(headlineColor = dim, supportingColor = dim),
                        modifier = Modifier.clickable(enabled = enabled, role = Role.Button) { remindMenu = true },
                    )
                    DropdownMenu(remindMenu, { remindMenu = false }) {
                        REMIND_CHOICES.forEach { minutes ->
                            DropdownMenuItem(
                                { Text(res.getString(R.string.remind_minutes, minutes)) },
                                { remindMenu = false; Hub.setRemindBefore(minutes) },
                                Modifier.semantics { selected = minutes == remindBefore },
                                trailingIcon = if (minutes == remindBefore) ({ Icon(painterResource(R.drawable.ic_check_circle), null) }) else null,
                            )
                        }
                    }
                }
            }
            // Sound, vibration and lock-screen visibility per kind live in the system's settings.
            ListItem(
                headlineContent = { Text(stringResource(R.string.notif_settings_title)) },
                supportingContent = { Text(stringResource(R.string.notif_settings_body)) },
                modifier = Modifier.clickable(role = Role.Button) { openNotificationSettings(ctx) },
            )
            Section(R.string.section_status_screen)
            SwitchRow(
                stringResource(R.string.clock_warning_title), stringResource(R.string.clock_warning_body), clockWarning,
                onChange = Hub::setClockWarning,
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, body: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = body?.let { { Text(it) } },
        trailingContent = { Switch(checked, onCheckedChange = null, enabled = enabled) },
        colors = if (enabled) ListItemDefaults.colors() else ListItemDefaults.colors(headlineColor = dim, supportingColor = dim),
        modifier = Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun Section(title: Int) {
    Text(
        stringResource(title), Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun TempRow(label: Int, target: Double, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        supportingContent = { Text(if (target > 0) stringResource(R.string.temp_c, target) else stringResource(R.string.off)) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun Fans(c: PrinterLink, st: Status) {
    // A dragged value wins until the printer has had a few seconds to report it back.
    var drag by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    LaunchedEffect(drag) { if (drag != null) { delay(4000); drag = null } }
    val v = drag ?: Triple(st.modelFan, st.auxFan, st.boxFan)
    val command = rememberCommand()
    val send = { f: Triple<Int, Int, Int> -> command(c.setFans(f.first, f.second, f.third)) }
    // Off and Max send at once, like letting go of a dragged slider.
    val set = { f: Triple<Int, Int, Int> -> drag = f; send(f) }
    val done = { drag?.let(send); Unit }
    FanSlider(R.string.fan_part, v.first, { drag = v.copy(first = it) }, done) { set(v.copy(first = it)) }
    FanSlider(R.string.fan_aux, v.second, { drag = v.copy(second = it) }, done) { set(v.copy(second = it)) }
    FanSlider(R.string.fan_chamber, v.third, { drag = v.copy(third = it) }, done) { set(v.copy(third = it)) }
}

@Composable
private fun FanSlider(label: Int, value: Int, onChange: (Int) -> Unit, onDone: () -> Unit, onSet: (Int) -> Unit) {
    val name = stringResource(label)
    val haptic = LocalHapticFeedback.current
    val offLabel = stringResource(R.string.fan_off_desc, name)
    val maxLabel = stringResource(R.string.fan_max_desc, name)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.percent, value),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onSet(0) }, Modifier.semantics { contentDescription = offLabel }) { Text(stringResource(R.string.off)) }
            TextButton(onClick = { onSet(100) }, Modifier.semantics { contentDescription = maxLabel }) { Text(stringResource(R.string.fan_max)) }
        }
        // 10 % steps, one tick each: the firmware rounds anyway, and a stepped slider is easier to hit.
        Slider(
            value.toFloat(),
            { v ->
                val pct = v.roundToInt()
                if (pct != value) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onChange(pct)
            },
            Modifier.semantics { contentDescription = name },
            valueRange = 0f..100f, steps = 9, onValueChangeFinished = onDone,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TempDialog(d: SettingsDialog.Temperature, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(d.current.toString()) }
    val value = text.toIntOrNull()?.takeIf { it in 0..d.max }
    val step = { delta: Int -> text = ((value ?: d.current) + delta).coerceIn(0, d.max).toString() }
    val res = LocalResources.current
    val down = stringResource(R.string.temp_down)
    val up = stringResource(R.string.temp_up)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(d.label)) },
        text = {
            // Stepper and material chips for the usual targets; typing stays for anything else.
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.temp_now, d.actual), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(onClick = { step(-5) }, Modifier.semantics { contentDescription = down }) { Text("−5") }
                    OutlinedTextField(
                        text, { text = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f),
                        label = { Text(stringResource(R.string.temp_unit)) },
                        supportingText = { Text(stringResource(R.string.temp_range, d.max)) },
                        singleLine = true, isError = value == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    FilledTonalIconButton(onClick = { step(5) }, Modifier.semantics { contentDescription = up }) { Text("+5") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    builtInPresets(res).forEach { p ->
                        val t = (if (d.nozzle) p.nozzle else p.bed) ?: return@forEach
                        FilterChip(
                            selected = value == t, onClick = { text = t.toString() },
                            label = { Text(if (t == 0) stringResource(R.string.off) else stringResource(R.string.temp_chip, p.name, t)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = value != null, onClick = { value?.let(d.set); onDismiss() }) { Text(stringResource(R.string.set)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Settings-style single choice: tapping an option applies it and closes. */
@Composable
private fun SpeedDialog(current: Int, set: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.print_speed)) },
        text = {
            Column {
                SPEED_PRESETS.forEach { (pct, label) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = current == pct, role = Role.RadioButton) { set(pct); onDismiss() }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == pct, onClick = null)
                        Text(stringResource(R.string.speed_option, stringResource(label), pct), Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun HomeDialog(home: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_axes)) },
        text = {
            Column {
                listOf("XYZ" to R.string.home_all, "X" to R.string.home_x, "Y" to R.string.home_y, "Z" to R.string.home_z)
                    .forEach { (axis, label) ->
                        Text(
                            stringResource(label),
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { home(axis); onDismiss() }
                                .padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Nudges X, Y or Z by a fixed step, relative to where the head is. Stays open for several moves;
 * the buttons go dim if the printer stops being idle (e.g. a print starts from its screen).
 */
@Composable
private fun JogDialog(jog: (String, Double) -> Unit, home: (String) -> Unit, idle: Boolean, onDismiss: () -> Unit) {
    var step by remember { mutableStateOf(JOG_STEPS[1]) }
    var confirmHome by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_axes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.jog_step), style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    JOG_STEPS.forEachIndexed { i, s ->
                        SegmentedButton(step == s, { step = s }, SegmentedButtonDefaults.itemShape(i, JOG_STEPS.size)) {
                            Text(stringResource(R.string.jog_mm, formatMm(s)))
                        }
                    }
                }
                // Laid out like Elegoo's app: a round XY pad (Y+ up, X+ right) and a Z rocker beside it.
                // The arrow icon points left; rotating it clockwise by `turn` aims it.
                val arrow: @Composable (String, Double, Float, Modifier) -> Unit = { axis, mm, turn, m ->
                    IconButton(onClick = { jog(axis, mm) }, enabled = idle, modifier = m.size(56.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back), stringResource(R.string.jog_desc, axis, formatMm(mm)),
                            Modifier.rotate(turn),
                        )
                    }
                }
                val pad = MaterialTheme.colorScheme.surfaceContainerHighest
                Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(184.dp).clip(CircleShape).background(pad)) {
                        arrow("Y", step, 90f, Modifier.align(Alignment.TopCenter))
                        arrow("X", step, 180f, Modifier.align(Alignment.CenterEnd))
                        arrow("Y", -step, 270f, Modifier.align(Alignment.BottomCenter))
                        arrow("X", -step, 0f, Modifier.align(Alignment.CenterStart))
                        FilledTonalIconButton(
                            onClick = { confirmHome = true }, enabled = idle, modifier = Modifier.align(Alignment.Center).size(64.dp),
                        ) { Icon(painterResource(R.drawable.ic_home), stringResource(R.string.home_all)) }
                    }
                    Column(
                        Modifier.clip(CircleShape).background(pad).padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        arrow("Z", step, 90f, Modifier)
                        Text("Z", style = MaterialTheme.typography.titleMedium)
                        arrow("Z", -step, 270f, Modifier)
                    }
                }
                Text(
                    stringResource(R.string.jog_z_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
    // The home button sits among the arrows; a missed tap mustn't send the whole head and bed home.
    if (confirmHome) AlertDialog(
        onDismissRequest = { confirmHome = false },
        title = { Text(stringResource(R.string.home_axes)) },
        text = { Text(stringResource(R.string.home_all_confirm)) },
        confirmButton = {
            TextButton(onClick = { confirmHome = false; home("XYZ") }, enabled = idle) { Text(stringResource(R.string.home_button)) }
        },
        dismissButton = { TextButton(onClick = { confirmHome = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
