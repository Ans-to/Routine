package app.routine.privilege.adb.transport

import android.net.ssl.SSLSockets
import android.os.Build
import app.routine.privilege.adb.crypto.AdbPairingCipher
import app.routine.privilege.adb.crypto.AdbRsaKeyManager
import app.routine.privilege.adb.crypto.AdbSpake2Session
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
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
import java.util.Collections
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
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
 * Explicitly restricted to loopback or local device network interfaces, with automatic fallback
 * across verified local IPv4 and `127.0.0.1` loopback interfaces when mDNS returns unscoped IPv6.
 */
class AdbTransportClient(
    private val keyManager: AdbRsaKeyManager,
    private val keyingMaterialExporter: (SSLSocket) -> ByteArray = Companion::exportConscryptKeyingMaterial,
) {
    /**
     * Completes Android 11+ Wireless Debugging TLS 1.3 + SPAKE2 pairing on [host]:[port] with [pairingCode].
     */
    fun pairWithLocalDaemon(host: String, port: Int, pairingCode: String, timeoutMs: Int = 10_000) {
        val candidates = resolveCandidateLocalAddresses(host)
        val sslContext = buildAdbSslContext()
        connectFirstReachable(candidates, port, timeoutMs).use { rawSocket ->
            rawSocket.tcpNoDelay = true
            rawSocket.soTimeout = timeoutMs
            val peerHost = rawSocket.inetAddress?.hostAddress ?: "127.0.0.1"
            val sslSocket = sslContext.socketFactory.createSocket(rawSocket, peerHost, port, true) as SSLSocket
            sslSocket.use { tls ->
                tls.enabledProtocols = arrayOf("TLSv1.3")
                tls.startHandshake()

                val exportedKey = keyingMaterialExporter(tls)
                val codeBytes = pairingCode.trim().encodeToByteArray()
                val password = ByteArray(codeBytes.size + exportedKey.size).also { buf ->
                    codeBytes.copyInto(buf, 0)
                    exportedKey.copyInto(buf, codeBytes.size)
                }

                val spake = AdbSpake2Session(AdbSpake2Session.Role.CLIENT, password)
                val dis = DataInputStream(tls.inputStream)
                val out = tls.outputStream

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
    }

    /**
     * Connects to the local `adbd` port, performs `CNXN` (+ `STLS` mutual TLS 1.3 or `AUTH` as requested),
     * runs `shell:<command>`, and returns UTF-8 output.
     */
    fun executeOneShotShell(
        host: String,
        port: Int,
        command: String,
        timeoutMs: Int = 8_000,
        abortOnUnauthorised: Boolean = false,
    ): String {
        val candidates = resolveCandidateLocalAddresses(host)
        connectFirstReachable(candidates, port, timeoutMs).use { tcpSocket ->
            tcpSocket.tcpNoDelay = true
            tcpSocket.soTimeout = timeoutMs
            val peerHost = tcpSocket.inetAddress?.hostAddress ?: "127.0.0.1"
            val (input, output) = negotiateAdbSession(tcpSocket, peerHost, port, abortOnUnauthorised)
            val localStreamId = 1
            AdbPacket(
                command = AdbPacket.CMD_OPEN,
                arg0 = localStreamId,
                arg1 = 0,
                payload = "shell:$command\u0000".encodeToByteArray(),
            ).writeTo(output)
            return readShellStream(input, output, localStreamId)
        }
    }

    private fun negotiateAdbSession(
        tcpSocket: Socket,
        host: String,
        port: Int,
        abortOnUnauthorised: Boolean,
    ): Pair<InputStream, OutputStream> {
        var input: InputStream = tcpSocket.inputStream
        var output: OutputStream = tcpSocket.outputStream
        AdbPacket(
            command = AdbPacket.CMD_CNXN,
            arg0 = AdbPacket.ADB_VERSION,
            arg1 = AdbPacket.MAX_PAYLOAD,
            payload = "host::\u0000".encodeToByteArray(),
        ).writeTo(output)

        var sentSignature = false
        var isTls = false
        var connected = false
        while (!connected) {
            val pkt = AdbPacket.readFrom(input)
            when (pkt.command) {
                AdbPacket.CMD_CNXN -> connected = true
                AdbPacket.CMD_STLS -> {
                    AdbPacket(AdbPacket.CMD_STLS, AdbPacket.STLS_VERSION, 0).writeTo(output)
                    val ssl = buildAdbSslContext().socketFactory
                        .createSocket(tcpSocket, host, port, true) as SSLSocket
                    ssl.enabledProtocols = arrayOf("TLSv1.3")
                    ssl.startHandshake()
                    input = ssl.inputStream
                    output = ssl.outputStream
                    isTls = true
                }
                AdbPacket.CMD_AUTH -> {
                    sentSignature = handleAuthChallenge(pkt, isTls, sentSignature, abortOnUnauthorised, output)
                }
                else -> error("Unexpected packet during ADB handshake: 0x${pkt.command.toString(16)}")
            }
        }
        return input to output
    }

    private fun handleAuthChallenge(
        pkt: AdbPacket,
        isTls: Boolean,
        sentSignature: Boolean,
        abortOnUnauthorised: Boolean,
        output: OutputStream,
    ): Boolean {
        if (isTls) return sentSignature
        if (sentSignature && abortOnUnauthorised) {
            throw IOException("ADB public key is not yet paired with local daemon.")
        }
        sendAuthResponse(pkt, sentSignature, output)
        return true
    }

    private fun sendAuthResponse(pkt: AdbPacket, sentSignature: Boolean, output: OutputStream) {
        require(pkt.arg0 == AdbPacket.AUTH_TYPE_TOKEN) { "Unexpected AUTH subtype ${pkt.arg0}" }
        if (!sentSignature) {
            val sig = keyManager.signAuthToken(pkt.payload)
            AdbPacket(AdbPacket.CMD_AUTH, AdbPacket.AUTH_TYPE_SIGNATURE, 0, sig).writeTo(output)
        } else {
            val pub = keyManager.buildAdbPublicKeyPayload()
            AdbPacket(AdbPacket.CMD_AUTH, AdbPacket.AUTH_TYPE_RSAPUBLICKEY, 0, pub).writeTo(output)
        }
    }

    private fun readShellStream(input: InputStream, output: OutputStream, localStreamId: Int): String {
        val outBuffer = ByteArrayOutputStream()
        var remoteStreamId = 0
        var open = true
        while (open) {
            val frame = AdbPacket.readFrom(input)
            when (frame.command) {
                AdbPacket.CMD_OKAY -> remoteStreamId = frame.arg0
                AdbPacket.CMD_WRTE -> {
                    remoteStreamId = frame.arg0
                    outBuffer.write(frame.payload)
                    AdbPacket(AdbPacket.CMD_OKAY, localStreamId, remoteStreamId).writeTo(output)
                }
                AdbPacket.CMD_CLSE -> {
                    runCatching { AdbPacket(AdbPacket.CMD_CLSE, localStreamId, remoteStreamId).writeTo(output) }
                        .onFailure { err -> android.util.Log.d(TAG, "Peer closed stream after CLSE", err) }
                    open = false
                }
                else -> open = false
            }
        }
        return outBuffer.toByteArray().decodeToString()
    }

    @Suppress("CustomX509TrustManager")
    internal fun buildAdbSslContext(): SSLContext {
        val km = object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(
                keyType: Array<out String>?,
                issuers: Array<out Principal>?,
                socket: Socket?,
            ): String? = selectRsaAlias(keyType)

            override fun chooseEngineClientAlias(
                keyType: Array<out String>?,
                issuers: Array<out Principal>?,
                engine: SSLEngine?,
            ): String? = selectRsaAlias(keyType)

            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
                if (keyType == null || keyType.equals("RSA", ignoreCase = true)) KEY_ALIAS else null

            override fun chooseEngineServerAlias(
                keyType: String?,
                issuers: Array<out Principal>?,
                engine: SSLEngine?,
            ): String? = if (keyType == null || keyType.equals("RSA", ignoreCase = true)) KEY_ALIAS else null

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == KEY_ALIAS) arrayOf(keyManager.certificate) else null

            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
                if (keyType == null || keyType.equals("RSA", ignoreCase = true)) arrayOf(KEY_ALIAS) else null

            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
                if (keyType == null || keyType.equals("RSA", ignoreCase = true)) arrayOf(KEY_ALIAS) else null

            override fun getPrivateKey(alias: String?): PrivateKey? =
                if (alias == KEY_ALIAS) keyManager.privateKey else null

            private fun selectRsaAlias(keyTypes: Array<out String>?): String? {
                if (keyTypes.isNullOrEmpty()) return KEY_ALIAS
                return if (keyTypes.any { it.equals("RSA", ignoreCase = true) }) KEY_ALIAS else null
            }
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

    private fun connectFirstReachable(candidates: List<InetAddress>, port: Int, timeoutMs: Int): Socket {
        var lastError: IOException? = null
        val perAttemptTimeout = (timeoutMs / candidates.size.coerceAtLeast(1)).coerceAtLeast(2_000)
        for (candidate in candidates) {
            val sock = Socket()
            try {
                sock.connect(InetSocketAddress(candidate, port), perAttemptTimeout)
                return sock
            } catch (err: IOException) {
                runCatching { sock.close() }
                    .onFailure { closeErr -> android.util.Log.d(TAG, "Ignored close failure", closeErr) }
                lastError = err
            }
        }
        throw lastError ?: IOException("Unable to connect to local ADB port $port.")
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
        private const val TAG = "AdbTransportClient"
        private const val KEY_ALIAS = "adb"
        const val EXPORTED_KEY_LABEL: String = "adb-label\u0000"
        const val EXPORT_KEY_SIZE: Int = 64
        const val PACKET_TYPE_SPAKE2_MSG: Byte = 0
        const val PACKET_TYPE_PEER_INFO: Byte = 1

        @Suppress("PrivateApi")
        internal fun exportConscryptKeyingMaterial(sslSocket: SSLSocket): ByteArray {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && SSLSockets.isSupportedSocket(sslSocket)) {
                val exported = SSLSockets.exportKeyingMaterial(sslSocket, EXPORTED_KEY_LABEL, null, EXPORT_KEY_SIZE)
                if (exported != null) return exported
            }
            val conscryptClassName = listOf("com.android.org.conscrypt.Conscrypt", "org.conscrypt.Conscrypt")
                .firstOrNull { name -> runCatching { Class.forName(name) }.isSuccess }
                ?: error("TLS 1.3 keying material exporter unavailable on this runtime.")
            val clazz = Class.forName(conscryptClassName)
            val method = clazz.getMethod(
                "exportKeyingMaterial",
                SSLSocket::class.java,
                String::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
            )
            return method.invoke(null, sslSocket, EXPORTED_KEY_LABEL, null, EXPORT_KEY_SIZE) as? ByteArray
                ?: error("Conscrypt exportKeyingMaterial returned null.")
        }

        fun resolveVerifiedLocalAddress(host: String): InetAddress = resolveCandidateLocalAddresses(host).first()

        fun resolveCandidateLocalAddresses(host: String): List<InetAddress> {
            val cleanHost = host.substringBefore('%').ifBlank { "127.0.0.1" }
            val requested = InetAddress.getByName(cleanHost)
            if (requested.isLoopbackAddress) {
                return listOf(InetAddress.getByName("127.0.0.1"))
            }
            val localAddresses = enumerateLocalInterfaceAddresses()
            val matchesLocalInterface = localAddresses.any { local -> local.address.contentEquals(requested.address) }
            require(matchesLocalInterface) {
                "Security policy violation: ADB Bridge refuses non-device-local address $host."
            }

            val ordered = ArrayList<InetAddress>(3)
            if (requested is Inet4Address) {
                ordered.add(requested)
            }
            for (local in localAddresses) {
                if (local is Inet4Address && !local.isLoopbackAddress && ordered.none { it == local }) {
                    ordered.add(local)
                }
            }
            val ipv4Loopback = InetAddress.getByName("127.0.0.1")
            if (ordered.none { it == ipv4Loopback }) {
                ordered.add(ipv4Loopback)
            }
            if (requested !is Inet4Address && !requested.isLinkLocalAddress && ordered.none { it == requested }) {
                ordered.add(requested)
            }
            return ordered
        }

        internal fun enumerateLocalInterfaceAddresses(): List<InetAddress> = runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching emptyList()
            Collections.list(interfaces)
                .flatMap { iface -> Collections.list(iface.inetAddresses) }
        }.onFailure { err ->
            android.util.Log.d(TAG, "Unable to enumerate local NetworkInterfaces", err)
        }.getOrDefault(emptyList())
    }
}
