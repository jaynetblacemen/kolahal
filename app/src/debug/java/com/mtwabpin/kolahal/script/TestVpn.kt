package com.mtwabpin.kolahal.script

import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor

/** A VPN for on-device tests: it takes the routes it's given and carries nothing. */
class TestVpn : VpnService() {
    inner class Local : Binder() {
        val vpn get() = this@TestVpn
    }

    private var tun: ParcelFileDescriptor? = null

    /** Whether it came up. Called again, it takes the new routes and address. */
    fun up(vararg routes: Pair<String, Int>, address: String = "10.111.222.1"): Boolean {
        val builder = Builder().setSession("test").addAddress(address, 32)
        routes.forEach { (to, length) -> builder.addRoute(to, length) }
        val before = tun
        tun = builder.establish()
        before?.close()
        return tun != null
    }

    fun down() {
        tun?.close()
        tun = null
    }

    override fun onBind(intent: Intent?): IBinder? = if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else Local()

    override fun onDestroy() {
        down()
        super.onDestroy()
    }
}
