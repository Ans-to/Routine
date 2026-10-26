package app.routine.feature.rules

import android.content.Context
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import app.routine.core.model.ExecutionResult
import app.routine.core.model.LogStatus
import app.routine.core.model.PrivilegeTier
import app.routine.core.model.Rule
import app.routine.core.model.SystemPermission
import app.routine.core.model.Trigger
import app.routine.core.model.TriggerType
import app.routine.core.permissions.Capability
import app.routine.core.permissions.CapabilityChecker
import app.routine.core.permissions.PermissionStateProvider
import app.routine.feature.onboarding.PermissionExplanationBottomSheet
import app.routine.feature.onboarding.PermissionPromptCatalog
import app.routine.trigger.standard.TriggerScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MyRulesViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val ruleRepository: RuleRepository,
    private val ruleEngine: RuleEngine,
    private val capabilityChecker: CapabilityChecker,
    private val permissionStateProvider: PermissionStateProvider,
    private val triggerScheduler: TriggerScheduler,
) : ViewModel() {
    val rules: StateFlow<List<Rule>> = ruleRepository.allRulesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val capabilitiesByRuleId: StateFlow<Map<String, Capability>> = combine(
        ruleRepository.allRulesFlow,
        permissionStateProvider.snapshotFlow,
    ) { allRules, snapshot ->
        allRules.associate { rule -> rule.id to capabilityChecker.evaluateRule(rule, snapshot) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val mutableStatusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = mutableStatusBanner.asStateFlow()

    private val mutablePendingPermission = MutableStateFlow<SystemPermission?>(null)
    val pendingPermission: StateFlow<SystemPermission?> = mutablePendingPermission.asStateFlow()

    private val mutableNeedsPrivilegeNav = MutableStateFlow(false)
    val needsPrivilegeNav: StateFlow<Boolean> = mutableNeedsPrivilegeNav.asStateFlow()

    fun refreshCapabilities() {
        viewModelScope.launch { permissionStateProvider.refresh() }
    }

    fun dismissBanner() {
        mutableStatusBanner.value = null
        mutableNeedsPrivilegeNav.value = false
    }

    fun requestPermissionExplanation(permission: SystemPermission?) {
        mutablePendingPermission.value = permission
    }

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
            val manualRule = rule.copy(
                enabled = true,
                cooldownSeconds = 0L,
                consecutiveFailures = 0,
                trigger = Trigger(TriggerType.CALENDAR_OR_MANUAL, mapOf("mode" to "MANUAL")),
            )
            when (val cap = capabilityChecker.checkRule(manualRule)) {
                is Capability.NeedsSetup -> {
                    mutablePendingPermission.value = cap.missingPermissions.firstOrNull()
                    return@launch
                }
                is Capability.RequiresPrivilegeTier -> {
                    if (cap.requiredTier == PrivilegeTier.ACCESSIBILITY) {
                        mutablePendingPermission.value = SystemPermission.ACCESSIBILITY_SERVICE
                    } else {
                        mutableNeedsPrivilegeNav.value = true
                        mutableStatusBanner.value = appContext.getString(
                            R.string.my_rules_run_needs_privilege,
                            rule.name,
                        )
                    }
                    return@launch
                }
                else -> Unit
            }

            mutableNeedsPrivilegeNav.value = false
            val result = ruleEngine.execute(rule = manualRule, event = null, dryRun = false)
            mutableStatusBanner.value = formatManualRunBanner(rule.name, result)
        }
    }

    fun deleteRule(rule: Rule) {
        viewModelScope.launch {
            triggerScheduler.unregisterRuleTrigger(rule)
            ruleRepository.deleteRule(rule.id)
        }
    }

    private fun formatManualRunBanner(ruleName: String, result: ExecutionResult): String = when (result) {
        is ExecutionResult.Succeeded -> {
            val steps = result.stepResults.joinToString(" → ") { it.summary }
            appContext.getString(R.string.my_rules_run_succeeded, ruleName, steps)
        }
        is ExecutionResult.Failed ->
            appContext.getString(R.string.my_rules_run_failed, ruleName, result.errorMessage)
        is ExecutionResult.BlockedByCapability ->
            appContext.getString(R.string.my_rules_run_failed, ruleName, result.reason)
        is ExecutionResult.TimedOut ->
            appContext.getString(R.string.my_rules_run_failed, ruleName, "Timed out after ${result.timeoutMs}ms")
        else -> appContext.getString(R.string.my_rules_run_succeeded, ruleName, result.status.name)
    }
}

/**
 * My Rules screen displaying Active and Inactive rule sections, toggles, capability setup prompts, and Run Now.
 */
