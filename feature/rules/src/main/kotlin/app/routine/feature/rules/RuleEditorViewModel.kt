package app.routine.feature.rules

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.routine.core.data.repository.RuleRepository
import app.routine.core.engine.RuleEngine
import app.routine.core.model.Action
import app.routine.core.model.ActionType
import app.routine.core.model.ExecutionResult
import app.routine.core.model.Rule
import app.routine.core.model.Trigger
import app.routine.core.model.TriggerType
import app.routine.core.model.ValidationResult
import app.routine.trigger.standard.TriggerScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class RuleEditorViewModel @Inject constructor(
    private val ruleRepository: RuleRepository,
    private val ruleEngine: RuleEngine,
    private val triggerScheduler: TriggerScheduler,
) : ViewModel() {
    private val mutableLoadedRule = MutableStateFlow<Rule?>(null)
    val loadedRule: StateFlow<Rule?> = mutableLoadedRule.asStateFlow()

    private val mutableDryRunSummary = MutableStateFlow<String?>(null)
    val dryRunSummary: StateFlow<String?> = mutableDryRunSummary.asStateFlow()

    fun loadRule(ruleId: String?) {
        if (ruleId.isNullOrBlank()) {
            mutableLoadedRule.value = null
            return
        }
        viewModelScope.launch {
            mutableLoadedRule.value = ruleRepository.getRuleById(ruleId)
        }
    }

    fun runDryRun(draft: Rule) {
        viewModelScope.launch {
            val result = ruleEngine.execute(rule = draft, event = null, dryRun = true)
            mutableDryRunSummary.value = formatDryRunResult(result)
        }
    }

    fun saveRule(draft: Rule, onSaved: () -> Unit) {
        viewModelScope.launch {
            if (draft.validate() is ValidationResult.Valid) {
                ruleRepository.upsertRule(draft)
                triggerScheduler.registerRuleTrigger(draft)
                onSaved()
            }
        }
    }

    companion object {
        fun formatDryRunResult(result: ExecutionResult): String = when (result) {
            is ExecutionResult.DryRunCompleted -> {
                val steps = result.stepResults.joinToString("\n") { step ->
                    "• Step ${step.actionIndex + 1} (${step.actionType.name}): ${step.summary}"
                }
                "${result.capabilityNote}\n$steps"
            }
            else -> "Status: ${result.status.name}"
        }
    }
}

/**
 * Linear Rule Editor screen supporting 1 trigger, 1..3 reorderable actions, and live "Test Rule" dry-run.
 */
@Composable
fun RuleEditorScreen(ruleId: String?, onNavigateBack: () -> Unit, viewModel: RuleEditorViewModel = hiltViewModel()) {
    LaunchedEffect(ruleId) { viewModel.loadRule(ruleId) }
    val existing by viewModel.loadedRule.collectAsStateWithLifecycle()
    val dryRunSummary by viewModel.dryRunSummary.collectAsStateWithLifecycle()

    RuleEditorContent(
        initialRule = existing,
        dryRunSummary = dryRunSummary,
        onTestRule = viewModel::runDryRun,
        onSaveRule = { draft -> viewModel.saveRule(draft, onNavigateBack) },
        onCancel = onNavigateBack,
    )
}

/**
 * Stateless linear Rule Editor Composable directly testable with `createComposeRule()`.
 */
