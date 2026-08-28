package dev.xspamfilter.lsposed.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import kotlinx.coroutines.runBlocking
import androidx.room.withTransaction

/**
 * Narrow bridge between the module app and the injected X process.
 * It intentionally exposes no general database access.
 */
class RuleBridgeProvider : ContentProvider() {
    private val database by lazy { AppDatabase.get(requireNotNull(context)) }
    private val dao by lazy { database.ruleDao() }
    private val repository by lazy { RuleRepository.get(requireNotNull(context)) }

    override fun onCreate(): Boolean {
        runBlocking { repository.ensureSeeded() }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        enforceAllowedCaller()
        require(MATCHER.match(uri) == SNAPSHOT) { "Unsupported query URI: $uri" }
        val cursor = MatrixCursor(SNAPSHOT_COLUMNS)
        runBlocking { database.withTransaction {
            val snapshot = dao.activeSnapshot()
                ?: error("No active, validated rule snapshot")
            dao.activeRules().forEach { rule ->
                val diagnosticUntil = requireNotNull(context)
                    .getSharedPreferences("rule_bridge", android.content.Context.MODE_PRIVATE)
                    .getLong(RuleRepository.KEY_DIAGNOSTIC_UNTIL, 0)
                cursor.addRow(arrayOf(
                    snapshot.version,
                    rule.id,
                    rule.sourceId,
                    rule.kind,
                    rule.pattern,
                    rule.priority,
                    diagnosticUntil,
                ))
            }
        } }
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        enforceTargetCaller()
        val safeValues = requireNotNull(values) { "Missing values" }
        when (MATCHER.match(uri)) {
            EVENTS -> insertEvent(safeValues)
            HEARTBEAT -> insertHeartbeat(safeValues)
            else -> error("Unsupported insert URI: $uri")
        }
        return uri
    }

    private fun insertEvent(values: ContentValues) = runBlocking {
        val action = values.getAsString("action")
        require(action == "BLOCK" || action == "ERROR" || action == "UNMATCHED") { "Unsupported event action" }
        dao.addEvent(
            MatchEventEntity(
                postId = values.getAsString("post_id")?.take(64),
                surface = values.getAsString("surface")?.take(40) ?: "timeline",
                ruleId = values.getAsLong("rule_id"),
                sourceId = values.getAsString("source_id")?.take(80),
                rulePattern = values.getAsString("rule_pattern")?.take(200),
                action = action,
                preview = values.getAsString("preview")?.take(120),
                error = values.getAsString("error")?.take(500),
            ),
        )
        dao.pruneEvents(System.currentTimeMillis() - EVENT_RETENTION_MS)
        dao.trimEvents()
    }

    private fun insertHeartbeat(values: ContentValues) = runBlocking {
        dao.putHeartbeat(
            HookHeartbeatEntity(
                lastSeenAt = System.currentTimeMillis(),
                targetVersion = values.getAsString("target_version")?.take(80),
                processName = values.getAsString("process")?.take(100) ?: "com.twitter.android",
                snapshotVersion = values.getAsLong("snapshot_version") ?: 0,
                status = values.getAsString("status")?.take(80) ?: "ACTIVE",
            ),
        )
    }

    private fun enforceAllowedCaller() {
        val callingUid = Binder.getCallingUid()
        if (callingUid == requireNotNull(context).applicationInfo.uid) return
        enforceTargetCaller()
    }

    private fun enforceTargetCaller() {
        val packages = requireNotNull(context).packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (TARGET_PACKAGE !in packages) {
            throw SecurityException("Rule bridge rejected caller UID ${Binder.getCallingUid()}")
        }
    }

    override fun getType(uri: Uri): String? = when (MATCHER.match(uri)) {
        SNAPSHOT -> "vnd.android.cursor.dir/vnd.xspamfilter.rule"
        EVENTS, HEARTBEAT -> "vnd.android.cursor.item/vnd.xspamfilter.event"
        else -> null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Delete is not exposed")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Update is not exposed")

    companion object {
        private const val AUTHORITY = HookBridgeContract.AUTHORITY
        private const val TARGET_PACKAGE = "com.twitter.android"
        private const val SNAPSHOT = 1
        private const val EVENTS = 2
        private const val HEARTBEAT = 3
        private const val EVENT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private val SNAPSHOT_COLUMNS = arrayOf("snapshot_version", "rule_id", "source_id", "kind", "pattern", "priority", "diagnostic_until")
        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "snapshot", SNAPSHOT)
            addURI(AUTHORITY, "events", EVENTS)
            addURI(AUTHORITY, "heartbeat", HEARTBEAT)
        }
    }
}
