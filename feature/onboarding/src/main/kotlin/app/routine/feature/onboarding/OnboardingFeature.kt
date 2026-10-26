package app.routine.feature.onboarding

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.routine.core.data.repository.UserPreferencesRepository
import app.routine.core.model.SystemPermission
import app.routine.core.permissions.PermissionSnapshot
import app.routine.core.permissions.PermissionStateProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Localized explanation metadata shown inside [PermissionExplanationBottomSheet] BEFORE any system
 * permission dialog or Settings intent is ever launched.
 */
data class PermissionExplanationSpec(
    val permission: SystemPermission,
    @StringRes val titleRes: Int,
    @StringRes val whyRes: Int,
    @StringRes val fallbackRes: Int,
    val runtimeManifestPermission: String? = null,
)

/**
 * Maps every [SystemPermission] to its user-facing rationale and target Android Settings intent.
 */
object PermissionPromptCatalog {
    fun specFor(permission: SystemPermission): PermissionExplanationSpec = when (permission) {
        SystemPermission.POST_NOTIFICATIONS -> PermissionExplanationSpec(
            permission = permission,
            titleRes = R.string.perm_notifications_title,
            whyRes = R.string.perm_notifications_why,
            fallbackRes = R.string.perm_notifications_fallback,
            runtimeManifestPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.POST_NOTIFICATIONS
            } else {
                null
            },
        )
        SystemPermission.SCHEDULE_EXACT_ALARM -> PermissionExplanationSpec(
            permission = permission,
            titleRes = R.string.perm_exact_alarm_title,
            whyRes = R.string.perm_exact_alarm_why,
            fallbackRes = R.string.perm_exact_alarm_fallback,
        )
        SystemPermission.IGNORE_BATTERY_OPTIMIZATIONS -> PermissionExplanationSpec(
            permission = permission,
            titleRes = R.string.perm_battery_title,
            whyRes = R.string.perm_battery_why,
            fallbackRes = R.string.perm_battery_fallback,
        )
        SystemPermission.NOTIFICATION_POLICY_ACCESS -> PermissionExplanationSpec(
            permission = permission,
            titleRes = R.string.perm_dnd_title,
            whyRes = R.string.perm_dnd_why,
            fallbackRes = R.string.perm_dnd_fallback,
        )
        SystemPermission.WRITE_SYSTEM_SETTINGS -> PermissionExplanationSpec(
            permission = permission,
            titleRes = R.string.perm_write_settings_title,
            whyRes = R.string.perm_write_settings_why,
            fallbackRes = R.string.perm_write_settings_fallback,
        )
        SystemPermission.ACCESS_WIFI_STATE,
        SystemPermission.ACCESS_FINE_LOCATION,
        SystemPermission.ACCESS_BACKGROUND_LOCATION,
        -> PermissionExplanationSpec(
            permission = permission,
            titleRes = R.string.perm_location_title,
            whyRes = R.string.perm_location_why,
            fallbackRes = R.string.perm_location_fallback,
            runtimeManifestPermission = android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        SystemPermission.BLUETOOTH_CONNECT -> PermissionExplanationSpec(
            permission = permission,
            titleRes = R.string.perm_bluetooth_title,
            whyRes = R.string.perm_bluetooth_why,
            fallbackRes = R.string.perm_bluetooth_fallback,
            runtimeManifestPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.Manifest.permission.BLUETOOTH_CONNECT
            } else {
                null
            },
        )
        SystemPermission.READ_CALENDAR -> PermissionExplanationSpec(
            permission = permission,
            titleRes = R.string.perm_calendar_title,
            whyRes = R.string.perm_calendar_why,
            fallbackRes = R.string.perm_calendar_fallback,
            runtimeManifestPermission = android.Manifest.permission.READ_CALENDAR,
        )
        SystemPermission.READ_CONTACTS,
        SystemPermission.CALL_SCREENING_ROLE,
        -> PermissionExplanationSpec(
            permission = permission,
            titleRes = R.string.perm_contacts_call_title,
            whyRes = R.string.perm_contacts_call_why,
            fallbackRes = R.string.perm_contacts_call_fallback,
            runtimeManifestPermission = if (permission == SystemPermission.READ_CONTACTS) {
                android.Manifest.permission.READ_CONTACTS
            } else {
                null
            },
        )
        SystemPermission.PACKAGE_USAGE_STATS -> PermissionExplanationSpec(
            permission = permission,
            titleRes = R.string.perm_usage_stats_title,
            whyRes = R.string.perm_usage_stats_why,
            fallbackRes = R.string.perm_usage_stats_fallback,
        )
        SystemPermission.SEND_SMS -> PermissionExplanationSpec(
            permission = permission,
            titleRes = R.string.perm_sms_title,
            whyRes = R.string.perm_sms_why,
            fallbackRes = R.string.perm_sms_fallback,
            runtimeManifestPermission = android.Manifest.permission.SEND_SMS,
        )
        SystemPermission.ACCESSIBILITY_SERVICE -> PermissionExplanationSpec(
            permission = permission,
            titleRes = R.string.perm_accessibility_title,
            whyRes = R.string.perm_accessibility_why,
            fallbackRes = R.string.perm_accessibility_fallback,
        )
        SystemPermission.WRITE_SECURE_SETTINGS,
        SystemPermission.ADB_SHELL_BRIDGE,
        -> PermissionExplanationSpec(
            permission = permission,
            titleRes = R.string.perm_privileged_title,
            whyRes = R.string.perm_privileged_why,
            fallbackRes = R.string.perm_privileged_fallback,
        )
    }

    @Suppress("BatteryLife")
    fun buildSettingsIntent(context: Context, permission: SystemPermission): Intent {
        val pkgUri = Uri.parse("package:${context.packageName}")
        return when (permission) {
            SystemPermission.SCHEDULE_EXACT_ALARM -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            }
            SystemPermission.IGNORE_BATTERY_OPTIMIZATIONS ->
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
            SystemPermission.NOTIFICATION_POLICY_ACCESS ->
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            SystemPermission.WRITE_SYSTEM_SETTINGS ->
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri)
            SystemPermission.PACKAGE_USAGE_STATS ->
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            SystemPermission.ACCESSIBILITY_SERVICE ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            SystemPermission.CALL_SCREENING_ROLE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val rm = context.getSystemService(RoleManager::class.java)
                    rm?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                        ?: Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                } else {
                    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                }
            }
            SystemPermission.ADB_SHELL_BRIDGE,
            SystemPermission.WRITE_SECURE_SETTINGS,
            -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * Mandatory explanatory bottom sheet shown BEFORE launching any system permission prompt or Settings intent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionExplanationBottomSheet(
    permission: SystemPermission,
    onConfirmProceed: (PermissionExplanationSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    val spec = PermissionPromptCatalog.specFor(permission)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(id = spec.titleRes),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(id = R.string.perm_sheet_why_heading),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(id = spec.whyRes),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(id = R.string.perm_sheet_fallback_heading),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                text = stringResource(id = spec.fallbackRes),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(id = R.string.perm_sheet_cancel))
                }
                Button(onClick = { onConfirmProceed(spec) }) {
                    Text(text = stringResource(id = R.string.perm_sheet_continue))
                }
            }
        }
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val preferencesRepository: UserPreferencesRepository,
    private val permissionStateProvider: PermissionStateProvider,
) : ViewModel() {
    val permissionSnapshot: StateFlow<PermissionSnapshot> = permissionStateProvider.snapshotFlow

    fun refreshPermissions() {
        viewModelScope.launch {
            permissionStateProvider.refresh()
        }
    }

    fun completeOnboarding(onFinished: () -> Unit) {
        viewModelScope.launch {
            preferencesRepository.setOnboardingCompleted(true)
            onFinished()
        }
    }
}

