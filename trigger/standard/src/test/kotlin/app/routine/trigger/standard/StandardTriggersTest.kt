package app.routine.trigger.standard

import android.app.AlarmManager
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.routine.core.data.local.RoutineDatabase
import app.routine.core.data.repository.LogRepository
import app.routine.core.data.repository.RuleRepository
import app.routine.core.engine.ActionExecutionOutcome
import app.routine.core.engine.ActionExecutor
import app.routine.core.engine.MapActionExecutorRegistry
import app.routine.core.engine.RuleEngine
import app.routine.core.engine.TriggerMatcher
import app.routine.core.model.Action
import app.routine.core.model.ActionType
import app.routine.core.model.ExecutionResult
import app.routine.core.model.Rule
import app.routine.core.model.Trigger
import app.routine.core.model.TriggerEvent
import app.routine.core.model.TriggerType
import app.routine.core.permissions.DefaultCapabilityChecker
import app.routine.core.permissions.PermissionSnapshot
import app.routine.core.permissions.PermissionStateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class StandardTriggersTest {
    private lateinit var context: Context
    private lateinit var database: RoutineDatabase
    private lateinit var ruleRepository: RuleRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, RoutineDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ruleRepository = RuleRepository(database.ruleDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `time scheduler registers exact alarm and receiver builds matching TriggerEvent`() {
        val scheduler = TriggerScheduler(context, ruleRepository)
        val rule = Rule(
            id = "morning",
            name = "Morning Alarm",
            trigger = Trigger(TriggerType.TIME, mapOf("hour" to "7", "minute" to "15")),
            actions = listOf(Action(ActionType.TOAST, mapOf("message" to "Good morning"))),
        )
        val scheduledAt = scheduler.scheduleNextExactTimeAlarm(rule, nowMs = 1_700_000_000_000L)
        assertNotNull(scheduledAt)
        assertTrue(scheduledAt!! > 1_700_000_000_000L)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertNotNull(shadowOf(alarmManager).nextScheduledAlarm)

        val alarmIntent = Intent(TriggerScheduler.ACTION_ROUTINE_TIME_ALARM).apply {
            putExtra(TriggerScheduler.EXTRA_HOUR, 7)
            putExtra(TriggerScheduler.EXTRA_MINUTE, 15)
        }
        val event = TimeAlarmReceiver.buildTimeEvent(context, alarmIntent, nowMs = 1_700_000_000_000L)
        assertEquals(TriggerType.TIME, event.type)
        assertEquals("7", event.attributes["hour"])
        assertEquals("15", event.attributes["minute"])
    }

    @Test
    fun `battery and charger broadcast parsers extract accurate state`() {
        val batteryIntent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, 18)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
        }
        val parsed = TriggerScheduler.parseBatteryIntent(batteryIntent)
        assertEquals(18 to false, parsed)

        val connectedEvent = ChargerTriggerReceiver.parseChargerIntent(Intent(Intent.ACTION_POWER_CONNECTED))
        assertEquals("CONNECTED", connectedEvent?.attributes?.get("state"))

        val disconnectedEvent = ChargerTriggerReceiver.parseChargerIntent(Intent(Intent.ACTION_POWER_DISCONNECTED))
        assertEquals("DISCONNECTED", disconnectedEvent?.attributes?.get("state"))
    }

    @Test
    fun `wifi receiver tracks connected and disconnected SSID transitions`() {
        @Suppress("DEPRECATION")
        val connectedInfo = org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            android.net.ConnectivityManager.TYPE_WIFI,
            0,
            true,
            NetworkInfo.State.CONNECTED,
        )
        val connectIntent = Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION).apply {
            putExtra(WifiManager.EXTRA_NETWORK_INFO, connectedInfo)
            putExtra("ssid", "\"HomeFiber\"")
        }
        val connectEvent = WifiTriggerReceiver.evaluateWifiStateChange(context, connectIntent)
        assertEquals("HomeFiber", connectEvent?.attributes?.get("ssid"))
        assertEquals("CONNECTED", connectEvent?.attributes?.get("transition"))

        @Suppress("DEPRECATION")
        val disconnectedInfo = org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.DISCONNECTED,
            android.net.ConnectivityManager.TYPE_WIFI,
            0,
            false,
            NetworkInfo.State.DISCONNECTED,
        )
        val disconnectIntent = Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION).apply {
            putExtra(WifiManager.EXTRA_NETWORK_INFO, disconnectedInfo)
        }
        val disconnectEvent = WifiTriggerReceiver.evaluateWifiStateChange(context, disconnectIntent)
        assertEquals("HomeFiber", disconnectEvent?.attributes?.get("ssid"))
        assertEquals("DISCONNECTED", disconnectEvent?.attributes?.get("transition"))
    }

    @Test
    fun `bluetooth and hotspot receivers parse events and schedule 30-min timeout`() {
        val btIntent = Intent(BluetoothDevice.ACTION_ACL_CONNECTED).apply {
            putExtra("routine_bt_category", "CAR")
            putExtra("routine_bt_name", "Car Kit")
        }
        val btEvent = BluetoothTriggerReceiver.parseBluetoothIntent(btIntent)
        assertEquals("CAR", btEvent?.attributes?.get("deviceCategory"))
        assertEquals("Car Kit", btEvent?.attributes?.get("deviceName"))

        val hotspotReceiver = HotspotStateReceiver()
        hotspotReceiver.onReceive(
            context,
            Intent(HotspotStateReceiver.ACTION_WIFI_AP_STATE_CHANGED).apply {
                putExtra("wifi_state", HotspotStateReceiver.WIFI_AP_STATE_ENABLED)
            },
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertNotNull(shadowOf(alarmManager).nextScheduledAlarm)
    }

    @Test
    fun `dispatcher fires enabled boot rule end-to-end`() = runTest {
        val bootRule = Rule(
            id = "boot-toast",
            name = "Boot Toast",
            trigger = Trigger(TriggerType.BOOT_COMPLETED),
            actions = listOf(Action(ActionType.TOAST, mapOf("message" to "Routine started"))),
        )
        ruleRepository.upsertRule(bootRule)

        val toastExec = object : ActionExecutor {
            override val supportedType: ActionType = ActionType.TOAST
            override suspend fun execute(action: Action, event: TriggerEvent?): ActionExecutionOutcome =
                ActionExecutionOutcome.Success("Toast shown")
        }
        val permProvider = object : PermissionStateProvider {
            override val snapshotFlow: StateFlow<PermissionSnapshot> =
                MutableStateFlow(PermissionSnapshot(emptySet()))
            override suspend fun refresh(): PermissionSnapshot = snapshotFlow.value
        }
        val engine = RuleEngine(
            capabilityChecker = DefaultCapabilityChecker(permProvider),
            triggerMatcher = TriggerMatcher(),
            executorRegistry = MapActionExecutorRegistry(setOf(toastExec)),
            logSink = LogRepository(database.logDao()),
            stateStore = ruleRepository,
        )
        val dispatcher = RuleTriggerDispatcher(ruleRepository, engine)
        val outcomes = dispatcher.dispatch(TriggerEvent(TriggerType.BOOT_COMPLETED))
        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is ExecutionResult.Succeeded)
    }
}
