package app.routine.action.standard

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.routine.core.engine.ActionExecutionOutcome
import app.routine.core.model.Action
import app.routine.core.model.ActionType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class StandardActionExecutorsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `notify executor posts notification to manager`() = runTest {
        val executor = NotifyActionExecutor(context)
        val outcome = executor.execute(
            Action(
                ActionType.NOTIFY,
                mapOf("title" to "Storage Low", "message" to "Free space below 10%", "includeLocalBriefing" to "true"),
            ),
        )
        assertTrue(outcome is ActionExecutionOutcome.Success)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals(1, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun `toast executor shows toast on main thread`() = runTest {
        val executor = ToastActionExecutor(context)
        val outcome = executor.execute(Action(ActionType.TOAST, mapOf("message" to "Rule fired!")))
        assertTrue(outcome is ActionExecutionOutcome.Success)
        assertEquals("Rule fired!", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `ringer executor sets vibrate and silent modes respecting policy access`() = runTest {
        val executor = RingerActionExecutor(context)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val vibrateOutcome = executor.execute(Action(ActionType.RINGER_MODE, mapOf("mode" to "VIBRATE")))
        assertTrue(vibrateOutcome is ActionExecutionOutcome.Success)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)

        // Without DND policy access, SILENT returns structured Failure
        shadowOf(nm).setNotificationPolicyAccessGranted(false)
        val blockedSilent = executor.execute(Action(ActionType.RINGER_MODE, mapOf("mode" to "SILENT")))
        assertTrue(blockedSilent is ActionExecutionOutcome.Failure)

        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        val silentOutcome = executor.execute(Action(ActionType.RINGER_MODE, mapOf("mode" to "SILENT")))
        assertTrue(silentOutcome is ActionExecutionOutcome.Success)
    }

    @Test
    fun `open url and open app executors start expected intents`() = runTest {
        val urlExecutor = OpenUrlActionExecutor(context)
        val urlOutcome = urlExecutor.execute(Action(ActionType.OPEN_URL, mapOf("url" to "https://f-droid.org")))
        assertTrue(urlOutcome is ActionExecutionOutcome.Success)
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started.action)

        val appExecutor = OpenAppActionExecutor(context)
        val musicOutcome = appExecutor.execute(Action(ActionType.OPEN_APP, mapOf("category" to "MUSIC")))
        assertTrue(musicOutcome is ActionExecutionOutcome.Success)
    }

    @Test
    fun `brightness and rotation lock executors update Settings System when permitted`() = runTest {
        val brightness = BrightnessActionExecutor(context, canWriteSettingsCheck = { true })
        val bOutcome = brightness.execute(Action(ActionType.BRIGHTNESS, mapOf("percent" to "20")))
        assertTrue(bOutcome is ActionExecutionOutcome.Success)
        assertEquals(51, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))

        val rotation = RotationLockActionExecutor(context, canWriteSettingsCheck = { true })
        val rOutcome = rotation.execute(Action(ActionType.ROTATION_LOCK, mapOf("locked" to "true")))
        assertTrue(rOutcome is ActionExecutionOutcome.Success)
        assertEquals(0, Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION))
    }

    @Test
    fun `dnd, sms auto-reply, battery saver, and unknown caller executors execute deterministically`() = runTest {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        val dnd = DoNotDisturbActionExecutor(context)
        val dndOutcome = dnd.execute(Action(ActionType.DO_NOT_DISTURB, mapOf("enabled" to "true")))
        assertTrue(dndOutcome is ActionExecutionOutcome.Success)

        val sms = SmsAutoReplyActionExecutor(context)
        assertTrue(
            sms.execute(Action(ActionType.SMS_AUTO_REPLY, mapOf("message" to "Driving", "mode" to "INTENT")))
                is ActionExecutionOutcome.Success,
        )

        val saver = BatterySaverActionExecutor(context)
        assertTrue(saver.execute(Action(ActionType.BATTERY_SAVER)) is ActionExecutionOutcome.Success)

        val call = SilenceUnknownCallerActionExecutor()
        assertTrue(call.execute(Action(ActionType.SILENCE_UNKNOWN_CALLER)) is ActionExecutionOutcome.Success)
    }
}
