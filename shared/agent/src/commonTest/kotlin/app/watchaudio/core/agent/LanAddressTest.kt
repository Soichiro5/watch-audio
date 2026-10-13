package app.watchaudio.core.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The phone connects only to addresses on a local network or VPN (#184). */
class LanAddressTest {
    @Test
    fun localAddressesAreAllowed() {
        listOf(
            "10.0.2.2", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.1.20", "169.254.10.1",
            "100.64.0.1", "100.127.255.254", "127.0.0.1",
            "fe80::1", "fe80::1%wlan0", "[fe80::abcd:1]", "fd7a:115c:a1e0::1", "fc00::", "::1",
        ).forEach { assertTrue(isLanHost(it), it) }
    }

    @Test
    fun publicAddressesAndNamesAreRefused() {
        listOf(
            "8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.0.1", "100.63.255.255", "100.128.0.1", "203.0.113.5",
            "2001:db8::1", "2606:4700::1111", "::", "::ffff:192.168.1.1",
            "example.com", "laptop.local", "localhost", "", "1.2.3", "1.2.3.4.5", "256.1.1.1", "01234.1.1.1",
            "fe80:::1", "1:2:3:4:5:6:7:8:9", "gggg::1",
        ).forEach { assertFalse(isLanHost(it), it) }
    }
}
