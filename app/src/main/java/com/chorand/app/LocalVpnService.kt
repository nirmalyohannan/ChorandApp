package com.chorand.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class LocalVpnService : VpnService() {

    companion object {
        const val TAG = "LocalVpnService"
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val ACTION_STOP = "com.chorand.app.ACTION_STOP"

        val eventFlow = MutableSharedFlow<ApiEvent>(extraBufferCapacity = 100)

        @Volatile
        var isRunning = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null
    private lateinit var jsonlWriter: JsonlWriter
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        val filePath = intent?.getStringExtra(EXTRA_FILE_PATH) ?: return START_NOT_STICKY
        jsonlWriter = JsonlWriter(File(filePath))

        if (isRunning) {
            return START_STICKY
        }

        isRunning = true
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "vpn_channel")
            .setContentTitle("Chorand VPN Interceptor Active")
            .setContentText("Monitoring device DNS / API requests")
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(1, notification)
        }

        val builder = Builder()
            .setSession("Chorand VPN Interceptor")
            .addAddress("10.0.0.1", 24)
            .addRoute("10.0.0.0", 24)
            .addDnsServer("10.0.0.2")

        try {
            vpnInterface = builder.establish()
            Log.d(TAG, "VPN Interface established")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN interface", e)
            stopSelf()
            return
        }

        vpnThread = Thread {
            runVpnLoop()
        }.apply { start() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "vpn_channel",
                "Chorand VPN Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun runVpnLoop() {
        val fd = vpnInterface?.fileDescriptor ?: return
        inputStream = FileInputStream(fd)
        outputStream = FileOutputStream(fd)
        val packet = ByteBuffer.allocate(32768)

        try {
            while (isRunning) {
                packet.clear()
                val read = inputStream?.read(packet.array()) ?: -1
                if (read > 0) {
                    packet.limit(read)
                    outputStream?.let { processPacket(packet, it) }
                } else if (read < 0) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "VPN read loop stopped: ${e.message}")
        } finally {
            closeStreams()
        }
    }

    private fun closeStreams() {
        try {
            inputStream?.close()
        } catch (e: Exception) {}
        inputStream = null
        try {
            outputStream?.close()
        } catch (e: Exception) {}
        outputStream = null
    }

    private fun processPacket(packet: ByteBuffer, outputStream: FileOutputStream) {
        val buffer = packet.array()
        val length = packet.limit()

        if (length < 20) return
        val versionAndIHL = buffer[0].toInt() and 0xFF
        val version = versionAndIHL ushr 4
        val ihl = (versionAndIHL and 0x0F) * 4
        if (version != 4) return

        val protocol = buffer[9].toInt() and 0xFF
        if (protocol != 17) return // 17 is UDP

        if (length < ihl + 8) return
        val srcPort = ((buffer[ihl].toInt() and 0xFF) shl 8) or (buffer[ihl + 1].toInt() and 0xFF)
        val destPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)
        val udpLen = ((buffer[ihl + 4].toInt() and 0xFF) shl 8) or (buffer[ihl + 5].toInt() and 0xFF)

        if (destPort != 53) return

        val dnsOffset = ihl + 8
        if (length < dnsOffset + 12) return

        val transactionId = ((buffer[dnsOffset].toInt() and 0xFF) shl 8) or (buffer[dnsOffset + 1].toInt() and 0xFF)
        val flags = ((buffer[dnsOffset + 2].toInt() and 0xFF) shl 8) or (buffer[dnsOffset + 3].toInt() and 0xFF)
        val isQuery = (flags and 0x8000) == 0
        if (!isQuery) return

        val qdCount = ((buffer[dnsOffset + 4].toInt() and 0xFF) shl 8) or (buffer[dnsOffset + 5].toInt() and 0xFF)
        if (qdCount <= 0) return

        var pos = dnsOffset + 12
        val domain = StringBuilder()
        while (pos < length) {
            val labelLen = buffer[pos].toInt() and 0xFF
            pos++
            if (labelLen == 0) break
            if (domain.isNotEmpty()) domain.append('.')
            if (pos + labelLen > length) return
            domain.append(String(buffer, pos, labelLen))
            pos += labelLen
        }

        val domainName = domain.toString()
        if (domainName.isEmpty()) return

        Log.d(TAG, "Intercepted DNS query for: $domainName")
        logDnsEvent(domainName)

        val dnsLen = udpLen - 8
        if (dnsOffset + dnsLen > length) return
        val dnsPayload = ByteArray(dnsLen)
        System.arraycopy(buffer, dnsOffset, dnsPayload, 0, dnsLen)

        // Copy client and DNS server IP bytes synchronously to prevent concurrency buffer corruption
        val clientIp = ByteArray(4)
        System.arraycopy(buffer, 12, clientIp, 0, 4)
        val dnsIp = ByteArray(4)
        System.arraycopy(buffer, 16, dnsIp, 0, 4)

        forwardDnsQuery(domainName, srcPort, destPort, clientIp, dnsIp, dnsPayload, outputStream)
    }

    private fun logDnsEvent(domain: String) {
        val requestEvent = ApiEvent(
            type = "request",
            url = "https://$domain/",
            method = "CONNECT",
            initiator = "vpn",
            timestamp = System.currentTimeMillis()
        )

        serviceScope.launch {
            try {
                jsonlWriter.open()
                jsonlWriter.write(requestEvent)
                eventFlow.emit(requestEvent)
            } catch (e: Exception) {
                Log.e(TAG, "Error writing request event to JSONL", e)
            }
        }
    }

    private fun forwardDnsQuery(
        domain: String,
        clientUdpPort: Int,
        dnsPort: Int,
        clientIp: ByteArray,
        dnsIp: ByteArray,
        dnsPayload: ByteArray,
        outputStream: FileOutputStream
    ) {
        serviceScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            var socket: DatagramSocket? = null
            var responseDnsPayload: ByteArray? = null
            var errorMsg: String? = null

            try {
                socket = DatagramSocket()
                protect(socket) // Exclude from VPN routing to avoid infinite recursion
                socket.soTimeout = 3000

                // Query non-VPN physical networks' DNS servers, falling back to 8.8.8.8
                val activeDns = try {
                    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    var dnsResolverAddress: InetAddress? = null
                    for (network in connectivityManager.allNetworks) {
                        val caps = connectivityManager.getNetworkCapabilities(network)
                        if (caps != null && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                            val lp = connectivityManager.getLinkProperties(network)
                            val ipAddress = lp?.dnsServers?.firstOrNull { it is java.net.Inet4Address }
                            if (ipAddress != null) {
                                dnsResolverAddress = ipAddress
                                break
                            }
                        }
                    }
                    dnsResolverAddress
                } catch (e: Exception) {
                    null
                }
                val dnsResolverIp = activeDns ?: InetAddress.getByName("8.8.8.8")

                val sendPacket = DatagramPacket(dnsPayload, dnsPayload.size, dnsResolverIp, 53)
                socket.send(sendPacket)

                val recvBuf = ByteArray(4096)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(recvPacket)

                val responseLen = recvPacket.length
                responseDnsPayload = ByteArray(responseLen)
                System.arraycopy(recvBuf, 0, responseDnsPayload, 0, responseLen)
            } catch (e: Exception) {
                errorMsg = e.message ?: "DNS Forwarding failed"
                Log.e(TAG, "DNS query forwarding failed for $domain", e)
            } finally {
                socket?.close()
            }

            val duration = System.currentTimeMillis() - startTime
            var status = 404
            var statusText = "Failed"
            var responseBody = errorMsg ?: "No response from DNS server"

            if (responseDnsPayload != null && responseDnsPayload.size > 4) {
                val rFlags = ((responseDnsPayload[2].toInt() and 0xFF) shl 8) or (responseDnsPayload[3].toInt() and 0xFF)
                val rcode = rFlags and 0x0F
                status = if (rcode == 0) 200 else 404
                statusText = if (rcode == 0) "Resolved" else "Error (RCODE $rcode)"
                responseBody = if (rcode == 0) "DNS query resolved successfully." else "DNS Error RCODE: $rcode"
            }

            val responseEvent = ApiEvent(
                type = "response",
                url = "https://$domain/",
                method = "CONNECT",
                status = status,
                statusText = statusText,
                durationMs = duration,
                initiator = "vpn",
                responseBody = responseBody,
                timestamp = System.currentTimeMillis()
            )

            try {
                jsonlWriter.write(responseEvent)
                eventFlow.emit(responseEvent)
            } catch (e: Exception) {
                Log.e(TAG, "Error writing response event to JSONL", e)
            }

            if (responseDnsPayload == null) return@launch

            val totalLen = 20 + 8 + responseDnsPayload.size
            val responsePacket = ByteArray(totalLen)

            // IPv4 Header
            responsePacket[0] = 0x45.toByte() // Version 4, IHL 5
            responsePacket[1] = 0x00.toByte()
            responsePacket[2] = (totalLen ushr 8).toByte()
            responsePacket[3] = (totalLen and 0xFF).toByte()
            responsePacket[4] = 0x00.toByte()
            responsePacket[5] = 0x00.toByte()
            responsePacket[6] = 0x40.toByte()
            responsePacket[7] = 0x00.toByte()
            responsePacket[8] = 64.toByte()
            responsePacket[9] = 17.toByte()

            // Source IP of response = Destination IP of query (DNS Server, e.g. 10.0.0.2)
            System.arraycopy(dnsIp, 0, responsePacket, 12, 4)
            // Destination IP of response = Source IP of query (Client, e.g. 10.0.0.1)
            System.arraycopy(clientIp, 0, responsePacket, 16, 4)

            // Calculate IP Checksum (zero field first)
            responsePacket[10] = 0
            responsePacket[11] = 0
            val ipChecksum = computeChecksum(responsePacket, 20)
            responsePacket[10] = (ipChecksum ushr 8).toByte()
            responsePacket[11] = (ipChecksum and 0xFF).toByte()

            val udpOffset = 20
            // Source port is DNS Server port (53)
            responsePacket[udpOffset] = (dnsPort ushr 8).toByte()
            responsePacket[udpOffset + 1] = (dnsPort and 0xFF).toByte()
            // Destination port is client port
            responsePacket[udpOffset + 2] = (clientUdpPort ushr 8).toByte()
            responsePacket[udpOffset + 3] = (clientUdpPort and 0xFF).toByte()
            val udpLengthVal = 8 + responseDnsPayload.size
            responsePacket[udpOffset + 4] = (udpLengthVal ushr 8).toByte()
            responsePacket[udpOffset + 5] = (udpLengthVal and 0xFF).toByte()
            responsePacket[udpOffset + 6] = 0
            responsePacket[udpOffset + 7] = 0

            System.arraycopy(responseDnsPayload, 0, responsePacket, udpOffset + 8, responseDnsPayload.size)

            synchronized(outputStream) {
                try {
                    outputStream.write(responsePacket)
                    outputStream.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write DNS response packet", e)
                }
            }
        }
    }

    private fun computeChecksum(buf: ByteArray, length: Int): Int {
        var sum = 0
        var i = 0
        var len = length
        while (len > 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
            len -= 2
        }
        if (len > 0) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv()) and 0xFFFF
    }

    private fun stopVpn() {
        isRunning = false
        closeStreams()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {}
        vpnInterface = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        closeStreams()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {}
        vpnInterface = null
        vpnThread?.interrupt()
        vpnThread = null
        serviceScope.launch {
            try {
                jsonlWriter.close()
            } catch (e: Exception) {}
        }
        super.onDestroy()
    }
}
