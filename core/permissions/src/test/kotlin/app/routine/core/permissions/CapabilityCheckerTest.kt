package app.routine.core.permissions

import app.routine.core.model.Action
import app.routine.core.model.ActionType
import app.routine.core.model.PrivilegeTier
import app.routine.core.model.Rule
import app.routine.core.model.SystemPermission
import app.routine.core.model.Template
import app.routine.core.model.Trigger
import app.routine.core.model.TriggerType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityCheckerTest {
    private class FakePermissionStateProvider(initial: PermissionSnapshot) : PermissionStateProvider {
        private val flow = MutableStateFlow(initial)
        override val snapshotFlow: StateFlow<PermissionSnapshot> = flow
        override suspend fun refresh(): PermissionSnapshot = flow.value
        fun update(snapshot: PermissionSnapshot) {
            flow.value = snapshot
        }
    }

    @Test
    fun `returns Ready when all standard permissions are granted`() = runTest {
        val provider = FakePermissionStateProvider(
            PermissionSnapshot(
                grantedPermissions = setOf(
                    SystemPermission.SCHEDULE_EXACT_ALARM,
                    SystemPermission.POST_NOTIFICATIONS,
                ),
            ),
        )
        val checker = DefaultCapabilityChecker(provider)
        val rule = Rule(
            id = "r1",
            name = "Reminder",
            trigger = Trigger(TriggerType.TIME, mapOf("hour" to "8", "minute" to "0")),
            actions = listOf(Action(ActionType.NOTIFY, mapOf("title" to "Hi", "message" to "Morning"))),
        )
        assertEquals(Capability.Ready, checker.checkRule(rule))
    }

    @Test
    fun `returns NeedsSetup when standard permission is missing`() = runTest {
        val provider = FakePermissionStateProvider(PermissionSnapshot(emptySet()))
        val checker = DefaultCapabilityChecker(provider)
        val rule = Rule(
            id = "r2",
            name = "Cinema Silent",
            trigger = Trigger(
                TriggerType.LOCATION_GEOFENCE,
                mapOf("latitude" to "24.8607", "longitude" to "67.0011", "radiusMeters" to "150"),
            ),
            actions = listOf(Action(ActionType.RINGER_MODE, mapOf("mode" to "SILENT"))),
        )
        val capability = checker.checkRule(rule)
        assertTrue(capability is Capability.NeedsSetup)
        val needsSetup = capability as Capability.NeedsSetup
        assertTrue(SystemPermission.ACCESS_FINE_LOCATION in needsSetup.missingPermissions)
        assertTrue(SystemPermission.NOTIFICATION_POLICY_ACCESS in needsSetup.missingPermissions)
    }

    @Test
    fun `returns RequiresPrivilegeTier when privileged engine is not configured`() = runTest {
        val provider = FakePermissionStateProvider(
            PermissionSnapshot(grantedPermissions = setOf(SystemPermission.SCHEDULE_EXACT_ALARM)),
        )
        val checker = DefaultCapabilityChecker(provider)
        val wifiOffRule = Rule(
            id = "wifi-bedtime",
            name = "Wi-Fi Off at Bedtime",
            trigger = Trigger(TriggerType.TIME, mapOf("hour" to "23", "minute" to "0")),
            actions = listOf(Action(ActionType.WIFI_TOGGLE, mapOf("enabled" to "false"))),
        )

        val capBefore = checker.checkRule(wifiOffRule)
        assertTrue(capBefore is Capability.RequiresPrivilegeTier)
        assertEquals(PrivilegeTier.PRIVILEGED, (capBefore as Capability.RequiresPrivilegeTier).requiredTier)

        provider.update(
            PermissionSnapshot(
                grantedPermissions = setOf(SystemPermission.SCHEDULE_EXACT_ALARM),
                isAdbBridgeReady = true,
            ),
        )
        assertEquals(Capability.Ready, checker.checkRule(wifiOffRule))
    }

    @Test
    fun `one-time grant satisfies WRITE_SECURE_SETTINGS without ADB shell bridge`() {
        val provider = FakePermissionStateProvider(PermissionSnapshot(emptySet()))
        val checker = DefaultCapabilityChecker(provider)
        val nightLightRule = Rule(
            id = "night-light",
            name = "Night Light On",
            trigger = Trigger(TriggerType.TIME, mapOf("hour" to "21", "minute" to "0")),
            actions = listOf(
                Action(
                    ActionType.SECURE_SETTING,
                    mapOf("namespace" to "SECURE", "key" to "night_display_activated", "value" to "1"),
                ),
            ),
        )
        val snapshot = PermissionSnapshot(
            grantedPermissions = setOf(SystemPermission.SCHEDULE_EXACT_ALARM),
            isOneTimeGrantReady = true,
        )
        assertEquals(Capability.Ready, checker.evaluateRule(nightLightRule, snapshot))
    }

    @Test
    fun `returns Blocked when circuit breaker threshold reached or template invalid`() {
        val checker = DefaultCapabilityChecker(FakePermissionStateProvider(PermissionSnapshot(emptySet())))
        val trippedRule = Rule(
            id = "tripped",
            name = "Tripped Rule",
            trigger = Trigger(TriggerType.BOOT_COMPLETED),
            actions = listOf(Action(ActionType.TOAST, mapOf("message" to "Booted"))),
            consecutiveFailures = Rule.CIRCUIT_BREAKER_THRESHOLD,
        )
        assertTrue(checker.evaluateRule(trippedRule, PermissionSnapshot(emptySet())) is Capability.Blocked)

        val badTemplate = Template(
            id = "",
            title = "Bad",
            description = "Desc",
            iconName = "bolt",
            rule = trippedRule.copy(consecutiveFailures = 0),
            requiredTier = PrivilegeTier.STANDARD,
        )
        assertTrue(checker.evaluateTemplate(badTemplate, PermissionSnapshot(emptySet())) is Capability.Blocked)
    }
}
