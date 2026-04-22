package io.github.evokelektrique.tunnelforge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsSupportTest {

    @Test
    fun `dns-over-https accepts custom endpoint path and preserves it`() {
        val sanitized =
            DnsConfigSupport.sanitize(
                listOf(
                    DnsServerConfig(
                        host = "wikimedia-dns.org/custom-path?dns=1",
                        protocol = DnsProtocol.dnsOverHttps,
                    ),
                ),
            )

        assertEquals(1, sanitized.size)
        assertEquals("wikimedia-dns.org/custom-path?dns=1", sanitized.single().host)
        assertTrue(
            DnsConfigSupport.isValid(
                DnsServerConfig(
                    host = "wikimedia-dns.org/custom-path?dns=1",
                    protocol = DnsProtocol.dnsOverHttps,
                ),
            ),
        )
    }

    @Test
    fun `dns-over-https accepts full https urls`() {
        val sanitized =
            DnsConfigSupport.sanitize(
                listOf(
                    DnsServerConfig(
                        host = "https://wikimedia-dns.org/custom-path?dns=1",
                        protocol = DnsProtocol.dnsOverHttps,
                    ),
                ),
            )

        assertEquals(1, sanitized.size)
        assertEquals("https://wikimedia-dns.org/custom-path?dns=1", sanitized.single().host)
        assertTrue(
            DnsConfigSupport.isValid(
                DnsServerConfig(
                    host = "https://wikimedia-dns.org/custom-path?dns=1",
                    protocol = DnsProtocol.dnsOverHttps,
                ),
            ),
        )
    }

    @Test
    fun `dns-over-https rejects non-https urls`() {
        assertFalse(
            DnsConfigSupport.isValid(
                DnsServerConfig(
                    host = "http://wikimedia-dns.org/dns-query",
                    protocol = DnsProtocol.dnsOverHttps,
                ),
            ),
        )
    }

    @Test
    fun validationMessageDoesNotEchoInputValue() {
        assertEquals(
            "DNS 1 must be a hostname or HTTPS URL for DNS-over-HTTPS",
            DnsConfigSupport.validationMessage(
                "DNS 1",
                DnsServerConfig(
                    host = "ftp://wikimedia-dns.org/custom-path",
                    protocol = DnsProtocol.dnsOverHttps,
                ),
            ),
        )
    }

    @Test
    fun `dns-over-https request uses configured endpoint target`() {
        val output = ByteArrayOutputStream()

        val response =
            exchangeDnsOverHttps(
                ByteArrayInputStream(
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Length: 2\r\n" +
                            "\r\nOK"
                    ).toByteArray(),
                ),
                output,
                byteArrayOf(0x01, 0x02),
                ResolvedDnsServerConfig(
                    host = "https://wikimedia-dns.org/custom-path?dns=1",
                    protocol = DnsProtocol.dnsOverHttps,
                    resolvedIpv4 = "1.1.1.1",
                    tlsHostname = "wikimedia-dns.org",
                    requestAuthority = "wikimedia-dns.org",
                    requestPath = "/custom-path?dns=1",
                ),
            )

        assertEquals("OK", response.decodeToString())
        val request = output.toString(Charsets.US_ASCII.name())
        assertTrue(request.contains("POST /custom-path?dns=1 HTTP/1.1"))
        assertTrue(request.contains("Host: wikimedia-dns.org"))
    }
}
