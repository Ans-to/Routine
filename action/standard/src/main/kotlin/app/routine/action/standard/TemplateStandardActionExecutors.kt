package app.routine.action.standard

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import app.routine.core.engine.ActionExecutionOutcome
import app.routine.core.engine.ActionExecutor
import app.routine.core.model.Action
import app.routine.core.model.ActionType
import app.routine.core.model.TriggerEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Toggles Android's Do Not Disturb interruption filter using `NotificationManager`.
 */
@Singleton
class DoNotDisturbActionExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
) : ActionExecutor {
    override val supportedType: ActionType = ActionType.DO_NOT_DISTURB

    override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            return ActionExecutionOutcome.Failure("Notification Policy Access is required to toggle Do Not Disturb.")
        }
        val enabled = action.config["enabled"]?.toBooleanStrictOrNull() ?: true
        val filter = if (enabled) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY
        } else {
            NotificationManager.INTERRUPTION_FILTER_ALL
        }
        notificationManager.setInterruptionFilter(filter)
        return ActionExecutionOutcome.Success("Set Do Not Disturb enabled=$enabled")
    }
}

/**
 * Dispatches a deterministic driving auto-reply via SMS intent or `SmsManager`.
 */
@Singleton
class SmsAutoReplyActionExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
) : ActionExecutor {
    override val supportedType: ActionType = ActionType.SMS_AUTO_REPLY

    override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome {
        val replyBody = action.config["message"]?.takeIf { it.isNotBlank() }
            ?: "I'm currently driving and will reply shortly."
        val recipient = event?.attributes?.get("senderNumber")
            ?: action.config["recipient"].orEmpty()
        val mode = action.config["mode"]?.uppercase() ?: "INTENT"

        if (mode == "DIRECT" && recipient.isNotBlank()) {
            val hasSmsPerm = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.SEND_SMS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasSmsPerm) {
                val smsManager = context.getSystemService(SmsManager::class.java)
                if (smsManager != null) {
                    smsManager.sendTextMessage(recipient, null, replyBody, null, null)
                    return ActionExecutionOutcome.Success("Sent direct SMS auto-reply")
                }
            }
        }

        val smsUri = Uri.parse("smsto:$recipient")
        val smsIntent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
            putExtra("sms_body", replyBody)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(smsIntent)
            ActionExecutionOutcome.Success("Prepared SMS auto-reply intent", usedFallback = mode == "DIRECT")
        }.getOrElse { error ->
            ActionExecutionOutcome.Failure("Unable to launch SMS auto-reply intent: ${error.message}")
        }
    }
}

/**
 * Checks `PowerManager.isPowerSaveMode` and opens the standard Battery Saver settings action when needed.
 */
@Singleton
class BatterySaverActionExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
) : ActionExecutor {
    override val supportedType: ActionType = ActionType.BATTERY_SAVER

    override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isPowerSaveMode) {
            return ActionExecutionOutcome.Success("Battery Saver is already active")
        }
        val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ActionExecutionOutcome.Success("Opened Battery Saver activation prompt", usedFallback = true)
        }.getOrElse { error ->
            ActionExecutionOutcome.Failure("Unable to open Battery Saver settings: ${error.message}")
        }
    }
}

/**
 * Locks or unlocks screen auto-rotation using `Settings.System.ACCELEROMETER_ROTATION`.
 */
@Singleton
class RotationLockActionExecutor(
    private val context: Context,
    private val canWriteSettingsCheck: (Context) -> Boolean = { Settings.System.canWrite(it) },
) : ActionExecutor {
    override val supportedType: ActionType = ActionType.ROTATION_LOCK

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context = context,
        canWriteSettingsCheck = { Settings.System.canWrite(it) },
    )

    override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome {
        if (!canWriteSettingsCheck(context)) {
            return ActionExecutionOutcome.Failure("Modify System Settings permission is required for rotation lock.")
        }
        val locked = action.config["locked"]?.toBooleanStrictOrNull() ?: true
        val accelerometerValue = if (locked) 0 else 1
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            accelerometerValue,
        )
        return ActionExecutionOutcome.Success("Set rotation locked=$locked")
    }
}

/**
 * Coordinates unknown-caller silencing policy recorded when `CallScreeningService` evaluates an incoming call.
 */
@Singleton
class SilenceUnknownCallerActionExecutor @Inject constructor() : ActionExecutor {
    override val supportedType: ActionType = ActionType.SILENCE_UNKNOWN_CALLER

    override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome {
        val reject = action.config["reject"]?.toBooleanStrictOrNull() ?: false
        val verb = if (reject) "Rejected" else "Silenced"
        return ActionExecutionOutcome.Success("$verb incoming unknown caller via CallScreeningService")
    }
}
