package com.portalremote.ui

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.portalremote.data.SavedHost
import com.portalremote.net.ShareEntry
import com.portalremote.ui.theme.Motion

/** The two directions things move between the phone and the PC. */
internal enum class TransferMode(val label: String, val icon: ImageVector) {
    SHARE("Share", Icons.Filled.SwapVert),
    FILES("Files", Icons.Filled.Folder),
}

/**
 * Moving things between the two machines — the share thread, and the PC's shared folder.
 *
 * One tab because they answer the same question ("get this over there") by two routes:
 * Share is the conversation of links and text you push at the moment you have them,
 * Files is the folder you go and fetch from. Keeping them apart cost two bottom-nav
 * slots to express a distinction people were already making by guessing.
 *
 * Both halves run their list under the floating nav capsule (the `underGlass` pair in
 * RemoteScreen), so each takes [bottomInset] and re-applies it to its own content rather
 * than being padded from above.
 */
@Composable
fun TransferScreen(
    host: SavedHost,
    shares: List<ShareEntry>,
    onShareText: (String) -> Unit,
    onShareUri: (Uri) -> Unit,
    onRetryShare: (Long) -> Unit,
    bottomInset: Dp = 0.dp,
) {
    var mode by rememberSaveable { mutableStateOf(TransferMode.SHARE) }

    Column(modifier = Modifier.fillMaxSize()) {
        PortalSubTabRow(
            entries = TransferMode.entries,
            selected = mode,
            label = { it.label },
            icon = { it.icon },
            onSelect = { mode = it },
        )

        Crossfade(
            targetState = mode,
            animationSpec = tween(Motion.TabSwitchDurationMs, easing = Motion.EaseOut),
            modifier = Modifier.fillMaxSize(),
            label = "transfer-mode",
        ) { selected ->
            Box(modifier = Modifier.fillMaxSize()) {
                when (selected) {
                    TransferMode.SHARE -> ShareScreen(
                        host = host,
                        shares = shares,
                        onShareText = onShareText,
                        onShareUri = onShareUri,
                        onRetry = onRetryShare,
                        bottomInset = bottomInset,
                    )
                    TransferMode.FILES -> FilesScreen(
                        host = host,
                        bottomInset = bottomInset,
                    )
                }
            }
        }
    }
}
