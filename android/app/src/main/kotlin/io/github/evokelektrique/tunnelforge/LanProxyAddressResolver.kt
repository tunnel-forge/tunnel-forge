package io.github.evokelektrique.tunnelforge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class ProxyExposureInfo(
    val active: Boolean,
    val bindAddress: String,
    val displayAddress: String,
    val httpPort: Int,
    val socksPort: Int,
    val lanRequested: Boolean,
    val lanActive: Boolean,
    val warning: String? = null,
) {
    fun endpointSummary(httpEnabled: Boolean = true, socksEnabled: Boolean = true): String {
        val endpoints = mutableListOf<String>()
        if (httpEnabled) endpoints.add("HTTP $displayAddress:$httpPort")
        if (socksEnabled) endpoints.add("SOCKS5 $displayAddress:$socksPort")
        return endpoints.joinToString(", ")
    }

    fun listenerBindAddresses(): List<String> {
        val addresses = linkedSetOf<String>()
        if (lanActive && bindAddress != LOOPBACK) {
            addresses += LOOPBACK
        }
        addresses += bindAddress
        return addresses.toList()
    }

    companion object {
        internal const val LOOPBACK = "127.0.0.1"

        fun inactive(
            httpPort: Int = 0,
            socksPort: Int = 0,
            lanRequested: Boolean = false,
        ): ProxyExposureInfo =
            ProxyExposureInfo(
                active = false,
                bindAddress = LOOPBACK,
                displayAddress = LOOPBACK,
                httpPort = httpPort,
                socksPort = socksPort,
                lanRequested = lanRequested,
                lanActive = false,
            )

        fun loopback(
            httpPort: Int,
            socksPort: Int,
            lanRequested: Boolean,
            warning: String? = null,
            active: Boolean = true,
        ): ProxyExposureInfo =
            ProxyExposureInfo(
                active = active,
                bindAddress = LOOPBACK,
                displayAddress = LOOPBACK,
                httpPort = httpPort,
                socksPort = socksPort,
                lanRequested = lanRequested,
                lanActive = false,
                warning = warning,
            )
    }
}

internal enum class ProxyInterfaceTransport {
    HOTSPOT,
    WIFI,
    ETHERNET,
    OTHER,
    CELLULAR,
}

internal data class ProxyInterfaceCandidate(
    val interfaceName: String,
    val address: String,
    val transport: ProxyInterfaceTransport,
    val isActive: Boolean = false,
    val isPrivateAddress: Boolean = false,
)

internal object ProxyExposurePlanner {
    const val LAN_UNAVAILABLE_WARNING =
        "LAN sharing is enabled, but no shareable local IPv4 is currently available."

    fun choose(
        httpPort: Int,
        socksPort: Int,
        lanRequested: Boolean,
        candidates: List<ProxyInterfaceCandidate>,
    ): ProxyExposureInfo {
        if (!lanRequested) {
            return ProxyExposureInfo.loopback(
                httpPort = httpPort,
                socksPort = socksPort,
                lanRequested = false,
            )
        }
        val chosen =
            candidates
                .asSequence()
                .filter(::isShareable)
                .sortedWith(
                    compareBy<ProxyInterfaceCandidate>(
                        { it.transport.rank() },
                        { if (it.isActive) 0 else 1 },
                        { if (it.isPrivateAddress) 0 else 1 },
                        { it.interfaceName },
                        { it.address },
                    ),
                ).firstOrNull()
                ?: return ProxyExposureInfo.loopback(
                    httpPort = httpPort,
                    socksPort = socksPort,
                    lanRequested = true,
                    warning = LAN_UNAVAILABLE_WARNING,
                )
        return ProxyExposureInfo(
            active = true,
            bindAddress = chosen.address,
            displayAddress = chosen.address,
            httpPort = httpPort,
            socksPort = socksPort,
            lanRequested = true,
            lanActive = true,
        )
    }

