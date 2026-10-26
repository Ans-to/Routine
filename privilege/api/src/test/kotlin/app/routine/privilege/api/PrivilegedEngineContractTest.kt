package app.routine.privilege.api

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedEngineContractTest {
    private class FakeEngine(
        var availability: PrivilegeAvailability,
        var shellHandler: (String) -> ShellCommandResult = {
            ShellCommandResult.Failed("Shell unsupported in this tier")
        },
        var grantHandler: (String, String) -> PrivilegeOperationResult = { pkg, perm ->
            PrivilegeOperationResult.Succeeded("Granted $perm to $pkg")
        },
        var writeHandler: (SecureSettingNamespace, String, String) -> PrivilegeOperationResult = { ns, k, v ->
            PrivilegeOperationResult.Succeeded("Wrote $ns:$k=$v")
        },
    ) : PrivilegedEngine {
        override suspend fun checkAvailability(): PrivilegeAvailability = availability

        override suspend fun executeShellCommand(command: String, timeoutMs: Long): ShellCommandResult =
            shellHandler(command)

        override suspend fun grantPermission(packageName: String, permission: String): PrivilegeOperationResult =
            grantHandler(packageName, permission)

        override suspend fun writeSecureSetting(
            namespace: SecureSettingNamespace,
            key: String,
            value: String,
        ): PrivilegeOperationResult = writeHandler(namespace, key, value)
    }

    @Test
    fun `composite engine prioritizes ADB bridge for availability and shell commands`() = runTest {
        val bridge = FakeEngine(
            availability = PrivilegeAvailability.AdbBridgeConnected(uid = 2000),
            shellHandler = { cmd -> ShellCommandResult.Succeeded(stdout = "ran:$cmd", exitCode = 0) },
        )
        val grant = FakeEngine(availability = PrivilegeAvailability.OneTimeGrantActive)
        val composite = CompositePrivilegedEngine(bridge, grant)

        val availability = composite.checkAvailability()
        assertTrue(availability.isAvailable())
        assertEquals(PrivilegeAvailability.AdbBridgeConnected(2000), availability)

        val shellRes = composite.executeShellCommand("svc wifi disable")
        assertTrue(shellRes is ShellCommandResult.Succeeded)
        assertEquals("ran:svc wifi disable", (shellRes as ShellCommandResult.Succeeded).stdout)

        val blankRes = composite.executeShellCommand("   ")
        assertTrue(blankRes is ShellCommandResult.Failed)
    }

    @Test
    fun `composite engine uses direct OneTimeGrant for secure settings and falls back cleanly`() = runTest {
        val bridge = FakeEngine(availability = PrivilegeAvailability.NotConfigured("Disconnected"))
        val grant = FakeEngine(availability = PrivilegeAvailability.OneTimeGrantActive)
        val composite = CompositePrivilegedEngine(bridge, grant)

        assertEquals(PrivilegeAvailability.OneTimeGrantActive, composite.checkAvailability())
        val writeRes = composite.writeSecureSetting(
            SecureSettingNamespace.SECURE,
            "night_display_activated",
            "1",
        )
        assertTrue(writeRes is PrivilegeOperationResult.Succeeded)

        // When only bridge is available, writeSecureSetting delegates to bridge
        grant.availability = PrivilegeAvailability.NotConfigured("Revoked")
        bridge.availability = PrivilegeAvailability.AdbBridgeConnected(2000)
        val bridgeWrite = composite.writeSecureSetting(
            SecureSettingNamespace.GLOBAL,
            "adb_wifi_enabled",
            "1",
        )
        assertTrue(bridgeWrite is PrivilegeOperationResult.Succeeded)

        // When neither is available, writeSecureSetting returns structured Failed
        bridge.availability = PrivilegeAvailability.NotConfigured("Offline")
        assertFalse(composite.checkAvailability().isAvailable())
        val neitherWrite = composite.writeSecureSetting(
            SecureSettingNamespace.SECURE,
            "night_display_activated",
            "0",
        )
        assertTrue(neitherWrite is PrivilegeOperationResult.Failed)
    }

    @Test
    fun `identifier validation blocks shell metacharacters and allows valid Android identifiers`() {
        assertTrue(PrivilegedEngine.isSafeIdentifier("app.routine"))
        assertTrue(PrivilegedEngine.isSafeIdentifier("android.permission.WRITE_SECURE_SETTINGS"))
        assertTrue(PrivilegedEngine.isSafeIdentifier("night_display_activated"))
        assertFalse(PrivilegedEngine.isSafeIdentifier(""))
        assertFalse(PrivilegedEngine.isSafeIdentifier("night_display; rm -rf /"))
        assertFalse(PrivilegedEngine.isSafeIdentifier("pkg name"))
    }

    @Test
    fun `grantPermission rejects unsafe identifiers and routes to active tier`() = runTest {
        val bridge = FakeEngine(availability = PrivilegeAvailability.AdbBridgeConnected(2000))
        val grant = FakeEngine(availability = PrivilegeAvailability.NotConfigured())
        val composite = CompositePrivilegedEngine(bridge, grant)

        val bad = composite.grantPermission("app.routine;id", "android.permission.DUMP")
        assertTrue(bad is PrivilegeOperationResult.Failed)

        val ok = composite.grantPermission("app.routine", "android.permission.WRITE_SECURE_SETTINGS")
        assertTrue(ok is PrivilegeOperationResult.Succeeded)

        bridge.availability = PrivilegeAvailability.NotConfigured()
        grant.availability = PrivilegeAvailability.OneTimeGrantActive
        val fallbackGrant = composite.grantPermission("app.routine", "android.permission.WRITE_SECURE_SETTINGS")
        assertTrue(fallbackGrant is PrivilegeOperationResult.Succeeded)

        val endpoint = PairingEndpointInfo(host = "127.0.0.1", port = 39123, label = "debug")
        assertEquals(39123, endpoint.port)
        assertEquals("127.0.0.1", endpoint.host)
        assertEquals("debug", endpoint.label)
    }
}