@Composable
fun RuleEditorContent(
    initialRule: Rule?,
    dryRunSummary: String?,
    onTestRule: (Rule) -> Unit,
    onSaveRule: (Rule) -> Unit,
    onCancel: () -> Unit,
) {
    val seedId = initialRule?.id ?: remember { UUID.randomUUID().toString() }
    var name by remember(initialRule?.id) { mutableStateOf(initialRule?.name ?: "Custom Automation") }
    var cooldownText by remember(initialRule?.id) {
        mutableStateOf((initialRule?.cooldownSeconds ?: 60L).toString())
    }
    var trigger by remember(initialRule?.id) {
        mutableStateOf(
            initialRule?.trigger ?: Trigger(
                type = TriggerType.CHARGER,
                config = RuleSummaryFormatter.defaultTriggerConfig(TriggerType.CHARGER),
            ),
        )
    }
    var actions by remember(initialRule?.id) {
        mutableStateOf(
            initialRule?.actions ?: listOf(
                Action(ActionType.TOAST, RuleSummaryFormatter.defaultActionConfig(ActionType.TOAST)),
            ),
        )
    }

    fun buildDraft(): Rule = Rule(
        id = seedId,
        name = name.trim().ifEmpty { "Untitled Rule" },
        trigger = trigger,
        actions = actions,
        enabled = initialRule?.enabled ?: true,
        cooldownSeconds = cooldownText.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
    )

    Scaffold(
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.editor_cancel))
                }
                OutlinedButton(
                    onClick = { onTestRule(buildDraft()) },
                    modifier = Modifier.testTag("editor_test_rule_button"),
                ) {
                    Text(stringResource(R.string.editor_test_rule))
                }
                Button(
                    onClick = { onSaveRule(buildDraft()) },
                    modifier = Modifier.testTag("editor_save_rule_button"),
                ) {
                    Text(stringResource(R.string.editor_save_rule))
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("editor_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (dryRunSummary != null) {
                item(key = "dry_run_banner") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_dry_run_banner"),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.editor_dry_run_heading),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(text = dryRunSummary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                val headingRes = if (initialRule == null) R.string.editor_title_new else R.string.editor_title_edit
                Text(
                    text = stringResource(headingRes),
                    style = MaterialTheme.typography.headlineSmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.editor_name_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("editor_rule_name"),
                )
                OutlinedTextField(
                    value = cooldownText,
                    onValueChange = { cooldownText = it },
                    label = { Text(stringResource(R.string.editor_cooldown_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                TriggerSelectorSection(
                    trigger = trigger,
                    onTriggerChange = { trigger = it },
                )
            }

            item {
                Text(
                    text = stringResource(R.string.editor_actions_heading, actions.size),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            itemsIndexed(actions) { idx, action ->
                ActionStepEditorCard(
                    index = idx,
                    totalCount = actions.size,
                    action = action,
                    onUpdate = { updated ->
                        actions = actions.toMutableList().also { it[idx] = updated }
                    },
                    onMoveUp = {
                        if (idx > 0) {
                            actions = actions.toMutableList().also { list ->
                                val tmp = list[idx - 1]
                                list[idx - 1] = list[idx]
                                list[idx] = tmp
                            }
                        }
                    },
                    onMoveDown = {
                        if (idx < actions.lastIndex) {
                            actions = actions.toMutableList().also { list ->
                                val tmp = list[idx + 1]
                                list[idx + 1] = list[idx]
                                list[idx] = tmp
                            }
                        }
                    },
                    onRemove = {
                        if (actions.size > 1) {
                            actions = actions.toMutableList().also { it.removeAt(idx) }
                        }
                    },
                )
            }

            item {
                if (actions.size < Rule.MAX_ACTIONS) {
                    OutlinedButton(
                        onClick = {
                            actions = actions + Action(
                                type = ActionType.NOTIFY,
                                config = RuleSummaryFormatter.defaultActionConfig(ActionType.NOTIFY),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.editor_add_action))
                    }
                }
            }
        }
    }
}

@Composable
private fun TriggerSelectorSection(trigger: Trigger, onTriggerChange: (Trigger) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.editor_trigger_heading),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TriggerType.entries.forEach { candidate ->
                    FilterChip(
                        selected = trigger.type == candidate,
                        onClick = {
                            onTriggerChange(
                                Trigger(
                                    type = candidate,
                                    config = RuleSummaryFormatter.defaultTriggerConfig(candidate),
                                ),
                            )
                        },
                        label = { Text(candidate.name) },
                    )
                }
            }
            trigger.config.entries.forEach { (key, value) ->
                OutlinedTextField(
                    value = value,
                    onValueChange = { updatedVal ->
                        onTriggerChange(trigger.copy(config = trigger.config + (key to updatedVal)))
                    },
                    label = { Text(key) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ActionStepEditorCard(
    index: Int,
    totalCount: Int,
    action: Action,
    onUpdate: (Action) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "#${index + 1} ${action.type.name}", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (index > 0) {
                        TextButton(onClick = onMoveUp) { Text(stringResource(R.string.editor_move_up)) }
                    }
                    if (index < totalCount - 1) {
                        TextButton(onClick = onMoveDown) { Text(stringResource(R.string.editor_move_down)) }
                    }
                    if (totalCount > 1) {
                        TextButton(onClick = onRemove) { Text(stringResource(R.string.editor_remove_action)) }
                    }
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ActionType.entries.forEach { candidate ->
                    FilterChip(
                        selected = action.type == candidate,
                        onClick = {
                            onUpdate(
                                Action(
                                    type = candidate,
                                    config = RuleSummaryFormatter.defaultActionConfig(candidate),
                                ),
                            )
                        },
                        label = { Text(candidate.name) },
                    )
                }
            }
            action.config.entries.forEach { (key, value) ->
                OutlinedTextField(
                    value = value,
                    onValueChange = { updatedVal ->
                        onUpdate(action.copy(config = action.config + (key to updatedVal)))
                    },
                    label = { Text(key) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
