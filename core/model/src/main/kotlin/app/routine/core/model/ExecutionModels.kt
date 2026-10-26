package app.routine.core.model

import kotlinx.serialization.Serializable

/**
 * Runtime event emitted by a trigger source for evaluation against active [Rule]s.
 */
@Serializable
data class TriggerEvent(
    val type: TriggerType,
    val attributes: Map<String, String> = emptyMap(),
    val timestampEpochMs: Long = System.currentTimeMillis(),
)

/**
 * Persisted status of a rule execution or dry-run entry.
 */
@Serializable
enum class LogStatus {
    SUCCESS,
    DRY_RUN,
    SKIPPED_DISABLED,
    SKIPPED_TRIGGER_MISMATCH,
    SKIPPED_COOLDOWN,
    BLOCKED_CAPABILITY,
    TIMEOUT,
    FAILED,
}

/**
 * Result of executing or dry-running a single [Action] step.
 */
@Serializable
data class ActionStepResult(
    val actionIndex: Int,
    val actionType: ActionType,
    val succeeded: Boolean,
    val summary: String,
    val usedFallback: Boolean = false,
)

/**
 * Structured outcome returned by `RuleEngine.execute`.
 */
sealed interface ExecutionResult {
    val ruleId: String
    val status: LogStatus

    data class Succeeded(
        override val ruleId: String,
        val stepResults: List<ActionStepResult>,
        val durationMs: Long,
    ) : ExecutionResult {
        override val status: LogStatus = LogStatus.SUCCESS
    }

    data class DryRunCompleted(
        override val ruleId: String,
        val stepResults: List<ActionStepResult>,
        val capabilityReady: Boolean,
        val capabilityNote: String,
    ) : ExecutionResult {
        override val status: LogStatus = LogStatus.DRY_RUN
    }

    data class SkippedDisabled(
        override val ruleId: String,
    ) : ExecutionResult {
        override val status: LogStatus = LogStatus.SKIPPED_DISABLED
    }

    data class SkippedTriggerMismatch(
        override val ruleId: String,
        val reason: String,
    ) : ExecutionResult {
        override val status: LogStatus = LogStatus.SKIPPED_TRIGGER_MISMATCH
    }

    data class SkippedCooldown(
        override val ruleId: String,
        val remainingCooldownSeconds: Long,
    ) : ExecutionResult {
        override val status: LogStatus = LogStatus.SKIPPED_COOLDOWN
    }

    data class BlockedByCapability(
        override val ruleId: String,
        val reason: String,
    ) : ExecutionResult {
        override val status: LogStatus = LogStatus.BLOCKED_CAPABILITY
    }

    data class TimedOut(
        override val ruleId: String,
        val failedActionIndex: Int,
        val timeoutMs: Long,
        val completedSteps: List<ActionStepResult>,
    ) : ExecutionResult {
        override val status: LogStatus = LogStatus.TIMEOUT
    }

    data class Failed(
        override val ruleId: String,
        val failedActionIndex: Int,
        val errorMessage: String,
        val completedSteps: List<ActionStepResult>,
        val circuitBreakerTripped: Boolean = false,
    ) : ExecutionResult {
        override val status: LogStatus = LogStatus.FAILED
    }
}

/**
 * Immutable execution log entry stored in Routine's encrypted Room database (capped at 1,000 rows).
 */
@Serializable
data class ExecutionLogEntry(
    val id: Long = 0L,
    val ruleId: String,
    val ruleName: String,
    val status: LogStatus,
    val message: String,
    val timestampEpochMs: Long,
)
