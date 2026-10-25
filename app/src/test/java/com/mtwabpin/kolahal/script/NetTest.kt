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

    @Test fun webLinksOnly() {
        assertEquals("https://example.com/a", Net.webUrl(" https://example.com/a "))
        assertNull(Net.webUrl("upi://pay?pa=x@y&am=9"))
        assertNull(Net.webUrl("tel:*21*123#"))
        assertNull(Net.webUrl("intent://x#Intent;end"))
        assertNull(Net.webUrl("file:///sdcard/x"))
    }
}
