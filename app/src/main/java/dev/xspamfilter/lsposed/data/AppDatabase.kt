package dev.xspamfilter.lsposed.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {
    @Query("SELECT COUNT(*) FROM rules")
    suspend fun countRules(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSources(sources: List<RuleSourceEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun addRules(rules: List<RuleEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun addRule(rule: RuleEntity): Long

    @Query("DELETE FROM rules WHERE id = :id AND sourceId = 'custom'")
    suspend fun deleteCustomRule(id: Long)

    @Query("DELETE FROM rules WHERE sourceId = :sourceId")
    suspend fun deleteRulesForSource(sourceId: String)

    @Query("DELETE FROM rules")
    suspend fun deleteAllRules()

    @Query("SELECT COUNT(*) FROM rules WHERE sourceId = :sourceId")
    suspend fun countRulesForSource(sourceId: String): Int

    @Query("SELECT * FROM rules WHERE sourceId = 'custom' ORDER BY id")
    suspend fun customRules(): List<RuleEntity>

    @Query("UPDATE rules SET enabled = :enabled WHERE id = :id")
    suspend fun setRuleEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE rule_sources SET enabled = :enabled WHERE id = :id")
    suspend fun setSourceEnabled(id: String, enabled: Boolean)

    @Query("SELECT * FROM rule_sources ORDER BY CASE id WHEN 'builtin' THEN 0 WHEN 'zpvip-live' THEN 1 WHEN 'community' THEN 2 WHEN 'custom' THEN 3 ELSE 4 END, name")
    fun observeSources(): Flow<List<RuleSourceEntity>>

    @Query("SELECT * FROM rule_sources ORDER BY name")
    suspend fun getSources(): List<RuleSourceEntity>

    @Query("SELECT * FROM rule_sources WHERE id = :id LIMIT 1")
    suspend fun getSource(id: String): RuleSourceEntity?

    @Query("DELETE FROM rule_sources WHERE id = :id")
    suspend fun deleteSource(id: String)

    @Query("""
        SELECT r.id, r.sourceId, s.name AS sourceName, r.kind, r.pattern, r.category, r.enabled, r.priority, s.enabled AS sourceEnabled
        FROM rules r JOIN rule_sources s ON s.id = r.sourceId
        WHERE (:query = '' OR r.pattern LIKE '%' || :query || '%' OR s.name LIKE '%' || :query || '%')
        ORDER BY r.enabled DESC, r.priority, r.id DESC
    """)
    fun observeRules(query: String): Flow<List<RuleWithSource>>

    @Query("""
        SELECT r.id, r.sourceId, s.name AS sourceName, r.kind, r.pattern, r.category, r.enabled, r.priority, s.enabled AS sourceEnabled
        FROM rules r JOIN rule_sources s ON s.id = r.sourceId
        WHERE r.enabled = 1 AND s.enabled = 1
        ORDER BY r.priority, r.id
    """)
    suspend fun activeRules(): List<RuleWithSource>

    @Query("""
        SELECT r.id, r.sourceId, s.name AS sourceName, r.kind, r.pattern, r.category, r.enabled, r.priority, s.enabled AS sourceEnabled
        FROM rules r JOIN rule_sources s ON s.id = r.sourceId
        ORDER BY r.priority, r.id
    """)
    suspend fun allRules(): List<RuleWithSource>

    @Query("SELECT COUNT(*) FROM rules r JOIN rule_sources s ON s.id = r.sourceId WHERE r.enabled = 1 AND s.enabled = 1")
    suspend fun activeRuleCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSnapshot(snapshot: RuleSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSnapshotRules(rules: List<SnapshotRuleEntity>)

    @Query("SELECT * FROM snapshot_rules WHERE snapshotVersion = :version ORDER BY priority, ruleId")
    suspend fun rulesForSnapshot(version: Long): List<SnapshotRuleEntity>

    @Query("SELECT * FROM rule_snapshots ORDER BY version DESC LIMIT 10")
    fun observeSnapshots(): Flow<List<RuleSnapshotEntity>>

    @Query("DELETE FROM snapshot_rules WHERE snapshotVersion NOT IN (SELECT version FROM rule_snapshots ORDER BY version DESC LIMIT 10)")
    suspend fun pruneSnapshotRules()

    @Query("DELETE FROM rule_snapshots WHERE version NOT IN (SELECT version FROM rule_snapshots ORDER BY version DESC LIMIT 10)")
    suspend fun pruneSnapshots()

    @Query("UPDATE rule_snapshots SET active = 0")
    suspend fun deactivateSnapshots()

    @Query("SELECT * FROM rule_snapshots WHERE active = 1 ORDER BY version DESC LIMIT 1")
    suspend fun activeSnapshot(): RuleSnapshotEntity?

    @Insert
    suspend fun addEvent(event: MatchEventEntity)

    @Query("SELECT * FROM match_events ORDER BY createdAt DESC LIMIT 500")
    fun observeEvents(): Flow<List<MatchEventEntity>>

    @Query("DELETE FROM match_events")
    suspend fun clearEvents()

    @Query("DELETE FROM match_events WHERE createdAt < :cutoff")
    suspend fun pruneEvents(cutoff: Long)

    @Query("DELETE FROM match_events WHERE id NOT IN (SELECT id FROM match_events ORDER BY createdAt DESC LIMIT 5000)")
    suspend fun trimEvents()

    @Query("SELECT COUNT(*) FROM match_events WHERE action = 'BLOCK' AND createdAt >= :start")
    suspend fun blockedSince(start: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putHeartbeat(heartbeat: HookHeartbeatEntity)

    @Query("SELECT * FROM hook_heartbeat WHERE id = 1")
    fun observeHeartbeat(): Flow<HookHeartbeatEntity?>

    @Query("SELECT * FROM hook_heartbeat WHERE id = 1")
    suspend fun heartbeat(): HookHeartbeatEntity?

    @Transaction
    suspend fun activateSnapshot(snapshot: RuleSnapshotEntity) {
        deactivateSnapshots()
        addSnapshot(snapshot)
    }

    @Transaction
    suspend fun pruneSnapshotHistory() {
        pruneSnapshotRules()
        pruneSnapshots()
    }

    @Transaction
    suspend fun replaceSourceRules(
        source: RuleSourceEntity,
        sourceId: String,
        rules: List<RuleEntity>,
    ) {
        deleteRulesForSource(sourceId)
        putSources(listOf(source))
        addRules(rules)
    }
}

@Database(
    entities = [RuleSourceEntity::class, RuleEntity::class, RuleSnapshotEntity::class, SnapshotRuleEntity::class, MatchEventEntity::class, HookHeartbeatEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "xspamfilter.db")
                .build()
                .also { instance = it }
        }
    }
}
