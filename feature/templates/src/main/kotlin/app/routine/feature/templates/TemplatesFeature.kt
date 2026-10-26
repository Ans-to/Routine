package app.routine.feature.templates

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.WifiTetheringOff
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.routine.core.data.repository.RuleRepository
import app.routine.core.model.SystemPermission
import app.routine.core.model.Template
import app.routine.core.permissions.Capability
import app.routine.core.permissions.CapabilityChecker
import app.routine.core.permissions.PermissionStateProvider
import app.routine.feature.onboarding.PermissionExplanationBottomSheet
import app.routine.feature.onboarding.PermissionPromptCatalog
import app.routine.trigger.standard.TriggerScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI model pairing a [Template] with its live [Capability] badge state.
 */
data class TemplateCardUiModel(
    val template: Template,
    val capability: Capability,
)

enum class TemplateBadgeFilter {
    ALL,
    READY,
    NEEDS_SETUP,
    PRIVILEGED,
}

@HiltViewModel
class TemplatesViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    providers: Set<@JvmSuppressWildcards TemplateProvider>,
    private val capabilityChecker: CapabilityChecker,
    private val permissionStateProvider: PermissionStateProvider,
    private val ruleRepository: RuleRepository,
    private val triggerScheduler: TriggerScheduler,
) : ViewModel() {
    private val orderedTemplates: List<Template> = providers
        .sortedBy { it.order }
        .map { it.provide(appContext) }

    val cards: StateFlow<List<TemplateCardUiModel>> = permissionStateProvider.snapshotFlow
        .map { snapshot ->
            orderedTemplates.map { tpl ->
                TemplateCardUiModel(
                    template = tpl,
                    capability = capabilityChecker.evaluateTemplate(tpl, snapshot),
                )
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            orderedTemplates.map { tpl ->
                TemplateCardUiModel(
                    template = tpl,
                    capability = capabilityChecker.evaluateTemplate(tpl, permissionStateProvider.snapshotFlow.value),
                )
            },
        )

    fun refreshCapabilities() {
        viewModelScope.launch { permissionStateProvider.refresh() }
    }

    fun activateTemplate(template: Template, customName: String, onActivated: (String) -> Unit) {
        viewModelScope.launch {
            val trimmed = customName.trim().ifEmpty { template.title }
            val newRule = template.rule.copy(
                id = UUID.randomUUID().toString(),
                name = trimmed,
                enabled = true,
            )
            ruleRepository.upsertRule(newRule)
            triggerScheduler.registerRuleTrigger(newRule)
            onActivated(trimmed)
        }
    }
}

/**
 * Home screen displaying the 20 built-in automation templates with Green/Amber/Grey capability badges.
 */
@Composable
fun TemplatesHomeScreen(
    onNavigateToPrivilegeSetup: () -> Unit,
    onRequestRuntimePermission: (String) -> Unit,
    onShowMessage: (String) -> Unit,
    viewModel: TemplatesViewModel = hiltViewModel(),
) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    TemplatesGridContent(
        cards = cards,
        onActivate = { tpl, customName ->
            viewModel.activateTemplate(tpl, customName) { activatedName ->
                onShowMessage(activatedName)
            }
        },
        onNavigateToPrivilegeSetup = onNavigateToPrivilegeSetup,
        onRequestRuntimePermission = { perm ->
            onRequestRuntimePermission(perm)
            viewModel.refreshCapabilities()
        },
        onRefreshCapabilities = viewModel::refreshCapabilities,
    )
}

