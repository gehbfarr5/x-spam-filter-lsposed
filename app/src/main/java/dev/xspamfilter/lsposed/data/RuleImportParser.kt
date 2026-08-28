package dev.xspamfilter.lsposed.data

import org.json.JSONObject

data class ImportedRule(
    val kind: RuleKind,
    val pattern: String,
    val category: String = "自定义",
    val enabled: Boolean = true,
)

object RuleImportParser {
    fun parse(text: String): List<ImportedRule> {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "文件中没有可用规则" }
        val rules = if (trimmed.startsWith("{")) parseJson(trimmed) else parseText(trimmed)
        require(rules.isNotEmpty()) { "文件中没有可用规则" }
        require(rules.size <= 10_000) { "一次最多导入 10,000 条规则" }
        rules.forEach { RuleEngine.validate(it.kind, it.pattern) }
        return rules.distinctBy { Triple(it.kind, it.pattern, it.category) }
    }

    private fun parseText(text: String): List<ImportedRule> = text.lineSequence()
        .map(String::trim)
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { ImportedRule(RuleKind.LITERAL, it) }
        .toList()

    private fun parseJson(text: String): List<ImportedRule> {
        val root = JSONObject(text)
        require(root.getInt("version") == 1) { "不支持的规则文件版本" }
        val array = root.getJSONArray("rules")
        return buildList(array.length()) {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val kind = runCatching { RuleKind.valueOf(item.getString("type")) }
                    .getOrElse { throw IllegalArgumentException("第 ${index + 1} 条规则类型无效") }
                val rawPattern = item.optString("pattern", "")
                val pattern = if (kind == RuleKind.ALL_OF && item.has("parts")) {
                    val parts = item.getJSONArray("parts")
                    (0 until parts.length()).map(parts::getString).joinToString(RuleText.ALL_OF_SEPARATOR)
                } else {
                    rawPattern
                }
                add(ImportedRule(
                    kind = kind,
                    pattern = pattern,
                    category = item.optString("category", "自定义").take(60),
                    enabled = item.optBoolean("enabled", true),
                ))
            }
        }
    }
}
