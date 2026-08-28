package dev.xspamfilter.lsposed.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class RuleRepository private constructor(private val context: Context) {
    private val database = AppDatabase.get(context)
    private val dao = database.ruleDao()
    private val seedMutex = Mutex()

    val sources: Flow<List<RuleSourceEntity>> = dao.observeSources()
    val events: Flow<List<MatchEventEntity>> = dao.observeEvents()
    val heartbeat: Flow<HookHeartbeatEntity?> = dao.observeHeartbeat()
    val snapshots: Flow<List<RuleSnapshotEntity>> = dao.observeSnapshots()

    fun rules(query: String): Flow<List<RuleWithSource>> = dao.observeRules(query)

    suspend fun ensureSeeded() = withContext(Dispatchers.IO) {
        seedMutex.withLock {
            ensureCatalogSources()
            if (dao.countRules() != 0) {
                publishActiveSnapshot()
                return@withLock
            }
            val rules = context.assets.open("keywords_zpvip.txt").bufferedReader().useLines { lines ->
                lines.map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                    .map { RuleEntity(sourceId = "builtin", kind = RuleKind.LITERAL.name, pattern = it) }
                    .toList()
            }
            rules.forEach { RuleEngine.validate(RuleKind.LITERAL, it.pattern) }
            dao.addRules(rules)
            createSnapshot("首次安装：载入内置基础词库")
        }
    }

    suspend fun dashboard(): DashboardState = withContext(Dispatchers.IO) {
        val dayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        DashboardState(
            activeRuleCount = dao.activeRuleCount(),
            snapshotVersion = dao.activeSnapshot()?.version ?: 0,
            blockedToday = dao.blockedSince(dayStart),
            heartbeat = dao.heartbeat(),
            diagnosticUntil = preferences().getLong(KEY_DIAGNOSTIC_UNTIL, 0),
        )
    }

    suspend fun addRule(kind: RuleKind, rawPattern: String) = withContext(Dispatchers.IO) {
        val pattern = when (kind) {
            RuleKind.ALL_OF -> rawPattern.split('+').map(String::trim).filter(String::isNotBlank)
                .joinToString(RuleText.ALL_OF_SEPARATOR)
            else -> rawPattern.trim()
        }
        RuleEngine.validate(kind, pattern)
        dao.addRule(RuleEntity(sourceId = "custom", kind = kind.name, pattern = pattern, priority = 50))
        createSnapshot("新增自定义规则")
    }

    suspend fun importDocument(text: String): Int = withContext(Dispatchers.IO) {
        val entities = RuleImportParser.parse(text).map {
            RuleEntity(
                sourceId = "custom",
                kind = it.kind.name,
                pattern = it.pattern,
                category = it.category,
                enabled = it.enabled,
                priority = 50,
            )
        }
        dao.addRules(entities)
        createSnapshot("导入 ${entities.size} 条本地规则")
        entities.size
    }

    suspend fun test(text: String): RuleMatch? = withContext(Dispatchers.Default) {
        RuleEngine.firstMatch(text, dao.activeRules())
    }

    suspend fun setRuleEnabled(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        dao.setRuleEnabled(id, enabled)
        createSnapshot(if (enabled) "启用规则" else "停用规则")
    }

    suspend fun deleteCustomRule(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteCustomRule(id)
        createSnapshot("删除自定义规则")
    }

    suspend fun setSourceEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val source = requireNotNull(dao.getSource(id)) { "规则来源不存在" }
        require(!enabled || source.url == null || source.upstreamVersion != null) { "请先检查并激活该订阅" }
        dao.setSourceEnabled(id, enabled)
        createSnapshot(if (enabled) "启用规则来源" else "停用规则来源")
    }

    suspend fun subscribeSource(name: String, rawUrl: String, license: String) = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        require(cleanName.length in 2..60) { "来源名称需要 2–60 个字符" }
        val url = normalizeSubscriptionUrl(rawUrl)
        val idHash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        val id = "subscription-$idHash"
        require(dao.getSource(id) == null) { "这个订阅地址已经存在" }
        dao.putSources(listOf(RuleSourceEntity(
            id = id,
            name = cleanName,
            description = "用户订阅的远端规则；检查差异并确认后生效",
            url = url,
            license = license.trim().ifBlank { "用户提供" }.take(40),
            enabled = false,
            updateMode = "订阅",
        )))
    }

    suspend fun removeSubscription(id: String) = withContext(Dispatchers.IO) {
        val source = requireNotNull(dao.getSource(id)) { "规则来源不存在" }
        require(source.updateMode == "订阅") { "只能移除用户添加的订阅" }
        database.withTransaction { dao.deleteSource(id) }
        createSnapshot("移除订阅 ${source.name}")
    }

    suspend fun clearEvents() = withContext(Dispatchers.IO) { dao.clearEvents() }

    suspend fun exportCustomRules(): String = withContext(Dispatchers.IO) {
        val array = JSONArray()
        dao.customRules().forEach { rule ->
            val item = JSONObject()
                .put("type", rule.kind)
                .put("category", rule.category)
                .put("enabled", rule.enabled)
            if (rule.kind == RuleKind.ALL_OF.name) {
                item.put("parts", JSONArray(rule.pattern.split(RuleText.ALL_OF_SEPARATOR)))
            } else {
                item.put("pattern", rule.pattern)
            }
            array.put(item)
        }
        JSONObject()
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("rules", array)
            .toString(2)
    }

    suspend fun setDiagnosticEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        val until = if (enabled) System.currentTimeMillis() + 15 * 60_000 else 0
        check(preferences().edit().putLong(KEY_DIAGNOSTIC_UNTIL, until).commit()) {
            "无法保存诊断模式状态"
        }
        publishActiveSnapshot()
    }

    suspend fun rollbackTo(versionToRestore: Long) = withContext(Dispatchers.IO) {
        val saved = dao.rulesForSnapshot(versionToRestore)
        require(saved.isNotEmpty()) { "所选快照没有可恢复规则" }
        val restored = saved.map {
            RuleEntity(it.ruleId, it.sourceId, it.kind, it.pattern, it.category, it.enabled, it.priority)
        }
        restored.forEach { RuleEngine.validate(RuleKind.valueOf(it.kind), it.pattern) }
        database.withTransaction {
            val sources = dao.getSources().associateBy { it.id }
            saved.groupBy { it.sourceId }.forEach { (sourceId, rules) ->
                sources[sourceId]?.let { dao.putSources(listOf(it.copy(enabled = rules.first().sourceEnabled))) }
            }
            dao.deleteAllRules()
            dao.addRules(restored)
            val current = dao.activeRules()
            val allCurrent = dao.allRules()
            val version = nextVersion()
            val checksum = checksum(current)
            dao.activateSnapshot(RuleSnapshotEntity(version, version, current.size, checksum, "回滚自 #$versionToRestore", true))
            dao.addSnapshotRules(allCurrent.map { it.toSnapshotRule(version) })
            dao.pruneSnapshotHistory()
        }
        publishActiveSnapshot()
    }

    suspend fun checkSourceUpdate(sourceId: String): SourceUpdateCandidate = withContext(Dispatchers.IO) {
        val source = requireNotNull(dao.getSource(sourceId)) { "规则来源不存在" }
        val sourceUrl = requireNotNull(source.url) { "这个来源不支持远端更新" }
        val validatedUrl = normalizeSubscriptionUrl(sourceUrl)
        val connection = (URL(validatedUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "text/plain")
            setRequestProperty("User-Agent", "XSpamFilter/${dev.xspamfilter.lsposed.BuildConfig.VERSION_NAME}")
        }
        try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "${source.name} 请求失败：HTTP ${connection.responseCode}"
            }
            normalizeSubscriptionUrl(connection.url.toString())
            require(!connection.contentType.orEmpty().contains("text/html", ignoreCase = true)) {
                "远端返回了网页，不是规则文本"
            }
            val declaredLength = connection.contentLengthLong
            require(declaredLength < 0 || declaredLength <= MAX_SOURCE_BYTES) { "订阅内容超过 2 MB 上限" }
            val bytes = connection.inputStream.use { input ->
                val result = input.readBytes()
                require(result.size <= MAX_SOURCE_BYTES) { "订阅内容超过 2 MB 上限" }
                result
            }
            val version = connection.getHeaderField("ETag")?.trim('"')
                ?: MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
            SubscriptionRuleParser.parse(
                text = bytes.toString(Charsets.UTF_8),
                source = source,
                version = version,
                previousCount = dao.countRulesForSource(source.id),
                minimumExpected = if (source.id == "community") 100 else 1,
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend fun activateSourceUpdate(candidate: SourceUpdateCandidate) = withContext(Dispatchers.IO) {
        require(candidate.rules.isNotEmpty()) { "候选词库没有可激活规则" }
        candidate.rules.forEach { RuleEngine.validate(RuleKind.valueOf(it.kind), it.pattern) }
        val source = requireNotNull(dao.getSource(candidate.sourceId)) { "规则来源不存在" }.copy(
            enabled = true,
            upstreamVersion = candidate.upstreamVersion,
            availableVersion = null,
            lastCheckedAt = System.currentTimeMillis(),
        )
        database.withTransaction {
            dao.deleteRulesForSource(candidate.sourceId)
            dao.putSources(listOf(source))
            dao.addRules(candidate.rules)
            val rules = dao.activeRules()
            val allRules = dao.allRules()
            val canonical = rules.joinToString("\n") { "${it.id}|${it.kind}|${it.pattern}" }
            val checksum = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val version = nextVersion()
            dao.activateSnapshot(
                RuleSnapshotEntity(
                    version = version,
                    createdAt = version,
                    ruleCount = rules.size,
                    checksum = checksum,
                    reason = "激活 ${candidate.sourceName} ${candidate.upstreamVersion}",
                    active = true,
                ),
            )
            dao.addSnapshotRules(allRules.map { it.toSnapshotRule(version) })
            dao.pruneSnapshotHistory()
        }
        publishActiveSnapshot()
    }

    suspend fun recordSourceCheck(candidate: SourceUpdateCandidate) = withContext(Dispatchers.IO) {
        val source = requireNotNull(dao.getSource(candidate.sourceId)) { "规则来源不存在" }
        dao.putSources(listOf(source.copy(
            availableVersion = candidate.upstreamVersion.takeIf { it != source.upstreamVersion },
            lastCheckedAt = System.currentTimeMillis(),
        )))
    }

    suspend fun checkAllRemoteSources() = withContext(Dispatchers.IO) {
        dao.getSources().filter { it.url != null }.forEach { source ->
            recordSourceCheck(checkSourceUpdate(source.id))
        }
    }

    private suspend fun createSnapshot(reason: String) {
        val rules = dao.activeRules()
        val allRules = dao.allRules()
        rules.forEach { RuleEngine.validate(RuleKind.valueOf(it.kind), it.pattern) }
        val checksum = checksum(rules)
        val version = nextVersion()
        database.withTransaction {
            dao.activateSnapshot(RuleSnapshotEntity(version, version, rules.size, checksum, reason, true))
            dao.addSnapshotRules(allRules.map { it.toSnapshotRule(version) })
            dao.pruneSnapshotHistory()
        }
        publishActiveSnapshot()
    }

    suspend fun publishActiveSnapshot() = withContext(Dispatchers.IO) {
        val snapshot = dao.activeSnapshot() ?: error("没有可发布的活动规则快照")
        HookRuleSnapshotStore.publish(
            context = context,
            version = snapshot.version,
            rules = dao.activeRules(),
            diagnosticUntil = preferences().getLong(KEY_DIAGNOSTIC_UNTIL, 0),
        )
    }

    suspend fun recordBridgeEvent(
        action: String?,
        postId: String?,
        surface: String?,
        ruleId: Long?,
        sourceId: String?,
        rulePattern: String?,
        preview: String?,
        error: String?,
    ) = withContext(Dispatchers.IO) {
        require(action == "BLOCK" || action == "ERROR" || action == "UNMATCHED") {
            "不支持的 Hook 事件类型"
        }
        dao.addEvent(
            MatchEventEntity(
                postId = postId?.take(64),
                surface = surface?.take(40) ?: "timeline",
                ruleId = ruleId,
                sourceId = sourceId?.take(80),
                rulePattern = rulePattern?.take(200),
                action = requireNotNull(action),
                preview = preview?.take(120),
                error = error?.take(500),
            ),
        )
        dao.pruneEvents(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
        dao.trimEvents()
    }

    suspend fun recordBridgeHeartbeat(
        targetVersion: String?,
        processName: String?,
        snapshotVersion: Long,
        status: String?,
    ) = withContext(Dispatchers.IO) {
        dao.putHeartbeat(
            HookHeartbeatEntity(
                lastSeenAt = System.currentTimeMillis(),
                targetVersion = targetVersion?.take(80),
                processName = processName?.take(100) ?: HookBridgeContract.TARGET_PACKAGE,
                snapshotVersion = snapshotVersion,
                status = status?.take(80) ?: "ACTIVE",
            ),
        )
    }

    private fun checksum(rules: List<RuleWithSource>): String {
        val canonical = rules.joinToString("\n") { "${it.id}|${it.kind}|${it.pattern}" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun nextVersion(): Long = maxOf(
        System.currentTimeMillis(),
        (dao.activeSnapshot()?.version ?: 0L) + 1L,
    )

    private fun RuleWithSource.toSnapshotRule(version: Long) = SnapshotRuleEntity(
        snapshotVersion = version,
        ruleId = id,
        sourceId = sourceId,
        kind = kind,
        pattern = pattern,
        category = category,
        enabled = enabled,
        priority = priority,
        sourceEnabled = sourceEnabled,
    )

    private fun preferences() = context.getSharedPreferences("rule_bridge", Context.MODE_PRIVATE)

    private suspend fun ensureCatalogSources() {
        val existing = dao.getSources().associateBy { it.id }
        val catalog = listOf(
            RuleSourceEntity(
                id = "builtin",
                name = "ZPVIP 内置快照",
                description = "随应用发布的 184 条基础规则，固定版本且可离线使用",
                license = "Apache-2.0",
                updateMode = "内置",
                upstreamVersion = "9cf7cc1",
            ),
            RuleSourceEntity(
                id = "zpvip-live",
                name = "ZPVIP 在线词库",
                description = "跟踪 ZPVIP/x-spam-filter 的最新 keywords.txt",
                url = ZPVIP_RULES_URL,
                license = "Apache-2.0",
                enabled = false,
                updateMode = "订阅",
            ),
            RuleSourceEntity(
                id = "community",
                name = "x-comment-blocker 常规词库",
                description = "活跃社区维护；只导入“常规屏蔽词”分区",
                url = COMMUNITY_RULES_URL,
                license = "MIT",
                enabled = false,
                updateMode = "订阅",
            ),
            RuleSourceEntity(
                id = "custom",
                name = "我的规则",
                description = "本地创建或导入，不会被上游更新覆盖",
                license = "本地",
                updateMode = "本地",
            ),
        )
        dao.putSources(catalog.map { preset ->
            existing[preset.id]?.let { saved ->
                preset.copy(
                    enabled = saved.enabled,
                    upstreamVersion = saved.upstreamVersion ?: preset.upstreamVersion,
                    availableVersion = saved.availableVersion,
                    lastCheckedAt = saved.lastCheckedAt,
                )
            } ?: preset
        })
    }

    private fun normalizeSubscriptionUrl(raw: String): String {
        var candidate = raw.trim()
        val githubBlob = Regex("^https://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$").matchEntire(candidate)
        if (githubBlob != null) {
            val (owner, repo, branch, path) = githubBlob.destructured
            candidate = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
        }
        val uri = runCatching { URI(candidate) }.getOrElse { throw IllegalArgumentException("订阅地址无效") }
        require(uri.scheme.equals("https", ignoreCase = true)) { "订阅地址必须使用 HTTPS" }
        require(uri.userInfo == null && uri.fragment == null) { "订阅地址不能包含账号信息或片段" }
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("订阅地址缺少主机名")
        require(host != "localhost" && !host.endsWith(".local") && ':' !in host && !host.matches(Regex("^\\d+(\\.\\d+){3}$"))) {
            "订阅地址不能指向本机或局域网地址"
        }
        return uri.toASCIIString()
    }

    companion object {
        private const val MAX_SOURCE_BYTES = 2 * 1024 * 1024
        private const val ZPVIP_RULES_URL =
            "https://raw.githubusercontent.com/ZPVIP/x-spam-filter/main/keywords.txt"
        private const val COMMUNITY_RULES_URL =
            "https://raw.githubusercontent.com/amahteru/x-comment-blocker/main/keywords.txt"
        const val KEY_DIAGNOSTIC_UNTIL = "diagnostic_until"
        @Volatile private var instance: RuleRepository? = null
        fun get(context: Context): RuleRepository = instance ?: synchronized(this) {
            instance ?: RuleRepository(context.applicationContext).also { instance = it }
        }
    }
}
