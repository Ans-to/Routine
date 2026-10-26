package app.routine.privilege.adb.transport

import android.net.ssl.SSLSockets
import android.os.Build
import app.routine.privilege.adb.crypto.AdbPairingCipher
import app.routine.privilege.adb.crypto.AdbRsaKeyManager
import app.routine.privilege.adb.crypto.AdbSpake2Session
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Pure-Kotlin ADB transport packet (`CNXN`, `STLS`, `AUTH`, `OPEN`, `OKAY`, `WRTE`, `CLSE`).
 */
data class AdbPacket(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = ByteArray(0),
) {
    fun writeTo(out: OutputStream) {
        val crc = payload.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(payload.size)
            putInt(crc)
            putInt(command.inv())
        }.array()
        out.write(header)
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdbPacket) return false
        val headersMatch = command == other.command && arg0 == other.arg0 && arg1 == other.arg1
        return headersMatch && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var res = command
        res = 31 * res + arg0
        res = 31 * res + arg1
        res = 31 * res + payload.contentHashCode()
        return res
    }

    companion object {
        const val HEADER_BYTES: Int = 24
        const val CMD_CNXN: Int = 0x4E584E43
        const val CMD_STLS: Int = 0x534C5453
        const val CMD_AUTH: Int = 0x48545541
        const val CMD_OPEN: Int = 0x4E45504F
        const val CMD_OKAY: Int = 0x59414B4F
        const val CMD_WRTE: Int = 0x45545257
        const val CMD_CLSE: Int = 0x45534C43

        const val ADB_VERSION: Int = 0x01000000
        const val STLS_VERSION: Int = 0x01000000
        const val MAX_PAYLOAD: Int = 1024 * 1024
        const val AUTH_TYPE_TOKEN: Int = 1
        const val AUTH_TYPE_SIGNATURE: Int = 2
        const val AUTH_TYPE_RSAPUBLICKEY: Int = 3

        fun readFrom(input: InputStream): AdbPacket {
            val dis = DataInputStream(input)
            val headerBytes = ByteArray(HEADER_BYTES)
            dis.readFully(headerBytes)
            val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = header.int
            val arg0 = header.int
            val arg1 = header.int
            val len = header.int
            header.int // crc
            val magic = header.int
            require(magic == cmd.inv()) { "Invalid ADB packet magic word." }
            require(len in 0..MAX_PAYLOAD) { "Invalid ADB payload length $len." }
            val body = ByteArray(len)
            if (len > 0) dis.readFully(body)
            return AdbPacket(cmd, arg0, arg1, body)
        }
    }
}

/**
 * Pure-Kotlin local ADB Wireless Debugging pairing & shell transport client.
 * Explicitly restricted to loopback or local device network interfaces.
 */
