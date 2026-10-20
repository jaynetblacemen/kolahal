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

    @Test fun webLinksOnly() {
        assertEquals("https://example.com/a", Net.webUrl(" https://example.com/a "))
        assertNull(Net.webUrl("upi://pay?pa=x@y&am=9"))
        assertNull(Net.webUrl("tel:*21*123#"))
        assertNull(Net.webUrl("intent://x#Intent;end"))
        assertNull(Net.webUrl("file:///sdcard/x"))
    }
}
