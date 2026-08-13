package com.portalremote.ui

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.portalremote.MainActivity
import com.portalremote.R
import com.portalremote.data.SavedHost
import com.portalremote.net.FileApi
import com.portalremote.net.ShareEntry
import com.portalremote.net.ShareKind
import kotlinx.coroutines.launch

/**
 * Quick share: the running list of things this phone and the PC have handed each
 * other, and the one button for pushing the phone's clipboard across.
 *
 * The list is deliberately the *secondary* surface. The primary way in is the
 * system share sheet (see the ACTION_SEND filter in AndroidManifest.xml) — opening
 * this app to share something would defeat the point of the feature.
 *
 * [bottomInset] is the height of the shell's nav bar, which this screen's list runs
 * *under* (it's the frosted one — see RemoteScreen). The list re-applies it as content
 * padding so the last row still clears the bar, and the FAB and snackbar sit above it.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ShareScreen(
    host: SavedHost,
    shares: List<ShareEntry>,
    onShareText: (String) -> Unit,
    onRetry: (Long) -> Unit,
    bottomInset: Dp = 0.dp,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val fileApi = remember { FileApi() }

    // Asked for here rather than at launch: this is the only screen that posts a
    // notification, and a permission prompt in front of a screen that doesn't need
    // one reads as overreach (same reasoning as PairScreen's camera prompt).
    val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        null
    }
    LaunchedEffect(Unit) {
        if (notifications?.status?.isGranted == false) notifications.launchPermissionRequest()
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHost, modifier = Modifier.padding(bottom = bottomInset))
        },
        // The bar this screen slides under is frosted, not opaque, so the FAB has to
        // clear it itself — a "Send clipboard" button read through a blur is a button
        // you can see and can't reliably hit.
        floatingActionButton = {
            ExtendedFloatingActionButton(
                modifier = Modifier.padding(bottom = bottomInset),
                onClick = {
                    val text = readClipboard(context)
                    if (text.isNullOrBlank()) {
                        scope.launch { snackbarHost.showSnackbar("Nothing on the clipboard to send.") }
                    } else {
                        onShareText(text)
                    }
                },
                icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                text = { Text("Send clipboard") },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (shares.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.SwapVert,
                    title = "Nothing shared yet",
                    detail = "Share a link or image to Portal Remote from any app, or tap " +
                        "Send clipboard. From the PC, press Ctrl+Alt+V.",
                )
            } else {
                // Content padding, not a Modifier.padding: the rows have to scroll
                // *under* the frosted bar — that translucency has to have something
                // moving behind it — while the last one still ends above it.
                LazyColumn(contentPadding = PaddingValues(bottom = bottomInset)) {
                    items(shares, key = { it.id }) { entry ->
                        ShareRow(
                            entry = entry,
                            onClick = {
                                // A queued share retries on the next reconnect by
                                // itself; the tap is for when you can see the PC is
                                // back and don't want to wait for the socket to notice.
                                if (entry.isQueued) {
                                    onRetry(entry.id)
                                } else {
                                    openShare(context, fileApi, host, entry)?.let { message ->
                                        scope.launch { snackbarHost.showSnackbar(message) }
                                    }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** Standard Material3 list item spec — see docs/design-system.md §7. */
@Composable
private fun ShareRow(entry: ShareEntry, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                entry.preview,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (entry.incoming) "from ${entry.from}" else "sent to your PC",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        // Not error-red: a share that hasn't gone yet isn't lost, it's
                        // queued, and the app sends it on the next reconnect without
                        // being asked. Colouring it as a failure would say otherwise.
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        leadingContent = {
            Icon(
                iconForShare(entry.kind),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = if (entry.incoming) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

private fun iconForShare(kind: String): ImageVector = when (kind) {
    ShareKind.LINK -> Icons.Filled.Link
    ShareKind.IMAGE -> Icons.Filled.Image
    ShareKind.FILE -> Icons.AutoMirrored.Filled.InsertDriveFile
    else -> Icons.AutoMirrored.Filled.Article
}

/**
 * What tapping a row does, per kind: open a link, download a file the PC sent, and
 * for anything else put it back on the clipboard — the text was copied when it
 * arrived, but by the time you scroll back to an older entry it no longer is.
 * Returns a message to show, or null if the action speaks for itself.
 */
private fun openShare(
    context: Context,
    fileApi: FileApi,
    host: SavedHost,
    entry: ShareEntry,
): String? {
    val path = entry.path
    if (path != null) {
        val request = DownloadManager.Request(fileApi.downloadUrl(host, path).toUri()).apply {
            addRequestHeader("Authorization", "Bearer ${host.token}")
            setTitle(entry.fileName ?: "Shared file")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, entry.fileName ?: "shared.bin")
            setAllowedOverMetered(true)
        }
        context.getSystemService(DownloadManager::class.java).enqueue(request)
        Toast.makeText(context, "Downloading ${entry.fileName}…", Toast.LENGTH_SHORT).show()
        return null
    }

    val text = entry.text ?: return null
    if (entry.kind == ShareKind.LINK) {
        // ACTION_VIEW with no chooser: the user's default browser is the right answer,
        // and this is a link they just sent themselves from the other device.
        val browse = Intent(Intent.ACTION_VIEW, text.trim().toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(browse) }
            .fold(onSuccess = { null }, onFailure = { "No app can open that link." })
    }

    copyToClipboard(context, text)
    return "Copied to clipboard"
}

// --- Clipboard and notifications ------------------------------------------------
// Live here rather than in net/ because they're this feature's *output* to the user,
// the same category as the screen above.

internal fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    // Android 13+ shows its own "copied" confirmation, so nothing more to say here.
    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Portal Remote", text)) }
}

private fun readClipboard(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context).toString()
}

private const val SHARE_CHANNEL_ID = "shares"

/**
 * Post a heads-up notification for something the PC just sent. Only fires while the
 * app is running — receiving with the app closed would need a foreground service and
 * its permanent notification, which is a worse trade than it sounds; see
 * docs/phase5-share.md.
 */
internal fun notifyShare(context: Context, entry: ShareEntry) {
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return

    manager.createNotificationChannel(
        NotificationChannel(
            SHARE_CHANNEL_ID,
            "Shares",
            // HIGH so it arrives as a heads-up banner: the whole value is noticing it
            // without going looking, and these are rare and always user-initiated.
            NotificationManager.IMPORTANCE_HIGH,
        )
    )

    val open = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val title = when (entry.kind) {
        ShareKind.LINK -> "${entry.from} shared a link"
        ShareKind.IMAGE -> "${entry.from} shared an image"
        ShareKind.FILE -> "${entry.from} shared a file"
        else -> "${entry.from} shared a note"
    }

    val notification = NotificationCompat.Builder(context, SHARE_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_portal_mark)
        .setContentTitle(title)
        .setContentText(entry.preview)
        .setStyle(NotificationCompat.BigTextStyle().bigText(entry.text ?: entry.preview))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()

    // The runtime grant is asked for on ShareScreen; if it was refused,
    // areNotificationsEnabled() above already sent us home.
    runCatching { manager.notify(entry.id.toInt(), notification) }
}

/** The human-readable name behind a content:// uri, for naming an upload. */
internal fun displayNameOf(context: Context, uri: Uri): String? {
    var cursor: Cursor? = null
    return try {
        cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        } else {
            null
        }
    } finally {
        cursor?.close()
    }
}
