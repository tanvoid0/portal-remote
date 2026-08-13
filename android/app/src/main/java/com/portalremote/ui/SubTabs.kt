package com.portalremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.portalremote.ui.theme.LocalHaptics
import com.portalremote.ui.theme.PortalRemoteTheme

/**
 * The row that sits under the title bar on a tab holding more than one screen.
 *
 * Three tabs use it now — Control (trackpad/keyboard/media/remote), Monitor
 * (screen/stats) and Transfer (share/files) — and the reason it's one composable rather
 * than three copies is that they have to look identical: the whole argument in
 * docs/design-system.md §13 for folding screens together is that the row costs 48dp
 * *once*, and three near-identical rows that drift apart is how that stops being true.
 *
 * Hand-rolled tab content rather than `Tab(text =, icon =)`: the stock pair stacks the
 * icon above the label and takes the row from 48dp to 72dp, which is 24dp of chrome for
 * the same two pieces of information.
 */
@OptIn(ExperimentalMaterial3Api::class) // PrimaryTabRow
@Composable
fun <T> PortalSubTabRow(
    entries: List<T>,
    selected: T,
    label: (T) -> String,
    icon: (T) -> ImageVector,
    onSelect: (T) -> Unit,
) {
    val haptics = LocalHaptics.current
    PrimaryTabRow(
        selectedTabIndex = entries.indexOf(selected).coerceAtLeast(0),
        containerColor = PortalRemoteTheme.hud.panel,
        // The stock divider is a full-width rule under the row, which on top of the
        // selected tab's own indicator is two lines saying one thing. The panel edge
        // below already separates the row from the screen.
        divider = {},
    ) {
        entries.forEach { entry ->
            Tab(
                selected = entry == selected,
                // Only on an actual change, like the bottom bar: re-tapping the tab you
                // are on shouldn't feel like it did something.
                onClick = {
                    if (entry != selected) {
                        haptics.tick()
                        onSelect(entry)
                    }
                },
                modifier = Modifier.height(48.dp),
                selectedContentColor = MaterialTheme.colorScheme.primary,
                // Matches the nav bar's idle grey, so the row and the bar below it read
                // as one family.
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        icon(entry),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        label(entry),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}
