package io.github.evokelektrique.tunnelforge

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class VpnDnsPacketBridge(
    private val virtualDnsIpv4: String,
    private val exchangeClient: DnsExchangeClient,
    private val logger: (Int, String) -> Unit,
    private val nativeIo: VpnDnsNativeIo = VpnDnsNativeIo.Default,
    workerCount: Int = DEFAULT_WORKER_COUNT,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val executor =
        ThreadPoolExecutor(
            workerCount.coerceAtLeast(1),
            workerCount.coerceAtLeast(1),
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity.coerceAtLeast(1)),
            NamedThreadFactory("vpn-dns-query-worker"),
            ThreadPoolExecutor.AbortPolicy(),
        )

    @Volatile
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread =
            Thread(::runLoop, "vpn-dns-packet-bridge").also {
                it.start()
            }
    }

    override fun close() {
        running.set(false)
        thread?.interrupt()
        thread = null
        executor.shutdownNow()
    }

    private fun runLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted) {
            val packet = nativeIo.readQuery(MAX_PACKET_LEN)
            if (packet == null) {
                try {
                    Thread.sleep(IDLE_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                continue
            }
            submitQueryPacket(packet)
        }
    }

    private fun submitQueryPacket(packet: ByteArray) {
        try {
            executor.execute {
                handleQueryPacket(packet)
            }
        } catch (_: RejectedExecutionException) {
            if (!running.get()) return
            logger(Log.WARN, "vpn dns query dropped: worker queue full")
            buildServfailResponsePacket(packet, virtualDnsIpv4)?.let(::queueResponse)
        }
    }

    private fun handleQueryPacket(packet: ByteArray) {
        val response =
            try {
                buildResponsePacket(packet, virtualDnsIpv4, exchangeClient)
            } catch (e: IOException) {
                logger(Log.WARN, "vpn dns upstream error=${e.message}")
                buildServfailResponsePacket(packet, virtualDnsIpv4)
            } catch (e: IllegalArgumentException) {
                logger(Log.WARN, "vpn dns malformed query error=${e.message}")
                null
            }
        if (response == null) return
        queueResponse(response)
    }

    private fun queueResponse(response: ByteArray) {
        if (!running.get()) return
        if (nativeIo.queueResponse(response) != 0) {
            if (running.get()) {
                logger(Log.WARN, "vpn dns response inject failed")
            }
        }
    }

    companion object {
        private const val MAX_PACKET_LEN = 65_535
        private const val IDLE_DELAY_MS = 50L
        private const val DEFAULT_WORKER_COUNT = 4
        private const val DEFAULT_QUEUE_CAPACITY = 64
        private const val DNS_PORT = 53
        private const val DNS_HEADER_LEN = 12

        @Throws(IOException::class)
        internal fun buildResponsePacket(
            queryPacket: ByteArray,
            virtualDnsIpv4: String,
            exchangeClient: DnsExchangeClient,
        ): ByteArray {
            val parsed = parseQueryPacket(queryPacket)
            val responsePayload = exchangeClient.exchange(parsed.payload)
            return buildUdpResponsePacket(parsed, virtualDnsIpv4, responsePayload)
        }

        internal fun buildServfailResponsePacket(
            queryPacket: ByteArray,
            virtualDnsIpv4: String,
        ): ByteArray? {
            val parsed =
                try {
                    parseQueryPacket(queryPacket)
                } catch (_: IllegalArgumentException) {
                    return null
                }
            val responsePayload = buildServfailPayload(parsed.payload)
            return buildUdpResponsePacket(parsed, virtualDnsIpv4, responsePayload)
        }

        private fun parseQueryPacket(packet: ByteArray): ParsedDnsQueryPacket {
            val ipv4 = IpPacketParser.parseIpv4(packet)
                ?: throw IllegalArgumentException("not IPv4")
            val udp = IpPacketParser.parseUdp(packet, ipv4)
                ?: throw IllegalArgumentException("not UDP")
            if (udp.destinationPort != DNS_PORT) {
                throw IllegalArgumentException("not DNS")
            }
            val end = udp.payloadOffset + udp.payloadLength
            if (end > packet.size) {
                throw IllegalArgumentException("truncated UDP payload")
            }
            return ParsedDnsQueryPacket(
                sourceIp = ipv4.sourceIp,
                sourcePort = udp.sourcePort,
                payload = packet.copyOfRange(udp.payloadOffset, end),
            )
        }

        private fun buildUdpResponsePacket(
            query: ParsedDnsQueryPacket,
            virtualDnsIpv4: String,
            responsePayload: ByteArray,
        ): ByteArray =
            UdpPacketBuilder.buildIpv4UdpPacket(
                sourceIp = virtualDnsIpv4,
                destinationIp = query.sourceIp,
                sourcePort = DNS_PORT,
                destinationPort = query.sourcePort,
                payload = responsePayload,
            )

        private fun buildServfailPayload(queryPayload: ByteArray): ByteArray {
            if (queryPayload.size < DNS_HEADER_LEN) return byteArrayOf()
            val questionEnd = skipQuestionSection(queryPayload)
            val response = ByteArray(questionEnd)
            queryPayload.copyInto(response, endIndex = questionEnd)
            response[2] = 0x81.toByte()
            response[3] = 0x82.toByte()
            response[6] = 0
            response[7] = 0
            response[8] = 0
            response[9] = 0
            response[10] = 0
            response[11] = 0
            return response
        }

        private fun skipQuestionSection(payload: ByteArray): Int {
            val qdCount = readUint16(payload, 4)
            var offset = DNS_HEADER_LEN
            repeat(qdCount) {
                offset = skipDnsName(payload, offset)
                if (offset + 4 > payload.size) return DNS_HEADER_LEN
                offset += 4
            }
            return offset.coerceIn(DNS_HEADER_LEN, payload.size)
        }

        private fun skipDnsName(payload: ByteArray, start: Int): Int {
            var offset = start
            while (offset < payload.size) {
                val len = payload[offset].toInt() and 0xff
                offset += 1
                if (len == 0) return offset
                if ((len and 0xc0) == 0xc0) {
                    return (offset + 1).coerceAtMost(payload.size)
                }
                if ((len and 0xc0) != 0 || offset + len > payload.size) return DNS_HEADER_LEN
                offset += len
            }
            return DNS_HEADER_LEN
        }

        private fun readUint16(buf: ByteArray, offset: Int): Int =
            ((buf[offset].toInt() and 0xff) shl 8) or (buf[offset + 1].toInt() and 0xff)
    }
}

internal interface VpnDnsNativeIo {
    fun readQuery(maxLen: Int): ByteArray?

    fun queueResponse(packet: ByteArray): Int

    object Default : VpnDnsNativeIo {
        override fun readQuery(maxLen: Int): ByteArray? = VpnBridge.nativeReadVpnDnsQuery(maxLen)

        override fun queueResponse(packet: ByteArray): Int = VpnBridge.nativeQueueVpnDnsResponse(packet)
    }
}

private class NamedThreadFactory(
    private val prefix: String,
) : ThreadFactory {
    private val nextId = AtomicInteger(1)

    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, "$prefix-${nextId.getAndIncrement()}")
}

private data class ParsedDnsQueryPacket(
    val sourceIp: String,
    val sourcePort: Int,
    val payload: ByteArray,
)
