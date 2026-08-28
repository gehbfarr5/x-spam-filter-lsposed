package dev.xspamfilter.lsposed.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RuleEngineTest {
    @Test
    fun literal_ignoresSpacesCaseAndFormatCharacters() {
        val match = RuleEngine.firstMatch("加 微\u200B 信 领 红 包", listOf(rule(RuleKind.LITERAL, "加微信领红包")))
        assertNotNull(match)
    }

    @Test
    fun allOf_matchesTheReportedSpamPhrase() {
        val pattern = listOf("她", "骚", "好看").joinToString(RuleText.ALL_OF_SEPARATOR)
        val match = RuleEngine.firstMatch(
            "比她好看的没她骚比她骚的没她好看 @hh131wvu",
            listOf(rule(RuleKind.ALL_OF, pattern)),
        )
        assertEquals("她 + 骚 + 好看", match?.matchedText)
    }

    @Test
    fun regex_andNonMatch_areDeterministic() {
        assertNotNull(RuleEngine.firstMatch("vx：abc123", listOf(rule(RuleKind.REGEX, "v[x信][：:]?[a-z0-9]{4,}"))))
        assertNull(RuleEngine.firstMatch("普通的技术讨论", listOf(rule(RuleKind.LITERAL, "兼职赚钱"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRegex_failsBeforeActivation() {
        RuleEngine.validate(RuleKind.REGEX, "[")
    }

    @Test
    fun versionedJsonImport_preservesMixedRuleTypes() {
        val rules = RuleImportParser.parse(
            """{"version":1,"rules":[
              {"type":"LITERAL","pattern":"加微信"},
              {"type":"REGEX","pattern":"v[x信].{2,}"},
              {"type":"ALL_OF","pattern":"ignored","parts":["她","骚","好看"]}
            ]}""",
        )
        assertEquals(listOf(RuleKind.LITERAL, RuleKind.REGEX, RuleKind.ALL_OF), rules.map { it.kind })
    }

    @Test
    fun communityParser_keepsRegularCategoryAndReportsIncompatibleRegex() {
        val document = buildString {
            appendLine("# [常规屏蔽词]")
            repeat(100) { appendLine("测试词$it") }
            appendLine("比她骚")
            appendLine("/比.*她.*好看.*没.*她.*骚/i")
            appendLine("/^[\\p{Emoji}]+$/u")
            appendLine("# [仇恨用语]")
            appendLine("不应导入")
        }
        val candidate = CommunityRuleParser.parse(document, "test", 0)
        assertEquals(102, candidate.rules.size)
        assertEquals(1, candidate.invalidRules.size)
        assertNull(candidate.rules.firstOrNull { it.pattern == "不应导入" })
    }

    @Test
    fun subscriptionParser_supportsPlainTextCommentsAndRegex() {
        val source = RuleSourceEntity(
            id = "subscription-test",
            name = "测试订阅",
            description = "测试",
            license = "MIT",
        )
        val candidate = SubscriptionRuleParser.parse(
            text = """
                # comment
                加微信
                /v[x信][：:]?[a-z0-9]{4,}/i
                加微信
            """.trimIndent(),
            source = source,
            version = "v1",
            previousCount = 0,
        )
        assertEquals("subscription-test", candidate.sourceId)
        assertEquals(2, candidate.rules.size)
        assertEquals(listOf("LITERAL", "REGEX"), candidate.rules.map { it.kind })
    }

    private fun rule(kind: RuleKind, pattern: String) = RuleWithSource(
        id = 1,
        sourceId = "test",
        sourceName = "测试",
        kind = kind.name,
        pattern = pattern,
        category = "常规",
        enabled = true,
        priority = 1,
    )
}
