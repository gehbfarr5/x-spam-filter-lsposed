package dev.xspamfilter.lsposed.data

object HookBridgeContract {
    const val MODULE_PACKAGE = "dev.xspamfilter.lsposed"
    const val TARGET_PACKAGE = "com.twitter.android"
    const val AUTHORITY = "dev.xspamfilter.lsposed.rules"
    const val SNAPSHOT_URI = "content://$AUTHORITY/snapshot"
    const val EVENTS_URI = "content://$AUTHORITY/events"
    const val HEARTBEAT_URI = "content://$AUTHORITY/heartbeat"
}
