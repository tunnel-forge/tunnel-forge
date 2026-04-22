package io.github.evokelektrique.tunnelforge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsSupportTest {

    @Test
    fun `dns-over-https accepts host plus fixed path and normalizes to hostname`() {
        val sanitized =
            DnsConfigSupport.sanitize(
                listOf(
                    DnsServerConfig(
                        host = "wikimedia-dns.org/dns-query",
                        protocol = DnsProtocol.dnsOverHttps,
                    ),
                ),
            )

        assertEquals(1, sanitized.size)
        assertEquals("wikimedia-dns.org", sanitized.single().host)
        assertTrue(
            DnsConfigSupport.isValid(
                DnsServerConfig(
                    host = "wikimedia-dns.org/dns-query",
                    protocol = DnsProtocol.dnsOverHttps,
                ),
            ),
        )
    }

    @Test
    fun `dns-over-https rejects full urls and custom paths`() {
        assertFalse(
            DnsConfigSupport.isValid(
                DnsServerConfig(
                    host = "https://wikimedia-dns.org/dns-query",
                    protocol = DnsProtocol.dnsOverHttps,
                ),
            ),
        )
        assertFalse(
            DnsConfigSupport.isValid(
                DnsServerConfig(
                    host = "wikimedia-dns.org/custom-path",
                    protocol = DnsProtocol.dnsOverHttps,
                ),
            ),
        )
    }

    @Test
    fun validationMessageDoesNotEchoInputValue() {
        assertEquals(
            "DNS 1 must be a hostname for DNS-over-HTTPS",
            DnsConfigSupport.validationMessage(
                "DNS 1",
                DnsServerConfig(
                    host = "wikimedia-dns.org/custom-path",
                    protocol = DnsProtocol.dnsOverHttps,
                ),
            ),
        )
    }
}
