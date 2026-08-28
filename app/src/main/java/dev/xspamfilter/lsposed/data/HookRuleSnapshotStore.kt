package dev.xspamfilter.lsposed.data

import android.content.Context
import android.net.Uri

object HookRuleSnapshotStore {
    fun publish(
        context: Context,
        version: Long,
        rules: List<RuleWithSource>,
        diagnosticUntil: Long,
    ) {
        require(version > 0) { "规则快照版本无效" }
        require(rules.isNotEmpty()) { "拒绝发布空规则快照" }
        require(diagnosticUntil >= 0) { "诊断截止时间无效" }
        context.contentResolver.notifyChange(Uri.parse(HookBridgeContract.SNAPSHOT_URI), null)
    }
}
