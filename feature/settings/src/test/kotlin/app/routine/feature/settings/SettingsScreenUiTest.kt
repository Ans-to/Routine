package app.routine.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.routine.core.data.repository.UserPreferences
import app.routine.core.permissions.PermissionSnapshot
import app.routine.privilege.api.PrivilegeAvailability
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsScreenUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `recheck status button triggers privilege refresh in SettingsContent`() {
        var recheckClicked = false
        val qr = Array(33) { BooleanArray(33) }
        composeTestRule.setContent {
            MaterialTheme {
                SettingsContent(
                    preferences = UserPreferences(),
                    snapshot = PermissionSnapshot(emptySet()),
                    availability = PrivilegeAvailability.OneTimeGrantActive,
                    oneTimeCommand = "adb shell pm grant app.routine android.permission.WRITE_SECURE_SETTINGS",
                    qrMatrix = qr,
                    discoveredEndpoint = null,
                    statusBanner = "Privileged Tier Active",
                    onRecheckStatus = { recheckClicked = true },
                    onCopyCommand = {},
                    onSetScanning = {},
                    onSendPairingNotification = {},
                    onPairDirect = { _, _ -> },
                    onSetAutoReconnect = {},
                    onExportBackup = {},
                    onImportBackup = {},
                    onSetThemeMode = {},
                    onSetDynamicColor = {},
                    onReplayOnboarding = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("settings_status_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_recheck_privilege_button").performClick()
        assertTrue(recheckClicked)
    }
}