@Composable
fun MyRulesScreen(
    onCreateRule: () -> Unit,
    onEditRule: (String) -> Unit,
    onRequestRuntimePermission: (String) -> Unit = {},
    onNavigateToPrivilegeSetup: () -> Unit = {},
    viewModel: MyRulesViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilitiesByRuleId.collectAsStateWithLifecycle()
    val banner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val pendingPerm by viewModel.pendingPermission.collectAsStateWithLifecycle()
    val showPrivilegeButton by viewModel.needsPrivilegeNav.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshCapabilities() }

    pendingPerm?.let { perm ->
        PermissionExplanationBottomSheet(
            permission = perm,
            onDismiss = { viewModel.requestPermissionExplanation(null) },
            onConfirmProceed = { spec ->
                viewModel.requestPermissionExplanation(null)
                val runtimePerm = spec.runtimeManifestPermission
                if (runtimePerm != null) {
                    onRequestRuntimePermission(runtimePerm)
                } else {
                    runCatching {
                        context.startActivity(PermissionPromptCatalog.buildSettingsIntent(context, perm))
                    }.onFailure { err ->
                        android.util.Log.w("MyRulesScreen", "Failed to start settings intent", err)
                    }
                }
                viewModel.refreshCapabilities()
            },
        )
    }

    MyRulesContent(
        rules = rules,
        onToggleRule = viewModel::setRuleEnabled,
        onRunRule = viewModel::runRuleNow,
        onEditRule = onEditRule,
        onDeleteRule = viewModel::deleteRule,
        onCreateRule = onCreateRule,
        capabilitiesByRuleId = capabilities,
        statusBanner = banner,
        showPrivilegeSetupButton = showPrivilegeButton,
        onDismissBanner = viewModel::dismissBanner,
        onRequestPermission = { perm -> viewModel.requestPermissionExplanation(perm) },
        onNavigateToPrivilegeSetup = {
            viewModel.dismissBanner()
            onNavigateToPrivilegeSetup()
        },
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
    capabilitiesByRuleId: Map<String, Capability> = emptyMap(),
    statusBanner: String? = null,
    showPrivilegeSetupButton: Boolean = false,
    onDismissBanner: () -> Unit = {},
    onRequestPermission: (SystemPermission) -> Unit = {},
    onNavigateToPrivilegeSetup: () -> Unit = {},
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
            if (statusBanner != null) {
                item(key = "my_rules_status_banner") {
                    RuleExecutionBannerCard(
                        message = statusBanner,
                        showPrivilegeSetupButton = showPrivilegeSetupButton,
                        onOpenPrivilegeSetup = onNavigateToPrivilegeSetup,
                        onDismiss = onDismissBanner,
                    )
                }
            }
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
                        capability = capabilitiesByRuleId[rule.id],
                        onToggle = { enabled -> onToggleRule(rule, enabled) },
                        onRun = { onRunRule(rule) },
                        onEdit = { onEditRule(rule.id) },
                        onDelete = { onDeleteRule(rule) },
                        onRequestPermission = onRequestPermission,
                        onNavigateToPrivilegeSetup = onNavigateToPrivilegeSetup,
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
                        capability = capabilitiesByRuleId[rule.id],
                        onToggle = { enabled -> onToggleRule(rule, enabled) },
                        onRun = { onRunRule(rule) },
                        onEdit = { onEditRule(rule.id) },
                        onDelete = { onDeleteRule(rule) },
                        onRequestPermission = onRequestPermission,
                        onNavigateToPrivilegeSetup = onNavigateToPrivilegeSetup,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleExecutionBannerCard(
    message: String,
    showPrivilegeSetupButton: Boolean,
    onOpenPrivilegeSetup: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("my_rules_status_banner"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showPrivilegeSetupButton) {
                    Button(onClick = onOpenPrivilegeSetup) {
                        Text(stringResource(R.string.my_rules_open_privilege_setup))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.my_rules_dismiss_banner))
                }
            }
        }
    }
}

@Composable
private fun RuleItemCard(
    rule: Rule,
    capability: Capability?,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRequestPermission: (SystemPermission) -> Unit,
    onNavigateToPrivilegeSetup: () -> Unit,
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
            RuleCapabilityRow(
                capability = capability,
                onRequestPermission = onRequestPermission,
                onNavigateToPrivilegeSetup = onNavigateToPrivilegeSetup,
            )
            val lastTriggered = rule.lastTriggeredEpochMs
            val lastTriggeredLabel = if (lastTriggered != null) {
                stringResource(
                    R.string.my_rules_last_triggered_fmt,
                    RuleSummaryFormatter.formatTimestamp(lastTriggered),
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
                Button(
                    onClick = onRun,
                    modifier = Modifier.testTag("rule_run_${rule.id}"),
                ) {
                    Text(stringResource(R.string.my_rules_run_now))
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.my_rules_delete))
                }
            }
        }
    }
}

@Composable
private fun RuleCapabilityRow(
    capability: Capability?,
    onRequestPermission: (SystemPermission) -> Unit,
    onNavigateToPrivilegeSetup: () -> Unit,
) {
    when (capability) {
        Capability.Ready -> {
            Text(
                text = stringResource(R.string.my_rules_status_ready),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF2E7D32),
            )
        }
        is Capability.NeedsSetup -> {
            val missingPerm = capability.missingPermissions.firstOrNull()
            if (missingPerm != null) {
                OutlinedButton(onClick = { onRequestPermission(missingPerm) }) {
                    Text(stringResource(R.string.my_rules_grant_permission))
                }
            }
        }
        is Capability.RequiresPrivilegeTier -> {
            if (capability.requiredTier == PrivilegeTier.ACCESSIBILITY) {
                OutlinedButton(onClick = { onRequestPermission(SystemPermission.ACCESSIBILITY_SERVICE) }) {
                    Text(stringResource(R.string.my_rules_grant_permission))
                }
            } else {
                OutlinedButton(onClick = onNavigateToPrivilegeSetup) {
                    Text(stringResource(R.string.my_rules_open_privilege_setup))
                }
            }
        }
        else -> Unit
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
