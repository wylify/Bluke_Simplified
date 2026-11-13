package dev.arnv.bluke.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import dev.arnv.bluke.update.GitHubRelease
import dev.arnv.bluke.update.plainReleaseNotes
import dev.arnv.bluke.update.skipUpdateVersion

/**
 * Asks before opening anything: the browser only opens when the user taps Download.
 * Later and tapping outside just close it; Skip stops asking about this version.
 */
@Composable
fun UpdateAvailableDialog(
    release: GitHubRelease,
    currentVersion: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val notes = remember(release) { plainReleaseNotes(release.notes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
        title = { Text("Update available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Simply Bluke ${release.version} is available. You have $currentVersion.")
                if (notes.isNotEmpty()) {
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    )
                }
                Text(
                    text = if (release.apkUrl != null) {
                        "Download opens the APK in your browser. Install it to update."
                    } else {
                        "Download opens the release page on GitHub."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                openUpdateLink(context, release)
                onDismiss()
            }) { Text("Download") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).skipUpdateVersion(release.version)
                    onDismiss()
                }) { Text("Skip") }
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        },
    )
}

/** Asked once, on first open. Nothing goes over the network unless the user taps Turn on. */
@Composable
fun UpdateOptInDialog(onAnswer: (enable: Boolean) -> Unit) {
    AlertDialog(
        // Back counts as "Not now"; a stray tap outside doesn't answer for the user.
        onDismissRequest = { onAnswer(false) },
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
        title = { Text("Check for updates?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Simply Bluke can ask GitHub for a new release while the app is open, " +
                        "about once a week, and offer to download it.",
                )
                Text(
                    "This is the app's only internet use. Nothing about you or what you type is sent. " +
                        "You can change this or how often it checks in Settings → About → Updates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Not now") } },
    )
}

private fun openUpdateLink(context: Context, release: GitHubRelease) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, release.downloadUrl.toUri()))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No browser found to open the download", Toast.LENGTH_LONG).show()
    }
}
