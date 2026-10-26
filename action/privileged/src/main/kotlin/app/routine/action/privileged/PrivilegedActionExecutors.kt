package app.routine.action.privileged

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import app.routine.core.engine.ActionExecutionOutcome
import app.routine.core.engine.ActionExecutor
import app.routine.core.model.Action
import app.routine.core.model.ActionType
import app.routine.core.model.TriggerEvent
import app.routine.privilege.api.CompositePrivilegedEngine
import app.routine.privilege.api.PrivilegeOperationResult
import app.routine.privilege.api.PrivilegedEngine
import app.routine.privilege.api.SecureSettingNamespace
import app.routine.privilege.api.ShellCommandResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Toggles device Wi-Fi via `svc wifi enable|disable` through [PrivilegedEngine], falling back
 * to Android's Quick Settings Wi-Fi panel when the ADB Bridge is offline.
 */
@Singleton
class WifiToggleActionExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val privilegedEngine: PrivilegedEngine,
) : ActionExecutor {
    override val supportedType: ActionType = ActionType.WIFI_TOGGLE

    override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome {
        val enabled = action.config["enabled"]?.toBooleanStrictOrNull() ?: false
        val subcommand = if (enabled) "enable" else "disable"
        return when (val shellResult = privilegedEngine.executeShellCommand("svc wifi $subcommand")) {
            is ShellCommandResult.Succeeded ->
                ActionExecutionOutcome.Success("Set Wi-Fi enabled=$enabled via ADB Bridge")
            is ShellCommandResult.Failed -> openWifiPanelFallback(shellResult.reason)
        }
    }

    private fun openWifiPanelFallback(reason: String): ActionExecutionOutcome {
        val panelAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Settings.Panel.ACTION_WIFI
        } else {
            Settings.ACTION_WIFI_SETTINGS
        }
        val intent = Intent(panelAction).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return runCatching {
            context.startActivity(intent)
            ActionExecutionOutcome.Success("Opened Wi-Fi settings panel fallback ($reason)", usedFallback = true)
        }.getOrElse { error ->
            ActionExecutionOutcome.Failure("Wi-Fi toggle failed ($reason) and panel failed: ${error.message}")
        }
    }
}

/**
 * Toggles Wi-Fi hotspot tethering via [PrivilegedEngine].
 */
@Singleton
class HotspotToggleActionExecutor @Inject constructor(
    private val privilegedEngine: PrivilegedEngine,
) : ActionExecutor {
    override val supportedType: ActionType = ActionType.HOTSPOT_TOGGLE

    override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome {
        val enabled = action.config["enabled"]?.toBooleanStrictOrNull() ?: false
        val command = if (enabled) {
            "cmd connectivity start-tethering wifi"
        } else {
            "cmd connectivity stop-tethering"
        }
        return when (val res = privilegedEngine.executeShellCommand(command)) {
            is ShellCommandResult.Succeeded -> ActionExecutionOutcome.Success("Set hotspot enabled=$enabled")
            is ShellCommandResult.Failed -> ActionExecutionOutcome.Failure("Hotspot toggle failed: ${res.reason}")
        }
    }
}

/**
 * Writes validated settings (`night_display_activated`, `screen_off_timeout`, or custom safe keys)
 * across `SECURE`, `GLOBAL`, and `SYSTEM` namespaces via [PrivilegedEngine.writeSecureSetting].
 */
@Singleton
class SecureSettingActionExecutor @Inject constructor(
    private val privilegedEngine: PrivilegedEngine,
) : ActionExecutor {
    override val supportedType: ActionType = ActionType.SECURE_SETTING

    override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome {
        val nsName = action.config["namespace"]?.uppercase() ?: "SECURE"
        val namespace = runCatching { SecureSettingNamespace.valueOf(nsName) }.getOrNull()
            ?: return ActionExecutionOutcome.Failure("Invalid settings namespace '$nsName'.")
        val key = action.config["key"]?.trim().orEmpty()
        val value = action.config["value"].orEmpty()
        if (!PrivilegedEngine.isSafeIdentifier(key)) {
            return ActionExecutionOutcome.Failure("Invalid setting key '$key'.")
        }
        return when (val res = privilegedEngine.writeSecureSetting(namespace, key, value)) {
            is PrivilegeOperationResult.Succeeded -> ActionExecutionOutcome.Success(res.detail)
            is PrivilegeOperationResult.Failed -> ActionExecutionOutcome.Failure(res.reason)
        }
    }
}

/**
 * Executes a validated shell command via [PrivilegedEngine.executeShellCommand], blocking destructive commands.
 */
@Singleton
class ShellCommandActionExecutor @Inject constructor(
    private val privilegedEngine: PrivilegedEngine,
) : ActionExecutor {
    override val supportedType: ActionType = ActionType.SHELL_COMMAND

    override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome {
        val command = action.config["command"]?.trim().orEmpty()
        if (command.isEmpty()) {
            return ActionExecutionOutcome.Failure("Shell command must not be empty.")
        }
        if (isDestructiveCommand(command)) {
            return ActionExecutionOutcome.Failure("Blocked potentially destructive shell command.")
        }
        return when (val res = privilegedEngine.executeShellCommand(command)) {
            is ShellCommandResult.Succeeded -> ActionExecutionOutcome.Success("Shell command exited 0")
            is ShellCommandResult.Failed -> ActionExecutionOutcome.Failure("Shell command failed: ${res.reason}")
        }
    }

    companion object {
        private val DESTRUCTIVE_PATTERNS = listOf(
            Regex("\\brm\\s+-[rRfF]*\\s+/"),
            Regex("\\bmkfs\\b"),
            Regex("\\bdd\\s+if="),
            Regex("\\breboot\\s+(bootloader|fastboot|recovery)\\b"),
        )

        fun isDestructiveCommand(command: String): Boolean = DESTRUCTIVE_PATTERNS.any { it.containsMatchIn(command) }
    }
}

/**
 * Hilt multibindings registering all privileged-tier [ActionExecutor]s and [CompositePrivilegedEngine].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PrivilegedActionsModule {
    @Binds
    @Singleton
    abstract fun bindCompositePrivilegedEngine(impl: CompositePrivilegedEngine): PrivilegedEngine

    @Binds
    @IntoSet
    abstract fun bindWifiToggle(executor: WifiToggleActionExecutor): ActionExecutor

    @Binds
    @IntoSet
    abstract fun bindHotspotToggle(executor: HotspotToggleActionExecutor): ActionExecutor

    @Binds
    @IntoSet
    abstract fun bindSecureSetting(executor: SecureSettingActionExecutor): ActionExecutor

    @Binds
    @IntoSet
    abstract fun bindShellCommand(executor: ShellCommandActionExecutor): ActionExecutor
}
