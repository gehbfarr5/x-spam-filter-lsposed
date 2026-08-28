package dev.xspamfilter.lsposed

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.xspamfilter.lsposed.data.DashboardState
import dev.xspamfilter.lsposed.data.SourceUpdateCandidate
import dev.xspamfilter.lsposed.data.CommunityUpdateWorker
import dev.xspamfilter.lsposed.data.MatchEventEntity
import dev.xspamfilter.lsposed.data.RuleKind
import dev.xspamfilter.lsposed.data.RuleSourceEntity
import dev.xspamfilter.lsposed.data.RuleText
import dev.xspamfilter.lsposed.data.RuleWithSource
import dev.xspamfilter.lsposed.ui.XSpamFilterTheme
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CommunityUpdateWorker.schedule(this)
        setContent { XSpamFilterTheme { XSpamFilterApp() } }
    }
}

private enum class Destination(val label: String, val icon: ImageVector) {
    OVERVIEW("概览", Icons.Default.Home),
    SOURCES("来源", Icons.Default.Settings),
    RULES("规则", Icons.AutoMirrored.Filled.List),
    LOGS("日志", Icons.AutoMirrored.Filled.List),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XSpamFilterApp(viewModel: MainViewModel = viewModel()) {
    var destination by remember { mutableStateOf(Destination.OVERVIEW) }
    var showAddRule by remember { mutableStateOf(false) }
    var showAddSource by remember { mutableStateOf(false) }
    var showTester by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsStateWithLifecycle()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importRules)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::exportRules)
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (expanded) {
                AppNavigationRail(destination) { destination = it }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(destination.label, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "X Spam Filter · 规则快照管理",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        actions = {
                            if (destination == Destination.RULES) {
                                IconButton(onClick = { showTester = true }) {
                                    Icon(Icons.Default.Search, contentDescription = "测试文本")
                                }
                                IconButton(onClick = { importer.launch(arrayOf("text/plain", "application/json")) }) {
                                    Icon(Icons.Default.Add, contentDescription = "导入规则文件")
                                }
                                IconButton(onClick = { exporter.launch("x-spam-filter-rules.json") }) {
                                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "导出自定义规则")
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    )
                },
                bottomBar = { if (!expanded) AppNavigationBar(destination) { destination = it } },
                floatingActionButton = {
                    if (destination == Destination.RULES) {
                        ExtendedFloatingActionButton(
                            onClick = { showAddRule = true },
                            icon = { Icon(Icons.Default.Add, null) },
                            text = { Text("新建规则") },
                        )
                    }
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
                contentWindowInsets = WindowInsets.safeDrawing,
            ) { padding ->
                when (destination) {
                    Destination.OVERVIEW -> OverviewScreen(viewModel, padding)
                    Destination.SOURCES -> SourcesScreen(viewModel, padding) { showAddSource = true }
                    Destination.RULES -> RulesScreen(viewModel, padding)
                    Destination.LOGS -> LogsScreen(viewModel, padding)
                }
            }
        }
    }

    if (showAddRule) AddRuleDialog(viewModel) { showAddRule = false }
    if (showAddSource) AddSourceDialog(viewModel) { showAddSource = false }
    if (showTester) RuleTesterDialog(viewModel) { showTester = false; viewModel.resetTest() }
    val sourceCandidate by viewModel.sourceCandidate.collectAsStateWithLifecycle()
    sourceCandidate?.let { SourceUpdateDialog(it, viewModel) }
}

@Composable
private fun AppNavigationBar(selected: Destination, select: (Destination) -> Unit) {
    NavigationBar {
        Destination.entries.forEach { item ->
            NavigationBarItem(
                selected = selected == item,
                onClick = { select(item) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label) },
            )
        }
    }
}

