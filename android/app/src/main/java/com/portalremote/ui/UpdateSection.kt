package com.portalremote.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.portalremote.net.Release
import com.portalremote.net.Updates
import kotlinx.coroutines.launch
import java.io.File

/**
 * The update half of Settings: check GitHub for a newer release, download its APK,
 * and hand it to the system installer. A sideloaded build has no store behind it,
 * so this is the only upgrade path that doesn't start with "go find the repo".
 *
 * One button that changes what it says rather than a row of them — at any moment
 * there is exactly one thing worth doing.
 */
@Composable
fun UpdateSection(currentVersion: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("App update", style = MaterialTheme.typography.bodyLarge)
        Text(
            state.describe(currentVersion),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (state is UpdateState.Busy) {
            CircularProgressIndicator(modifier = Modifier.padding(vertical = 4.dp))
            return@Column
        }

        val available = state as? UpdateState.Available
        Button(onClick = {
            scope.launch {
                if (available == null) {
                    state = UpdateState.Busy("Checking…")
                    state = runCatching { Updates.latest() }.fold(
                        onSuccess = { release ->
                            when {
                                release == null -> UpdateState.Failed("No published release to compare against.")
                                Updates.isNewer(release.version, currentVersion) -> UpdateState.Available(release)
                                else -> UpdateState.UpToDate
                            }
                        },
                        onFailure = { UpdateState.Failed("Could not reach GitHub. Check the phone's internet connection.") },
                    )
                    return@launch
                }

                // Asked for after the check, not before: someone on the newest build
                // should never see a permissions screen for an install that isn't
                // going to happen.
                if (!context.packageManager.canRequestPackageInstalls()) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    state = UpdateState.Available(
                        available.release,
                        hint = "Allow Portal Remote to install apps, then tap again",
                    )
                    return@launch
                }

                state = UpdateState.Busy("Downloading ${available.release.version}…")
                state = runCatching {
                    val apk = File(context.cacheDir, "updates/portal-remote-${available.release.version}.apk")
                    Updates.download(available.release, apk)
                    install(context, apk)
                }.fold(
                    // Left on Available: the installer is a separate process, and if
                    // the user backs out of it the same button should still offer it.
                    onSuccess = { UpdateState.Available(available.release) },
                    onFailure = { UpdateState.Failed(it.message ?: "The download failed.") },
                )
            }
        }) {
            Text(if (available == null) "Check for updates" else "Install ${available.release.version}")
        }
    }
}

private fun install(context: Context, apk: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
    context.startActivity(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}

private sealed interface UpdateState {
    data object Idle : UpdateState
    data class Busy(val label: String) : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release, val hint: String? = null) : UpdateState
    data class Failed(val reason: String) : UpdateState

    fun describe(currentVersion: String): String = when (this) {
        is Idle -> "Installed $currentVersion. Updates come from the project's GitHub releases"
        is Busy -> label
        is UpToDate -> "$currentVersion is the newest release"
        is Available -> hint ?: "Version ${release.version} is available"
        is Failed -> reason
    }
}
