package app.routine.privilege.adb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.routine.privilege.adb.binder.RoutineShellService
import app.routine.privilege.adb.crypto.AdbPairingCipher
import app.routine.privilege.adb.crypto.AdbRsaKeyManager
import app.routine.privilege.adb.crypto.AdbSpake2Session
import app.routine.privilege.adb.discovery.WirelessAdbDiscovery
import app.routine.privilege.adb.transport.AdbPacket
import app.routine.privilege.adb.transport.AdbTransportClient
import app.routine.privilege.api.PrivilegeAvailability
import app.routine.privilege.api.PrivilegeOperationResult
import app.routine.privilege.api.SecureSettingNamespace
import app.routine.privilege.api.ShellCommandResult
import java.io.File
import java.net.ServerSocket
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
    fun `Ed25519 SPAKE2 client and server derive matching AES-128-GCM session keys and exchange PeerInfo`() {
        val sharedPairingSecret = "482915".encodeToByteArray() + ByteArray(64) { (it * 3).toByte() }
        val client = AdbSpake2Session(AdbSpake2Session.Role.CLIENT, sharedPairingSecret)
        val server = AdbSpake2Session(AdbSpake2Session.Role.SERVER, sharedPairingSecret)

        val clientCipher = client.processPeerMessage(server.publicMessage)
        val serverCipher = server.processPeerMessage(client.publicMessage)

        val keyDir = File(context.cacheDir, "test_adb_keys_${System.nanoTime()}")
        val keyManager = AdbRsaKeyManager(keyDir)
        val peerInfoPlain = AdbPairingCipher.buildPeerInfoPayload(keyManager.buildAdbPublicKeyPayload())

        val encryptedFromClient = clientCipher.encrypt(peerInfoPlain)
        val decryptedAtServer = serverCipher.decrypt(encryptedFromClient)
        assertArrayEquals(peerInfoPlain, decryptedAtServer)
    }

    @Test
    fun `AdbRsaKeyManager generates valid 524-byte Android RSA struct and self-signed X509 certificate`() {
        val keyDir = File(context.cacheDir, "test_rsa_${System.nanoTime()}")
        val keyManager = AdbRsaKeyManager(keyDir)
        val struct = AdbRsaKeyManager.encodeAndroidRsaPublicKeyStruct(keyManager.publicKey)
        assertEquals(AdbRsaKeyManager.ANDROID_RSA_PUBKEY_STRUCT_BYTES, struct.size)

        keyManager.certificate.verify(keyManager.publicKey)
        val sig = keyManager.signAuthToken(ByteArray(20) { 0x42 })
        assertEquals(256, sig.size)
    }

    @Test
    fun `AdbTransportClient executes AUTH and shell command over local loopback mock adbd`() {
        val keyDir = File(context.cacheDir, "test_transport_${System.nanoTime()}")
        val keyManager = AdbRsaKeyManager(keyDir)
        val client = AdbTransportClient(keyManager)

        ServerSocket(0).use { serverSocket ->
            val port = serverSocket.localPort
            val serverThread = thread {
                serverSocket.accept().use { sock ->
                    val input = sock.getInputStream()
                    val output = sock.getOutputStream()

                    val cnxn = AdbPacket.readFrom(input)
                    assertEquals(AdbPacket.CMD_CNXN, cnxn.command)

                    AdbPacket(AdbPacket.CMD_AUTH, AdbPacket.AUTH_TYPE_TOKEN, 0, ByteArray(20) { 7 }).writeTo(output)
                    val authSig = AdbPacket.readFrom(input)
                    assertEquals(AdbPacket.CMD_AUTH, authSig.command)
                    assertEquals(AdbPacket.AUTH_TYPE_SIGNATURE, authSig.arg0)

                    AdbPacket(AdbPacket.CMD_CNXN, AdbPacket.ADB_VERSION, AdbPacket.MAX_PAYLOAD).writeTo(output)

                    val open = AdbPacket.readFrom(input)
                    assertEquals(AdbPacket.CMD_OPEN, open.command)
                    AdbPacket(AdbPacket.CMD_OKAY, 42, open.arg0).writeTo(output)
                    AdbPacket(
                        AdbPacket.CMD_WRTE,
                        42,
                        open.arg0,
                        "uid=2000(shell)\n".encodeToByteArray(),
                    ).writeTo(output)
                    val ack = AdbPacket.readFrom(input)
                    assertEquals(AdbPacket.CMD_OKAY, ack.command)
                    AdbPacket(AdbPacket.CMD_CLSE, 42, open.arg0).writeTo(output)
                }
            }

            val output = client.executeOneShotShell("127.0.0.1", port, "id")
            serverThread.join(3_000L)
            assertEquals("uid=2000(shell)\n", output)
        }
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
}
