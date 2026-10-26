package app.routine.action.standard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import app.routine.core.engine.ActionExecutionOutcome
import app.routine.core.engine.ActionExecutor
import app.routine.core.model.Action
import app.routine.core.model.ActionType
import app.routine.core.model.TriggerEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Posts structured local notifications on Routine's automation channel without any network access.
 */
@Singleton
class NotifyActionExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
) : ActionExecutor {
    override val supportedType: ActionType = ActionType.NOTIFY

    override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome {
        val title = action.config["title"]?.takeIf { it.isNotBlank() }
            ?: return ActionExecutionOutcome.Failure("Missing notification title.")
        val baseMessage = action.config["message"]?.takeIf { it.isNotBlank() }
            ?: return ActionExecutionOutcome.Failure("Missing notification message.")
        val includeBriefing = action.config["includeLocalBriefing"]?.toBooleanStrictOrNull() ?: false
        val body = if (includeBriefing) {
            val formattedTime = SimpleDateFormat("EEE HH:mm", Locale.US).format(Date())
            "$baseMessage ($formattedTime)"
        } else {
            baseMessage
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        val notificationId = (title.hashCode() xor System.nanoTime().toInt()) and 0x7FFFFFFF
        manager.notify(notificationId, notification)
        return ActionExecutionOutcome.Success("Posted notification '$title'")
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Routine Automations",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Notifications triggered by active Routine rules"
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID: String = "routine_automations_channel"
    }
}

/**
 * Displays a short user-visible [Toast] on the main thread.
 */
@Singleton
class ToastActionExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
) : ActionExecutor {
    override val supportedType: ActionType = ActionType.TOAST

    override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome {
        val message = action.config["message"]?.takeIf { it.isNotBlank() }
            ?: return ActionExecutionOutcome.Failure("Missing toast message.")
        withContext(Dispatchers.Main.immediate) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        return ActionExecutionOutcome.Success("Displayed toast")
    }
}

/**
 * Adjusts the device audio ringer mode (`SILENT`, `VIBRATE`, or `NORMAL`).
 */
@Singleton
class RingerActionExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
) : ActionExecutor {
    override val supportedType: ActionType = ActionType.RINGER_MODE

    override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome {
        val modeName = action.config["mode"]?.uppercase() ?: "SILENT"
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (modeName == "SILENT" && !notificationManager.isNotificationPolicyAccessGranted) {
            return ActionExecutionOutcome.Failure("Do Not Disturb policy access is required for SILENT ringer mode.")
        }

        val targetMode = when (modeName) {
            "SILENT" -> AudioManager.RINGER_MODE_SILENT
            "VIBRATE" -> AudioManager.RINGER_MODE_VIBRATE
            "NORMAL" -> AudioManager.RINGER_MODE_NORMAL
            else -> return ActionExecutionOutcome.Failure("Unsupported ringer mode '$modeName'.")
        }
        audioManager.ringerMode = targetMode
        return ActionExecutionOutcome.Success("Set ringer mode to $modeName")
    }
}

/**
 * Launches an installed app by explicit `packageName` or standard app category (`MUSIC`, `NEWS`).
 */
@Singleton
class OpenAppActionExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
) : ActionExecutor {
    override val supportedType: ActionType = ActionType.OPEN_APP

    override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome {
        val packageName = action.config["packageName"]?.trim().orEmpty()
        val category = action.config["category"]?.trim()?.uppercase().orEmpty()

        val launchIntent: Intent? = when {
            packageName.isNotEmpty() -> context.packageManager.getLaunchIntentForPackage(packageName)
            category == "MUSIC" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
            category == "NEWS" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
            else -> null
        }

        if (launchIntent == null) {
            val targetLabel = packageName.ifEmpty { category.ifEmpty { "unspecified" } }
            return ActionExecutionOutcome.Failure("Unable to resolve launch intent for '$targetLabel'.")
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launchIntent)
            ActionExecutionOutcome.Success("Launched app (${packageName.ifEmpty { category }})")
        }.getOrElse { error ->
            ActionExecutionOutcome.Failure("Failed to start activity: ${error.message}")
        }
    }
}

/**
 * Opens a URI using `Intent.ACTION_VIEW`.
 */
@Singleton
class OpenUrlActionExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
) : ActionExecutor {
    override val supportedType: ActionType = ActionType.OPEN_URL

    override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome {
        val rawUrl = action.config["url"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ActionExecutionOutcome.Failure("Missing URL parameter.")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(rawUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ActionExecutionOutcome.Success("Opened URI '$rawUrl'")
        }.getOrElse { error ->
            ActionExecutionOutcome.Failure("Failed to open URI: ${error.message}")
        }
    }
}

/**
 * Writes system screen brightness (`0..100`% mapped onto Android's `0..255` range).
 */
@Singleton
class BrightnessActionExecutor(
    private val context: Context,
    private val canWriteSettingsCheck: (Context) -> Boolean = { Settings.System.canWrite(it) },
) : ActionExecutor {
    override val supportedType: ActionType = ActionType.BRIGHTNESS

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context = context,
        canWriteSettingsCheck = { Settings.System.canWrite(it) },
    )

    override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome {
        val percent = action.config["percent"]?.toIntOrNull()
        if (percent == null || percent !in 0..100) {
            return ActionExecutionOutcome.Failure("Brightness percent must be in 0..100.")
        }
        if (!canWriteSettingsCheck(context)) {
            return ActionExecutionOutcome.Failure("Modify System Settings permission is not granted.")
        }
        val rawBrightness = ((percent * 255) / 100).coerceIn(0, 255)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, rawBrightness)
        return ActionExecutionOutcome.Success("Set brightness to $percent% ($rawBrightness/255)")
    }
}