class AdbTransportClient(private val keyManager: AdbRsaKeyManager) {
    /**
     * Completes Android 11+ Wireless Debugging TLS 1.3 + SPAKE2 pairing on [host]:[port] with [pairingCode].
     */
    fun pairWithLocalDaemon(host: String, port: Int, pairingCode: String, timeoutMs: Int = 10_000) {
        val address = resolveVerifiedLocalAddress(host)
        val sslContext = buildAdbSslContext()
        Socket().use { rawSocket ->
            rawSocket.connect(InetSocketAddress(address, port), timeoutMs)
            rawSocket.soTimeout = timeoutMs
            val sslSocket = sslContext.socketFactory.createSocket(rawSocket, host, port, true) as SSLSocket
            sslSocket.enabledProtocols = arrayOf("TLSv1.3")
            sslSocket.startHandshake()

            val exportedKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                SSLSockets.exportKeyingMaterial(sslSocket, EXPORTED_KEY_LABEL, null, 64)
                    ?: error("TLS keying material export returned null.")
            } else {
                error("Wireless Debugging pairing requires Android 10+ (API 29+).")
            }

            val codeBytes = pairingCode.encodeToByteArray()
            val password = ByteArray(codeBytes.size + exportedKey.size).also { buf ->
                codeBytes.copyInto(buf, 0)
                exportedKey.copyInto(buf, codeBytes.size)
            }

            val spake = AdbSpake2Session(AdbSpake2Session.Role.CLIENT, password)
            val dis = DataInputStream(sslSocket.inputStream)
            val out = sslSocket.outputStream

            // PairingPacketHeader: version=1 (1B), type=SPAKE2_MSG(0) (1B), payloadSize=32 (4B BE)
            writePairingPacket(out, PACKET_TYPE_SPAKE2_MSG, spake.publicMessage)
            val (peerType, peerSpakeMsg) = readPairingPacket(dis)
            require(peerType == PACKET_TYPE_SPAKE2_MSG) { "Expected SPAKE2_MSG(0), got $peerType." }

            val cipher = spake.processPeerMessage(peerSpakeMsg)
            val myPeerInfo = AdbPairingCipher.buildPeerInfoPayload(keyManager.buildAdbPublicKeyPayload())
            writePairingPacket(out, PACKET_TYPE_PEER_INFO, cipher.encrypt(myPeerInfo))

            val (peerInfoType, encryptedPeerInfo) = readPairingPacket(dis)
            require(peerInfoType == PACKET_TYPE_PEER_INFO) { "Expected PEER_INFO(1), got $peerInfoType." }
            val decryptedPeerInfo = cipher.decrypt(encryptedPeerInfo)
            require(decryptedPeerInfo.isNotEmpty()) { "Empty PeerInfo from adbd." }
        }
    }

    /**
     * Connects to the local `adbd` port, performs `CNXN` (+ `STLS` or `AUTH` as requested),
     * runs `shell:<command>`, and returns UTF-8 output.
     */
    fun executeOneShotShell(host: String, port: Int, command: String, timeoutMs: Int = 8_000): String {
        val address = resolveVerifiedLocalAddress(host)
        Socket().use { tcpSocket ->
            tcpSocket.connect(InetSocketAddress(address, port), timeoutMs)
            tcpSocket.soTimeout = timeoutMs
            var input: InputStream = tcpSocket.inputStream
            var output: OutputStream = tcpSocket.outputStream

            AdbPacket(
                command = AdbPacket.CMD_CNXN,
                arg0 = AdbPacket.ADB_VERSION,
                arg1 = AdbPacket.MAX_PAYLOAD,
                payload = "host::features=shell_v2,cmd\u0000".encodeToByteArray(),
            ).writeTo(output)

            var sentSignature = false
            while (true) {
                val pkt = AdbPacket.readFrom(input)
                when (pkt.command) {
                    AdbPacket.CMD_CNXN -> break
                    AdbPacket.CMD_STLS -> {
                        AdbPacket(AdbPacket.CMD_STLS, AdbPacket.STLS_VERSION, 0).writeTo(output)
                        val ssl = buildAdbSslContext().socketFactory
                            .createSocket(tcpSocket, host, port, false) as SSLSocket
                        ssl.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
                        ssl.startHandshake()
                        input = ssl.inputStream
                        output = ssl.outputStream
                    }
                    AdbPacket.CMD_AUTH -> {
                        require(pkt.arg0 == AdbPacket.AUTH_TYPE_TOKEN) { "Unexpected AUTH subtype ${pkt.arg0}" }
                        if (!sentSignature) {
                            sentSignature = true
                            val sig = keyManager.signAuthToken(pkt.payload)
                            AdbPacket(AdbPacket.CMD_AUTH, AdbPacket.AUTH_TYPE_SIGNATURE, 0, sig).writeTo(output)
                        } else {
                            val pub = keyManager.buildAdbPublicKeyPayload()
                            AdbPacket(AdbPacket.CMD_AUTH, AdbPacket.AUTH_TYPE_RSAPUBLICKEY, 0, pub).writeTo(output)
                        }
                    }
                    else -> error("Unexpected packet during ADB handshake: 0x${pkt.command.toString(16)}")
                }
            }

            val localStreamId = 1
            AdbPacket(
                command = AdbPacket.CMD_OPEN,
                arg0 = localStreamId,
                arg1 = 0,
                payload = "shell:$command\u0000".encodeToByteArray(),
            ).writeTo(output)

            val outBuffer = ByteArrayOutputStream()
            var remoteStreamId = 0
            while (true) {
                val frame = AdbPacket.readFrom(input)
                when (frame.command) {
                    AdbPacket.CMD_OKAY -> remoteStreamId = frame.arg0
                    AdbPacket.CMD_WRTE -> {
                        remoteStreamId = frame.arg0
                        outBuffer.write(frame.payload)
                        AdbPacket(AdbPacket.CMD_OKAY, localStreamId, remoteStreamId).writeTo(output)
                    }
                    AdbPacket.CMD_CLSE -> {
                        AdbPacket(AdbPacket.CMD_CLSE, localStreamId, remoteStreamId).writeTo(output)
                        break
                    }
                    else -> break
                }
            }
            return outBuffer.toByteArray().decodeToString()
        }
    }

    @Suppress("CustomX509TrustManager")
    private fun buildAdbSslContext(): SSLContext {
        val km = object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(
                keyType: Array<out String>?,
                issuers: Array<out Principal>?,
                socket: Socket?,
            ): String = "adb"

            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String =
                "adb"

            override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(keyManager.certificate)

            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
                arrayOf("adb")

            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
                arrayOf("adb")

            override fun getPrivateKey(alias: String?): PrivateKey = keyManager.privateKey
        }
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLSv1.3").apply {
            init(arrayOf(km), arrayOf(tm), SecureRandom())
        }
    }

    private fun writePairingPacket(out: OutputStream, type: Byte, payload: ByteArray) {
        val header = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN).apply {
            put(1.toByte())
            put(type)
            putInt(payload.size)
        }.array()
        out.write(header)
        out.write(payload)
        out.flush()
    }

    private fun readPairingPacket(dis: DataInputStream): Pair<Byte, ByteArray> {
        val headerBytes = ByteArray(6)
        dis.readFully(headerBytes)
        val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        val version = buf.get().toInt()
        require(version == 1) { "Unsupported ADB pairing header version $version." }
        val type = buf.get()
        val length = buf.int
        require(length in 1..16_384) { "Invalid ADB pairing packet length $length." }
        val body = ByteArray(length)
        dis.readFully(body)
        return type to body
    }

    companion object {
        private const val EXPORTED_KEY_LABEL = "adb-label\u0000"
        private const val PACKET_TYPE_SPAKE2_MSG: Byte = 0
        private const val PACKET_TYPE_PEER_INFO: Byte = 1

        fun resolveVerifiedLocalAddress(host: String): InetAddress {
            val addr = InetAddress.getByName(host)
            val isLocal = addr.isLoopbackAddress ||
                NetworkInterface.getByInetAddress(addr) != null
            require(isLocal) {
                "Security policy violation: ADB Bridge refuses non-device-local address $host."
            }
            return addr
        }
    }
}
