package dev.xspamfilter.lsposed

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.xspamfilter.lsposed.data.DashboardState
import dev.xspamfilter.lsposed.data.SourceUpdateCandidate
import dev.xspamfilter.lsposed.data.RuleKind
import dev.xspamfilter.lsposed.data.RuleMatch
import dev.xspamfilter.lsposed.data.RuleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RuleRepository.get(application)
    private val query = MutableStateFlow("")

    val sources = repository.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val rules = query.flatMapLatest(repository::rules)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val events = repository.events.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val heartbeat = repository.heartbeat.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val snapshots = repository.snapshots.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _dashboard = MutableStateFlow(DashboardState())
    val dashboard: StateFlow<DashboardState> = _dashboard
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val _testMatch = MutableStateFlow<RuleMatch?>(null)
    val testMatch: StateFlow<RuleMatch?> = _testMatch
    private val _testCompleted = MutableStateFlow(false)
    val testCompleted: StateFlow<Boolean> = _testCompleted
    private val _sourceCandidate = MutableStateFlow<SourceUpdateCandidate?>(null)
    val sourceCandidate: StateFlow<SourceUpdateCandidate?> = _sourceCandidate
    private val _checkingSourceId = MutableStateFlow<String?>(null)
    val checkingSourceId: StateFlow<String?> = _checkingSourceId

    init {
        viewModelScope.launch {
            runCatching { repository.ensureSeeded() }
                .onFailure { _message.value = it.message ?: "初始化规则失败" }
            loadDashboard()
        }
        viewModelScope.launch {
            combine(repository.events, repository.heartbeat) { _, _ -> Unit }
                .collect { loadDashboard() }
        }
    }

    fun search(value: String) { query.value = value }
    fun consumeMessage() { _message.value = null }

    private suspend fun loadDashboard() {
        runCatching { repository.dashboard() }
            .onSuccess { _dashboard.value = it }
            .onFailure { _message.value = it.message ?: "读取状态失败" }
    }

    fun refreshDashboard() = viewModelScope.launch { loadDashboard() }

    fun addRule(kind: RuleKind, pattern: String, done: () -> Unit) = viewModelScope.launch {
        runCatching { repository.addRule(kind, pattern) }
            .onSuccess { _message.value = "规则已验证并生效"; refreshDashboard(); done() }
            .onFailure { _message.value = it.message ?: "规则无效" }
    }

    fun importRules(uri: Uri) = viewModelScope.launch {
        runCatching {
            val resolver = getApplication<Application>().contentResolver
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val data = input.readBytes()
                require(data.size <= 2 * 1024 * 1024) { "导入文件不能超过 2 MB" }
                data
            } ?: error("无法打开所选文件")
            repository.importDocument(bytes.toString(Charsets.UTF_8))
        }.onSuccess {
            _message.value = "已导入 $it 条规则"
            refreshDashboard()
        }.onFailure { _message.value = it.message ?: "导入失败" }
    }

    fun exportRules(uri: Uri) = viewModelScope.launch {
        runCatching {
            val resolver = getApplication<Application>().contentResolver
            val json = repository.exportCustomRules()
            resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(json) }
                ?: error("无法写入所选文件")
        }.onSuccess { _message.value = "自定义规则已导出" }
            .onFailure { _message.value = it.message ?: "导出失败" }
    }

    fun setRuleEnabled(id: Long, enabled: Boolean) = viewModelScope.launch {
        runCatching { repository.setRuleEnabled(id, enabled) }
            .onSuccess { refreshDashboard() }
            .onFailure { _message.value = it.message ?: "更新规则失败" }
    }

    fun deleteCustomRule(id: Long) = viewModelScope.launch {
        runCatching { repository.deleteCustomRule(id) }
            .onSuccess { _message.value = "自定义规则已删除"; refreshDashboard() }
            .onFailure { _message.value = it.message ?: "删除失败" }
    }

    fun setSourceEnabled(id: String, enabled: Boolean) = viewModelScope.launch {
        runCatching { repository.setSourceEnabled(id, enabled) }
            .onSuccess { refreshDashboard() }
            .onFailure { _message.value = it.message ?: "更新来源失败" }
    }

    fun subscribeSource(name: String, url: String, license: String, done: () -> Unit) = viewModelScope.launch {
        runCatching { repository.subscribeSource(name, url, license) }
            .onSuccess { _message.value = "订阅已添加，请先检查并确认规则"; done() }
            .onFailure { _message.value = it.message ?: "添加订阅失败" }
    }

    fun removeSubscription(id: String) = viewModelScope.launch {
        runCatching { repository.removeSubscription(id) }
            .onSuccess { _message.value = "订阅已移除"; refreshDashboard() }
            .onFailure { _message.value = it.message ?: "移除订阅失败" }
    }

    fun test(text: String) = viewModelScope.launch {
        _testMatch.value = repository.test(text)
        _testCompleted.value = true
    }

    fun resetTest() { _testMatch.value = null; _testCompleted.value = false }

    fun clearEvents() = viewModelScope.launch {
        repository.clearEvents()
        _message.value = "日志已清除"
        refreshDashboard()
    }

    fun setDiagnosticEnabled(enabled: Boolean) = viewModelScope.launch {
        runCatching { repository.setDiagnosticEnabled(enabled) }
            .onSuccess {
                _message.value = if (enabled) "遗漏诊断已开启 15 分钟" else "遗漏诊断已停止"
                refreshDashboard()
            }
            .onFailure { _message.value = it.message ?: "切换诊断模式失败" }
    }

    fun rollbackTo(version: Long) = viewModelScope.launch {
        runCatching { repository.rollbackTo(version) }
            .onSuccess { _message.value = "已生成回滚快照"; refreshDashboard() }
            .onFailure { _message.value = it.message ?: "回滚失败" }
    }

    fun checkSourceUpdate(sourceId: String) = viewModelScope.launch {
        _checkingSourceId.value = sourceId
        runCatching { repository.checkSourceUpdate(sourceId) }
            .onSuccess { _sourceCandidate.value = it }
            .onFailure { _message.value = it.message ?: "检查订阅失败" }
        _checkingSourceId.value = null
    }

    fun dismissSourceCandidate() { _sourceCandidate.value = null }

    fun activateSourceUpdate() = viewModelScope.launch {
        val candidate = _sourceCandidate.value ?: return@launch
        runCatching { repository.activateSourceUpdate(candidate) }
            .onSuccess {
                _message.value = "${candidate.sourceName} 已验证并激活"
                _sourceCandidate.value = null
                refreshDashboard()
            }
            .onFailure { _message.value = it.message ?: "激活订阅失败" }
    }
}
