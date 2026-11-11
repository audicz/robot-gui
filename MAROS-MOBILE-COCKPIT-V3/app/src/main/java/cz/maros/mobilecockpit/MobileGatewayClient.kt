package cz.maros.mobilecockpit

import android.content.Context
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * Receives the original ESP-NOW datagrams forwarded by the ESP32-S3 display.
 * The Android phone does not implement ESP-NOW itself; the display remains the
 * radio gateway. Each UDP datagram contains exactly one original ESP-NOW frame.
 */
data class RobotTelemetry(
    val valid: Boolean = false,
    val sequence: Int = 0,
    val flags: Int = 0,
    val leftPwm: Int = 0,
    val rightPwm: Int = 0,
    val encLeft: Long = 0,
    val encRight: Long = 0,
    val currentLeftV: Float = Float.NaN,
    val currentRightV: Float = Float.NaN,
    val receivedAt: Long = 0L
)

data class GatewayStatus(
    val online: Boolean = false,
    val packets: Long = 0L,
    val badPackets: Long = 0L,
    val droppedScans: Long = 0L,
    val lastRxAt: Long = 0L,
    val robot: RobotTelemetry = RobotTelemetry()
)

class MobileGatewayClient(
    context: Context,
    private val changed: (LidarScan?, GatewayStatus) -> Unit
) {
    companion object {
        const val UDP_PORT = 4210
        private const val FRAME_D9 = 0xD9
        private const val FRAME_D1 = 0xD1
    }

    @Suppress("unused")
    private val appContext = context.applicationContext
    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    private var worker: Thread? = null
    private var scanSeq = -1
    private var fragmentMask = 0
    private var stagedRanges = IntArray(360)
    private var lastScan: LidarScan? = null
    private var packetCount = 0L
    private var badCount = 0L
    private var droppedCount = 0L
    private var lastRx = 0L
    private var robot = RobotTelemetry()

    @Synchronized fun start() {
        if (running) return
        running = true
        worker = thread(name = "maros-udp-gateway", isDaemon = true) {
            receiveLoop()
        }
    }

    @Synchronized fun stop() {
        running = false
        socket?.close()
        socket = null
        worker?.interrupt()
        worker = null
    }

    private fun receiveLoop() {
        var s: DatagramSocket? = null
        try {
            s = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 1000
                bind(java.net.InetSocketAddress(UDP_PORT))
            }
            socket = s
            val buf = ByteArray(512)
            while (running) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    s.receive(packet)
                    val frame = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    synchronized(this) { consume(frame) }
                } catch (_: java.net.SocketTimeoutException) {
                    publishStatus()
                } catch (_: SocketException) {
                    if (running) synchronized(this) { badCount++ }
                } catch (_: Exception) {
                    synchronized(this) { badCount++ }
                }
            }
        } catch (_: Exception) {
            synchronized(this) { badCount++ }
        } finally {
            try { s?.close() } catch (_: Exception) {}
            socket = null
        }
    }

    private fun consume(p: ByteArray) {
        if (p.isEmpty()) { badCount++; publishStatus(); return }
        packetCount++
        lastRx = SystemClock.elapsedRealtime()
        when (p[0].toInt() and 0xff) {
            FRAME_D9 -> consumeLidar(p)
            FRAME_D1 -> consumeRobot(p)
            // D2 scan-head, D4 odometry, D5 IMU, D6 GPS, DB status and
            // DC per-bin quality are deliberately counted here. Their typed
            // decoders are added only against the exact firmware layouts.
            0xD2, 0xD4, 0xD5, 0xD6, 0xDB, 0xDC -> if (p.size < 2) badCount++
            else -> badCount++
        }
        publishStatus()
    }

    private fun consumeLidar(p: ByteArray) {
        if (p.size != 185) { badCount++; return }
        val seq = p[1].toInt() and 0xff
        val frag = p[2].toInt() and 0xff
        if (frag !in 0..3) { badCount++; return }
        if (scanSeq == -1 || fragmentMask == 0) {
            scanSeq = seq
            fragmentMask = 0
            stagedRanges = IntArray(360)
        } else if (seq != scanSeq) {
            val delta = (seq - scanSeq) and 0xff
            if (delta >= 128) return // late fragment from previous revolution
            if (fragmentMask != 0x0f) droppedCount++
            scanSeq = seq
            fragmentMask = 0
            stagedRanges = IntArray(360)
        }
        val offset = frag * 90
        for (i in 0 until 90) {
            val at = 5 + i * 2
            stagedRanges[offset + i] =
                (p[at].toInt() and 0xff) or ((p[at + 1].toInt() and 0xff) shl 8)
        }
        fragmentMask = fragmentMask or (1 shl frag)
        if (fragmentMask == 0x0f) {
            val rpm = (p[3].toInt() and 0xff) * 2
            val duty = p[4].toInt() and 0xff
            if (stagedRanges.any { it in 80..12000 }) {
                lastScan = LidarScan(stagedRanges.copyOf(), rpm, duty, seq, System.currentTimeMillis())
                changed(lastScan, status())
            } else {
                badCount++
            }
            fragmentMask = 0
        }
    }

    private fun consumeRobot(p: ByteArray) {
        if (p.size != 21 || p[1].toInt().toChar() != 'R' || p[2].toInt() != 7) {
            badCount++
            return
        }
        fun u16(i: Int) = (p[i].toInt() and 0xff) or ((p[i + 1].toInt() and 0xff) shl 8)
        fun u32(i: Int): Long = (p[i].toLong() and 0xff) or
            ((p[i + 1].toLong() and 0xff) shl 8) or
            ((p[i + 2].toLong() and 0xff) shl 16) or
            ((p[i + 3].toLong() and 0xff) shl 24)
        val lcs = u16(17)
        val rcs = u16(19)
        robot = RobotTelemetry(
            valid = true,
            sequence = p[3].toInt() and 0xff,
            flags = p[4].toInt() and 0xff,
            leftPwm = u16(5).toShort().toInt(),
            rightPwm = u16(7).toShort().toInt(),
            encLeft = u32(9).toInt().toLong(),
            encRight = u32(13).toInt().toLong(),
            currentLeftV = if (lcs == 0xffff) Float.NaN else lcs / 1000f,
            currentRightV = if (rcs == 0xffff) Float.NaN else rcs / 1000f,
            receivedAt = System.currentTimeMillis()
        )
    }

    @Synchronized private fun status(): GatewayStatus {
        val now = SystemClock.elapsedRealtime()
        return GatewayStatus(
            online = lastRx != 0L && now - lastRx < 3000L,
            packets = packetCount,
            badPackets = badCount,
            droppedScans = droppedCount,
            lastRxAt = lastRx,
            robot = robot
        )
    }

    private fun publishStatus() = changed(lastScan, status())
}
