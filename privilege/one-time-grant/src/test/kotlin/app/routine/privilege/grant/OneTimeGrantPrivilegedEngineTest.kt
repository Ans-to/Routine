package app.routine.privilege.grant

import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.routine.privilege.api.PrivilegeAvailability
import app.routine.privilege.api.PrivilegeOperationResult
import app.routine.privilege.api.SecureSettingNamespace
import app.routine.privilege.api.ShellCommandResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OneTimeGrantPrivilegedEngineTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `one-time grant engine writes Settings Secure and Global when granted and blocks when ungranted`() = runTest {
        var granted = false
        val engine = OneTimeGrantPrivilegedEngine(context) { granted }

        assertFalse(engine.checkAvailability().isAvailable())
        val deniedWrite = engine.writeSecureSetting(
            SecureSettingNamespace.SECURE,
            "night_display_activated",
            "1",
        )
        assertTrue(deniedWrite is PrivilegeOperationResult.Failed)

        granted = true
        assertEquals(PrivilegeAvailability.OneTimeGrantActive, engine.checkAvailability())

        val secureWrite = engine.writeSecureSetting(
            SecureSettingNamespace.SECURE,
            "night_display_activated",
            "1",
        )
        assertTrue(secureWrite is PrivilegeOperationResult.Succeeded)
        assertEquals("1", Settings.Secure.getString(context.contentResolver, "night_display_activated"))

        val globalWrite = engine.writeSecureSetting(
            SecureSettingNamespace.GLOBAL,
            "wifi_on",
            "0",
        )
        assertTrue(globalWrite is PrivilegeOperationResult.Succeeded)
        assertEquals("0", Settings.Global.getString(context.contentResolver, "wifi_on"))

        val shellRes = engine.executeShellCommand("svc wifi disable")
        assertTrue(shellRes is ShellCommandResult.Failed)
    }

    @Test
    fun `setup helper copies exact adb command to clipboard and generates valid 33x33 QR matrix`() {
        val copied = OneTimeGrantSetupHelper.copyCommandToClipboard(context)
        assertTrue(copied.contains("adb shell pm grant"))
        assertTrue(copied.contains(OneTimeGrantConstants.WRITE_SECURE_SETTINGS_PERMISSION))

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(copied, clipboard.primaryClip?.getItemAt(0)?.text?.toString())

        val matrix = OneTimeGrantSetupHelper.encodeQrMatrix(copied)
        assertEquals(33, matrix.size)
        assertEquals(33, matrix[0].size)
        // Finder pattern top-left outer corners and center must be dark
        assertTrue(matrix[0][0])
        assertTrue(matrix[0][6])
        assertTrue(matrix[6][0])
        assertTrue(matrix[3][3])
    }
}
