package cz.maros.mobilecockpit

import android.content.Context
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

data class ScanHeadTelemetry(
    val valid: Boolean = false,
    val sequence: Int = 0,
    val flags: Int = 0,
    val elevationDeg: Float = 0f,
    val azimuthDeg: Float = 0f,
    val tofMm: Int = 0xffff,
    val ultrasonicMm: Int = 0xffff,
    val tofStatus: Int = 0,
    val updatedAt: Long = 0L
)

data class LidarBridgeTelemetry(
    val valid: Boolean = false,
    val motorOn: Boolean = false,
    val feedbackFresh: Boolean = false,
    val rpm: Float = 0f,
    val dutyPct: Int = 0,
    val targetRpm: Int = 0,
    val validPoints: Int = 0,
    val packetsPerSecond: Int = 0,
    val badPackets: Int = 0,
    val checksumErrors: Int = 0,
    val incompleteScans: Int = 0,
    val completeScans: Int = 0,
    val averageQuality: Int = 0,
    val warningPercent: Int = 0,
    val updatedAt: Long = 0L
)

data class GatewayStatus(
    val connecting: Boolean = false,
    val online: Boolean = false,
    val host: String = "",
    val packets: Long = 0,
    val badPackets: Long = 0,
    val droppedScans: Long = 0,
    val lastRxElapsed: Long = 0,
    val robotSequence: Int = 0,
    val robotFlags: Int = 0,
    val leftPwm: Int = 0,
    val rightPwm: Int = 0,
    val encLeft: Long = 0,
    val encRight: Long = 0,
    val imu: ImuTelemetry = ImuTelemetry(),
    val gps: GpsTelemetry = GpsTelemetry(),
    val scanHead: ScanHeadTelemetry = ScanHeadTelemetry(),
    val lidarBridge: LidarBridgeTelemetry = LidarBridgeTelemetry(),
    val lidarQuality: IntArray = IntArray(0),
    val odomValid: Boolean = false,
    val odomSequence: Int = 0,
    val odomLeftTicks: Long = 0,
    val odomRightTicks: Long = 0,
    val odomGyroRadS: Float = 0f,
    val odomDtMs: Int = 0
)

/**
 * MAROS Mobile Gateway v1.
 *
 * The ESP32-S3 VESC display remains the ESP-NOW radio. It forwards original
 * ESP-NOW frames in an M7/1/0x80 UDP envelope. Android never attempts to call
 * ESP-NOW directly.
 *
 * Envelope (little endian):
 *   M 7 | version=1 | type | payloadLength:u16 | transportSeq:u16 | payload
 * type 0x01 = HELLO (phone -> display)
 * type 0x80 = forwarded original ESP-NOW frame (display -> phone)
 */
