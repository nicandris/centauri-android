package app.centauri.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(c: SdcpClient, st: Status?, connected: Boolean, onBack: () -> Unit, onChangePrinter: () -> Unit) {
    BackHandler(onBack = onBack)
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val close = { dialog = null }
    val notify by Hub.notifications.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Printer settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Section("Printer")
            ListItem(
                headlineContent = { Text(c.printer.name.ifEmpty { "Centauri Carbon" }) },
                supportingContent = { Text("${c.printer.ip} · ${if (connected) "Connected" else "Not connected"}") },
            )
            ListItem(headlineContent = { Text("Change printer") }, modifier = Modifier.clickable(onClick = onChangePrinter))

            if (st != null) {
                Section("Temperature")
                TempRow("Nozzle target", st.nozzleTarget) {
                    dialog = { TempDialog("Nozzle target", st.nozzleTarget.toInt(), 320, c::setNozzle, close) }
                }
                TempRow("Bed target", st.bedTarget) {
                    dialog = { TempDialog("Bed target", st.bedTarget.toInt(), 110, c::setBed, close) }
                }

                Section("Print")
                val speed = SPEED_PRESETS.firstOrNull { it.first == st.print.speedPct }?.second
                ListItem(
                    headlineContent = { Text("Print speed") },
                    supportingContent = { Text(listOfNotNull(speed, "${st.print.speedPct}%").joinToString(" · ")) },
                    modifier = Modifier.clickable { dialog = { SpeedDialog(st.print.speedPct, c::setSpeed, close) } },
                )

                Section("Fans")
                Fans(c, st)

                Section("Motion")
                ListItem(
                    headlineContent = { Text("Home axes") },
                    supportingContent = { Text(if (st.idle) "Move to the home position" else "Available when the printer is idle") },
                    colors = if (st.idle) ListItemDefaults.colors() else ListItemDefaults.colors(
                        headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        supportingColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    ),
                    modifier = Modifier.clickable(enabled = st.idle) { dialog = { HomeDialog(c::home, close) } },
                )
            }

            Section("Notifications")
            ListItem(
                headlineContent = { Text("Print progress") },
                supportingContent = { Text("Progress in the notification shade, and an alert when a print finishes or stops") },
                trailingContent = { Switch(notify, Hub::setNotifications) },
                modifier = Modifier.clickable { Hub.setNotifications(!notify) },
            )
        }
    }
    dialog?.invoke()
}

@Composable
private fun Section(title: String) {
    Text(
        title, Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun TempRow(label: String, target: Double, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(if (target > 0) "%.0f °C".format(target) else "Off") },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun Fans(c: SdcpClient, st: Status) {
    // A dragged value wins until the printer has had a few seconds to report it back.
    var drag by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    LaunchedEffect(drag) { if (drag != null) { delay(4000); drag = null } }
    val v = drag ?: Triple(st.modelFan, st.auxFan, st.boxFan)
    val send = { drag?.let { c.setFans(it.first, it.second, it.third) } }
    FanSlider("Part cooling", v.first, { drag = v.copy(first = it) }, { send() })
    FanSlider("Auxiliary", v.second, { drag = v.copy(second = it) }, { send() })
    FanSlider("Chamber", v.third, { drag = v.copy(third = it) }, { send() })
}

@Composable
private fun FanSlider(label: String, value: Int, onChange: (Int) -> Unit, onDone: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("$value%", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value.toFloat(), { onChange(it.roundToInt()) }, valueRange = 0f..100f, onValueChangeFinished = onDone)
    }
}

@Composable
private fun TempDialog(label: String, current: Int, max: Int, set: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val value = text.toIntOrNull()?.takeIf { it in 0..max }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            OutlinedTextField(
                text, { text = it.filter(Char::isDigit).take(3) },
                label = { Text("°C") }, supportingText = { Text("0 to $max. 0 turns the heater off.") },
                singleLine = true, isError = value == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(enabled = value != null, onClick = { set(value!!); onDismiss() }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Settings-style single choice: tapping an option applies it and closes. */
@Composable
private fun SpeedDialog(current: Int, set: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Print speed") },
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
                        Text("$label · $pct%", Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HomeDialog(home: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Home axes") },
        text = {
            Column {
                listOf("XYZ" to "All axes", "X" to "X axis", "Y" to "Y axis", "Z" to "Z axis").forEach { (axis, label) ->
                    Text(
                        label,
                        Modifier.fillMaxWidth().clickable { home(axis); onDismiss() }.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
