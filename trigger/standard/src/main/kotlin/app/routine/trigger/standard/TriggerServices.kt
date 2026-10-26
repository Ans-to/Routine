package app.routine.trigger.standard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.routine.core.data.repository.RuleRepository
import app.routine.core.model.TriggerEvent
import app.routine.core.model.TriggerType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hilt entry point for system-instantiated Accessibility and CallScreening services.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TriggerServiceEntryPoint {
    fun dispatcher(): RuleTriggerDispatcher
    fun ruleRepository(): RuleRepository
}

/**
 * Optional deterministic AccessibilityService used strictly to observe foreground package transitions
 * (`TYPE_WINDOW_STATE_CHANGED`) for user-configured `APP_OPENED` rules. Never performs UI tapping or scraping.
 */
class RoutineAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastForegroundPackage: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (pkg == lastForegroundPackage || pkg == packageName) return
        lastForegroundPackage = pkg

        val dispatcher = EntryPointAccessors.fromApplication(
            applicationContext,
            TriggerServiceEntryPoint::class.java,
        ).dispatcher()

        serviceScope.launch {
            dispatcher.dispatch(
                TriggerEvent(
                    type = TriggerType.APP_OPENED,
                    attributes = mapOf("packageName" to pkg),
                ),
            )
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}

/**
 * Screens incoming calls on Android 10+ when the user activates the "Silence Unknown Callers" rule.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class RoutineCallScreeningService : CallScreeningService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            TriggerServiceEntryPoint::class.java,
        )
        val handleNumber = callDetails.handle?.schemeSpecificPart.orEmpty()

        serviceScope.launch {
            val rules = entryPoint.ruleRepository().getEnabledRulesForTrigger(TriggerType.CALL_RECEIVED)
            if (rules.isEmpty()) {
                respondToCall(callDetails, CallResponse.Builder().build())
                return@launch
            }
            val known = isNumberInContacts(applicationContext, handleNumber)
            if (!known) {
                val shouldReject = rules.any { rule ->
                    rule.actions.any { it.config["reject"] == "true" }
                }
                val response = CallResponse.Builder()
                    .setSilenceCall(true)
                    .setDisallowCall(shouldReject)
                    .setRejectCall(shouldReject)
                    .build()
                respondToCall(callDetails, response)
            } else {
                respondToCall(callDetails, CallResponse.Builder().build())
            }
            entryPoint.dispatcher().dispatch(
                TriggerEvent(
                    type = TriggerType.CALL_RECEIVED,
                    attributes = mapOf("isKnownContact" to known.toString()),
                ),
            )
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        fun isNumberInContacts(context: Context, number: String): Boolean {
            if (number.isBlank()) return false
            val hasContacts = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_CONTACTS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasContacts) return false
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number),
            )
            return runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(ContactsContract.PhoneLookup._ID),
                    null,
                    null,
                    null,
                ).use { cursor -> cursor != null && cursor.count > 0 }
            }.getOrDefault(false)
        }
    }
}
