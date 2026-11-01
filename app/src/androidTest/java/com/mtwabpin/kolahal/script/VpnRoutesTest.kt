package com.mtwabpin.kolahal.script

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.IBinder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * With a real VPN up whose routes split the internet in two, as VPNs that leave the local network
 * alone do. A user's scripts all failed with one on: "zenquotes.io is this phone or the local network".
 * And with narrower ones, whose networks must stay out of a script's reach.
 */
@RunWith(AndroidJUnit4::class)
class VpnRoutesTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(60)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private var vpn: TestVpn? = null
    private val connected = CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            vpn = (service as TestVpn.Local).vpn
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {}
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { FileInputStream(it.fileDescriptor).readBytes() }
    }

    private fun vpnIsDefault() = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            assertTrue(what, SystemClock.uptimeMillis() < until)
            SystemClock.sleep(50)
        }
    }

    @Before fun bringItUp() {
        shell("appops set ${app.packageName} ACTIVATE_VPN allow")
        assumeTrue("this device won't let a test start a VPN", VpnService.prepare(app) == null)
        app.bindService(Intent(app, TestVpn::class.java), connection, Context.BIND_AUTO_CREATE)
        assertTrue("the test VPN never bound", connected.await(5, TimeUnit.SECONDS))
        assertTrue("the test VPN didn't come up", vpn!!.up("0.0.0.0" to 1, "128.0.0.0" to 1))
        waitFor("the VPN never became the default network", ::vpnIsDefault)
        // Time for the networks' callbacks to arrive.
        SystemClock.sleep(1_000)
    }

    @After fun takeItDown() {
        vpn?.down()
        runCatching { app.unbindService(connection) }
        runCatching { waitFor("the VPN stayed the default network") { !vpnIsDefault() } }
        shell("appops set ${app.packageName} ACTIVATE_VPN default")
    }

    @Test fun theInternetStaysPublic() {
        for (ip in listOf("164.90.138.201", "8.8.8.8", "1.1.1.1")) {
            assertFalse("$ip counts as local", Net.isLocal(InetAddress.getByName(ip)))
        }
    }

    /** The VPN here carries nothing, so the request fails, but it's not refused as local. */
    @Test fun aScriptsRequestIsntRefusedAsLocal() {
        val client = Net.publicClient.newBuilder().callTimeout(2, TimeUnit.SECONDS).build()
        val call = client.newCall(Request.Builder().url("https://164.90.138.201/").build())
        val message = runCatching { call.execute().close() }.exceptionOrNull()?.message.orEmpty()
        assertFalse(message, "local network" in message)
    }

    /** On an IPv6 home network the other devices have public addresses: a VPN doesn't make them any less local. */
    @Suppress("DEPRECATION")
    @Test fun theNetworksUnderTheVpnStayLocal() {
        val under = cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false }
            .filter { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true }
            .flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }
        assertTrue("no network under the VPN", under.isNotEmpty())
        for (link in under) {
            assertTrue("${link.address} isn't among the phone's networks", Net.links.any { (prefix, length) -> Net.inPrefix(link.address.address, prefix, length) })
        }
    }

    /** Its own address is this phone, on public addresses too, whatever it carries. */
    @Test fun aVpnsOwnAddressIsLocal() {
        assertTrue(vpn!!.up("0.0.0.0" to 1, "128.0.0.0" to 1, address = "203.0.113.7"))
        waitFor("its own address isn't local") { Net.isLocal(InetAddress.getByName("203.0.113.7")) }
        assertFalse("its neighbour counts as local", Net.isLocal(InetAddress.getByName("203.0.113.8")))
        assertFalse("the internet counts as local", Net.isLocal(InetAddress.getByName("8.8.8.8")))
    }

    /** Back home or to work on public addresses: what's on it is as local as a private range would be. */
    @Test fun aNarrowVpnsNetworkIsLocal() {
        assertTrue(vpn!!.up("198.51.100.0" to 24, "2001:db8:1:2::" to 64, address = "203.0.113.7"))
        for (ip in listOf("198.51.100.9", "2001:db8:1:2::10", "203.0.113.7")) {
            waitFor("$ip isn't local") { Net.isLocal(InetAddress.getByName(ip)) }
        }
        assertFalse("the internet counts as local", Net.isLocal(InetAddress.getByName("8.8.8.8")))
        // Gone with the VPN.
        vpn!!.down()
        waitFor("198.51.100.9 stayed local") { !Net.isLocal(InetAddress.getByName("198.51.100.9")) }
    }
}