/**
 * Skippable 3-step first-launch Onboarding flow.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    onRequestRuntimePermission: (String) -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val snapshot by viewModel.permissionSnapshot.collectAsStateWithLifecycle()
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    var pendingPermission by rememberSaveable { mutableStateOf<SystemPermission?>(null) }

    pendingPermission?.let { perm ->
        PermissionExplanationBottomSheet(
            permission = perm,
            onDismiss = { pendingPermission = null },
            onConfirmProceed = { spec ->
                pendingPermission = null
                if (spec.runtimeManifestPermission != null) {
                    onRequestRuntimePermission(spec.runtimeManifestPermission)
                } else {
                    runCatching {
                        context.startActivity(PermissionPromptCatalog.buildSettingsIntent(context, perm))
                    }.onFailure { err ->
                        android.util.Log.w("OnboardingScreen", "Unable to open permission intent", err)
                    }
                }
                viewModel.refreshPermissions()
            },
        )
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(id = R.string.onboarding_step_indicator, stepIndex + 1),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = { viewModel.completeOnboarding(onFinished) }) {
                    Text(text = stringResource(id = R.string.onboarding_skip))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (stepIndex) {
                0 -> OnboardingWelcomeStep()
                1 -> OnboardingReliabilityStep(
                    snapshot = snapshot,
                    onReviewPermission = { pendingPermission = it },
                )
                else -> OnboardingPrivilegeTiersStep()
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                if (stepIndex > 0) {
                    OutlinedButton(onClick = { stepIndex -= 1 }) {
                        Text(text = stringResource(id = R.string.onboarding_back))
                    }
                }
                if (stepIndex < 2) {
                    Button(onClick = { stepIndex += 1 }) {
                        Text(text = stringResource(id = R.string.onboarding_next))
                    }
                } else {
                    Button(onClick = { viewModel.completeOnboarding(onFinished) }) {
                        Text(text = stringResource(id = R.string.onboarding_finish))
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingWelcomeStep() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(id = R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(id = R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(id = R.string.onboarding_welcome_bullet_1))
                Text(text = stringResource(id = R.string.onboarding_welcome_bullet_2))
                Text(text = stringResource(id = R.string.onboarding_welcome_bullet_3))
            }
        }
    }
}

@Composable
private fun OnboardingReliabilityStep(snapshot: PermissionSnapshot, onReviewPermission: (SystemPermission) -> Unit) {
    val notifGranted = snapshot.isSatisfied(SystemPermission.POST_NOTIFICATIONS)
    val batteryGranted = snapshot.isSatisfied(SystemPermission.IGNORE_BATTERY_OPTIMIZATIONS)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(id = R.string.onboarding_reliability_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(id = R.string.onboarding_reliability_body),
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedButton(
            onClick = { onReviewPermission(SystemPermission.POST_NOTIFICATIONS) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            val label = stringResource(id = R.string.onboarding_grant_notifications)
            val suffix = if (notifGranted) " (${stringResource(id = R.string.onboarding_granted_badge)})" else ""
            Text(text = label + suffix)
        }
        OutlinedButton(
            onClick = { onReviewPermission(SystemPermission.IGNORE_BATTERY_OPTIMIZATIONS) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            val label = stringResource(id = R.string.onboarding_grant_battery)
            val suffix = if (batteryGranted) " (${stringResource(id = R.string.onboarding_granted_badge)})" else ""
            Text(text = label + suffix)
        }
    }
}

@Composable
private fun OnboardingPrivilegeTiersStep() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(id = R.string.onboarding_tiers_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(id = R.string.onboarding_tiers_body),
            style = MaterialTheme.typography.bodyLarge,
        )
        TierSummaryCard(
            title = stringResource(id = R.string.onboarding_tier_standard_title),
            desc = stringResource(id = R.string.onboarding_tier_standard_desc),
        )
        TierSummaryCard(
            title = stringResource(id = R.string.onboarding_tier_grant_title),
            desc = stringResource(id = R.string.onboarding_tier_grant_desc),
        )
        TierSummaryCard(
            title = stringResource(id = R.string.onboarding_tier_bridge_title),
            desc = stringResource(id = R.string.onboarding_tier_bridge_desc),
        )
    }
}

@Composable
private fun TierSummaryCard(title: String, desc: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(text = desc, style = MaterialTheme.typography.bodySmall)
        }
    }
}
