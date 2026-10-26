package app.routine.core.engine

import app.routine.core.model.Action
import app.routine.core.model.ActionType
import app.routine.core.model.ExecutionLogEntry
import app.routine.core.model.ExecutionResult
import app.routine.core.model.LogStatus
import app.routine.core.model.Rule
import app.routine.core.model.SystemPermission
import app.routine.core.model.Trigger
import app.routine.core.model.TriggerEvent
import app.routine.core.model.TriggerType
import app.routine.core.permissions.DefaultCapabilityChecker
import app.routine.core.permissions.PermissionSnapshot
import app.routine.core.permissions.PermissionStateProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineEndToEndTest {
    private class MutableClock(var currentMs: Long = 1_700_000_000_000L) : EngineClock {
        override fun nowEpochMs(): Long = currentMs
    }

    private class FakePermissionProvider(snapshot: PermissionSnapshot) : PermissionStateProvider {
        val flow = MutableStateFlow(snapshot)
        override val snapshotFlow: StateFlow<PermissionSnapshot> = flow
        override suspend fun refresh(): PermissionSnapshot = flow.value
    }

    private class InMemoryStateStore : RuleExecutionStateStore {
        val failuresByRule = mutableMapOf<String, Int>()
        val lastSuccessByRule = mutableMapOf<String, Long>()

        override suspend fun recordSuccess(ruleId: String, triggeredAtEpochMs: Long) {
            failuresByRule[ruleId] = 0
            lastSuccessByRule[ruleId] = triggeredAtEpochMs
        }

        override suspend fun recordFailure(rule: Rule): Int {
            val next = (failuresByRule[rule.id] ?: rule.consecutiveFailures) + 1
            failuresByRule[rule.id] = next
            return next
        }
    }

    @Test
    fun `executes mock rule end-to-end from trigger match through action sequence to log sink`() = runTest {
        val executedOrder = mutableListOf<ActionType>()
        val logs = mutableListOf<ExecutionLogEntry>()
        val stateStore = InMemoryStateStore()
        val clock = MutableClock()

        val dndExecutor = object : ActionExecutor {
            override val supportedType: ActionType = ActionType.DO_NOT_DISTURB
            override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome {
                executedOrder += supportedType
                return ActionExecutionOutcome.Success("DND enabled=${action.config["enabled"]}")
            }
        }
        val brightnessExecutor = object : ActionExecutor {
            override val supportedType: ActionType = ActionType.BRIGHTNESS
            override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome {
                executedOrder += supportedType
                return ActionExecutionOutcome.Success("Brightness set to ${action.config["percent"]}%")
            }
        }

        val permissions = FakePermissionProvider(
            PermissionSnapshot(
                grantedPermissions = setOf(
                    SystemPermission.SCHEDULE_EXACT_ALARM,
                    SystemPermission.NOTIFICATION_POLICY_ACCESS,
                    SystemPermission.WRITE_SYSTEM_SETTINGS,
                ),
            ),
        )

        val engine = RuleEngine(
            capabilityChecker = DefaultCapabilityChecker(permissions),
            triggerMatcher = TriggerMatcher(),
            executorRegistry = MapActionExecutorRegistry(setOf(dndExecutor, brightnessExecutor)),
            logSink = ExecutionLogSink { logs += it },
            stateStore = stateStore,
            clock = clock,
        )

        val bedtimeRule = Rule(
            id = "bedtime-mode",
            name = "Bedtime Mode",
            trigger = Trigger(TriggerType.TIME, mapOf("hour" to "22", "minute" to "30")),
            actions = listOf(
                Action(ActionType.DO_NOT_DISTURB, mapOf("enabled" to "true")),
                Action(ActionType.BRIGHTNESS, mapOf("percent" to "10")),
            ),
            cooldownSeconds = 120L,
        )

        val event = TriggerEvent(
            type = TriggerType.TIME,
            attributes = mapOf("hour" to "22", "minute" to "30"),
            timestampEpochMs = clock.currentMs,
        )

        val result = engine.execute(bedtimeRule, event = event)
        assertTrue(result is ExecutionResult.Succeeded)
        assertEquals(listOf(ActionType.DO_NOT_DISTURB, ActionType.BRIGHTNESS), executedOrder)
        assertEquals(1, logs.size)
        assertEquals(LogStatus.SUCCESS, logs.single().status)
        assertTrue(logs.single().message.contains("DND enabled=true"))
        assertEquals(0, stateStore.failuresByRule["bedtime-mode"])

        // Cooldown blocks immediate re-trigger 30 seconds later
        clock.currentMs += 30_000L
        val cooldownResult = engine.execute(bedtimeRule, event = event)
        assertTrue(cooldownResult is ExecutionResult.SkippedCooldown)
        assertEquals(90L, (cooldownResult as ExecutionResult.SkippedCooldown).remainingCooldownSeconds)

        // Advancing past cooldown allows execution again
        clock.currentMs += 95_000L
        val secondRun = engine.execute(bedtimeRule, event = event)
        assertTrue(secondRun is ExecutionResult.Succeeded)
        assertEquals(4, executedOrder.size)
    }

    @Test
    fun `blocks execution when capability is missing, disabled, invalid, or mismatched`() = runTest {
        val logs = mutableListOf<ExecutionLogEntry>()
        val permissions = FakePermissionProvider(PermissionSnapshot(emptySet()))
        val engine = RuleEngine(
            capabilityChecker = DefaultCapabilityChecker(permissions),
            triggerMatcher = TriggerMatcher(),
            executorRegistry = MapActionExecutorRegistry(emptySet()),
            logSink = ExecutionLogSink { logs += it },
        )

        val wifiRule = Rule(
            id = "wifi-off",
            name = "Wi-Fi Off",
            trigger = Trigger(TriggerType.TIME, mapOf("hour" to "23", "minute" to "0")),
            actions = listOf(Action(ActionType.WIFI_TOGGLE, mapOf("enabled" to "false"))),
        )

        val blocked = engine.execute(wifiRule)
        assertTrue(blocked is ExecutionResult.BlockedByCapability)
        assertEquals(LogStatus.BLOCKED_CAPABILITY, logs.last().status)

        val disabled = engine.execute(wifiRule.copy(enabled = false))
        assertTrue(disabled is ExecutionResult.SkippedDisabled)

        val mismatch = engine.execute(
            wifiRule,
            event = TriggerEvent(TriggerType.TIME, mapOf("hour" to "09", "minute" to "0")),
        )
        assertTrue(mismatch is ExecutionResult.SkippedTriggerMismatch)

        val invalid = engine.execute(wifiRule.copy(name = ""))
        assertTrue(invalid is ExecutionResult.Failed)
    }

    @Test
    fun `enforces per-action timeout and dry-run simulation without side effects`() = runTest {
        var sideEffects = 0
        val logs = mutableListOf<ExecutionLogEntry>()
        val slowExecutor = object : ActionExecutor {
            override val supportedType: ActionType = ActionType.TOAST
            override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome {
                sideEffects++
                delay(5_000L)
                return ActionExecutionOutcome.Success("Shown")
            }
        }
        val engine = RuleEngine(
            capabilityChecker = DefaultCapabilityChecker(FakePermissionProvider(PermissionSnapshot(emptySet()))),
            triggerMatcher = TriggerMatcher(),
            executorRegistry = MapActionExecutorRegistry(setOf(slowExecutor)),
            logSink = ExecutionLogSink { logs += it },
            defaultActionTimeoutMs = 100L,
        )
        val rule = Rule(
            id = "toast-rule",
            name = "Slow Toast",
            trigger = Trigger(TriggerType.BOOT_COMPLETED),
            actions = listOf(Action(ActionType.TOAST, mapOf("message" to "secret=myPin123"))),
        )

        // Dry run executes zero side effects and redacts secret
        val dry = engine.execute(rule, dryRun = true)
        assertTrue(dry is ExecutionResult.DryRunCompleted)
        assertEquals(0, sideEffects)
        assertEquals(LogStatus.DRY_RUN, logs.last().status)
        assertFalse(logs.last().message.contains("myPin123"))

        // Live run times out cleanly
        val timedOut = engine.execute(rule)
        assertTrue(timedOut is ExecutionResult.TimedOut)
        assertEquals(LogStatus.TIMEOUT, logs.last().status)
    }

    @Test
    fun `trips circuit breaker after five consecutive failures`() = runTest {
        val trippedEvents = mutableListOf<Int>()
        val stateStore = InMemoryStateStore()
        val failingExecutor = object : ActionExecutor {
            override val supportedType: ActionType = ActionType.TOAST
            override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome =
                ActionExecutionOutcome.Failure("Simulated executor error")
        }
        val engine = RuleEngine(
            capabilityChecker = DefaultCapabilityChecker(FakePermissionProvider(PermissionSnapshot(emptySet()))),
            triggerMatcher = TriggerMatcher(),
            executorRegistry = MapActionExecutorRegistry(setOf(failingExecutor)),
            logSink = ExecutionLogSink { },
            stateStore = stateStore,
            circuitBreakerListener = CircuitBreakerListener { _, count, _ -> trippedEvents += count },
        )
        val rule = Rule(
            id = "flaky-rule",
            name = "Flaky Rule",
            trigger = Trigger(TriggerType.BOOT_COMPLETED),
            actions = listOf(Action(ActionType.TOAST, mapOf("message" to "Hello"))),
        )

        repeat(4) {
            val res = engine.execute(rule) as ExecutionResult.Failed
            assertFalse(res.circuitBreakerTripped)
        }
        val fifth = engine.execute(rule) as ExecutionResult.Failed
        assertTrue(fifth.circuitBreakerTripped)
        assertEquals(listOf(5), trippedEvents)
    }
}
