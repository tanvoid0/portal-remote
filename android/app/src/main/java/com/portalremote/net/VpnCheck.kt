package com.portalremote.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * True when this phone's traffic is going through a VPN.
 *
 * Worth checking because a VPN hides itself in every symptom it causes. The PC can
 * still reach the phone, so the network looks fine from that end; but anything the
 * *phone* opens is routed into the tunnel and never reaches a PC sitting on the
 * same Wi-Fi. What the user sees is a PC that won't connect, which sends them
 * hunting through Windows Firewall — where they find nothing wrong, because there
 * is nothing wrong there.
 */
fun isVpnActive(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val active = manager.activeNetwork ?: return false
    val caps = manager.getNetworkCapabilities(active) ?: return false
    // Both directions of the same question: a VPN transport on the active network,
    // or an active network that has been marked as not-not-VPN. Always-on VPNs show
    // up as the latter on some OEM builds.
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
}
