package com.portalremote.data

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * What this phone calls itself, for the PC to label it with.
 *
 * The user's own device name where Android exposes one ("Tanveer's S26 Ultra"), the
 * model otherwise ("SM-S948B") — a list of two phones is only useful if the rows say
 * which phone, and a model number is at least a phone somebody can recognise.
 */
fun deviceName(context: Context): String =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        ?.takeIf { it.isNotBlank() }
        ?: Build.MODEL
