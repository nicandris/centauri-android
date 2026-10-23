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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Features built from other clients' payloads but not yet run on a real printer; off until switched on. */
enum class Lab(@StringRes val title: Int, @StringRes val body: Int) {
    PRINT_STEPS(R.string.lab_steps_title, R.string.lab_steps_body),
    CANVAS_TRAY(R.string.lab_tray_title, R.string.lab_tray_body),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabsScreen(on: Set<Lab>, onToggle: (Lab, Boolean) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_labs)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back)) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
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
            }
        }
    }
}
