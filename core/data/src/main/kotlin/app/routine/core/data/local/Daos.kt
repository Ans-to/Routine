package app.routine.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.routine.core.data.DatabaseConstants
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for [RuleEntity] CRUD and execution-state updates.
 */
@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY enabled DESC, name ASC")
    fun observeAllRules(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules WHERE enabled = 1 ORDER BY name ASC")
    fun observeEnabledRules(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules ORDER BY name ASC")
    suspend fun getAllRules(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE enabled = 1 AND triggerType = :triggerType")
    suspend fun getEnabledRulesByTrigger(triggerType: String): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE id = :id LIMIT 1")
    suspend fun getRuleById(id: String): RuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(entity: RuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRules(entities: List<RuleEntity>)

    @Update
    suspend fun updateRule(entity: RuleEntity)

    @Query(
        """
        UPDATE rules
        SET enabled = :enabled,
            consecutiveFailures = CASE WHEN :enabled = 1 THEN 0 ELSE consecutiveFailures END
        WHERE id = :id
        """,
    )
    suspend fun setRuleEnabled(id: String, enabled: Boolean)

    @Query("UPDATE rules SET consecutiveFailures = 0, lastTriggeredEpochMs = :timestampEpochMs WHERE id = :id")
    suspend fun recordSuccess(id: String, timestampEpochMs: Long)

    @Transaction
    suspend fun incrementFailureAndMaybeDisable(id: String, threshold: Int): Int {
        val current = getRuleById(id) ?: return 0
        val updatedCount = current.consecutiveFailures + 1
        val remainEnabled = if (updatedCount >= threshold) false else current.enabled
        updateRule(
            current.copy(
                consecutiveFailures = updatedCount,
                enabled = remainEnabled,
            ),
        )
        return updatedCount
    }

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun deleteRuleById(id: String)
}

/**
 * Data access object for [LogEntity] with automatic 1,000-entry rotation.
 */
@Dao
interface LogDao {
    @Query("SELECT * FROM execution_logs ORDER BY timestampEpochMs DESC, id DESC")
    fun observeAllLogs(): Flow<List<LogEntity>>

    @Query(
        """
        SELECT * FROM execution_logs
        WHERE (:ruleId IS NULL OR ruleId = :ruleId)
          AND (:status IS NULL OR status = :status)
          AND (:fromEpochMs IS NULL OR timestampEpochMs >= :fromEpochMs)
          AND (:toEpochMs IS NULL OR timestampEpochMs <= :toEpochMs)
        ORDER BY timestampEpochMs DESC, id DESC
        """,
    )
    fun observeFilteredLogs(
        ruleId: String?,
        status: String?,
        fromEpochMs: Long?,
        toEpochMs: Long?,
    ): Flow<List<LogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRaw(entity: LogEntity): Long

    @Query(
        """
        DELETE FROM execution_logs
        WHERE id NOT IN (
            SELECT id FROM execution_logs
            ORDER BY timestampEpochMs DESC, id DESC
            LIMIT :maxEntries
        )
        """,
    )
    suspend fun pruneExcessLogs(maxEntries: Int = DatabaseConstants.MAX_LOG_ENTRIES)

    @Transaction
    suspend fun insertWithRotation(entity: LogEntity, maxEntries: Int = DatabaseConstants.MAX_LOG_ENTRIES): Long {
        val id = insertRaw(entity)
        pruneExcessLogs(maxEntries)
        return id
    }

    @Query("SELECT COUNT(*) FROM execution_logs")
    suspend fun countLogs(): Int

    @Query("DELETE FROM execution_logs")
    suspend fun clearAll()
}
