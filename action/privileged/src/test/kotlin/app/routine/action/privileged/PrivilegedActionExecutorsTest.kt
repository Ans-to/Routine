package app.routine.action.privileged

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.routine.core.engine.ActionExecutionOutcome
import app.routine.core.model.Action
import app.routine.core.model.ActionType
import app.routine.privilege.api.PrivilegeAvailability
import app.routine.privilege.api.PrivilegeOperationResult
import app.routine.privilege.api.PrivilegedEngine
import app.routine.privilege.api.SecureSettingNamespace
import app.routine.privilege.api.ShellCommandResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrivilegedActionExecutorsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private class RecordingPrivilegedEngine(
        var shellAvailable: Boolean = true,
    ) : PrivilegedEngine {
        val commands = mutableListOf<String>()
        val settingsWritten = mutableMapOf<String, String>()

        override suspend fun checkAvailability(): PrivilegeAvailability = if (shellAvailable) {
            PrivilegeAvailability.AdbBridgeConnected(2000)
        } else {
            PrivilegeAvailability.OneTimeGrantActive
        }

        override suspend fun executeShellCommand(command: String, timeoutMs: Long): ShellCommandResult =
            if (shellAvailable) {
                commands += command
                ShellCommandResult.Succeeded(stdout = "ok")
            } else {
                ShellCommandResult.Failed("Bridge disconnected")
            }

        override suspend fun grantPermission(packageName: String, permission: String): PrivilegeOperationResult =
            PrivilegeOperationResult.Succeeded("granted")

        override suspend fun writeSecureSetting(
            namespace: SecureSettingNamespace,
            key: String,
            value: String,
        ): PrivilegeOperationResult {
            settingsWritten["${namespace.name}:$key"] = value
            return PrivilegeOperationResult.Succeeded("wrote $key=$value")
        }
    }

    @Test
    fun `wifi toggle executes svc wifi and falls back to panel when bridge is offline`() = runTest {
        val engine = RecordingPrivilegedEngine(shellAvailable = true)
        val wifiExec = WifiToggleActionExecutor(context, engine)

        val direct = wifiExec.execute(Action(ActionType.WIFI_TOGGLE, mapOf("enabled" to "false")))
        assertTrue(direct is ActionExecutionOutcome.Success)
        assertEquals(listOf("svc wifi disable"), engine.commands)

        engine.shellAvailable = false
        val fallback = wifiExec.execute(Action(ActionType.WIFI_TOGGLE, mapOf("enabled" to "false")))
        assertTrue(fallback is ActionExecutionOutcome.Success)
        assertTrue((fallback as ActionExecutionOutcome.Success).usedFallback)
    }

    @Test
    fun `hotspot, secure setting presets, and shell executors run safely`() = runTest {
        val engine = RecordingPrivilegedEngine(shellAvailable = true)

        val hotspot = HotspotToggleActionExecutor(engine)
        val hotspotOutcome = hotspot.execute(Action(ActionType.HOTSPOT_TOGGLE, mapOf("enabled" to "false")))
        assertTrue(hotspotOutcome is ActionExecutionOutcome.Success)

        val secureSetting = SecureSettingActionExecutor(engine)
        val nightOutcome = secureSetting.execute(
            Action(
                ActionType.SECURE_SETTING,
                mapOf("namespace" to "SECURE", "key" to "night_display_activated", "value" to "1"),
            ),
        )
        assertTrue(nightOutcome is ActionExecutionOutcome.Success)
        assertEquals("1", engine.settingsWritten["SECURE:night_display_activated"])

        val timeoutOutcome = secureSetting.execute(
            Action(
                ActionType.SECURE_SETTING,
                mapOf("namespace" to "SYSTEM", "key" to "screen_off_timeout", "value" to "30000"),
            ),
        )
        assertTrue(timeoutOutcome is ActionExecutionOutcome.Success)
        assertEquals("30000", engine.settingsWritten["SYSTEM:screen_off_timeout"])

        val shell = ShellCommandActionExecutor(engine)
        val shellOutcome = shell.execute(Action(ActionType.SHELL_COMMAND, mapOf("command" to "input keyevent 85")))
        assertTrue(shellOutcome is ActionExecutionOutcome.Success)
        val blocked = shell.execute(Action(ActionType.SHELL_COMMAND, mapOf("command" to "rm -rf /data")))
        assertTrue(blocked is ActionExecutionOutcome.Failure)
    }
}
