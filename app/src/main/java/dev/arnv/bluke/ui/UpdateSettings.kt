package dev.arnv.bluke.ui

import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.arnv.bluke.update.GitHubRelease
import dev.arnv.bluke.update.UpdateCheckResult
import dev.arnv.bluke.update.UpdateChecker
import dev.arnv.bluke.update.UpdateInterval
import dev.arnv.bluke.update.isUpdateCheckEnabled
import dev.arnv.bluke.update.updateCheckInterval
import dev.arnv.bluke.update.updateLastChecked
import kotlinx.coroutines.launch

/** The "Updates" group on the About screen: opt-in switch, frequency and a manual check. */
@Composable
fun UpdateSettingsSection(currentVersion: String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(prefs.isUpdateCheckEnabled) }
    var interval by remember { mutableStateOf(prefs.updateCheckInterval) }
    var lastChecked by remember { mutableLongStateOf(prefs.updateLastChecked) }
    var checking by remember { mutableStateOf(false) }
    var choosingInterval by remember { mutableStateOf(false) }
    var foundRelease by remember { mutableStateOf<GitHubRelease?>(null) }

    val items = buildList {
        add(
            SettingsItemData(
                title = "Check for updates",
                subtitle = "While the app is open, ask GitHub for a new release and offer to download it. " +
                    "This is the app's only internet use.",
                icon = { Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary) },
                action = {
                    Switch(enabled, {
                        enabled = it
                        prefs.isUpdateCheckEnabled = it
                    })
                },
                onClick = {
                    enabled = !enabled
                    prefs.isUpdateCheckEnabled = enabled
                },
            ),
        )
        if (enabled) {
            add(
                SettingsItemData(
                    title = "How often",
                    subtitle = interval.label,
                    icon = { Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { choosingInterval = true },
                ),
            )
        }
        add(
            SettingsItemData(
                title = "Check now",
                subtitle = if (lastChecked > 0) {
                    "Last checked " + DateUtils.getRelativeTimeSpanString(
                        lastChecked, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                    )
                } else {
                    "Not checked yet"
                },
                icon = { Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary) },
                action = if (checking) {
                    { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
                } else {
                    null
                },
                onClick = {
                    if (!checking) {
                        checking = true
                        scope.launch {
                            val result = UpdateChecker(prefs, currentVersion).checkNow()
                            checking = false
                            lastChecked = prefs.updateLastChecked
                            when (result) {
                                is UpdateCheckResult.Available -> foundRelease = result.release
                                UpdateCheckResult.UpToDate ->
                                    Toast.makeText(context, "You have the latest version", Toast.LENGTH_SHORT).show()
                                UpdateCheckResult.Failed, UpdateCheckResult.NotDue ->
                                    Toast.makeText(context, "Couldn't reach GitHub. Try again later.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
            ),
        )
    }
    SettingsCardGroup(title = "Updates", items = items)

    if (choosingInterval) {
        AlertDialog(
            onDismissRequest = { choosingInterval = false },
            title = { Text("Check for updates") },
            text = {
                Column {
                    UpdateInterval.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    interval = option
                                    prefs.updateCheckInterval = option
                                    choosingInterval = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == interval, onClick = null)
                            Text(option.label, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingInterval = false }) { Text("Cancel") } },
        )
    }

    foundRelease?.let { release ->
        UpdateAvailableDialog(
            release = release,
            currentVersion = currentVersion,
            onDismiss = { foundRelease = null },
        )
    }
}