    private fun isShareable(candidate: ProxyInterfaceCandidate): Boolean =
        when (candidate.transport) {
            ProxyInterfaceTransport.CELLULAR -> candidate.isPrivateAddress
            else -> true
        }

    private fun ProxyInterfaceTransport.rank(): Int =
        when (this) {
            ProxyInterfaceTransport.HOTSPOT -> 0
            ProxyInterfaceTransport.WIFI -> 1
            ProxyInterfaceTransport.ETHERNET -> 2
            ProxyInterfaceTransport.OTHER -> 3
            ProxyInterfaceTransport.CELLULAR -> 4
        }
}

class LanProxyAddressResolver(
    private val context: Context,
) {
    fun resolve(
        httpPort: Int,
        socksPort: Int,
        lanRequested: Boolean,
    ): ProxyExposureInfo =
        ProxyExposurePlanner.choose(
            httpPort = httpPort,
            socksPort = socksPort,
            lanRequested = lanRequested,
            candidates = collectCandidates(),
        )

    internal fun collectCandidates(): List<ProxyInterfaceCandidate> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivity?.activeNetwork
        val activeInterface =
            activeNetwork?.let { network ->
                connectivity.getLinkProperties(network)?.interfaceName
            }
        val metadataByInterface = linkedMapOf<String, ProxyInterfaceMetadata>()
        if (connectivity != null && activeNetwork != null) {
            val capabilities = connectivity.getNetworkCapabilities(activeNetwork)
            val linkProperties = connectivity.getLinkProperties(activeNetwork)
            if (capabilities != null &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                linkProperties != null
            ) {
                val interfaceName = linkProperties.interfaceName
                if (interfaceName != null && !interfaceName.isIgnoredProxyLanInterface()) {
                    val metadata =
                        metadataByInterface.getOrPut(interfaceName) {
                            ProxyInterfaceMetadata(
                                transport = resolveTransport(interfaceName, capabilities),
                                isActive = interfaceName == activeInterface,
                            )
                        }
                    metadata.isActive = metadata.isActive || interfaceName == activeInterface
                    for (linkAddress in linkProperties.linkAddresses) {
                        val address = linkAddress.address as? Inet4Address ?: continue
                        if (!address.isUsableProxyLanAddress()) continue
                        metadata.addresses.add(address.hostAddress ?: continue)
                    }
                }
            }
        }
        return collectInterfaceCandidates(activeInterface, metadataByInterface)
    }

    private fun collectInterfaceCandidates(
        activeInterface: String?,
        metadataByInterface: Map<String, ProxyInterfaceMetadata>,
    ): List<ProxyInterfaceCandidate> {
        val out = mutableListOf<ProxyInterfaceCandidate>()
        val seen = mutableSetOf<String>()
        for ((interfaceName, metadata) in metadataByInterface) {
            for (address in metadata.addresses) {
                addCandidate(
                    out = out,
                    seen = seen,
                    interfaceName = interfaceName,
                    address = address,
                    transport = metadata.transport,
                    isActive = metadata.isActive,
                    isPrivateAddress = isPrivateIpv4(address),
                )
            }
        }
        val interfaces =
            try {
                val enumeration = NetworkInterface.getNetworkInterfaces() ?: return out
                Collections.list(enumeration)
            } catch (_: Exception) {
                emptyList()
            }
        for (networkInterface in interfaces) {
            val interfaceName = networkInterface.name ?: continue
            if (interfaceName.isIgnoredProxyLanInterface()) continue
            try {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
            } catch (_: Exception) {
                continue
            }
            val transport =
                metadataByInterface[interfaceName]?.transport
                    ?: resolveTransport(interfaceName, capabilities = null)
            for (inetAddress in Collections.list(networkInterface.inetAddresses)) {
                val ipv4 = inetAddress as? Inet4Address ?: continue
                if (!ipv4.isUsableProxyLanAddress()) continue
                addCandidate(
                    out = out,
                    seen = seen,
                    interfaceName = interfaceName,
                    address = ipv4.hostAddress ?: continue,
                    transport = transport,
                    isActive = interfaceName == activeInterface,
                    isPrivateAddress = ipv4.isSiteLocalAddress,
                )
            }
        }
        return out
    }

    private fun addCandidate(
        out: MutableList<ProxyInterfaceCandidate>,
        seen: MutableSet<String>,
        interfaceName: String,
        address: String,
        transport: ProxyInterfaceTransport,
        isActive: Boolean,
        isPrivateAddress: Boolean,
    ) {
        val key = "$interfaceName/$address"
        if (!seen.add(key)) return
        out +=
            ProxyInterfaceCandidate(
                interfaceName = interfaceName,
                address = address,
                transport = transport,
                isActive = isActive,
                isPrivateAddress = isPrivateAddress,
            )
    }
}

