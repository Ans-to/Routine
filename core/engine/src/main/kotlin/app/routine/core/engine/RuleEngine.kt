package app.routine.core.engine

import app.routine.core.model.ActionStepResult
import app.routine.core.model.ExecutionLogEntry
import app.routine.core.model.ExecutionResult
import app.routine.core.model.LogStatus
import app.routine.core.model.Rule
import app.routine.core.model.TriggerEvent
import app.routine.core.model.ValidationResult
import app.routine.core.permissions.Capability
import app.routine.core.permissions.CapabilityChecker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * UI-independent automation rule execution engine.
 */
@Singleton
class RuleEngine @Inject constructor(
    private val capabilityChecker: CapabilityChecker,
    private val triggerMatcher: TriggerMatcher,
    private val executorRegistry: ActionExecutorRegistry,
    private val logSink: ExecutionLogSink,
    private val stateStore: RuleExecutionStateStore = RuleExecutionStateStore.NO_OP,
    private val circuitBreakerListener: CircuitBreakerListener = CircuitBreakerListener { _, _, _ -> },
    private val clock: EngineClock = EngineClock { System.currentTimeMillis() },
    private val defaultActionTimeoutMs: Long = DEFAULT_ACTION_TIMEOUT_MS,
) : EngineContract {
    private val cooldownMutex = Mutex()
    private val lastExecutedByRuleId = mutableMapOf<String, Long>()

    /**
     * Evaluates and executes [rule], returning a structured [ExecutionResult] and writing a redacted log entry.
     *
     * Contract:
     * 1. Validates [rule] invariants.
     * 2. If [dryRun] is `false`, checks `rule.enabled`, optional [event] matching, cooldown window, and [Capability].
     * 3. Executes up to three actions linearly with per-action timeout [actionTimeoutMs].
     * 4. Records a redacted [ExecutionLogEntry] in [logSink] and updates circuit-breaker state on live runs.
     *
     * Failure modes:
     * - Never swallows exceptions silently: [TimeoutCancellationException] produces [ExecutionResult.TimedOut],
     *   parent coroutine [CancellationException] is rethrown immediately, and any other [Throwable] is logged
     *   with redaction and returned as [ExecutionResult.Failed].
     *
     * Thread safety: Safe for concurrent invocation across arbitrary coroutine dispatchers; internal cooldown map
     * is guarded by [cooldownMutex].
     */
    suspend fun execute(
        rule: Rule,
        event: TriggerEvent? = null,
        dryRun: Boolean = false,
        actionTimeoutMs: Long = defaultActionTimeoutMs,
    ): ExecutionResult {
        val startedAt = clock.nowEpochMs()

        when (val validation = rule.validate()) {
            ValidationResult.Valid -> Unit
            is ValidationResult.Invalid -> {
                val reason = validation.reasons.joinToString("; ")
                logExecution(rule, LogStatus.FAILED, "Validation failed: $reason", startedAt)
                return ExecutionResult.Failed(
                    ruleId = rule.id,
                    failedActionIndex = -1,
                    errorMessage = reason,
                    completedSteps = emptyList(),
                )
            }
        }

        if (!dryRun && !rule.enabled) {
            logExecution(rule, LogStatus.SKIPPED_DISABLED, "Rule is disabled.", startedAt)
            return ExecutionResult.SkippedDisabled(rule.id)
        }

        if (!dryRun && event != null && !triggerMatcher.matches(rule.trigger, event)) {
            val msg = "Incoming ${event.type} event did not match trigger predicates."
            logExecution(rule, LogStatus.SKIPPED_TRIGGER_MISMATCH, msg, startedAt)
            return ExecutionResult.SkippedTriggerMismatch(rule.id, msg)
        }

        val capability = capabilityChecker.checkRule(rule)

        if (dryRun) {
            return runDry(rule, capability, startedAt)
        }

        val remainingCooldownSec = checkCooldownRemainingSeconds(rule, startedAt)
        if (remainingCooldownSec > 0L) {
            val msg = "Cooldown active (${remainingCooldownSec}s remaining)."
            logExecution(rule, LogStatus.SKIPPED_COOLDOWN, msg, startedAt)
            return ExecutionResult.SkippedCooldown(rule.id, remainingCooldownSec)
        }

        if (capability != Capability.Ready) {
            val reason = describeCapabilityBlock(capability)
            logExecution(rule, LogStatus.BLOCKED_CAPABILITY, reason, startedAt)
            return ExecutionResult.BlockedByCapability(rule.id, reason)
        }

        return executeLiveSteps(rule, event, startedAt, actionTimeoutMs)
    }

    private suspend fun runDry(
        rule: Rule,
        capability: Capability,
        startedAt: Long,
    ): ExecutionResult.DryRunCompleted {
        val steps = rule.actions.mapIndexed { idx, action ->
            val executor = executorRegistry.executorFor(action.type)
            val summary = executor?.describeDryRun(action)
                ?: "Would execute ${action.type.name} (${LogRedactor.redactConfig(action.config)})"
            ActionStepResult(
                actionIndex = idx,
                actionType = action.type,
                succeeded = true,
                summary = LogRedactor.redact(summary),
            )
        }
        val capReady = capability == Capability.Ready
        val capNote = if (capReady) "All capabilities ready." else describeCapabilityBlock(capability)
        val combinedMessage = buildString {
            append("[DRY RUN] ")
            append(steps.joinToString(" -> ") { it.summary })
            append(" | ")
            append(capNote)
        }
        logExecution(rule, LogStatus.DRY_RUN, combinedMessage, startedAt)
        return ExecutionResult.DryRunCompleted(
            ruleId = rule.id,
            stepResults = steps,
            capabilityReady = capReady,
            capabilityNote = capNote,
        )
    }

    private suspend fun executeLiveSteps(
        rule: Rule,
        event: TriggerEvent?,
        startedAt: Long,
        actionTimeoutMs: Long,
    ): ExecutionResult {
        val completedSteps = mutableListOf<ActionStepResult>()
        for ((index, action) in rule.actions.withIndex()) {
            val executor = executorRegistry.executorFor(action.type)
            if (executor == null) {
                val err = "No ActionExecutor registered for ${action.type}."
                return handleStepFailure(rule, index, err, completedSteps, startedAt)
            }
            try {
                val outcome = withTimeout(actionTimeoutMs) {
                    executor.execute(action, event)
                }
                when (outcome) {
                    is ActionExecutionOutcome.Success -> {
                        completedSteps += ActionStepResult(
                            actionIndex = index,
                            actionType = action.type,
                            succeeded = true,
                            summary = LogRedactor.redact(outcome.summary),
                            usedFallback = outcome.usedFallback,
                        )
                    }
                    is ActionExecutionOutcome.Failure -> {
                        return handleStepFailure(rule, index, outcome.errorMessage, completedSteps, startedAt)
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                val msg = "Action[$index] (${action.type}) timed out after ${actionTimeoutMs}ms: ${timeout.message}"
                recordFailureAndMaybeTrip(rule, msg)
                logExecution(rule, LogStatus.TIMEOUT, msg, startedAt)
                return ExecutionResult.TimedOut(
                    ruleId = rule.id,
                    failedActionIndex = index,
                    timeoutMs = actionTimeoutMs,
                    completedSteps = completedSteps.toList(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unexpected: Throwable) {
                val msg = "Action[$index] (${action.type}) threw ${unexpected::class.simpleName}: " +
                    (unexpected.message ?: "unexpected error")
                return handleStepFailure(rule, index, msg, completedSteps, startedAt)
            }
        }

        val finishedAt = clock.nowEpochMs()
        cooldownMutex.withLock {
            lastExecutedByRuleId[rule.id] = startedAt
        }
        stateStore.recordSuccess(rule.id, startedAt)
        val summary = completedSteps.joinToString(" -> ") { it.summary }
        logExecution(rule, LogStatus.SUCCESS, summary, startedAt)
        return ExecutionResult.Succeeded(
            ruleId = rule.id,
            stepResults = completedSteps.toList(),
            durationMs = (finishedAt - startedAt).coerceAtLeast(0L),
        )
    }

    private suspend fun handleStepFailure(
        rule: Rule,
        index: Int,
        rawError: String,
        completedSteps: List<ActionStepResult>,
        startedAt: Long,
    ): ExecutionResult.Failed {
        val redactedError = LogRedactor.redact(rawError)
        val tripped = recordFailureAndMaybeTrip(rule, redactedError)
        logExecution(rule, LogStatus.FAILED, redactedError, startedAt)
        return ExecutionResult.Failed(
            ruleId = rule.id,
            failedActionIndex = index,
            errorMessage = redactedError,
            completedSteps = completedSteps.toList(),
            circuitBreakerTripped = tripped,
        )
    }

    private suspend fun recordFailureAndMaybeTrip(rule: Rule, redactedError: String): Boolean {
        val updatedCount = stateStore.recordFailure(rule)
        if (updatedCount >= Rule.CIRCUIT_BREAKER_THRESHOLD) {
            circuitBreakerListener.onCircuitBreakerTripped(rule, updatedCount, redactedError)
            return true
        }
        return false
    }

    private suspend fun checkCooldownRemainingSeconds(rule: Rule, nowEpochMs: Long): Long {
        if (rule.cooldownSeconds <= 0L) return 0L
        val lastRun = cooldownMutex.withLock {
            val inMemory = lastExecutedByRuleId[rule.id]
            val persisted = rule.lastTriggeredEpochMs
            listOfNotNull(inMemory, persisted).maxOrNull()
        } ?: return 0L

        val elapsedMs = nowEpochMs - lastRun
        val cooldownMs = rule.cooldownSeconds * 1_000L
        if (elapsedMs >= cooldownMs) return 0L
        return ((cooldownMs - elapsedMs) + 999L) / 1_000L
    }

    private fun describeCapabilityBlock(capability: Capability): String = when (capability) {
        Capability.Ready -> "Ready"
        is Capability.NeedsSetup -> capability.reason
        is Capability.RequiresPrivilegeTier -> capability.reason
        is Capability.Blocked -> capability.reason
    }

    private suspend fun logExecution(
        rule: Rule,
        status: LogStatus,
        rawMessage: String,
        timestampEpochMs: Long,
    ) {
        logSink.append(
            ExecutionLogEntry(
                ruleId = rule.id,
                ruleName = rule.name,
                status = status,
                message = LogRedactor.redact(rawMessage),
                timestampEpochMs = timestampEpochMs,
            ),
        )
    }

    companion object {
        const val DEFAULT_ACTION_TIMEOUT_MS: Long = 10_000L
    }
}
