package app.routine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.routine.core.data.repository.LogRepository
import app.routine.core.data.repository.RuleRepository
import app.routine.core.engine.RuleEngine
import app.routine.core.model.Action
import app.routine.core.model.ActionType
import app.routine.core.model.ExecutionLogEntry
import app.routine.core.model.Rule
import app.routine.core.model.Trigger
import app.routine.core.model.TriggerType
import app.routine.trigger.standard.TriggerScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel backing the Phase Two rule creation and live-execution verification harness.
 */
@HiltViewModel
class DebugRuleHarnessViewModel @Inject constructor(
    private val ruleRepository: RuleRepository,
    private val logRepository: LogRepository,
    private val ruleEngine: RuleEngine,
    private val triggerScheduler: TriggerScheduler,
) : ViewModel() {
    val rules: StateFlow<List<Rule>> = ruleRepository.allRulesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val logs: StateFlow<List<ExecutionLogEntry>> = logRepository.allLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createSampleToastRule() {
        viewModelScope.launch {
            val rule = Rule(
                id = UUID.randomUUID().toString(),
                name = "Charger Connected Toast",
                trigger = Trigger(TriggerType.CHARGER, mapOf("state" to "CONNECTED")),
                actions = listOf(Action(ActionType.TOAST, mapOf("message" to "Charger connected!"))),
            )
            ruleRepository.upsertRule(rule)
            triggerScheduler.registerRuleTrigger(rule)
        }
    }

    fun createSampleVibrateRule() {
        viewModelScope.launch {
            val rule = Rule(
                id = UUID.randomUUID().toString(),
                name = "Quick Vibrate Mode",
                trigger = Trigger(TriggerType.CALENDAR_OR_MANUAL, mapOf("mode" to "MANUAL_ONLY")),
                actions = listOf(Action(ActionType.RINGER_MODE, mapOf("mode" to "VIBRATE"))),
            )
            ruleRepository.upsertRule(rule)
            triggerScheduler.registerRuleTrigger(rule)
        }
    }

    fun fireRule(rule: Rule, dryRun: Boolean) {
        viewModelScope.launch {
            ruleEngine.execute(rule = rule, event = null, dryRun = dryRun)
        }
    }
}
