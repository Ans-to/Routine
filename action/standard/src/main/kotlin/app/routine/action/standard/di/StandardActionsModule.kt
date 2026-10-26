package app.routine.action.standard.di

import app.routine.action.standard.BatterySaverActionExecutor
import app.routine.action.standard.BrightnessActionExecutor
import app.routine.action.standard.DoNotDisturbActionExecutor
import app.routine.action.standard.NotifyActionExecutor
import app.routine.action.standard.OpenAppActionExecutor
import app.routine.action.standard.OpenUrlActionExecutor
import app.routine.action.standard.RingerActionExecutor
import app.routine.action.standard.RotationLockActionExecutor
import app.routine.action.standard.SilenceUnknownCallerActionExecutor
import app.routine.action.standard.SmsAutoReplyActionExecutor
import app.routine.action.standard.ToastActionExecutor
import app.routine.core.engine.ActionExecutor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Hilt multibindings registering all standard-tier [ActionExecutor] implementations.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StandardActionsModule {
    @Binds
    @IntoSet
    abstract fun bindNotify(executor: NotifyActionExecutor): ActionExecutor

    @Binds
    @IntoSet
    abstract fun bindToast(executor: ToastActionExecutor): ActionExecutor

    @Binds
    @IntoSet
    abstract fun bindRinger(executor: RingerActionExecutor): ActionExecutor

    @Binds
    @IntoSet
    abstract fun bindOpenApp(executor: OpenAppActionExecutor): ActionExecutor

    @Binds
    @IntoSet
    abstract fun bindOpenUrl(executor: OpenUrlActionExecutor): ActionExecutor

    @Binds
    @IntoSet
    abstract fun bindBrightness(executor: BrightnessActionExecutor): ActionExecutor

    @Binds
    @IntoSet
    abstract fun bindDoNotDisturb(executor: DoNotDisturbActionExecutor): ActionExecutor

    @Binds
    @IntoSet
    abstract fun bindSmsAutoReply(executor: SmsAutoReplyActionExecutor): ActionExecutor

    @Binds
    @IntoSet
    abstract fun bindBatterySaver(executor: BatterySaverActionExecutor): ActionExecutor

    @Binds
    @IntoSet
    abstract fun bindRotationLock(executor: RotationLockActionExecutor): ActionExecutor

    @Binds
    @IntoSet
    abstract fun bindSilenceUnknownCaller(executor: SilenceUnknownCallerActionExecutor): ActionExecutor
}
