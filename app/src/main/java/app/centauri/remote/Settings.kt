package app.centauri.remote

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Which settings dialog is open. */
private sealed interface SettingsDialog {
    data class Temperature(val label: Int, val current: Int, val max: Int, val set: (Int) -> Boolean) : SettingsDialog
    data class Speed(val current: Int) : SettingsDialog
    data object Home : SettingsDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    c: PrinterLink, st: Status?, connected: Boolean, notify: Boolean,
    onBack: () -> Unit, onChangePrinter: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val command = rememberCommand()
    val ctx = LocalContext.current
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    // Turning notifications on is the moment to ask for the permission; a "no" keeps them off.
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Hub.setNotifications(it) }
    val setNotify = { on: Boolean ->
        if (on && needsNotificationPermission(ctx)) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        else Hub.setNotifications(on)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_printer_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back)) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Section(R.string.section_printer)
            ListItem(
                headlineContent = { Text(c.displayName(LocalResources.current)) },
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

            if (st != null) {
                Section(R.string.section_temperature)
                TempRow(R.string.nozzle_target, st.nozzleTarget) {
                    dialog = SettingsDialog.Temperature(R.string.nozzle_target, st.nozzleTarget.toInt(), 320, c::setNozzle)
                }
                TempRow(R.string.bed_target, st.bedTarget) {
                    dialog = SettingsDialog.Temperature(R.string.bed_target, st.bedTarget.toInt(), 110, c::setBed)
                }

                Section(R.string.section_print)
                val preset = SPEED_PRESETS.firstOrNull { it.first == st.print.speedPct }?.second
                ListItem(
                    headlineContent = { Text(stringResource(R.string.print_speed)) },
                    supportingContent = {
                        Text(
                            if (preset != null) stringResource(R.string.speed_option, stringResource(preset), st.print.speedPct)
                            else stringResource(R.string.percent, st.print.speedPct),
                        )
                    },
                    modifier = Modifier.clickable { dialog = SettingsDialog.Speed(st.print.speedPct) },
                )

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
            }

            Section(R.string.section_notifications)
            ListItem(
                headlineContent = { Text(stringResource(R.string.notify_title)) },
                supportingContent = { Text(stringResource(R.string.notify_body)) },
                trailingContent = { Switch(notify, onCheckedChange = null) },
                modifier = Modifier.toggleable(notify, role = Role.Switch, onValueChange = setNotify),
            )
        }
    }
    when (val d = dialog) {
        null -> {}
        is SettingsDialog.Temperature -> TempDialog(d.copy(set = { v -> d.set(v).also(command) })) { dialog = null }
        is SettingsDialog.Speed -> SpeedDialog(d.current, { command(c.setSpeed(it)) }) { dialog = null }
        SettingsDialog.Home -> HomeDialog({ command(c.home(it)) }) { dialog = null }
    }
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
    val send = { drag?.let { command(c.setFans(it.first, it.second, it.third)) } }
    FanSlider(R.string.fan_part, v.first, { drag = v.copy(first = it) }, { send() })
    FanSlider(R.string.fan_aux, v.second, { drag = v.copy(second = it) }, { send() })
    FanSlider(R.string.fan_chamber, v.third, { drag = v.copy(third = it) }, { send() })
}

@Composable
private fun FanSlider(label: Int, value: Int, onChange: (Int) -> Unit, onDone: () -> Unit) {
    val name = stringResource(label)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.percent, value),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value.toFloat(), { onChange(it.roundToInt()) }, Modifier.semantics { contentDescription = name },
            valueRange = 0f..100f, onValueChangeFinished = onDone,
        )
    }
}

@Composable
private fun TempDialog(d: SettingsDialog.Temperature, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(d.current.toString()) }
    val value = text.toIntOrNull()?.takeIf { it in 0..d.max }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(d.label)) },
        text = {
            OutlinedTextField(
                text, { text = it.filter(Char::isDigit).take(3) },
                label = { Text(stringResource(R.string.temp_unit)) },
                supportingText = { Text(stringResource(R.string.temp_range, d.max)) },
                singleLine = true, isError = value == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
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
                            Modifier.fillMaxWidth().clickable { home(axis); onDismiss() }.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
