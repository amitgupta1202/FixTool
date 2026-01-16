package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.CaptureStatus
import com.knapsack.fixtool.model.CorrelationIdType
import com.knapsack.fixtool.model.NetworkInterfaceInfo
import com.knapsack.fixtool.model.PacketDirection
import com.knapsack.fixtool.model.PacketTimestamp
import com.knapsack.fixtool.model.TimestampSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.pcap4j.core.NotOpenException
import org.pcap4j.core.PacketListener
import org.pcap4j.core.PcapHandle
import org.pcap4j.core.PcapNativeException
import org.pcap4j.core.PcapNetworkInterface
import org.pcap4j.core.Pcaps
import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.IpV6Packet
import org.pcap4j.packet.Packet
import org.pcap4j.packet.TcpPacket
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for capturing network packets and extracting FIX messages with accurate timestamps.
 *
 * This service wraps pcap4j to provide packet-level timestamps (~10μs accuracy) for FIX messages.
 * If packet capture is unavailable (due to permissions), it gracefully falls back to
 * application-level timestamps.
 *
 * Key responsibilities:
 * - Start/stop packet capture on specified network interface and port
 * - Parse TCP stream to extract complete FIX messages (SOH-delimited)
 * - Extract timestamps from packet headers (kernel timestamp)
 * - Extract correlation IDs from FIX messages
 * - Emit events for correlation tracking
 */
