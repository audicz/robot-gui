package cz.maros.mobilecockpit

import android.content.Context
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

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
    val encRight: Long = 0
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
            s = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 250
                bind(InetSocketAddress(PHONE_UDP_PORT))
            }
            socket = s
            val receiveBuffer = ByteArray(512)
            var lastHello = 0L
            while (running) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastHello >= 1000L) {
                    sendHello(s, address)
                    lastHello = now
                }
                try {
                    val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    s.receive(packet)
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
            0xD2, 0xD4, 0xD5, 0xD6, 0xD7, 0xDB, 0xDC -> Unit
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
    }

    private fun u16(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)

    private fun u32(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0xff) or
        ((b[i + 1].toLong() and 0xff) shl 8) or
        ((b[i + 2].toLong() and 0xff) shl 16) or
        ((b[i + 3].toLong() and 0xff) shl 24)
}
