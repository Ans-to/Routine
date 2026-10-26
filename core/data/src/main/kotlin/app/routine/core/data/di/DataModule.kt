package app.routine.core.data.di

import android.content.Context
import app.routine.core.data.local.LogDao
import app.routine.core.data.local.RoutineDatabase
import app.routine.core.data.local.RuleDao
import app.routine.core.data.repository.LogRepository
import app.routine.core.data.repository.RuleRepository
import app.routine.core.engine.ExecutionLogSink
import app.routine.core.engine.RuleExecutionStateStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for Routine's encrypted Room database and repositories.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindingsModule {
    @Binds
    @Singleton
    abstract fun bindExecutionLogSink(logRepository: LogRepository): ExecutionLogSink

    @Binds
    @Singleton
    abstract fun bindRuleExecutionStateStore(ruleRepository: RuleRepository): RuleExecutionStateStore
}

/**
 * Hilt providers for [RoutineDatabase] and its DAOs.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideRoutineDatabase(@ApplicationContext context: Context): RoutineDatabase =
        RoutineDatabase.buildEncrypted(context)

    @Provides
    fun provideRuleDao(database: RoutineDatabase): RuleDao = database.ruleDao()

    @Provides
    fun provideLogDao(database: RoutineDatabase): LogDao = database.logDao()
}
