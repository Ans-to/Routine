package app.routine.feature.rules

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.routine.core.engine.ActionExecutionOutcome
import app.routine.core.engine.ActionExecutor
import app.routine.core.engine.MapActionExecutorRegistry
import app.routine.core.engine.RuleEngine
import app.routine.core.engine.TriggerMatcher
import app.routine.core.model.Action
import app.routine.core.model.ActionType
import app.routine.core.model.ExecutionLogEntry
import app.routine.core.model.LogStatus
import app.routine.core.model.Rule
import app.routine.core.model.Trigger
import app.routine.core.model.TriggerEvent
import app.routine.core.model.TriggerType
import app.routine.core.permissions.DefaultCapabilityChecker
import app.routine.core.permissions.PermissionSnapshot
import app.routine.core.permissions.PermissionStateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RulesAndEditorUiTest {
    @get:JUnitRule
    val composeTestRule = createComposeRule()

    @Test
    fun `toggling rule switch in MyRulesContent flips rule enabled state`() {
        var rulesState by mutableStateOf(
            listOf(
                Rule(
                    id = "r1",
                    name = "Charger Toast",
                    trigger = Trigger(TriggerType.CHARGER, mapOf("state" to "CONNECTED")),
                    actions = listOf(Action(ActionType.TOAST, mapOf("message" to "Plugged in"))),
                    enabled = true,
                ),
            ),
        )

        composeTestRule.setContent {
            MaterialTheme {
                MyRulesContent(
                    rules = rulesState,
                    onToggleRule = { target, newEnabled ->
                        rulesState = rulesState.map { if (it.id == target.id) it.copy(enabled = newEnabled) else it }
                    },
                    onRunRule = {},
                    onEditRule = {},
                    onDeleteRule = {},
                    onCreateRule = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("rule_toggle_r1").performClick()
        assertFalse(rulesState.single().enabled)

        composeTestRule.onNodeWithTag("rule_toggle_r1").performClick()
        assertTrue(rulesState.single().enabled)
    }

    @Test
    fun `clicking Test Rule in RuleEditorContent executes side-effect-free dry run and displays banner`() {
        var realExecuteCalled = false
        val recordedLogs = mutableListOf<ExecutionLogEntry>()
        val toastExecutor = object : ActionExecutor {
            override val supportedType: ActionType = ActionType.TOAST
            override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome {
                realExecuteCalled = true
                return ActionExecutionOutcome.Success("Executed")
            }
        }
        val permProvider = object : PermissionStateProvider {
            override val snapshotFlow: StateFlow<PermissionSnapshot> =
                MutableStateFlow(PermissionSnapshot(emptySet()))
            override suspend fun refresh(): PermissionSnapshot = snapshotFlow.value
        }
        val engine = RuleEngine(
            capabilityChecker = DefaultCapabilityChecker(permProvider),
            triggerMatcher = TriggerMatcher(),
            executorRegistry = MapActionExecutorRegistry(setOf(toastExecutor)),
            logSink = { entry -> recordedLogs += entry },
        )

        var dryRunBanner by mutableStateOf<String?>(null)
        composeTestRule.setContent {
            MaterialTheme {
                RuleEditorContent(
                    initialRule = null,
                    dryRunSummary = dryRunBanner,
                    onTestRule = { draft ->
                        val res = runBlocking { engine.execute(draft, dryRun = true) }
                        dryRunBanner = RuleEditorViewModel.formatDryRunResult(res)
                    },
                    onSaveRule = {},
                    onCancel = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("editor_test_rule_button").performClick()
        composeTestRule.onNodeWithTag("editor_list").performScrollToNode(hasTestTag("editor_dry_run_banner"))
        composeTestRule.onNodeWithTag("editor_dry_run_banner").assertIsDisplayed()

        assertFalse("Dry run must never execute real side effects", realExecuteCalled)
        assertEquals(1, recordedLogs.size)
        assertEquals(LogStatus.DRY_RUN, recordedLogs.single().status)
    }
}
