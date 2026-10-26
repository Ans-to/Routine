package app.routine.feature.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.routine.core.data.repository.RoutineBackupSerializer
import app.routine.core.data.repository.RuleRepository
import app.routine.core.data.repository.ThemeMode
import app.routine.core.data.repository.UserPreferences
import app.routine.core.data.repository.UserPreferencesRepository
import app.routine.core.model.SystemPermission
import app.routine.core.permissions.PermissionSnapshot
import app.routine.core.permissions.PermissionStateProvider
import app.routine.feature.onboarding.PermissionExplanationBottomSheet
import app.routine.feature.onboarding.PermissionPromptCatalog
import app.routine.privilege.api.PairingEndpointInfo
import app.routine.privilege.api.PrivilegeAvailability
import app.routine.privilege.api.PrivilegeOperationResult
import app.routine.privilege.api.PrivilegeSetupCoordinator
import app.routine.privilege.api.PrivilegedEngine
import app.routine.trigger.standard.TriggerScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: UserPreferencesRepository,
    private val ruleRepository: RuleRepository,
    private val triggerScheduler: TriggerScheduler,
    private val permissionStateProvider: PermissionStateProvider,
    private val privilegedEngine: PrivilegedEngine,
    private val setupCoordinator: PrivilegeSetupCoordinator,
) : ViewModel() {
    val preferences: StateFlow<UserPreferences> = preferencesRepository.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    val permissionSnapshot: StateFlow<PermissionSnapshot> = permissionStateProvider.snapshotFlow

    private val mutableAvailability = MutableStateFlow<PrivilegeAvailability>(
        PrivilegeAvailability.NotConfigured("Checking privileged tier status…"),
    )
    val availability: StateFlow<PrivilegeAvailability> = mutableAvailability.asStateFlow()

    private val mutableEndpoint = MutableStateFlow<PairingEndpointInfo?>(null)
    val discoveredEndpoint: StateFlow<PairingEndpointInfo?> = mutableEndpoint.asStateFlow()

    private val mutableStatusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = mutableStatusBanner.asStateFlow()

    private var discoveryJob: Job? = null

    val oneTimeCommand: String = setupCoordinator.buildOneTimeAdbCommand()
    val oneTimeQrMatrix: Array<BooleanArray> by lazy { setupCoordinator.encodeQrMatrix(oneTimeCommand) }

    fun refreshStatus() {
        viewModelScope.launch {
            permissionStateProvider.refresh()
            mutableAvailability.value = privilegedEngine.checkAvailability()
        }
    }

    fun copyCommand(copiedMsg: String) {
        setupCoordinator.copyOneTimeCommandToClipboard()
        mutableStatusBanner.value = copiedMsg
    }

    fun setScanning(enabled: Boolean) {
        discoveryJob?.cancel()
        discoveryJob = null
        if (!enabled) {
            mutableEndpoint.value = null
            return
        }
        discoveryJob = viewModelScope.launch {
            setupCoordinator.observeWirelessPairingEndpoints().collect { ep ->
                mutableEndpoint.value = ep
            }
        }
    }

    fun postPairingNotification(endpoint: PairingEndpointInfo) {
        setupCoordinator.showPairingCodeNotification(endpoint.host, endpoint.port)
    }

    fun pairDirect(endpoint: PairingEndpointInfo, code: String) {
        viewModelScope.launch {
            val result = setupCoordinator.pairWirelessAdb(endpoint.host, endpoint.port, code.trim())
            mutableStatusBanner.value = when (result) {
                is PrivilegeOperationResult.Succeeded -> result.detail
                is PrivilegeOperationResult.Failed -> result.reason
            }
            refreshStatus()
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferencesRepository.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setDynamicColor(enabled) }
    }

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setWirelessDebuggingAutoReconnect(enabled) }
    }

    fun exportBackup(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val rules = ruleRepository.getAllRules()
                val json = RoutineBackupSerializer.exportToJson(rules)
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri)
                        ?: error("Unable to open output stream for selected URI")
                    out.use { it.write(json.encodeToByteArray()) }
                }
                rules.size
            }.onSuccess { count ->
                mutableStatusBanner.value = context.getString(R.string.settings_export_success, count)
            }.onFailure { err ->
                android.util.Log.w("SettingsViewModel", "Backup export failed", err)
                mutableStatusBanner.value = context.getString(
                    R.string.settings_backup_error,
                    err.message ?: "Export error",
                )
            }
        }
    }

    fun importBackup(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val rawJson = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Unable to open input stream for selected URI")
                    input.bufferedReader().use { it.readText() }
                }
                val rules = RoutineBackupSerializer.importFromJson(rawJson).getOrThrow()
                rules.forEach { rule ->
                    ruleRepository.upsertRule(rule)
                    if (rule.enabled) {
                        triggerScheduler.registerRuleTrigger(rule)
                    } else {
                        triggerScheduler.unregisterRuleTrigger(rule)
                    }
                }
                rules.size
            }.onSuccess { count ->
                mutableStatusBanner.value = context.getString(R.string.settings_import_success, count)
            }.onFailure { err ->
                android.util.Log.w("SettingsViewModel", "Backup import failed", err)
                mutableStatusBanner.value = context.getString(
                    R.string.settings_backup_error,
                    err.message ?: "Import error",
                )
            }
        }
    }
}

