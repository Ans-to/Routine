package app.routine.privilege.adb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.routine.privilege.adb.binder.LoopbackRoutineShellClient
import app.routine.privilege.adb.binder.LoopbackRoutineShellDaemon
import app.routine.privilege.adb.binder.LoopbackShellCrypto
import app.routine.privilege.adb.binder.RoutineShellService
import app.routine.privilege.adb.crypto.AdbPairingCipher
import app.routine.privilege.adb.crypto.AdbRsaKeyManager
import app.routine.privilege.adb.crypto.AdbSpake2Session
import app.routine.privilege.adb.crypto.Ed25519Math
import app.routine.privilege.adb.discovery.WirelessAdbDiscovery
import app.routine.privilege.adb.transport.AdbPacket
import app.routine.privilege.adb.transport.AdbTransportClient
import app.routine.privilege.api.PrivilegeAvailability
import app.routine.privilege.api.PrivilegeOperationResult
import app.routine.privilege.api.SecureSettingNamespace
import app.routine.privilege.api.ShellCommandResult
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdbBridgePrivilegedEngineTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `Ed25519 SPAKE2 matches official BoringSSL spake25519 deterministic test vectors bit-for-bit`() {
        val rawAlice = hexToBytes(
            "47f6c458e5f062db8427d2d9bb20c954a76d6943959756a18d11d45e1ad190f9" +
                "80a86d185a93ca1d3025c5febe3aac4045b34a39b1f511385ca97fc4332137f3",
        )
        val rawBob = hexToBytes(
            "a6bf9f9bf7819e0ded8c2dd82a1aa38acb2f8a6403429cff33d64ea9c40439d5" +
                "fd7029811a5f5a8f7c89c8b44ac0b421f6b24ca2ba18d2069995831730cd8c5a",
        )
        val password = hexToBytes(
            "353932373831E63DD959651C211600F3B6561D0B9D90AF09D0A4A453EE2059A4" +
                "80CC7C5A94D4D48933F9FFF5FE43317D52FA7BFF8F8BC4F3488B8007330FEC7C7EDC91C20E5D",
        )

        val reduced = Ed25519Math.reduce512AndShiftLeft3(rawAlice)
        assertEquals(
            "00f4f4563dba61b24551c122bbbb630855b1b5ed3f0a619792a0d9fd2a2cfb38",
            toHex(reduced),
        )

        val p = Ed25519Math.PRIME_P
        val mYpx = toHex(Ed25519Math.toLittleEndian32(Ed25519Math.POINT_M.y.add(Ed25519Math.POINT_M.x).mod(p)))
        val nYpx = toHex(Ed25519Math.toLittleEndian32(Ed25519Math.POINT_N.y.add(Ed25519Math.POINT_N.x).mod(p)))
        assertEquals("2281e2108ecfc8ee61c5af20398b9dc8c6cd8a1b617aca71e06b022031674174", mYpx)
        assertEquals("43fea4be2695ff8addd37128638631b03202a5d195806acffb4c07fcb63b2467", nYpx)

        val alice = AdbSpake2Session(AdbSpake2Session.Role.CLIENT, password, rawAlice)
        val bob = AdbSpake2Session(AdbSpake2Session.Role.SERVER, password, rawBob)
        assertEquals("135d85fa69022bbc7445653e19047e5b6981aa5b9d309b0de6d2e704dfe1568c", toHex(alice.publicMessage))
        assertEquals("d5bd4ead287e42f0a073adcb8dc46acc0630c4925fe1d43350e7441a8b29e03a", toHex(bob.publicMessage))

        val aliceKey = alice.computeSharedKeyMaterial(bob.publicMessage)
        val bobKey = bob.computeSharedKeyMaterial(alice.publicMessage)
        assertArrayEquals(aliceKey, bobKey)
    }

    @Test
    fun `AdbTransportClient pairs over real TLSv1_3 socket and executes STLS mutual TLS shell command`() {
        val clientKeys = AdbRsaKeyManager(File(context.cacheDir, "test_client_${System.nanoTime()}"))
        val serverKeys = AdbRsaKeyManager(File(context.cacheDir, "test_server_${System.nanoTime()}"))
        val deterministicExported = ByteArray(64) { idx -> (idx * 7 + 3).toByte() }
        val client = AdbTransportClient(clientKeys) { deterministicExported }
        val serverSslContext = AdbTransportClient(serverKeys).buildAdbSslContext()

        // 1. Real TLS 1.3 + SPAKE2 + AES-128-GCM PeerInfo pairing server test
        val sslServer = serverSslContext.serverSocketFactory
            .createServerSocket(0, 4, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        sslServer.enabledProtocols = arrayOf("TLSv1.3")
        sslServer.use { pairingServer ->
            var receivedClientPubKey = ""
            val serverThread = thread {
                (pairingServer.accept() as SSLSocket).use { tls ->
                    tls.enabledProtocols = arrayOf("TLSv1.3")
                    tls.startHandshake()
                    val dis = DataInputStream(tls.inputStream)
                    val out = tls.outputStream

                    val clientMsgHeader = ByteArray(6).also { dis.readFully(it) }
                    val msgBuf = ByteBuffer.wrap(clientMsgHeader).order(ByteOrder.BIG_ENDIAN)
                    assertEquals(1, msgBuf.get().toInt())
                    assertEquals(AdbTransportClient.PACKET_TYPE_SPAKE2_MSG.toInt(), msgBuf.get().toInt())
                    val clientSpakeMsg = ByteArray(msgBuf.int).also { dis.readFully(it) }

                    val serverSecret = "619402".encodeToByteArray() + deterministicExported
                    val bob = AdbSpake2Session(AdbSpake2Session.Role.SERVER, serverSecret)
                    val serverMsgFrame = ByteBuffer.allocate(6 + bob.publicMessage.size)
                        .order(ByteOrder.BIG_ENDIAN)
                        .put(1.toByte())
                        .put(AdbTransportClient.PACKET_TYPE_SPAKE2_MSG)
                        .putInt(bob.publicMessage.size)
                        .put(bob.publicMessage)
                        .array()
                    out.write(serverMsgFrame)
                    out.flush()

                    val serverCipher = bob.processPeerMessage(clientSpakeMsg)
                    val peerHeader = ByteArray(6).also { dis.readFully(it) }
                    val peerBuf = ByteBuffer.wrap(peerHeader).order(ByteOrder.BIG_ENDIAN)
                    assertEquals(1, peerBuf.get().toInt())
                    assertEquals(AdbTransportClient.PACKET_TYPE_PEER_INFO.toInt(), peerBuf.get().toInt())
                    val encPeerInfo = ByteArray(peerBuf.int).also { dis.readFully(it) }
                    val decPeerInfo = serverCipher.decrypt(encPeerInfo)
                    receivedClientPubKey = decPeerInfo.copyOfRange(1, 64).decodeToString()

                    val serverPeerInfo = serverCipher.encrypt(
                        AdbPairingCipher.buildPeerInfoPayload(serverKeys.buildAdbPublicKeyPayload("adbd@device")),
                    )
                    val replyFrame = ByteBuffer.allocate(6 + serverPeerInfo.size)
                        .order(ByteOrder.BIG_ENDIAN)
                        .put(1.toByte())
                        .put(AdbTransportClient.PACKET_TYPE_PEER_INFO)
                        .putInt(serverPeerInfo.size)
                        .put(serverPeerInfo)
                        .array()
                    out.write(replyFrame)
                    out.flush()
                }
            }

            client.pairWithLocalDaemon("127.0.0.1", pairingServer.localPort, "619402")
            serverThread.join(5_000L)
            assertTrue(receivedClientPubKey.isNotBlank())
        }

        // 2. Real CNXN -> STLS (mutual TLS 1.3 with client certificate!) -> OPEN("shell:id") test
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { connectServer ->
            var verifiedClientCertSubject = ""
            val serverThread = thread {
                connectServer.accept().use { raw ->
                    val plainIn = raw.getInputStream()
                    val plainOut = raw.getOutputStream()
                    val cnxn = AdbPacket.readFrom(plainIn)
                    assertEquals(AdbPacket.CMD_CNXN, cnxn.command)

                    AdbPacket(AdbPacket.CMD_STLS, AdbPacket.STLS_VERSION, 0).writeTo(plainOut)
                    val stlsReply = AdbPacket.readFrom(plainIn)
                    assertEquals(AdbPacket.CMD_STLS, stlsReply.command)

                    val tls = serverSslContext.socketFactory
                        .createSocket(raw, "127.0.0.1", connectServer.localPort, true) as SSLSocket
                    tls.useClientMode = false
                    tls.needClientAuth = true
                    tls.enabledProtocols = arrayOf("TLSv1.3")
                    tls.startHandshake()
                    verifiedClientCertSubject = (tls.session.peerCertificates.first()).toString()

                    val tlsIn = tls.inputStream
                    val tlsOut = tls.outputStream
                    AdbPacket(AdbPacket.CMD_CNXN, AdbPacket.ADB_VERSION, AdbPacket.MAX_PAYLOAD).writeTo(tlsOut)

                    val open = AdbPacket.readFrom(tlsIn)
                    assertEquals(AdbPacket.CMD_OPEN, open.command)
                    AdbPacket(AdbPacket.CMD_OKAY, 77, open.arg0).writeTo(tlsOut)
                    AdbPacket(
                        AdbPacket.CMD_WRTE,
                        77,
                        open.arg0,
                        "uid=2000(shell)\n".encodeToByteArray(),
                    ).writeTo(tlsOut)
                    AdbPacket.readFrom(tlsIn)
                    AdbPacket(AdbPacket.CMD_CLSE, 77, open.arg0).writeTo(tlsOut)
                }
            }

            val out = client.executeOneShotShell("127.0.0.1", connectServer.localPort, "id")
            serverThread.join(5_000L)
            assertEquals("uid=2000(shell)\n", out)
            assertTrue(verifiedClientCertSubject.contains("Routine Local ADB"))
        }
    }

    @Test
    fun `LoopbackRoutineShellDaemon authenticates HMAC challenge-response and rejects forged secrets`() {
        val secret = LoopbackShellCrypto.generateSecret()
        val forgedSecret = LoopbackShellCrypto.generateSecret()

        LoopbackRoutineShellDaemon(
            secretKey = secret,
            uidProvider = { 2000 },
            commandExecutor = { cmd, _ -> ShellCommandResult.Succeeded(stdout = "ran:$cmd", exitCode = 0) },
        ).use { daemon ->
            daemon.startInBackground()
            val validClient = LoopbackRoutineShellClient(daemon.localPort, secret)
            assertTrue(validClient.isAlive())
            assertEquals(2000, validClient.getServerUid())
            val execRes = validClient.executeCommand("svc wifi enable", 5_000L)
            assertEquals(ShellCommandResult.Succeeded(stdout = "ran:svc wifi enable", exitCode = 0), execRes)

            val forgedClient = LoopbackRoutineShellClient(daemon.localPort, forgedSecret)
            assertFalse(forgedClient.isAlive())
            assertTrue(forgedClient.executeCommand("id", 2_000L) is ShellCommandResult.Failed)
        }
    }

    @Test
    fun `AdbRsaKeyManager generates valid 524-byte Android RSA struct and self-signed X509 certificate`() {
        val keyDir = File(context.cacheDir, "test_rsa_${System.nanoTime()}")
        val keyManager = AdbRsaKeyManager(keyDir)
        assertFalse(keyManager.hasPersistedIdentity())
        val struct = AdbRsaKeyManager.encodeAndroidRsaPublicKeyStruct(keyManager.publicKey)
        assertTrue(keyManager.hasPersistedIdentity())
        assertEquals(AdbRsaKeyManager.ANDROID_RSA_PUBKEY_STRUCT_BYTES, struct.size)

        keyManager.certificate.verify(keyManager.publicKey)
        val sig = keyManager.signAuthToken(ByteArray(20) { 0x42 })
        assertEquals(256, sig.size)
    }

    @Test
    fun `AdbBridgePrivilegedEngine fulfills PrivilegedEngine contract when disconnected and connected`() = runTest {
        var activeShell: RoutineShellService? = null
        val executedCommands = mutableListOf<String>()
        val engine = AdbBridgePrivilegedEngine(
            context = context,
            discovery = null,
            shellServiceProvider = { activeShell },
        )

        assertFalse(engine.checkAvailability().isAvailable())
        assertTrue(engine.executeShellCommand("svc wifi disable") is ShellCommandResult.Failed)

        activeShell = object : RoutineShellService {
            override fun getServerUid(): Int = 2000
            override fun executeCommand(command: String, timeoutMs: Long): ShellCommandResult {
                executedCommands += command
                return ShellCommandResult.Succeeded(stdout = "ok", exitCode = 0)
            }
        }

        assertEquals(PrivilegeAvailability.AdbBridgeConnected(2000), engine.checkAvailability())
        val grantRes = engine.grantPermission("app.routine", "android.permission.WRITE_SECURE_SETTINGS")
        assertTrue(grantRes is PrivilegeOperationResult.Succeeded)
        val writeRes = engine.writeSecureSetting(SecureSettingNamespace.SECURE, "night_display_activated", "1")
        assertTrue(writeRes is PrivilegeOperationResult.Succeeded)
        assertEquals(2, executedCommands.size)
        assertTrue(executedCommands[0].contains("pm grant app.routine"))
        assertTrue(executedCommands[1].contains("settings put secure night_display_activated"))
    }

    @Test
    fun `WirelessAdbDiscovery and pairAndBootstrap degrade cleanly without SecurityException when INTERNET absent`() =
        runTest {
            assertFalse(WirelessAdbDiscovery.hasInternetPermission(context))
            val discovery = WirelessAdbDiscovery(context)
            val endpoints = discovery.observePairingEndpoints().toList()
            assertTrue(endpoints.isEmpty())

            val engine = AdbBridgePrivilegedEngine(
                context = context,
                discovery = discovery,
                shellServiceProvider = { null },
            )
            val pairResult = engine.pairAndBootstrap("127.0.0.1", 39999, "123456")
            assertTrue(pairResult is PrivilegeOperationResult.Failed)
        }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
