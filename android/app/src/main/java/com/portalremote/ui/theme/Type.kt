package com.portalremote.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Type scale — see docs/design-system.md §4. System families only, no bundled font.
//
// **Two registers, split by what the text is for.**
//
// Prose — anything you read a sentence of: a hint, an error, an assistant reply, a
// filename in a list — stays in the system sans. Monospace prose is a costume; it is
// slower to read and this app puts real sentences in front of people at exactly the
// moments they are least patient (a failed pairing, a rejected token).
//
// Instrument text — labels, units, figures, and the short imperative words on controls
// — is monospace and tracked. That is the register the whole UI is drawn in (§3), and it
// buys two concrete things: tracked uppercase separates a label from the value beneath it
// without needing a second colour, and monospace figures don't reflow under the eye, so a
// steady reading stops looking like it is twitching.
//
// The split falls on Material's own axis: Label* is instrument, Body*/Title*/Display* is
// prose. Every Button, Chip and Tab in Material3 draws its text with a Label style, which
// is why the whole app picks this up without a single call site changing.

private val Display = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal)
private val Title = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold)
private val Body = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal)

// Tracking is wider at smaller sizes, the way a machined label is: at 11sp the letters
// need the air to stay countable, at 13sp they don't.
private val Label = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)

val PortalTypography = Typography(
    displayLarge = Display.copy(fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = 0.sp),
    displayMedium = Display.copy(fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = 0.sp),
    displaySmall = Display.copy(fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.sp),

    titleLarge = Title.copy(fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.sp),
    titleMedium = Title.copy(fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    titleSmall = Title.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),

    bodyLarge = Body.copy(fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    bodyMedium = Body.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodySmall = Body.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),

    // Monospace runs wider than the sans at the same nominal size, so each of these is a
    // point smaller than the sans scale it replaced — a Button's label has to fit the
    // same 48dp target it always did.
    labelLarge = Label.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp),
    labelMedium = Label.copy(fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 1.sp),
    labelSmall = Label.copy(fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp),
)