/**
 * Settings screen hosting Privileged Tier setup (Options A & B), Battery Optimization, SAF JSON
 * Backup/Restore, Theme preferences, and About info.
 */
@Composable
fun SettingsScreen(onReplayOnboarding: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val snapshot by viewModel.permissionSnapshot.collectAsStateWithLifecycle()
    val availability by viewModel.availability.collectAsStateWithLifecycle()
    val endpoint by viewModel.discoveredEndpoint.collectAsStateWithLifecycle()
    val banner by viewModel.statusBanner.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshStatus() }
    DisposableEffect(Unit) { onDispose { viewModel.setScanning(false) } }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) viewModel.exportBackup(context, uri) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.importBackup(context, uri) }

    val copiedToast = stringResource(R.string.settings_copied_toast)
    SettingsContent(
        preferences = preferences,
        snapshot = snapshot,
        availability = availability,
        oneTimeCommand = viewModel.oneTimeCommand,
        qrMatrix = viewModel.oneTimeQrMatrix,
        discoveredEndpoint = endpoint,
        statusBanner = banner,
        onRecheckStatus = viewModel::refreshStatus,
        onCopyCommand = { viewModel.copyCommand(copiedToast) },
        onSetScanning = viewModel::setScanning,
        onSendPairingNotification = viewModel::postPairingNotification,
        onPairDirect = viewModel::pairDirect,
        onSetAutoReconnect = viewModel::setAutoReconnect,
        onExportBackup = { exportLauncher.launch("routine-backup.json") },
        onImportBackup = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
        onSetThemeMode = viewModel::setThemeMode,
        onSetDynamicColor = viewModel::setDynamicColor,
        onReplayOnboarding = onReplayOnboarding,
    )
}

