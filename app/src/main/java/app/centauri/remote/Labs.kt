package app.centauri.remote

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Features built from other clients' payloads but not yet run on a real printer; off until switched on. */
enum class Lab(@StringRes val title: Int, @StringRes val body: Int) {
    PRINT_STEPS(R.string.lab_steps_title, R.string.lab_steps_body),
    CANVAS_TRAY(R.string.lab_tray_title, R.string.lab_tray_body),
    USB_STORAGE(R.string.lab_usb_title, R.string.lab_usb_body),
    TIMELAPSES(R.string.lab_timelapse_title, R.string.lab_timelapse_body),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabsScreen(on: Set<Lab>, firmware: String, onToggle: (Lab, Boolean) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val open = rememberOpenLink()
    val version = appVersion()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_labs)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).readableWidth().verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.labs_intro), Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Lab.entries.forEach { lab ->
                val enabled = lab in on
                ListItem(
                    headlineContent = { Text(stringResource(lab.title)) },
                    supportingContent = { Text(stringResource(lab.body)) },
                    trailingContent = { Switch(enabled, onCheckedChange = null) },
                    modifier = Modifier.toggleable(enabled, role = Role.Switch) { onToggle(lab, it) },
                )
                // Labs exist to get hardware confirmation, so each one asks for it.
                val spoken = stringResource(R.string.lab_feedback) + ": " + stringResource(lab.title)
                TextButton(
                    onClick = { open(issueUrl("[Lab] " + lab.name.lowercase().replace('_', ' '), firmware, version)) },
                    modifier = Modifier.padding(start = 4.dp).semantics { contentDescription = spoken },
                ) { Text(stringResource(R.string.lab_feedback)) }
            }
        }
    }
}