/**
 * Stateless template grid + activation sheet Composable, directly testable with `createComposeRule()`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesGridContent(
    cards: List<TemplateCardUiModel>,
    onActivate: (Template, String) -> Unit,
    onNavigateToPrivilegeSetup: () -> Unit,
    onRequestRuntimePermission: (String) -> Unit,
    onRefreshCapabilities: () -> Unit = {},
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(TemplateBadgeFilter.ALL) }
    var selectedCard by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.autoSaver()) {
        mutableStateOf<String?>(null)
    }
    var pendingPermission by rememberSaveable { mutableStateOf<SystemPermission?>(null) }

    val filtered = cards.filter { item ->
        val matchesText = query.isBlank() ||
            item.template.title.contains(query, ignoreCase = true) ||
            item.template.description.contains(query, ignoreCase = true)
        val matchesBadge = when (filter) {
            TemplateBadgeFilter.ALL -> true
            TemplateBadgeFilter.READY -> item.capability is Capability.Ready
            TemplateBadgeFilter.NEEDS_SETUP -> item.capability is Capability.NeedsSetup
            TemplateBadgeFilter.PRIVILEGED -> item.capability is Capability.RequiresPrivilegeTier
        }
        matchesText && matchesBadge
    }

    val activeCard = cards.firstOrNull { it.template.id == selectedCard }
    if (activeCard != null) {
        TemplateActivationBottomSheet(
            item = activeCard,
            onDismiss = { selectedCard = null },
            onConfirmActivate = { customName ->
                selectedCard = null
                onActivate(activeCard.template, customName)
            },
            onRequestPermissionSheet = { perm ->
                selectedCard = null
                pendingPermission = perm
            },
            onOpenPrivilegeSetup = {
                selectedCard = null
                onNavigateToPrivilegeSetup()
            },
        )
    }

    pendingPermission?.let { perm ->
        PermissionExplanationBottomSheet(
            permission = perm,
            onDismiss = { pendingPermission = null },
            onConfirmProceed = { spec ->
                pendingPermission = null
                val runtimePerm = spec.runtimeManifestPermission
                if (runtimePerm != null) {
                    onRequestRuntimePermission(runtimePerm)
                } else {
                    runCatching {
                        context.startActivity(PermissionPromptCatalog.buildSettingsIntent(context, perm))
                    }.onFailure { err ->
                        android.util.Log.w("TemplatesGridContent", "Failed to start settings intent", err)
                    }
                    onRefreshCapabilities()
                }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(text = stringResource(id = R.string.templates_search_placeholder)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        FilterChipsRow(selected = filter, onSelect = { filter = it })
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .testTag("templates_grid"),
        ) {
            items(filtered, key = { it.template.id }) { item ->
                TemplateGridCard(
                    item = item,
                    onClick = { selectedCard = item.template.id },
                )
            }
        }
    }
}

@Composable
private fun FilterChipsRow(selected: TemplateBadgeFilter, onSelect: (TemplateBadgeFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == TemplateBadgeFilter.ALL,
            onClick = { onSelect(TemplateBadgeFilter.ALL) },
            label = { Text(stringResource(R.string.templates_filter_all)) },
        )
        FilterChip(
            selected = selected == TemplateBadgeFilter.READY,
            onClick = { onSelect(TemplateBadgeFilter.READY) },
            label = { Text(stringResource(R.string.templates_filter_ready)) },
        )
        FilterChip(
            selected = selected == TemplateBadgeFilter.NEEDS_SETUP,
            onClick = { onSelect(TemplateBadgeFilter.NEEDS_SETUP) },
            label = { Text(stringResource(R.string.templates_filter_setup)) },
        )
        FilterChip(
            selected = selected == TemplateBadgeFilter.PRIVILEGED,
            onClick = { onSelect(TemplateBadgeFilter.PRIVILEGED) },
            label = { Text(stringResource(R.string.templates_filter_privileged)) },
        )
    }
}

@Composable
private fun TemplateGridCard(item: TemplateCardUiModel, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("template_card_${item.template.id}"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = resolveTemplateIcon(item.template.iconName),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                CapabilityBadgeChip(capability = item.capability)
            }
            Text(
                text = item.template.title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = item.template.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CapabilityBadgeChip(capability: Capability) {
    val (labelRes, containerColor, labelColor) = when (capability) {
        Capability.Ready -> Triple(
            R.string.badge_ready,
            Color(0xFF1B5E20).copy(alpha = 0.16f),
            Color(0xFF2E7D32),
        )
        is Capability.NeedsSetup -> Triple(
            R.string.badge_needs_setup,
            Color(0xFFE65100).copy(alpha = 0.16f),
            Color(0xFFEF6C00),
        )
        is Capability.RequiresPrivilegeTier,
        is Capability.Blocked,
        -> Triple(
            R.string.badge_privileged,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(text = stringResource(id = labelRes), style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = containerColor,
            disabledLabelColor = labelColor,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateActivationBottomSheet(
    item: TemplateCardUiModel,
    onDismiss: () -> Unit,
    onConfirmActivate: (String) -> Unit,
    onRequestPermissionSheet: (SystemPermission) -> Unit,
    onOpenPrivilegeSetup: () -> Unit,
) {
    var ruleName by rememberSaveable(item.template.id) { mutableStateOf(item.template.title) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = item.template.title, style = MaterialTheme.typography.headlineSmall)
            Text(text = item.template.description, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = ruleName,
                onValueChange = { ruleName = it },
                label = { Text(stringResource(R.string.template_sheet_rule_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            when (val cap = item.capability) {
                is Capability.NeedsSetup -> {
                    val firstPerm = cap.missingPermissions.firstOrNull()
                    if (firstPerm != null) {
                        OutlinedButton(
                            onClick = { onRequestPermissionSheet(firstPerm) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.template_sheet_grant_first))
                        }
                    }
                }
                is Capability.RequiresPrivilegeTier -> {
                    if (cap.requiredTier == app.routine.core.model.PrivilegeTier.ACCESSIBILITY) {
                        OutlinedButton(
                            onClick = { onRequestPermissionSheet(SystemPermission.ACCESSIBILITY_SERVICE) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.template_sheet_grant_first))
                        }
                    } else {
                        OutlinedButton(
                            onClick = onOpenPrivilegeSetup,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.template_sheet_open_privilege_setup))
                        }
                    }
                }
                else -> Unit
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.template_sheet_cancel))
                }
                Button(
                    onClick = { onConfirmActivate(ruleName) },
                    modifier = Modifier.testTag("confirm_activate_template_button"),
                ) {
                    Text(stringResource(R.string.template_sheet_activate))
                }
            }
        }
    }
}

private fun resolveTemplateIcon(iconName: String): ImageVector = when (iconName) {
    "wifi_off" -> Icons.Default.WifiOff
    "do_not_disturb" -> Icons.Default.DoNotDisturbOn
    "directions_car" -> Icons.Default.DirectionsCar
    "data_usage" -> Icons.Default.DataUsage
    "battery_saver" -> Icons.Default.BatterySaver
    "bedtime" -> Icons.Default.Bedtime
    "battery_charging_full" -> Icons.Default.BatteryChargingFull
    "work" -> Icons.Default.Work
    "volume_off" -> Icons.Default.VolumeOff
    "wb_sunny" -> Icons.Default.WbSunny
    "screen_lock_rotation" -> Icons.Default.ScreenLockRotation
    "nightlight" -> Icons.Default.Nightlight
    "wifi_tethering_off" -> Icons.Default.WifiTetheringOff
    "sd_storage" -> Icons.Default.SdStorage
    "headphones" -> Icons.Default.Headphones
    "home" -> Icons.Default.Home
    "timer" -> Icons.Default.Timer
    "photo_library" -> Icons.Default.PhotoLibrary
    "phone_disabled" -> Icons.Default.PhoneDisabled
    "alarm_on" -> Icons.Default.AlarmOn
    else -> Icons.Default.AutoAwesome
}
