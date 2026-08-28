package dev.xspamfilter.lsposed.data

object CommunityRuleParser {
    fun parse(
        text: String,
        version: String,
        previousCount: Int,
        minimumExpected: Int = 100,
    ): SourceUpdateCandidate {
        return SubscriptionRuleParser.parse(
            text = text,
            source = RuleSourceEntity(
                id = "community",
                name = "x-comment-blocker 常规词库",
                description = "测试解析来源",
                license = "MIT",
            ),
            version = version,
            previousCount = previousCount,
            minimumExpected = minimumExpected,
        )
    }
}
