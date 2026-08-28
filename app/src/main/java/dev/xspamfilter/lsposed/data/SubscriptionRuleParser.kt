package dev.xspamfilter.lsposed.data

object SubscriptionRuleParser {
    fun parse(
        text: String,
        source: RuleSourceEntity,
        version: String,
        previousCount: Int,
        minimumExpected: Int = 1,
    ): SourceUpdateCandidate {
        val lines = text.lineSequence().toList()
        val hasRegularSection = lines.any { it.trim().startsWith("#") && it.contains("常规屏蔽词") }
        val valid = ArrayList<RuleEntity>()
        val invalid = ArrayList<String>()
        var inRegularSection = !hasRegularSection
        lines.forEachIndexed { index, raw ->
            val line = raw.replace("\u200B", "").trim()
            if (line.startsWith("#")) {
                if (hasRegularSection) inRegularSection = line.contains("常规屏蔽词")
                return@forEachIndexed
            }
            if (!inRegularSection || line.isBlank()) return@forEachIndexed
            try {
                val (kind, pattern) = parseLine(line)
                RuleEngine.validate(kind, pattern)
                valid += RuleEntity(
                    sourceId = source.id,
                    kind = kind.name,
                    pattern = pattern,
                    category = "订阅",
                    priority = 80,
                )
            } catch (failure: RuntimeException) {
                invalid += "第 ${index + 1} 行：${failure.message ?: line.take(80)}"
            }
        }
        val distinct = valid.distinctBy { Triple(it.kind, RuleText.normalize(it.pattern), it.category) }
        require(distinct.size >= minimumExpected) {
            "${source.name} 格式异常：仅解析到 ${distinct.size} 条有效规则"
        }
        return SourceUpdateCandidate(
            sourceId = source.id,
            sourceName = source.name,
            rules = distinct,
            upstreamVersion = version,
            invalidRules = invalid,
            previousCount = previousCount,
        )
    }

    private fun parseLine(line: String): Pair<RuleKind, String> {
        if (!line.startsWith('/')) return RuleKind.LITERAL to line
        val lastSlash = line.lastIndexOf('/')
        require(lastSlash > 0) { "正则缺少结束分隔符" }
        var pattern = line.substring(1, lastSlash)
        val flags = line.substring(lastSlash + 1)
        require(flags.all { it in "imsu" }) { "不支持的正则标志：$flags" }
        val inlineFlags = buildString {
            if ('i' in flags) append('i')
            if ('m' in flags) append('m')
            if ('s' in flags) append('s')
        }
        if (inlineFlags.isNotEmpty()) pattern = "(?$inlineFlags)$pattern"
        return RuleKind.REGEX to pattern
    }
}
