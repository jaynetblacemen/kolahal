package com.mtwabpin.kolahal.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class NetTest {
    private fun local(ip: String) = Net.isLocal(InetAddress.getByName(ip))

    @Test fun localAddresses() {
        for (ip in listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.1.1", "100.108.239.10", "::1", "fd12::1", "fe80::1", "0.0.0.0")) {
            assertTrue(ip, local(ip))
        }
    }

    @Test fun publicAddresses() {
        for (ip in listOf("8.8.8.8", "1.1.1.1", "100.128.0.1", "172.32.0.1", "2606:4700::1111")) {
            assertFalse(ip, local(ip))
        }
    }

    /** The networks the phone is on: a prefix's first bits, the same family. */
    @Test fun anAddressIsInItsNetworksPrefix() {
        fun bytes(ip: String) = InetAddress.getByName(ip).address
        val home = bytes("2a02:1234:5678:9a00::")
        assertTrue(Net.inPrefix(bytes("2a02:1234:5678:9a00::1"), home, 64))
        assertTrue(Net.inPrefix(bytes("2a02:1234:5678:9a00:ffff:ffff:ffff:ffff"), home, 64))
        assertFalse(Net.inPrefix(bytes("2a02:1234:5678:9a01::1"), home, 64))
        // A length that isn't a whole byte.
        assertTrue(Net.inPrefix(bytes("203.0.113.130"), bytes("203.0.113.128"), 25))
        assertFalse(Net.inPrefix(bytes("203.0.113.127"), bytes("203.0.113.128"), 25))
        // Another family, or no length, never matches.
        assertFalse(Net.inPrefix(bytes("203.0.113.130"), home, 64))
        assertFalse(Net.inPrefix(bytes("8.8.8.8"), bytes("8.8.8.8"), 0))
    }

    /** A user's scripts all failed with a VPN like the first ones on, as every public address counted as local. */
    @Test fun aVpnsRoutesCountOnlyIfTheyDontCarryTheInternet() {
        fun routes(vararg r: Pair<String, Int>) = r.map { (a, l) -> InetAddress.getByName(a).address to l }
        fun left(vararg r: Pair<String, Int>) = Net.narrowRoutes(routes(*r)).map { InetAddress.getByAddress(it.first).hostAddress to it.second }
        assertEquals(emptyList<Any>(), left("0.0.0.0" to 0))
        assertEquals(emptyList<Any>(), left("0.0.0.0" to 1, "128.0.0.0" to 1))
        // All but the private ranges, as WireGuard's "exclude private IPs" lists it.
        assertEquals(emptyList<Any>(), left("0.0.0.0" to 5, "8.0.0.0" to 7, "11.0.0.0" to 8, "12.0.0.0" to 6, "16.0.0.0" to 4, "32.0.0.0" to 3, "64.0.0.0" to 2, "128.0.0.0" to 3, "160.0.0.0" to 5))
        assertEquals(emptyList<Any>(), left("::" to 0))
        assertEquals(emptyList<Any>(), left("2000::" to 3))
        assertEquals(listOf("11.0.0.0" to 8), left("11.0.0.0" to 8))
        assertEquals(listOf("203.0.113.0" to 24, "198.51.100.0" to 24), left("203.0.113.0" to 24, "198.51.100.0" to 24))
        assertEquals(1, Net.narrowRoutes(routes("2001:db8:1:2::" to 64)).size)
        // By family: an IPv4 tunnel doesn't make a home's IPv6 network less local, and Android blocks a
        // family a VPN doesn't use with a route to all of it (`::/0`), which doesn't touch the other.
        assertEquals(1, Net.narrowRoutes(routes("0.0.0.0" to 1, "128.0.0.0" to 1, "2001:db8:1:2::" to 64)).size)
        assertEquals(listOf("11.0.0.0" to 8), left("11.0.0.0" to 8, "::" to 0))
    }

    @Test fun webLinksOnly() {
        assertEquals("https://example.com/a", Net.webUrl(" https://example.com/a "))
        assertNull(Net.webUrl("upi://pay?pa=x@y&am=9"))
        assertNull(Net.webUrl("tel:*21*123#"))
        assertNull(Net.webUrl("intent://x#Intent;end"))
        assertNull(Net.webUrl("file:///sdcard/x"))
    }
}
