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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.routine.core.data.repository.LogRepository
import app.routine.core.data.repository.RuleRepository
import app.routine.core.engine.RuleEngine
import app.routine.core.model.ExecutionLogEntry
import app.routine.core.model.LogStatus
import app.routine.core.model.Rule
import app.routine.trigger.standard.TriggerScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MyRulesViewModel @Inject constructor(
    private val ruleRepository: RuleRepository,
    private val ruleEngine: RuleEngine,
    private val triggerScheduler: TriggerScheduler,
) : ViewModel() {
    val rules: StateFlow<List<Rule>> = ruleRepository.allRulesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setRuleEnabled(rule: Rule, enabled: Boolean) {
        viewModelScope.launch {
            ruleRepository.setRuleEnabled(rule.id, enabled)
            val updated = rule.copy(enabled = enabled)
            if (enabled) {
                triggerScheduler.registerRuleTrigger(updated)
            } else {
                triggerScheduler.unregisterRuleTrigger(updated)
            }
        }
    }

    fun runRuleNow(rule: Rule) {
        viewModelScope.launch {
            ruleEngine.execute(rule = rule, event = null, dryRun = false)
        }
    }

    fun deleteRule(rule: Rule) {
        viewModelScope.launch {
            triggerScheduler.unregisterRuleTrigger(rule)
            ruleRepository.deleteRule(rule.id)
        }
    }
}

/**
 * My Rules screen displaying Active and Inactive rule sections, toggles, last-run times, and quick actions.
 */
@Composable
fun MyRulesScreen(
    onCreateRule: () -> Unit,
    onEditRule: (String) -> Unit,
    viewModel: MyRulesViewModel = hiltViewModel(),
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    MyRulesContent(
        rules = rules,
        onToggleRule = viewModel::setRuleEnabled,
        onRunRule = viewModel::runRuleNow,
        onEditRule = onEditRule,
        onDeleteRule = viewModel::deleteRule,
        onCreateRule = onCreateRule,
    )
}

/**
 * Stateless My Rules Composable testable directly via `createComposeRule()`.
 */
@Composable
fun MyRulesContent(
    rules: List<Rule>,
    onToggleRule: (Rule, Boolean) -> Unit,
    onRunRule: (Rule) -> Unit,
    onEditRule: (String) -> Unit,
    onDeleteRule: (Rule) -> Unit,
    onCreateRule: () -> Unit,
) {
    val active = rules.filter { it.enabled }
    val inactive = rules.filterNot { it.enabled }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateRule,
                modifier = Modifier.testTag("my_rules_new_fab"),
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.my_rules_new_fab))
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (rules.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.my_rules_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                item {
                    Text(
                        text = stringResource(R.string.my_rules_active_heading, active.size),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(active, key = { it.id }) { rule ->
                    RuleItemCard(
                        rule = rule,
                        onToggle = { enabled -> onToggleRule(rule, enabled) },
                        onRun = { onRunRule(rule) },
                        onEdit = { onEditRule(rule.id) },
                        onDelete = { onDeleteRule(rule) },
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.my_rules_inactive_heading, inactive.size),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(inactive, key = { it.id }) { rule ->
                    RuleItemCard(
                        rule = rule,
                        onToggle = { enabled -> onToggleRule(rule, enabled) },
                        onRun = { onRunRule(rule) },
                        onEdit = { onEditRule(rule.id) },
                        onDelete = { onDeleteRule(rule) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleItemCard(
    rule: Rule,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = rule.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = RuleSummaryFormatter.summarize(rule),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("rule_toggle_${rule.id}"),
                )
            }
            val lastTriggeredLabel = if (rule.lastTriggeredEpochMs != null) {
                stringResource(
                    R.string.my_rules_last_triggered_fmt,
                    RuleSummaryFormatter.formatTimestamp(rule.lastTriggeredEpochMs!!),
                )
            } else {
                stringResource(R.string.my_rules_last_triggered_never)
            }
            Text(text = lastTriggeredLabel, style = MaterialTheme.typography.labelSmall)
            if (rule.consecutiveFailures >= Rule.CIRCUIT_BREAKER_THRESHOLD) {
                Text(
                    text = stringResource(R.string.my_rules_circuit_tripped, rule.consecutiveFailures),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) {
                    Text(stringResource(R.string.my_rules_edit))
                }
                Button(onClick = onRun) {
                    Text(stringResource(R.string.my_rules_run_now))
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.my_rules_delete))
                }
            }
        }
    }
}

@HiltViewModel
class LogViewerViewModel @Inject constructor(
    private val logRepository: LogRepository,
) : ViewModel() {
    val logs: StateFlow<List<ExecutionLogEntry>> = logRepository.allLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearLogs() {
        viewModelScope.launch { logRepository.clearLogs() }
    }
}

/**
 * Chronological, filterable execution log viewer backed by Routine's 1,000-entry redacted Room log table.
 */
@Composable
fun LogViewerScreen(viewModel: LogViewerViewModel = hiltViewModel()) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    LogViewerContent(logs = logs, onClearLogs = viewModel::clearLogs)
}

@Composable
fun LogViewerContent(logs: List<ExecutionLogEntry>, onClearLogs: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var statusFilter by rememberSaveable { mutableStateOf<LogStatus?>(null) }

    val filtered = logs.filter { item ->
        val matchesStatus = statusFilter == null || item.status == statusFilter
        val matchesText = search.isBlank() ||
            item.ruleName.contains(search, ignoreCase = true) ||
            item.message.contains(search, ignoreCase = true)
        matchesStatus && matchesText
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text(stringResource(R.string.logs_search_placeholder)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClearLogs) {
                Text(stringResource(R.string.logs_clear_button))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = statusFilter == null,
                onClick = { statusFilter = null },
                label = { Text(stringResource(R.string.logs_filter_all)) },
            )
            listOf(
                LogStatus.SUCCESS,
                LogStatus.DRY_RUN,
                LogStatus.FAILED,
                LogStatus.BLOCKED_CAPABILITY,
            ).forEach { st ->
                FilterChip(
                    selected = statusFilter == st,
                    onClick = { statusFilter = st },
                    label = { Text(st.name) },
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (filtered.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.logs_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                items(filtered, key = { it.id }) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "[${entry.status.name}] ${entry.ruleName}",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(text = entry.message, style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = RuleSummaryFormatter.formatTimestamp(entry.timestampEpochMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