class MobileGatewayClient(
    context: Context,
    private val onLidar: (LidarScan, Long, Long, Long) -> Unit,
    private val onStatus: (GatewayStatus) -> Unit
) {
    companion object {
        const val DISPLAY_UDP_PORT = 4210
        const val PHONE_UDP_PORT = 4211
        private const val TYPE_HELLO = 0x01
        private const val TYPE_FRAME = 0x80
    }

    @Suppress("unused")
    private val appContext = context.applicationContext
    @Volatile private var running = false
    @Volatile private var targetHost = ""
    @Volatile private var targetAddress: InetAddress? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var socket: DatagramSocket? = null
    private var transportSeq = 0
    private var packets = 0L
    private var bad = 0L
    private var dropped = 0L
    private var lastRx = 0L
    private var lastFrameAt = 0L
    private var scanSeq = -1
    private var fragmentMask = 0
    private var stagedRanges = IntArray(360)
    private var latestRobot = GatewayStatus()
    private var qualitySequence = -1
    private var qualityMask = 0
    private var stagedQuality = IntArray(360)

    @Synchronized fun connect(host: String) {
        disconnect()
        val clean = host.trim()
        if (clean.isEmpty()) {
            onStatus(GatewayStatus(connecting = false, online = false, host = "Neplatná adresa"))
            return
        }
        targetHost = clean
        running = true
        onStatus(GatewayStatus(connecting = true, host = clean))
        worker = thread(name = "maros-mobile-gateway", isDaemon = true) { runClient(clean) }
    }

    @Synchronized fun disconnect() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        worker?.interrupt()
        worker = null
        resetScan()
        onStatus(GatewayStatus(connecting = false, online = false, host = targetHost))
    }

    private fun runClient(host: String) {
        var s: DatagramSocket? = null
        try {
            val address = InetAddress.getByName(host)
            targetAddress = address
            val activeSocket = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 250
                bind(InetSocketAddress(PHONE_UDP_PORT))
            }
            s = activeSocket
            socket = activeSocket
            val receiveBuffer = ByteArray(512)
            var lastHello = 0L
            while (running) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastHello >= 1000L) {
                    sendHello(activeSocket, address)
                    lastHello = now
                }
                try {
                    val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    activeSocket.receive(packet)
                    val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    synchronized(this) { consumeEnvelope(bytes) }
                } catch (_: SocketTimeoutException) {
                    publishStatus()
                }
            }
        } catch (e: Exception) {
            if (running) onStatus(GatewayStatus(connecting = false, online = false, host = host))
        } finally {
            try { s?.close() } catch (_: Exception) {}
            socket = null
        }
    }

    private fun sendHello(s: DatagramSocket, host: InetAddress) {
        val b = byteArrayOf(0x4d, 0x37, 1, TYPE_HELLO.toByte(), 0, 0, 0, 0)
        try { s.send(DatagramPacket(b, b.size, host, DISPLAY_UDP_PORT)) } catch (_: Exception) {}
    }

    /** Safe, idempotent STOP request. The display converts it to its existing D0 zero command. */
    fun sendStop() = sendCommand(0x11, byteArrayOf())

    /** Existing LiDAR Bridge D7 calibration/rotation command; values follow firmware UI mapping. */
    fun sendBridgeCommand(command: Int) {
        if (command in 0..255) sendCommand(0x20, byteArrayOf(command.toByte()))
    }

    fun setLidarMotor(enabled: Boolean) {
        sendCommand(0x21, byteArrayOf(if (enabled) 1 else 0))
    }

    @Synchronized private fun sendCommand(type: Int, payload: ByteArray) {
        val s = socket ?: return
        val host = targetAddress ?: return
        val b = ByteArray(8 + payload.size)
        b[0] = 0x4d
        b[1] = 0x37
        b[2] = 1
        b[3] = type.toByte()
        b[4] = (payload.size and 0xff).toByte()
        b[5] = ((payload.size ushr 8) and 0xff).toByte()
        val seq = (++transportSeq) and 0xffff
        b[6] = (seq and 0xff).toByte()
        b[7] = ((seq ushr 8) and 0xff).toByte()
        payload.copyInto(b, 8)
        try { s.send(DatagramPacket(b, b.size, host, DISPLAY_UDP_PORT)) } catch (_: Exception) {}
    }

    @Synchronized private fun consumeEnvelope(b: ByteArray) {
        if (b.size < 8 || b[0] != 0x4d.toByte() || b[1] != 0x37.toByte() ||
            (b[2].toInt() and 0xff) != 1) {
            bad++
            publishStatus()
            return
        }
        val length = u16(b, 4)
        if (length != b.size - 8) {
            bad++
            publishStatus()
            return
        }
        val type = b[3].toInt() and 0xff
        lastRx = SystemClock.elapsedRealtime()
        if (type == 0x81 && length == 4) {
            publishStatus()
            return
        }
        if (type != TYPE_FRAME) {
            bad++
            publishStatus()
            return
        }
        val frame = b.copyOfRange(8, b.size)
        packets++
        when (frame.firstOrNull()?.toInt()?.and(0xff)) {
            0xD9 -> consumeLidar(frame)
            0xD1 -> consumeRobot(frame)
            // Forwarded sensor/scanhead/odometry/status frames remain available
            // to the raw diagnostics counter. Typed decoders must follow exact
            // source protocol structures before they are shown as engineering data.
            0xD2 -> consumeScanHead(frame)
            0xD4 -> consumeOdometry(frame)
            0xD5 -> consumeImu(frame)
            0xD6 -> consumeGps(frame)
            0xD7 -> Unit
            0xDB -> consumeLidarStatus(frame)
            0xDC -> consumeLidarQuality(frame)
            else -> bad++
        }
        publishStatus()
    }

    private fun consumeLidar(p: ByteArray) {
        if (p.size != 185) { bad++; return }
        val seq = p[1].toInt() and 0xff
        val fragment = p[2].toInt() and 0xff
        if (fragment !in 0..3) { bad++; return }
        if (scanSeq < 0 || fragmentMask == 0) {
            scanSeq = seq
            fragmentMask = 0
            stagedRanges = IntArray(360)
        } else if (seq != scanSeq) {
            val delta = (seq - scanSeq) and 0xff
            if (delta >= 128) return // late fragment from the preceding revolution
            if (fragmentMask != 0x0f) dropped++
            scanSeq = seq
            fragmentMask = 0
            stagedRanges = IntArray(360)
        }
        val offset = fragment * 90
        for (i in 0 until 90) {
            val at = 5 + i * 2
            stagedRanges[offset + i] = u16(p, at)
        }
        fragmentMask = fragmentMask or (1 shl fragment)
        if (fragmentMask == 0x0f) {
            val rpm = (p[3].toInt() and 0xff) * 2
            val duty = p[4].toInt() and 0xff
            if (stagedRanges.any { it in 80..12000 }) {
                val scan = LidarScan(stagedRanges.copyOf(), rpm, duty, seq, System.currentTimeMillis())
                onLidar(scan, packets, dropped, bad)
            } else bad++
            fragmentMask = 0
        }
    }

    private fun consumeScanHead(p: ByteArray) {
        if (p.size != 15 || p[1] != 'S'.code.toByte() || p[2] != 'H'.code.toByte() ||
            (p[14].toInt() and 0xff) != xor8(p, 0, 14)) { bad++; return }
        latestRobot = latestRobot.copy(scanHead = ScanHeadTelemetry(
            valid = true,
            sequence = p[3].toInt() and 0xff,
            flags = p[4].toInt() and 0xff,
            elevationDeg = s16(p, 5) / 10f,
            tofMm = u16(p, 7),
            ultrasonicMm = u16(p, 9),
            azimuthDeg = s16(p, 11) / 10f,
            tofStatus = p[13].toInt() and 0xff,
            updatedAt = System.currentTimeMillis()
        ))
    }

    private fun consumeOdometry(p: ByteArray) {
        if (p.size != 20 || (p[0].toInt() and 0xff) != 0xD4 ||
            crc16(p, 0, 16) != u16(p, 16)) { bad++; return }
        val dt = u16(p, 14)
        if (dt == 0 || dt > 1000) { bad++; return }
        latestRobot = latestRobot.copy(
            odomValid = true,
            odomSequence = p[1].toInt() and 0xff,
            odomLeftTicks = s32(p, 4).toLong(),
            odomRightTicks = s32(p, 8).toLong(),
            odomGyroRadS = s16(p, 12) / 1000f,
            odomDtMs = dt
        )
    }

    private fun consumeLidarStatus(p: ByteArray) {
        if (p.size != 24 || (p[0].toInt() and 0xff) != 0xDB ||
            p[1] != 'L'.code.toByte() || (p[2].toInt() and 0xff) != 1 ||
            (p[23].toInt() and 0xff) != xor8(p, 0, 23)) { bad++; return }
        val flags = p[3].toInt() and 0xff
        latestRobot = latestRobot.copy(lidarBridge = LidarBridgeTelemetry(
            valid = true,
            motorOn = (flags and 1) != 0,
            feedbackFresh = (flags and 2) != 0,
            rpm = u16(p, 4) / 10f,
            dutyPct = p[6].toInt() and 0xff,
            targetRpm = u16(p, 7),
            validPoints = u16(p, 9),
            packetsPerSecond = u16(p, 11),
            badPackets = u16(p, 13),
            checksumErrors = u16(p, 15),
            incompleteScans = u16(p, 17),
            completeScans = u16(p, 19),
            averageQuality = p[21].toInt() and 0xff,
            warningPercent = p[22].toInt() and 0xff,
            updatedAt = System.currentTimeMillis()
        ))
    }

    private fun consumeLidarQuality(p: ByteArray) {
        if (p.size != 93 || (p[0].toInt() and 0xff) != 0xDC) { bad++; return }
        val seq = p[1].toInt() and 0xff
        val frag = p[2].toInt() and 0xff
        if (frag !in 0..3) { bad++; return }
        if (qualitySequence != seq) {
            val delta = if (qualitySequence < 0) 1 else (seq - qualitySequence) and 0xff
            if (qualitySequence >= 0 && delta >= 128) return
            qualitySequence = seq
            qualityMask = 0
            stagedQuality = IntArray(360)
        }
        for (i in 0 until 90) stagedQuality[frag * 90 + i] = p[3 + i].toInt() and 0xff
        qualityMask = qualityMask or (1 shl frag)
        if (qualityMask == 0x0f) {
            latestRobot = latestRobot.copy(lidarQuality = stagedQuality.copyOf())
            qualityMask = 0
        }
    }

    private fun consumeImu(p: ByteArray) {
        if (p.size != 21) { bad++; return }
        val flags = p[1].toInt() and 0xff
        latestRobot = latestRobot.copy(imu = ImuTelemetry(
            valid = (flags and 0x01) != 0,
            zeroed = (flags and 0x02) != 0,
            magOk = (flags and 0x04) != 0,
            roll = s16(p, 4) / 100f,
            pitch = s16(p, 6) / 100f,
            gForward = s16(p, 8) / 1000f,
            gRight = s16(p, 10) / 1000f,
            gz = s16(p, 12) / 10f,
            gx = s16(p, 14) / 10f,
            gy = s16(p, 16) / 10f,
            heading = u16(p, 18) / 10f,
            temp = p[20].toInt().toFloat(),
            updatedAt = System.currentTimeMillis()
        ))
    }

    private fun consumeGps(p: ByteArray) {
        if (p.size != 19) { bad++; return }
        val flags = p[1].toInt() and 0xff
        latestRobot = latestRobot.copy(gps = GpsTelemetry(
            fix = (flags and 0x01) != 0,
            sats = p[2].toInt() and 0xff,
            hdop = (p[3].toInt() and 0xff) / 10f,
            latitude = s32(p, 4).toDouble() / 1e7,
            longitude = s32(p, 8).toDouble() / 1e7,
            speedKmh = u16(p, 12) / 100f,
            course = u16(p, 14) / 10f,
            altitude = s16(p, 16).toFloat(),
            updatedAt = System.currentTimeMillis()
        ))
    }

    private fun consumeRobot(p: ByteArray) {
        if (p.size != 21 || p[0] != 0xD1.toByte() ||
            p[1] != 'R'.code.toByte() || p[2] != '7'.code.toByte()) {
            bad++
            return
        }
        latestRobot = latestRobot.copy(
            robotSequence = p[3].toInt() and 0xff,
            robotFlags = p[4].toInt() and 0xff,
            leftPwm = u16(p, 5).toShort().toInt(),
            rightPwm = u16(p, 7).toShort().toInt(),
            encLeft = u32(p, 9),
            encRight = u32(p, 13)
        )
    }

    private fun publishStatus() {
        val now = SystemClock.elapsedRealtime()
        val online = lastRx != 0L && now - lastRx < 3000L
        onStatus(latestRobot.copy(
            connecting = running && !online,
            online = online,
            host = targetHost,
            packets = packets,
            badPackets = bad,
            droppedScans = dropped,
            lastRxElapsed = lastRx
        ))
    }

    private fun resetScan() {
        scanSeq = -1
        fragmentMask = 0
        stagedRanges = IntArray(360)
        packets = 0
        bad = 0
        dropped = 0
        lastRx = 0
        latestRobot = GatewayStatus()
        qualitySequence = -1
        qualityMask = 0
        stagedQuality = IntArray(360)
    }

    private fun u16(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)

    private fun s16(b: ByteArray, i: Int): Int = u16(b, i).toShort().toInt()

    private fun s32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or ((b[i+1].toInt() and 0xff) shl 8) or
        ((b[i+2].toInt() and 0xff) shl 16) or ((b[i+3].toInt() and 0xff) shl 24)

    private fun crc16(b: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xffff
        for (i in offset until offset + length) {
            crc = crc xor ((b[i].toInt() and 0xff) shl 8)
            repeat(8) { crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) and 0xffff else (crc shl 1) and 0xffff }
        }
        return crc
    }

    private fun xor8(b: ByteArray, offset: Int, length: Int): Int {
        var value = 0
        for (i in offset until offset + length) value = value xor (b[i].toInt() and 0xff)
        return value
    }

    private fun u32(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0xff) or
        ((b[i + 1].toLong() and 0xff) shl 8) or
        ((b[i + 2].toLong() and 0xff) shl 16) or
        ((b[i + 3].toLong() and 0xff) shl 24)
}
