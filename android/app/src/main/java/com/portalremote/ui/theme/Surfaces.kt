package com.portalremote.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The card/panel face, in one place — see docs/design-system.md §5.
 *
 * A filled card and the surface under it are one step apart on the neutral ramp, which is
 * a step a phone screen in daylight loses entirely; every card in this app therefore
 * carries a hairline edge as well as a fill. Doing it per call site is how the two halves
 * of a design system drift, so both the fill and the edge come from here — and both now
 * come from the instrument palette, so a `Card` and a [HudPanel] are the same object with
 * and without a bracket.
 *
 * Prefer [HudPanel] for anything that names its contents; this stays for the cases where
 * a plain filled surface is genuinely all that's wanted.
 */
@Composable
fun portalCardColors(): CardColors = CardDefaults.cardColors(
    containerColor = PortalRemoteTheme.hud.panel,
    contentColor = MaterialTheme.colorScheme.onSurface,
)

/** The hairline in [portalCardColors]'s note. Pass to `Card(border = …)`. */
@Composable
fun portalCardBorder(): BorderStroke = BorderStroke(1.dp, PortalRemoteTheme.hud.edge)

/**
 * A drop shadow tinted with the accent instead of black.
 *
 * Black shadow on a near-black surface is invisible, which is why dark UIs usually give
 * up on elevation and lose the depth cue with it; a colored one survives both themes and
 * reads as the surface glowing rather than as a grey smudge. Draw only — no layout, no
 * extra composable — so §6's transform/alpha budget is untouched.
 *
 * Reserved for the surfaces that are *doing* something (the nav capsule, a primary
 * action). On everything else it would be the ornament §2 rule 5 argues against.
 */
fun Modifier.accentGlow(
    color: Color,
    shape: Shape,
    elevation: Dp = 12.dp,
): Modifier = shadow(
    elevation = elevation,
    shape = shape,
    ambientColor = color,
    spotColor = color,
)
