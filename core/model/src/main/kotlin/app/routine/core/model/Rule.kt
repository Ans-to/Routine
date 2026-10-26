package app.routine.core.model

import kotlinx.serialization.Serializable

/**
 * Validation result for domain models.
 */
sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val reasons: List<String>) : ValidationResult
}

/**
 * Linear automation rule consisting of one [Trigger] and one to three ordered [Action] steps.
 */
@Serializable
data class Rule(
    val id: String,
    val name: String,
    val trigger: Trigger,
    val actions: List<Action>,
    val enabled: Boolean = true,
    val cooldownSeconds: Long = 0L,
    val consecutiveFailures: Int = 0,
    val lastTriggeredEpochMs: Long? = null,
    val templateId: String? = null,
) {
    /**
     * Computes the highest [PrivilegeTier] required across the rule's trigger and actions.
     */
    val requiredTier: PrivilegeTier
        get() = (actions.map { it.type.requiredTier } + trigger.type.requiredTier)
            .maxBy { it.ordinal }

    /**
     * Computes the full set of [SystemPermission]s required by the rule, including dynamic metric permissions.
     */
    fun requiredPermissions(): Set<SystemPermission> {
        val base = buildSet {
            addAll(trigger.type.requiredPermissions)
            actions.forEach { addAll(it.type.requiredPermissions) }
            if (trigger.type == TriggerType.PERIODIC_CHECK &&
                trigger.config["metric"] == "MOBILE_DATA_MB"
            ) {
                add(SystemPermission.PACKAGE_USAGE_STATS)
            }
            if (trigger.type == TriggerType.CALENDAR_OR_MANUAL &&
                trigger.config["mode"] == "MANUAL"
            ) {
                remove(SystemPermission.READ_CALENDAR)
            }
        }
        return base
    }

    /**
     * Validates structural and configuration invariants of this rule.
     */
    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()
        if (id.isBlank()) {
            errors += "Rule id must not be blank."
        }
        if (name.isBlank()) {
            errors += "Rule name must not be blank."
        }
        if (actions.isEmpty()) {
            errors += "Rule must contain at least 1 action."
        }
        if (actions.size > MAX_ACTIONS) {
            errors += "Rule may contain at most $MAX_ACTIONS actions (found ${actions.size})."
        }
        if (cooldownSeconds < 0L) {
            errors += "Rule cooldownSeconds must be non-negative."
        }
        if (consecutiveFailures < 0) {
            errors += "Rule consecutiveFailures must be non-negative."
        }
        validateTriggerConfig(errors)
        validateActionsConfig(errors)
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    private fun validateTriggerConfig(errors: MutableList<String>) {
        when (trigger.type) {
            TriggerType.TIME -> {
                val hour = trigger.config["hour"]?.toIntOrNull()
                val minute = trigger.config["minute"]?.toIntOrNull()
                if (hour == null || hour !in 0..23) {
                    errors += "TIME trigger requires 'hour' in 0..23."
                }
                if (minute == null || minute !in 0..59) {
                    errors += "TIME trigger requires 'minute' in 0..59."
                }
            }
            TriggerType.BATTERY_LEVEL -> {
                val pct = trigger.config["thresholdPercent"]?.toIntOrNull()
                if (pct == null || pct !in 1..100) {
                    errors += "BATTERY_LEVEL trigger requires 'thresholdPercent' in 1..100."
                }
            }
            TriggerType.WIFI_SSID -> {
                if (trigger.config["ssid"].isNullOrBlank()) {
                    errors += "WIFI_SSID trigger requires non-blank 'ssid'."
                }
            }
            TriggerType.APP_OPENED -> {
                if (trigger.config["packageNames"].isNullOrBlank()) {
                    errors += "APP_OPENED trigger requires non-blank 'packageNames'."
                }
            }
            TriggerType.LOCATION_GEOFENCE -> {
                val lat = trigger.config["latitude"]?.toDoubleOrNull()
                val lon = trigger.config["longitude"]?.toDoubleOrNull()
                if (lat == null || lat !in -90.0..90.0 || lon == null || lon !in -180.0..180.0) {
                    errors += "LOCATION_GEOFENCE trigger requires valid 'latitude' and 'longitude'."
                }
            }
            else -> Unit
        }
    }

    private fun validateActionsConfig(errors: MutableList<String>) {
        actions.forEachIndexed { index, action ->
            when (action.type) {
                ActionType.NOTIFY -> {
                    if (action.config["title"].isNullOrBlank() || action.config["message"].isNullOrBlank()) {
                        errors += "Action[$index] NOTIFY requires non-blank 'title' and 'message'."
                    }
                }
                ActionType.TOAST -> {
                    if (action.config["message"].isNullOrBlank()) {
                        errors += "Action[$index] TOAST requires non-blank 'message'."
                    }
                }
                ActionType.OPEN_URL -> {
                    if (action.config["url"].isNullOrBlank()) {
                        errors += "Action[$index] OPEN_URL requires non-blank 'url'."
                    }
                }
                ActionType.BRIGHTNESS -> {
                    val pct = action.config["percent"]?.toIntOrNull()
                    if (pct == null || pct !in 0..100) {
                        errors += "Action[$index] BRIGHTNESS requires 'percent' in 0..100."
                    }
                }
                ActionType.SHELL_COMMAND -> {
                    if (action.config["command"].isNullOrBlank()) {
                        errors += "Action[$index] SHELL_COMMAND requires non-blank 'command'."
                    }
                }
                ActionType.SECURE_SETTING -> {
                    if (action.config["key"].isNullOrBlank() || action.config["value"] == null) {
                        errors += "Action[$index] SECURE_SETTING requires non-blank 'key' and non-null 'value'."
                    }
                }
                else -> Unit
            }
        }
    }

    companion object {
        const val MAX_ACTIONS: Int = 3
        const val CIRCUIT_BREAKER_THRESHOLD: Int = 5
    }
}
