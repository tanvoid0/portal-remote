package com.portalremote.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Type scale — see docs/design-system.md §4. System font (Roboto/Google Sans), no
// custom FontFamily. Four semantic tiers (Display/Title/Body/Label), each with three
// M3 sizes so existing call sites (titleMedium, bodySmall, ...) land on the same family.

private val Display = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal)
private val Title = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold)
private val Body = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal)
private val Label = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium)

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

    labelLarge = Label.copy(fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp),
    labelMedium = Label.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp),
    labelSmall = Label.copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.1.sp),
)
