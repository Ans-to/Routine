package app.routine.di

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import app.routine.R
import app.routine.core.engine.ActionExecutor
import app.routine.core.engine.ActionExecutorRegistry
import app.routine.core.engine.CircuitBreakerListener
import app.routine.core.engine.ExecutionLogSink
import app.routine.core.engine.MapActionExecutorRegistry
import app.routine.core.engine.RuleEngine
import app.routine.core.engine.RuleExecutionStateStore
import app.routine.core.engine.TriggerMatcher
import app.routine.core.model.Rule
import app.routine.core.permissions.CapabilityChecker
import app.routine.core.permissions.DefaultCapabilityChecker
import app.routine.core.permissions.PermissionStateProvider
import app.routine.privilege.api.PrivilegeSetupCoordinator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Top-level Hilt module assembling [CapabilityChecker], [ActionExecutorRegistry], and [RuleEngine].
 */
@Module
@InstallIn(SingletonComponent::class)
object AppEngineModule {
    @Provides
    @Singleton
    fun providePermissionStateProvider(impl: AndroidPermissionStateProvider): PermissionStateProvider = impl

    @Provides
    @Singleton
    fun providePrivilegeSetupCoordinator(impl: AndroidPrivilegeSetupCoordinator): PrivilegeSetupCoordinator = impl

    @Provides
    @Singleton
    fun provideCapabilityChecker(provider: PermissionStateProvider): CapabilityChecker =
        DefaultCapabilityChecker(provider)

    @Provides
    @Singleton
    fun provideActionExecutorRegistry(executors: Set<@JvmSuppressWildcards ActionExecutor>): ActionExecutorRegistry =
        MapActionExecutorRegistry(executors)

    @Provides
    @Singleton
    fun provideCircuitBreakerListener(@ApplicationContext context: Context): CircuitBreakerListener =
        CircuitBreakerListener { rule: Rule, _: Int, lastError: String ->
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CIRCUIT_BREAKER_CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CIRCUIT_BREAKER_CHANNEL,
                        context.getString(R.string.channel_alerts_name),
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            val notification = NotificationCompat.Builder(context, CIRCUIT_BREAKER_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(R.string.circuit_breaker_title, rule.name))
                .setContentText(context.getString(R.string.circuit_breaker_body, lastError))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            nm.notify(("cb_${rule.id}").hashCode() and 0x7FFFFFFF, notification)
        }

    @Provides
    @Singleton
    fun provideRuleEngine(
        capabilityChecker: CapabilityChecker,
        triggerMatcher: TriggerMatcher,
        executorRegistry: ActionExecutorRegistry,
        logSink: ExecutionLogSink,
        stateStore: RuleExecutionStateStore,
        circuitBreakerListener: CircuitBreakerListener,
    ): RuleEngine = RuleEngine(
        capabilityChecker = capabilityChecker,
        triggerMatcher = triggerMatcher,
        executorRegistry = executorRegistry,
        logSink = logSink,
        stateStore = stateStore,
        circuitBreakerListener = circuitBreakerListener,
    )

    private const val CIRCUIT_BREAKER_CHANNEL = "routine_circuit_breaker_alerts"
}