class PacketCaptureService(
    private val correlationTags: List<Int> = CorrelationIdType.allTags(),
    private val onError: ((String) -> Unit)? = null,
) {
    private val logger = LoggerFactory.getLogger(PacketCaptureService::class.java)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null
    private var handle: PcapHandle? = null

    // TCP stream reassembly buffers (keyed by "srcIP:srcPort->dstIP:dstPort")
    private val tcpBuffers = ConcurrentHashMap<String, StringBuilder>()

    // Local addresses for direction detection
    private val localAddresses = mutableSetOf<String>()

    private val _status = MutableStateFlow<CaptureStatus>(CaptureStatus.Stopped)
    val status: StateFlow<CaptureStatus> = _status.asStateFlow()

    private var currentPort: Int = 0
    private var messageCallback: ((PacketTimestamp) -> Unit)? = null

    companion object {
        private const val SNAPSHOT_LENGTH = 65536
        private const val READ_TIMEOUT_MS = 10
        private const val SOH = '\u0001' // FIX message delimiter

        /**
         * Check if packet capture is available on this system.
         * Returns a pair of (available, reason) where reason is null if available.
         */
        fun checkAvailability(): Pair<Boolean, String?> {
            return try {
                val interfaces = Pcaps.findAllDevs()
                if (interfaces.isEmpty()) {
                    Pair(false, "No network interfaces found")
                } else {
                    Pair(true, null)
                }
            } catch (e: PcapNativeException) {
                val reason =
                    when {
                        e.message?.contains("permission", ignoreCase = true) == true ->
                            "Permission denied. Run with elevated privileges."

                        e.message?.contains("libpcap", ignoreCase = true) == true ->
                            "libpcap not found. Install libpcap-dev (Linux) or use Npcap (Windows)."

                        else -> e.message ?: "Unknown pcap error"
                    }
                Pair(false, reason)
            } catch (e: UnsatisfiedLinkError) {
                Pair(false, "Native pcap library not found: ${e.message}")
            } catch (e: Exception) {
                Pair(false, "Packet capture unavailable: ${e.message}")
            }
        }
    }

    /**
     * Get list of available network interfaces for capture
     */
    fun getAvailableInterfaces(): List<NetworkInterfaceInfo> {
        return try {
            Pcaps.findAllDevs().map { nif ->
                NetworkInterfaceInfo(
                    name = nif.name,
                    displayName = nif.description ?: nif.name,
                    description = nif.description ?: "",
                    addresses =
                        nif.addresses.mapNotNull { addr ->
                            addr.address?.hostAddress
                        },
                    isLoopback = nif.isLoopBack,
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to enumerate network interfaces: ${e.message}")
            emptyList()
        }
    }

    /**
     * Auto-detect the best network interface for FIX traffic capture.
     * Prefers non-loopback interfaces with IPv4 addresses.
     */
    fun autoDetectInterface(): String? {
        val interfaces = getAvailableInterfaces()

        // Prefer non-loopback interfaces with IPv4 addresses
        return interfaces
            .filter { !it.isLoopback && it.addresses.any { addr -> !addr.contains(":") } }
            .maxByOrNull { it.addresses.size }?.name
            ?: interfaces.firstOrNull()?.name
    }

    /**
     * Start packet capture on the specified interface and port.
     *
     * @param interfaceName Network interface name (or null for auto-detect)
     * @param port FIX server port to filter
     * @param onMessage Callback for each extracted FIX message with timestamp
     * @return true if capture started successfully, false if falling back to app-level
     */
    fun startCapture(
        interfaceName: String?,
        port: Int,
        onMessage: (PacketTimestamp) -> Unit,
    ): Boolean {
        stopCapture()

        currentPort = port
        messageCallback = onMessage

        // Collect local addresses for direction detection
        collectLocalAddresses()

        val effectiveInterface = interfaceName ?: autoDetectInterface()
        if (effectiveInterface == null) {
            val reason = "No suitable network interface found"
            logger.warn(reason)
            _status.value = CaptureStatus.Fallback(reason)
            return false
        }

        return try {
            val nif =
                Pcaps.getDevByName(effectiveInterface)
                    ?: throw PcapNativeException("Interface not found: $effectiveInterface")

            handle =
                nif.openLive(
                    SNAPSHOT_LENGTH,
                    PcapNetworkInterface.PromiscuousMode.PROMISCUOUS,
                    READ_TIMEOUT_MS,
                )

            // Set BPF filter for the FIX port
            handle?.setFilter("tcp port $port", org.pcap4j.core.BpfProgram.BpfCompileMode.OPTIMIZE)

            logger.info("Starting packet capture on $effectiveInterface for port $port")
            _status.value = CaptureStatus.Running(TimestampSource.PACKET, effectiveInterface)

            captureJob =
                scope.launch {
                    try {
                        captureLoop()
                    } catch (e: Exception) {
                        if (isActive) {
                            logger.error("Capture loop error: ${e.message}", e)
                            _status.value = CaptureStatus.Error(e.message ?: "Unknown error")
                        }
                    }
                }

            true
        } catch (e: PcapNativeException) {
            val reason = extractPermissionError(e)
            logger.warn("Packet capture failed: $reason")
            _status.value = CaptureStatus.Fallback(reason)
            false
        } catch (e: Exception) {
            val reason = e.message ?: "Unknown error"
            logger.warn("Packet capture failed: $reason")
            _status.value = CaptureStatus.Fallback(reason)
            false
        }
    }

    /**
     * Stop packet capture
     */
    fun stopCapture() {
        runBlocking {
            captureJob?.cancelAndJoin()
        }
        captureJob = null

        try {
            handle?.breakLoop()
        } catch (e: NotOpenException) {
            // Handle already closed
        }
        handle?.close()
        handle = null

        tcpBuffers.clear()
        _status.value = CaptureStatus.Stopped
        logger.info("Packet capture stopped")
    }

    /**
     * Main capture loop that processes packets
     */
    private suspend fun captureLoop() {
        val h = handle ?: return

        val listener =
            PacketListener { packet ->
                try {
                    processPacket(packet)
                } catch (e: Exception) {
                    logger.debug("Error processing packet: ${e.message}")
                }
            }

        try {
            // Loop with small batch size to allow cancellation checks
            while (scope.isActive) {
                try {
                    h.loop(100, listener)
                } catch (e: InterruptedException) {
                    break
                } catch (e: NotOpenException) {
                    break
                }
            }
        } catch (e: Exception) {
            if (scope.isActive) {
                throw e
            }
        }
    }

    /**
     * Process a captured packet
     */
    private fun processPacket(packet: Packet) {
        // Extract timestamp from packet header (kernel timestamp in microseconds)
        val timestamp = handle?.getTimestamp()
        val timestampMicros =
            if (timestamp != null) {
                timestamp.time * 1000 + timestamp.nanos / 1000
            } else {
                System.currentTimeMillis() * 1000
            }

        // Extract IP and TCP layers
        val ipPacket = packet.get(IpV4Packet::class.java) ?: packet.get(IpV6Packet::class.java)
        val tcpPacket = packet.get(TcpPacket::class.java)

        if (ipPacket == null || tcpPacket == null) {
            return
        }

        val srcAddr =
            when (ipPacket) {
                is IpV4Packet -> ipPacket.header.srcAddr.hostAddress
                is IpV6Packet -> ipPacket.header.srcAddr.hostAddress
                else -> return
            }

        val dstAddr =
            when (ipPacket) {
                is IpV4Packet -> ipPacket.header.dstAddr.hostAddress
                is IpV6Packet -> ipPacket.header.dstAddr.hostAddress
                else -> return
            }

        val srcPort = tcpPacket.header.srcPort.valueAsInt()
        val dstPort = tcpPacket.header.dstPort.valueAsInt()

        // Determine direction based on local addresses and port
        val direction =
            when {
                localAddresses.contains(srcAddr) && srcPort == currentPort -> PacketDirection.SEND
                localAddresses.contains(srcAddr) && dstPort == currentPort -> PacketDirection.SEND
                localAddresses.contains(dstAddr) && dstPort == currentPort -> PacketDirection.RECEIVE
                localAddresses.contains(dstAddr) && srcPort == currentPort -> PacketDirection.RECEIVE
                srcPort == currentPort -> PacketDirection.RECEIVE // Fallback: from server port = receive
                dstPort == currentPort -> PacketDirection.SEND // Fallback: to server port = send
                else -> return
            }

        // Extract TCP payload
        val payload = tcpPacket.payload?.rawData ?: return
        if (payload.isEmpty()) {
            return
        }

        // Build stream key for TCP reassembly
        val streamKey = "$srcAddr:$srcPort->$dstAddr:$dstPort"

        // Add payload to buffer and extract complete FIX messages
        val buffer = tcpBuffers.getOrPut(streamKey) { StringBuilder() }
        buffer.append(String(payload, Charsets.ISO_8859_1))

        // Extract complete FIX messages from buffer
        extractFixMessages(buffer, timestampMicros, direction)
    }

    /**
     * Extract complete FIX messages from a TCP stream buffer
     */
    private fun extractFixMessages(
        buffer: StringBuilder,
        timestampMicros: Long,
        direction: PacketDirection,
    ) {
        val content = buffer.toString()

        // FIX messages start with "8=FIX" and end with SOH followed by checksum
        // Pattern: 8=FIX.x.x|...|10=xxx|
        var startIndex = content.indexOf("8=FIX")
        while (startIndex >= 0) {
            // Find the end of this message (checksum field ending with SOH)
            val checksumStart = content.indexOf("${SOH}10=", startIndex)
            if (checksumStart < 0) {
                // Incomplete message, keep in buffer
                break
            }

            // Find the final SOH after checksum
            val messageEnd = content.indexOf(SOH, checksumStart + 5)
            if (messageEnd < 0) {
                // Incomplete checksum, keep in buffer
                break
            }

            // Extract complete message
            val rawMessage = content.substring(startIndex, messageEnd + 1)

            // Parse message and extract correlation IDs
            processFixMessage(rawMessage, timestampMicros, direction)

            // Look for next message
            startIndex = content.indexOf("8=FIX", messageEnd + 1)
        }

        // Keep only unprocessed data in buffer
        if (startIndex > 0) {
            buffer.delete(0, startIndex)
        } else if (startIndex < 0 && buffer.length > 10000) {
            // No FIX message found and buffer is large, clear it
            buffer.clear()
        }
    }

    /**
     * Process a complete FIX message and emit callback
     */
    private fun processFixMessage(
        rawMessage: String,
        timestampMicros: Long,
        direction: PacketDirection,
    ) {
        // Extract message type (tag 35)
        val messageType = extractTagValue(rawMessage, 35) ?: "UNKNOWN"

        // Try to extract correlation IDs
        for (tag in correlationTags) {
            val correlationId = extractTagValue(rawMessage, tag)
            if (!correlationId.isNullOrBlank()) {
                val correlationType = CorrelationIdType.fromTag(tag) ?: continue

                val packetTimestamp =
                    PacketTimestamp(
                        timestampMicros = timestampMicros,
                        direction = direction,
                        correlationId = correlationId,
                        correlationType = correlationType,
                        messageType = messageType,
                        rawFixMessage = rawMessage,
                        source = TimestampSource.PACKET,
                    )

                messageCallback?.invoke(packetTimestamp)
                return // Use first found correlation ID
            }
        }
    }

    /**
     * Extract a tag value from a FIX message
     */
    private fun extractTagValue(
        message: String,
        tag: Int,
    ): String? {
        // Match tag=value where value ends with SOH or pipe
        val pattern = "(?:^|[$SOH|])$tag=([^$SOH|]+)".toRegex()
        return pattern.find(message)?.groupValues?.get(1)
    }

    /**
     * Collect local IP addresses for direction detection
     */
    private fun collectLocalAddresses() {
        localAddresses.clear()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                nif.inetAddresses?.toList()?.forEach { addr ->
                    localAddresses.add(addr.hostAddress)
                }
            }
            // Also add loopback
            localAddresses.add("127.0.0.1")
            localAddresses.add("::1")
            localAddresses.add("localhost")
        } catch (e: Exception) {
            logger.warn("Failed to collect local addresses: ${e.message}")
            // Fallback to common addresses
            localAddresses.add("127.0.0.1")
            localAddresses.add("::1")
        }
    }

    /**
     * Extract a user-friendly error message from pcap exceptions
     */
    private fun extractPermissionError(e: PcapNativeException): String {
        val message = e.message ?: "Unknown error"
        return when {
            message.contains("permission", ignoreCase = true) ||
                message.contains("Operation not permitted", ignoreCase = true) ||
                message.contains("You don't have permission", ignoreCase = true) ->
                "Permission denied. Run with elevated privileges " +
                    "(macOS: add user to 'access_bpf' group or use sudo, " +
                    "Linux: grant CAP_NET_RAW or use sudo, " +
                    "Windows: run as Administrator with Npcap)"

            message.contains("No such device", ignoreCase = true) ->
                "Network interface not found"

            else -> message
        }
    }

    /**
     * Get the current timestamp source being used
     */
    fun getCurrentTimestampSource(): TimestampSource {
        return when (val s = _status.value) {
            is CaptureStatus.Running -> s.source
            is CaptureStatus.Fallback -> TimestampSource.APPLICATION
            else -> TimestampSource.APPLICATION
        }
    }

    /**
     * Check if capture is currently running
     */
    fun isRunning(): Boolean = _status.value is CaptureStatus.Running
}

/**
 * Creates an application-level timestamp for fallback mode.
 * This is used when packet capture is unavailable.
 */
fun createApplicationTimestamp(
    direction: PacketDirection,
    correlationId: String,
    correlationType: CorrelationIdType,
    messageType: String,
    rawFixMessage: String,
): PacketTimestamp {
    return PacketTimestamp(
        timestampMicros = System.currentTimeMillis() * 1000 + (System.nanoTime() % 1_000_000) / 1000,
        direction = direction,
        correlationId = correlationId,
        correlationType = correlationType,
        messageType = messageType,
        rawFixMessage = rawFixMessage,
        source = TimestampSource.APPLICATION,
    )
}