@Composable
private fun AppNavigationRail(selected: Destination, select: (Destination) -> Unit) {
    NavigationRail(Modifier.fillMaxHeight()) {
        Spacer(Modifier.height(72.dp))
        Destination.entries.forEach { item ->
            NavigationRailItem(
                selected = selected == item,
                onClick = { select(item) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label) },
            )
        }
    }
}

@Composable
private fun OverviewScreen(viewModel: MainViewModel, padding: PaddingValues) {
    val dashboard by viewModel.dashboard.collectAsStateWithLifecycle()
    val heartbeat by viewModel.heartbeat.collectAsStateWithLifecycle()
    val snapshots by viewModel.snapshots.collectAsStateWithLifecycle()
    LaunchedEffect(heartbeat) { viewModel.refreshDashboard() }
    val active = heartbeat != null &&
        heartbeat!!.status == "ACTIVE" &&
        heartbeat!!.snapshotVersion > 0 &&
        System.currentTimeMillis() - heartbeat!!.lastSeenAt < 5 * 60_000

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            StatusHero(active, dashboard.copy(heartbeat = heartbeat))
        }
        item {
            Text("运行数据", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("生效规则", dashboard.activeRuleCount.toString(), Modifier.weight(1f))
                MetricCard("今日拦截", dashboard.blockedToday.toString(), Modifier.weight(1f))
            }
        }
        item {
            val diagnosticActive = dashboard.diagnosticUntil > System.currentTimeMillis()
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("遗漏诊断", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (diagnosticActive) "正在记录未命中的帖子，至 ${formatTime(dashboard.diagnosticUntil)}"
                            else "临时记录未命中内容，15 分钟后自动结束",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(onClick = { viewModel.setDiagnosticEnabled(!diagnosticActive) }) {
                        Text(if (diagnosticActive) "停止" else "开启")
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("当前快照", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (dashboard.snapshotVersion == 0L) "正在初始化" else "#${dashboard.snapshotVersion}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "规则只有在格式校验和正则编译全部通过后才会切换到这个版本。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = viewModel::refreshDashboard) { Text("刷新状态") }
                }
            }
        }
        if (snapshots.size > 1) {
            item {
                Text("快照历史", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            items(snapshots.take(4), key = { it.version }) { snapshot ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(snapshot.reason, fontWeight = FontWeight.Medium)
                            Text("${snapshot.ruleCount} 条 · ${formatTime(snapshot.createdAt)}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (!snapshot.active) {
                            TextButton(onClick = { viewModel.rollbackTo(snapshot.version) }) { Text("回滚") }
                        } else {
                            AssistChip(onClick = {}, label = { Text("当前") })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusHero(active: Boolean, state: DashboardState) {
    val recentHeartbeat = state.heartbeat?.let { System.currentTimeMillis() - it.lastSeenAt < 5 * 60_000 } == true
    val container = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val content = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Card(colors = CardDefaults.cardColors(containerColor = container), shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (active) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = content,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        active -> "Hook 正在工作"
                        recentHeartbeat -> "Hook 已连接，但规则未就绪"
                        else -> "尚未收到 Hook 心跳"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = content,
                )
                Text(
                    when {
                        active -> "最近活动：${formatTime(state.heartbeat?.lastSeenAt)}"
                        recentHeartbeat -> "状态：${state.heartbeat?.status} · 快照 #${state.heartbeat?.snapshotVersion}"
                        else -> "打开 X 后返回此处刷新；若仍无心跳，请检查 LSPosed 作用域。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = content,
                )
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SourcesScreen(viewModel: MainViewModel, padding: PaddingValues, addSource: () -> Unit) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val checkingSourceId by viewModel.checkingSourceId.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "预置来源与自定义订阅彼此隔离。远端规则必须先检查差异、再确认激活，更新失败会保留当前快照。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                FilledTonalButton(onClick = addSource) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("订阅新来源")
                }
            }
        }
        items(sources, key = RuleSourceEntity::id) { source ->
            SourceCard(
                source,
                checking = checkingSourceId == source.id,
                onToggle = { viewModel.setSourceEnabled(source.id, it) },
                onCheck = { viewModel.checkSourceUpdate(source.id) },
                onRemove = { viewModel.removeSubscription(source.id) },
            )
        }
    }
}

@Composable
private fun SourceCard(
    source: RuleSourceEntity,
    checking: Boolean,
    onToggle: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(source.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(source.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = source.enabled,
                    onCheckedChange = onToggle,
                    enabled = source.url == null || source.upstreamVersion != null,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(source.license.take(18)) })
                AssistChip(onClick = {}, label = { Text(source.updateMode) })
            }
            source.url?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (source.url != null && source.lastCheckedAt == null) {
                Text("首次同步后需要确认差异才能启用", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
            }
            if (source.lastCheckedAt != null) {
                Text("上次检查：${formatTime(source.lastCheckedAt)}", style = MaterialTheme.typography.labelMedium)
            }
            if (source.availableVersion != null) {
                Text("发现可用更新：${source.availableVersion.take(18)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            if (source.url != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onCheck, enabled = !checking) {
                        Text(if (checking) "正在检查…" else "检查更新")
                    }
                    if (source.updateMode == "订阅" && source.id.startsWith("subscription-")) {
                        TextButton(onClick = onRemove) { Text("移除") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RulesScreen(viewModel: MainViewModel, padding: PaddingValues) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it; viewModel.search(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            label = { Text("搜索规则或来源") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
        )
        Text(
            "${rules.size} 条结果",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            contentPadding = PaddingValues(bottom = 96.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(rules, key = RuleWithSource::id) { rule ->
                RuleRow(rule, viewModel)
                HorizontalDivider(Modifier.padding(start = 72.dp))
            }
        }
    }
}

@Composable
private fun RuleRow(rule: RuleWithSource, viewModel: MainViewModel) {
    val display = rule.pattern.replace(RuleText.ALL_OF_SEPARATOR, " + ")
    ListItem(
        headlineContent = { Text(display, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${rule.sourceName} · ${kindLabel(rule.kind)} · ${rule.category}") },
        leadingContent = {
            Box(
                Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) { Text(kindBadge(rule.kind), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = rule.enabled, onCheckedChange = { viewModel.setRuleEnabled(rule.id, it) })
                if (rule.sourceId == "custom") {
                    IconButton(onClick = { viewModel.deleteCustomRule(rule.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除规则")
                    }
                }
            }
        },
    )
}

@Composable
private fun LogsScreen(viewModel: MainViewModel, padding: PaddingValues) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("最近 7 天", style = MaterialTheme.typography.titleMedium)
                Text("普通模式仅保存拦截和错误；诊断模式会临时记录未命中内容", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = viewModel::clearEvents, enabled = events.isNotEmpty()) { Text("清除") }
        }
        if (events.isEmpty()) {
            EmptyState("暂无拦截记录", "Hook 成功拦截内容后，规则和来源会显示在这里。")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(events, key = MatchEventEntity::id) { event ->
                    EventRow(event)
                    HorizontalDivider(Modifier.padding(start = 72.dp))
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: MatchEventEntity) {
    ListItem(
        headlineContent = {
            Text(when (event.action) {
                "BLOCK" -> "已拦截"
                "UNMATCHED" -> "诊断：未命中"
                else -> "过滤错误"
            })
        },
        supportingContent = {
            Column {
                Text(event.rulePattern ?: event.error ?: "未提供详情", maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${event.surface} · ${formatTime(event.createdAt)}", style = MaterialTheme.typography.labelMedium)
            }
        },
        leadingContent = {
            Icon(
                if (event.action == "BLOCK") Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = when (event.action) {
                    "BLOCK" -> MaterialTheme.colorScheme.primary
                    "UNMATCHED" -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                },
            )
        },
    )
}

@Composable
private fun EmptyState(title: String, body: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddRuleDialog(viewModel: MainViewModel, dismiss: () -> Unit) {
    var kind by remember { mutableStateOf(RuleKind.LITERAL) }
    var pattern by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("新建自定义规则") },
        text = {
            Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuleKind.entries.forEach { item ->
                        FilterChip(
                            selected = kind == item,
                            onClick = { kind = item },
                            label = { Text(kindLabel(item.name)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text(if (kind == RuleKind.ALL_OF) "用 + 分隔片段" else "规则内容") },
                    supportingText = {
                        Text(if (kind == RuleKind.REGEX) "保存前会验证正则表达式" else "空白和不可见格式字符会在匹配时忽略")
                    },
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.addRule(kind, pattern, dismiss) }, enabled = pattern.isNotBlank()) { Text("验证并生效") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } },
    )
}

@Composable
private fun RuleTesterDialog(viewModel: MainViewModel, dismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val match by viewModel.testMatch.collectAsStateWithLifecycle()
    val completed by viewModel.testCompleted.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("规则测试器") },
        text = {
            Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; viewModel.resetTest() },
                    label = { Text("粘贴帖子文本") },
                    minLines = 4,
                    maxLines = 8,
                )
                if (completed) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (match != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (match != null) "会被拦截" else "当前规则不会拦截", fontWeight = FontWeight.SemiBold)
                            match?.let {
                                Text("命中：${it.matchedText}")
                                Text("来源：${it.rule.sourceName} · ${kindLabel(it.rule.kind)}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { viewModel.test(text) }, enabled = text.isNotBlank()) { Text("开始匹配") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("关闭") } },
    )
}

@Composable
private fun AddSourceDialog(viewModel: MainViewModel, dismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var license by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("订阅新来源") },
        text = {
            Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "支持 HTTPS 纯文本地址；GitHub 文件页面会自动转换为 Raw 地址。每行一条关键词，也支持 /表达式/i 格式。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("来源名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("规则文件地址") },
                    supportingText = { Text("必须使用 HTTPS；单个文件上限 2 MB") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = license,
                    onValueChange = { license = it },
                    label = { Text("许可证（可选）") },
                    supportingText = { Text("例如 MIT、Apache-2.0；请确认来源允许使用") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { viewModel.subscribeSource(name, url, license, dismiss) },
                enabled = name.isNotBlank() && url.isNotBlank(),
            ) { Text("添加订阅") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } },
    )
}

@Composable
private fun SourceUpdateDialog(candidate: SourceUpdateCandidate, viewModel: MainViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissSourceCandidate,
        title = { Text("更新 ${candidate.sourceName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("候选版本：${candidate.upstreamVersion.take(18)}")
                Text("当前 ${candidate.previousCount} 条 → 候选 ${candidate.rules.size} 条")
                if (candidate.invalidRules.isNotEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("有 ${candidate.invalidRules.size} 条规则与 Android 正则不兼容，将明确排除：", fontWeight = FontWeight.SemiBold)
                            candidate.invalidRules.take(3).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                Text("确认后才会生成新快照；内置词库和我的规则不会被覆盖。", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { Button(onClick = viewModel::activateSourceUpdate) { Text("验证并激活") } },
        dismissButton = { TextButton(onClick = viewModel::dismissSourceCandidate) { Text("稍后") } },
    )
}

private fun kindLabel(kind: String): String = when (kind) {
    RuleKind.LITERAL.name -> "关键词"
    RuleKind.REGEX.name -> "正则"
    RuleKind.ALL_OF.name -> "组合"
    else -> kind
}

private fun kindBadge(kind: String): String = when (kind) {
    RuleKind.LITERAL.name -> "词"
    RuleKind.REGEX.name -> ".*"
    RuleKind.ALL_OF.name -> "+"
    else -> "?"
}

private fun formatTime(value: Long?): String = value?.let {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
} ?: "未知"
