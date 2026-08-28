package dev.xspamfilter.lsposed.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RuleKind { LITERAL, REGEX, ALL_OF }

@Entity(tableName = "rule_sources")
data class RuleSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val url: String? = null,
    val license: String,
    val enabled: Boolean = true,
    val updateMode: String = "MANUAL",
    val upstreamVersion: String? = null,
    val availableVersion: String? = null,
    val lastCheckedAt: Long? = null,
)

@Entity(
    tableName = "rules",
    foreignKeys = [ForeignKey(
        entity = RuleSourceEntity::class,
        parentColumns = ["id"],
        childColumns = ["sourceId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sourceId"), Index(value = ["sourceId", "kind", "pattern"], unique = true)],
)
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String,
    val kind: String,
    val pattern: String,
    val category: String = "常规",
    val enabled: Boolean = true,
    val priority: Int = 100,
)

@Entity(tableName = "rule_snapshots")
data class RuleSnapshotEntity(
    @PrimaryKey val version: Long,
    val createdAt: Long,
    val ruleCount: Int,
    val checksum: String,
    val reason: String,
    val active: Boolean,
)

@Entity(tableName = "snapshot_rules", primaryKeys = ["snapshotVersion", "ruleId"], indices = [Index("snapshotVersion")])
data class SnapshotRuleEntity(
    val snapshotVersion: Long,
    val ruleId: Long,
    val sourceId: String,
    val kind: String,
    val pattern: String,
    val category: String,
    val enabled: Boolean,
    val priority: Int,
    val sourceEnabled: Boolean = true,
)

@Entity(tableName = "match_events", indices = [Index("createdAt"), Index("ruleId")])
data class MatchEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val postId: String?,
    val surface: String,
    val ruleId: Long?,
    val sourceId: String?,
    val rulePattern: String?,
    val action: String,
    val preview: String?,
    val error: String? = null,
)

@Entity(tableName = "hook_heartbeat")
data class HookHeartbeatEntity(
    @PrimaryKey val id: Int = 1,
    val lastSeenAt: Long,
    val targetVersion: String?,
    val processName: String,
    val snapshotVersion: Long,
    val status: String,
)

data class RuleWithSource(
    val id: Long,
    val sourceId: String,
    val sourceName: String,
    val kind: String,
    val pattern: String,
    val category: String,
    val enabled: Boolean,
    val priority: Int,
    val sourceEnabled: Boolean = true,
)

data class DashboardState(
    val activeRuleCount: Int = 0,
    val snapshotVersion: Long = 0,
    val blockedToday: Int = 0,
    val heartbeat: HookHeartbeatEntity? = null,
    val diagnosticUntil: Long = 0,
)

data class RuleMatch(val rule: RuleWithSource, val matchedText: String)

data class SourceUpdateCandidate(
    val sourceId: String,
    val sourceName: String,
    val rules: List<RuleEntity>,
    val upstreamVersion: String,
    val invalidRules: List<String>,
    val previousCount: Int,
)

object RuleText {
    const val ALL_OF_SEPARATOR = "\u001F"

    fun normalize(value: String): String = buildString(value.length) {
        value.lowercase().forEach { char ->
            if (!char.isWhitespace() && !Character.isSpaceChar(char) && char.category != CharCategory.FORMAT) {
                append(char)
            }
        }
    }
}

object RuleEngine {
    fun validate(kind: RuleKind, pattern: String) {
        require(pattern.isNotBlank()) { "规则内容不能为空" }
        when (kind) {
            RuleKind.LITERAL -> require(RuleText.normalize(pattern).isNotEmpty()) { "关键词规范化后为空" }
            RuleKind.REGEX -> Regex(pattern)
            RuleKind.ALL_OF -> require(pattern.split(RuleText.ALL_OF_SEPARATOR).count { it.isNotBlank() } >= 2) {
                "组合规则至少需要两个片段"
            }
        }
    }

    fun firstMatch(text: String, rules: List<RuleWithSource>): RuleMatch? {
        val normalized = RuleText.normalize(text)
        return rules.asSequence().filter { it.enabled }.sortedBy { it.priority }.mapNotNull { rule ->
            val matched = when (RuleKind.valueOf(rule.kind)) {
                RuleKind.LITERAL -> RuleText.normalize(rule.pattern).takeIf(normalized::contains)
                RuleKind.REGEX -> Regex(rule.pattern, RegexOption.IGNORE_CASE).find(text)?.value
                RuleKind.ALL_OF -> rule.pattern.split(RuleText.ALL_OF_SEPARATOR)
                    .map(RuleText::normalize)
                    .takeIf { parts -> parts.all(normalized::contains) }
                    ?.joinToString(" + ")
            }
            matched?.let { RuleMatch(rule, it) }
        }.firstOrNull()
    }
}