private data class ProxyInterfaceMetadata(
    val transport: ProxyInterfaceTransport,
    var isActive: Boolean,
    val addresses: MutableSet<String> = linkedSetOf(),
)

private fun resolveTransport(
    interfaceName: String,
    capabilities: NetworkCapabilities?,
): ProxyInterfaceTransport {
    if (interfaceName.isHotspotProxyLanInterface()) return ProxyInterfaceTransport.HOTSPOT
    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
        return ProxyInterfaceTransport.WIFI
    }
    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) {
        return ProxyInterfaceTransport.ETHERNET
    }
    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
        return ProxyInterfaceTransport.CELLULAR
    }
    return when {
        interfaceName.isWifiProxyLanInterface() -> ProxyInterfaceTransport.WIFI
        interfaceName.isEthernetProxyLanInterface() -> ProxyInterfaceTransport.ETHERNET
        interfaceName.isCellularProxyLanInterface() -> ProxyInterfaceTransport.CELLULAR
        else -> ProxyInterfaceTransport.OTHER
    }
}

private fun Inet4Address.isUsableProxyLanAddress(): Boolean =
    !isAnyLocalAddress && !isLoopbackAddress && !isLinkLocalAddress

private fun isPrivateIpv4(address: String): Boolean =
    address.startsWith("10.") ||
        address.startsWith("192.168.") ||
        address.startsWith("172.16.") ||
        address.startsWith("172.17.") ||
        address.startsWith("172.18.") ||
        address.startsWith("172.19.") ||
        address.startsWith("172.20.") ||
        address.startsWith("172.21.") ||
        address.startsWith("172.22.") ||
        address.startsWith("172.23.") ||
        address.startsWith("172.24.") ||
        address.startsWith("172.25.") ||
        address.startsWith("172.26.") ||
        address.startsWith("172.27.") ||
        address.startsWith("172.28.") ||
        address.startsWith("172.29.") ||
        address.startsWith("172.30.") ||
        address.startsWith("172.31.")

private fun String.isIgnoredProxyLanInterface(): Boolean {
    val normalized = lowercase()
    return normalized == "lo" ||
        normalized.startsWith("tun") ||
        normalized.startsWith("ppp") ||
        normalized.startsWith("ipsec") ||
        normalized.startsWith("wg") ||
        normalized.startsWith("tap") ||
        normalized.startsWith("clat") ||
        normalized.startsWith("dummy")
}

private fun String.isHotspotProxyLanInterface(): Boolean {
    val normalized = lowercase()
    return normalized.startsWith("ap") ||
        normalized.startsWith("swlan") ||
        normalized.startsWith("rndis")
}

private fun String.isWifiProxyLanInterface(): Boolean {
    val normalized = lowercase()
    return normalized.startsWith("wlan") || normalized.startsWith("wifi")
}

private fun String.isEthernetProxyLanInterface(): Boolean {
    val normalized = lowercase()
    return normalized.startsWith("eth")
}

private fun String.isCellularProxyLanInterface(): Boolean {
    val normalized = lowercase()
    return normalized.startsWith("rmnet") ||
        normalized.startsWith("ccmni") ||
        normalized.startsWith("pdp") ||
        normalized.startsWith("v4-rmnet")
}
