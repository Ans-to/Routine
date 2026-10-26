package app.routine

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.routine.core.data.repository.ThemeMode
import app.routine.core.data.repository.UserPreferences
import app.routine.core.data.repository.UserPreferencesRepository
import app.routine.core.permissions.PermissionStateProvider
import app.routine.feature.onboarding.OnboardingScreen
import app.routine.feature.rules.LogViewerScreen
import app.routine.feature.rules.MyRulesScreen
import app.routine.feature.rules.RuleEditorScreen
import app.routine.feature.settings.SettingsScreen
import app.routine.feature.templates.TemplatesHomeScreen
import app.routine.ui.theme.RoutineTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class RoutineTab {
    HOME,
    RULES,
    LOGS,
    SETTINGS,
}

sealed interface AppUiReadyState {
    data object Loading : AppUiReadyState
    data class Loaded(val preferences: UserPreferences) : AppUiReadyState
}

@HiltViewModel
class RoutineRootViewModel @Inject constructor(
    preferencesRepository: UserPreferencesRepository,
    private val permissionStateProvider: PermissionStateProvider,
) : ViewModel() {
    val uiState: StateFlow<AppUiReadyState> = preferencesRepository.preferencesFlow
        .map<UserPreferences, AppUiReadyState> { AppUiReadyState.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiReadyState.Loading)

    fun refreshPermissions() {
        viewModelScope.launch {
            permissionStateProvider.refresh()
        }
    }
}

/**
 * Primary Compose host activity for Routine.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RoutineAppRoot()
        }
    }
}

@Composable
fun RoutineAppRoot(viewModel: RoutineRootViewModel = hiltViewModel()) {
    val readyState by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs = (readyState as? AppUiReadyState.Loaded)?.preferences ?: UserPreferences(onboardingCompleted = true)
    val darkTheme = when (prefs.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.refreshPermissions()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
    }

    RoutineTheme(darkTheme = darkTheme, dynamicColor = prefs.dynamicColor) {
        if (readyState is AppUiReadyState.Loaded) {
            RoutineAppScaffold(
                onboardingCompleted = prefs.onboardingCompleted,
                onRequestRuntimePermission = { perm -> runtimePermissionLauncher.launch(perm) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutineAppScaffold(onboardingCompleted: Boolean, onRequestRuntimePermission: (String) -> Unit) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var forceOnboarding by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(RoutineTab.HOME) }
    var editingRuleId by rememberSaveable { mutableStateOf<String?>(null) }
    var isEditorOpen by rememberSaveable { mutableStateOf(false) }

    if (!onboardingCompleted || forceOnboarding) {
        OnboardingScreen(
            onFinished = { forceOnboarding = false },
            onRequestRuntimePermission = onRequestRuntimePermission,
        )
        return
    }

    if (isEditorOpen) {
        Scaffold { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                RuleEditorScreen(
                    ruleId = editingRuleId,
                    onNavigateBack = {
                        isEditorOpen = false
                        editingRuleId = null
                    },
                )
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = stringResource(id = R.string.app_name)) })
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            RoutineBottomBar(
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (selectedTab) {
                RoutineTab.HOME -> TemplatesHomeScreen(
                    onNavigateToPrivilegeSetup = { selectedTab = RoutineTab.SETTINGS },
                    onRequestRuntimePermission = onRequestRuntimePermission,
                    onShowMessage = { ruleName ->
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.snackbar_template_activated, ruleName),
                            )
                        }
                        selectedTab = RoutineTab.RULES
                    },
                )
                RoutineTab.RULES -> MyRulesScreen(
                    onCreateRule = {
                        editingRuleId = null
                        isEditorOpen = true
                    },
                    onEditRule = { ruleId ->
                        editingRuleId = ruleId
                        isEditorOpen = true
                    },
                )
                RoutineTab.LOGS -> LogViewerScreen()
                RoutineTab.SETTINGS -> SettingsScreen(
                    onReplayOnboarding = { forceOnboarding = true },
                )
            }
        }
    }
}

@Composable
private fun RoutineBottomBar(selectedTab: RoutineTab, onSelectTab: (RoutineTab) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = selectedTab == RoutineTab.HOME,
            onClick = { onSelectTab(RoutineTab.HOME) },
            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_home)) },
        )
        NavigationBarItem(
            selected = selectedTab == RoutineTab.RULES,
            onClick = { onSelectTab(RoutineTab.RULES) },
            icon = { Icon(Icons.Default.Rule, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_rules)) },
        )
        NavigationBarItem(
            selected = selectedTab == RoutineTab.LOGS,
            onClick = { onSelectTab(RoutineTab.LOGS) },
            icon = { Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_logs)) },
        )
        NavigationBarItem(
            selected = selectedTab == RoutineTab.SETTINGS,
            onClick = { onSelectTab(RoutineTab.SETTINGS) },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_settings)) },
        )
    }
}