@Composable
fun SettingsContent(
    preferences: UserPreferences,
    snapshot: PermissionSnapshot,
    availability: PrivilegeAvailability,
    oneTimeCommand: String,
    qrMatrix: Array<BooleanArray>,
    discoveredEndpoint: PairingEndpointInfo?,
    statusBanner: String?,
    onRecheckStatus: () -> Unit,
    onCopyCommand: () -> Unit,
    onSetScanning: (Boolean) -> Unit,
    onSendPairingNotification: (PairingEndpointInfo) -> Unit,
    onPairDirect: (PairingEndpointInfo, String) -> Unit,
    onSetAutoReconnect: (Boolean) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onReplayOnboarding: () -> Unit,
) {
    val context = LocalContext.current
    var pendingPermission by rememberSaveable { mutableStateOf<SystemPermission?>(null) }

    pendingPermission?.let { perm ->
        PermissionExplanationBottomSheet(
            permission = perm,
            onDismiss = { pendingPermission = null },
            onConfirmProceed = {
                pendingPermission = null
                runCatching {
                    context.startActivity(PermissionPromptCatalog.buildSettingsIntent(context, perm))
                }.onFailure { err ->
                    android.util.Log.w("SettingsContent", "Failed to open settings intent", err)
                }
                onRecheckStatus()
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (statusBanner != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth().testTag("settings_status_banner")) {
                    Text(
                        text = statusBanner,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        item {
            PrivilegedSetupSection(
                availability = availability,
                oneTimeCommand = oneTimeCommand,
                qrMatrix = qrMatrix,
                discoveredEndpoint = discoveredEndpoint,
                autoReconnect = preferences.wirelessDebuggingAutoReconnect,
                onRecheckStatus = onRecheckStatus,
                onCopyCommand = onCopyCommand,
                onOpenDevOptions = { pendingPermission = SystemPermission.ADB_SHELL_BRIDGE },
                onSetScanning = onSetScanning,
                onSendPairingNotification = onSendPairingNotification,
                onPairDirect = onPairDirect,
                onSetAutoReconnect = onSetAutoReconnect,
            )
        }
        item {
            ReliabilitySection(
                snapshot = snapshot,
                onRequestPermissionSheet = { pendingPermission = it },
                onReplayOnboarding = onReplayOnboarding,
            )
        }
        item {
            BackupAndAppearanceSection(
                preferences = preferences,
                onExportBackup = onExportBackup,
                onImportBackup = onImportBackup,
                onSetThemeMode = onSetThemeMode,
                onSetDynamicColor = onSetDynamicColor,
            )
        }
    }
}

@Composable
private fun PrivilegedSetupSection(
    availability: PrivilegeAvailability,
    oneTimeCommand: String,
    qrMatrix: Array<BooleanArray>,
    discoveredEndpoint: PairingEndpointInfo?,
    autoReconnect: Boolean,
    onRecheckStatus: () -> Unit,
    onCopyCommand: () -> Unit,
    onOpenDevOptions: () -> Unit,
    onSetScanning: (Boolean) -> Unit,
    onSendPairingNotification: (PairingEndpointInfo) -> Unit,
    onPairDirect: (PairingEndpointInfo, String) -> Unit,
    onSetAutoReconnect: (Boolean) -> Unit,
) {
    var showQr by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var pairingCode by rememberSaveable { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.settings_privilege_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            val (statusText, statusColor) = when (availability) {
                is PrivilegeAvailability.AdbBridgeConnected ->
                    stringResource(R.string.settings_status_adb_bridge, availability.uid) to Color(0xFF2E7D32)
                PrivilegeAvailability.OneTimeGrantActive ->
                    stringResource(R.string.settings_status_one_time_grant) to Color(0xFF2E7D32)
                is PrivilegeAvailability.NotConfigured -> {
                    val msg = stringResource(R.string.settings_status_not_configured, availability.reason)
                    msg to MaterialTheme.colorScheme.error
                }
            }
            Text(text = statusText, color = statusColor, style = MaterialTheme.typography.labelLarge)
            OutlinedButton(
                onClick = onRecheckStatus,
                modifier = Modifier.testTag("settings_recheck_privilege_button"),
            ) {
                Text(stringResource(R.string.settings_recheck_button))
            }

            Text(text = stringResource(R.string.settings_opt_b_title), style = MaterialTheme.typography.titleSmall)
            Text(text = stringResource(R.string.settings_opt_b_steps), style = MaterialTheme.typography.bodySmall)
            Text(
                text = oneTimeCommand,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCopyCommand) { Text(stringResource(R.string.settings_copy_command)) }
                val qrBtnRes = if (showQr) R.string.settings_toggle_qr_hide else R.string.settings_toggle_qr_show
                OutlinedButton(onClick = { showQr = !showQr }) {
                    Text(stringResource(qrBtnRes))
                }
            }
            if (showQr && qrMatrix.isNotEmpty()) {
                QrMatrixCanvas(matrix = qrMatrix)
            }

            Text(text = stringResource(R.string.settings_opt_a_title), style = MaterialTheme.typography.titleSmall)
            Text(text = stringResource(R.string.settings_opt_a_steps), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenDevOptions) {
                    Text(stringResource(R.string.settings_open_dev_options))
                }
                val scanBtnRes = if (scanning) R.string.settings_scan_ports_stop else R.string.settings_scan_ports_start
                Button(
                    onClick = {
                        scanning = !scanning
                        onSetScanning(scanning)
                    },
                ) {
                    Text(stringResource(scanBtnRes))
                }
            }
            if (scanning) {
                if (discoveredEndpoint == null) {
                    Text(stringResource(R.string.settings_endpoint_waiting), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        text = stringResource(
                            R.string.settings_endpoint_found,
                            discoveredEndpoint.host,
                            discoveredEndpoint.port,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    OutlinedButton(onClick = { onSendPairingNotification(discoveredEndpoint) }) {
                        Text(stringResource(R.string.settings_send_pair_notification))
                    }
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                        label = { Text(stringResource(R.string.settings_pairing_code_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onPairDirect(discoveredEndpoint, pairingCode) },
                        enabled = pairingCode.length == 6,
                    ) {
                        Text(stringResource(R.string.settings_pair_now))
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_auto_reconnect_label),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(checked = autoReconnect, onCheckedChange = onSetAutoReconnect)
            }
        }
    }
}

@Composable
private fun QrMatrixCanvas(matrix: Array<BooleanArray>) {
    val modules = matrix.size
    Canvas(modifier = Modifier.size(176.dp).padding(4.dp)) {
        drawRect(color = Color.White, size = size)
        val cell = size.minDimension / modules.toFloat()
        for (y in 0 until modules) {
            for (x in 0 until modules) {
                if (matrix[y][x]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * cell, y * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReliabilitySection(
    snapshot: PermissionSnapshot,
    onRequestPermissionSheet: (SystemPermission) -> Unit,
    onReplayOnboarding: () -> Unit,
) {
    val batteryExempt = snapshot.isSatisfied(SystemPermission.IGNORE_BATTERY_OPTIMIZATIONS)
    val exactAlarmReady = snapshot.isSatisfied(SystemPermission.SCHEDULE_EXACT_ALARM)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.settings_reliability_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            val batteryRes = if (batteryExempt) {
                R.string.settings_battery_status_exempt
            } else {
                R.string.settings_battery_status_optimized
            }
            Text(text = stringResource(batteryRes), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { onRequestPermissionSheet(SystemPermission.IGNORE_BATTERY_OPTIMIZATIONS) }) {
                Text(stringResource(R.string.settings_configure_battery))
            }
            val alarmRes = if (exactAlarmReady) {
                R.string.settings_exact_alarm_ready
            } else {
                R.string.settings_exact_alarm_needed
            }
            Text(text = stringResource(alarmRes), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { onRequestPermissionSheet(SystemPermission.SCHEDULE_EXACT_ALARM) }) {
                Text(stringResource(R.string.settings_configure_exact_alarm))
            }
            OutlinedButton(onClick = onReplayOnboarding) {
                Text(stringResource(R.string.settings_replay_onboarding))
            }
        }
    }
}

@Composable
private fun BackupAndAppearanceSection(
    preferences: UserPreferences,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.settings_backup_heading),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(text = stringResource(R.string.settings_backup_desc), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onExportBackup,
                        modifier = Modifier.testTag("settings_export_json_button"),
                    ) {
                        Text(stringResource(R.string.settings_export_json))
                    }
                    OutlinedButton(
                        onClick = onImportBackup,
                        modifier = Modifier.testTag("settings_import_json_button"),
                    ) {
                        Text(stringResource(R.string.settings_import_json))
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.settings_appearance_heading),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = preferences.themeMode == mode,
                            onClick = { onSetThemeMode(mode) },
                            label = { Text(mode.name) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_dynamic_color_label),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = preferences.dynamicColor, onCheckedChange = onSetDynamicColor)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.settings_about_heading),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.settings_about_version),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(text = stringResource(R.string.settings_about_privacy), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
