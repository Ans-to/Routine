package app.routine

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.routine.core.model.ExecutionLogEntry
import app.routine.core.model.Rule
import app.routine.ui.theme.RoutineTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Primary Compose host activity for Routine.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RoutineTheme {
                DebugHarnessScreen()
            }
        }
    }
}

@Composable
private fun DebugHarnessScreen(viewModel: DebugRuleHarnessViewModel = hiltViewModel()) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(id = R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = stringResource(id = R.string.debug_harness_title),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::createSampleToastRule) {
                        Text(text = stringResource(id = R.string.debug_add_toast_rule))
                    }
                    OutlinedButton(onClick = viewModel::createSampleVibrateRule) {
                        Text(text = stringResource(id = R.string.debug_add_ringer_rule))
                    }
                }
            }
            item {
                Text(
                    text = stringResource(id = R.string.debug_active_rules_heading, rules.size),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(rules, key = { it.id }) { rule ->
                DebugRuleCard(
                    rule = rule,
                    onDryRun = { viewModel.fireRule(rule, dryRun = true) },
                    onFire = { viewModel.fireRule(rule, dryRun = false) },
                )
            }
            item {
                Text(
                    text = stringResource(id = R.string.debug_logs_heading, logs.size),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (logs.isEmpty()) {
                item {
                    Text(
                        text = stringResource(id = R.string.debug_no_logs),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                items(logs, key = { it.id }) { entry ->
                    DebugLogCard(entry = entry)
                }
            }
        }
    }
}

@Composable
private fun DebugRuleCard(rule: Rule, onDryRun: () -> Unit, onFire: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = rule.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = "${rule.trigger.type.name} -> ${rule.actions.joinToString { it.type.name }}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDryRun) {
                    Text(text = stringResource(id = R.string.debug_dry_run))
                }
                Button(onClick = onFire) {
                    Text(text = stringResource(id = R.string.debug_fire_now))
                }
            }
        }
    }
}

@Composable
private fun DebugLogCard(entry: ExecutionLogEntry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "[${entry.status.name}] ${entry.ruleName}",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(text = entry.summary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
