package com.example.ui.viewmodel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppFeature
import com.example.data.AppLogEntity
import com.example.data.FeatureHealthEntity
import com.example.data.FeatureHealthStatus
import com.example.data.LogLevel
import com.example.data.SettingsDataStore
import com.example.logging.AppLogger
import com.example.logging.FeatureSelfTestRunner
import com.example.logging.RadioStreamCheckResult
import com.example.util.StorageHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FeatureHealthUiRow(
    val feature: AppFeature,
    val status: FeatureHealthStatus,
    val lastSuccessMillis: Long?,
    val lastErrorMessage: String?
)

enum class LogQuickFilter {
    ALL,
    ERRORS,
    WARNINGS
}

data class LogScreenState(
    val summaryLine: String = "0 working, 0 failed, 0 not supported",
    val workingCount: Int = 0,
    val fallbackCount: Int = 0,
    val failedCount: Int = 0,
    val notSupportedCount: Int = 0,
    val notTestedCount: Int = AppFeature.entries.size,
    val healthRows: List<FeatureHealthUiRow> = AppFeature.entries.map {
        FeatureHealthUiRow(it, FeatureHealthStatus.NOT_TESTED, null, null)
    },
    val radioStreams: List<RadioStreamCheckResult> = emptyList(),
    val testingStreamUrl: String? = null,
    val filteredLogs: List<AppLogEntity> = emptyList(),
    val totalLogCount: Int = 0,
    val availableFeatureTags: List<String> = emptyList(),
    val quickFilter: LogQuickFilter = LogQuickFilter.ALL,
    val selectedFeatureTag: String? = null,
    val searchQuery: String = "",
    val isRunningAllTests: Boolean = false,
    val testProgress: Float = 0f,
    val currentTestingLabel: String = "",
    val testingFeatureId: String? = null,
    val isRefreshing: Boolean = false
)

@HiltViewModel
class LogViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger,
    private val selfTestRunner: FeatureSelfTestRunner,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _quickFilter = MutableStateFlow(LogQuickFilter.ALL)
    private val _selectedFeatureTag = MutableStateFlow<String?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _isRunningAllTests = MutableStateFlow(false)
    private val _testProgress = MutableStateFlow(0f)
    private val _currentTestingLabel = MutableStateFlow("")
    private val _testingFeatureId = MutableStateFlow<String?>(null)
    private val _testingStreamUrl = MutableStateFlow<String?>(null)
    private val _isRefreshing = MutableStateFlow(false)

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val filterInputs = combine(
        _quickFilter,
        _selectedFeatureTag,
        _searchQuery
    ) { qf, tag, q -> Triple(qf, tag, q) }

    private val testRunnerInputs = combine(
        _isRunningAllTests,
        _testProgress,
        _currentTestingLabel,
        _testingFeatureId,
        _isRefreshing
    ) { runningAll, progress, label, singleId, refreshing ->
        TestProgressSnapshot(runningAll, progress, label, singleId, refreshing)
    }

    private val radioStreamInputs = combine(
        settingsDataStore.settingsFlow,
        selfTestRunner.radioStreamResults,
        _testingStreamUrl
    ) { settings, resultsMap, testingUrl ->
        val items = settings.spiritBoxStreamUrls.map { url ->
            resultsMap[url] ?: RadioStreamCheckResult(
                url = url,
                isReachable = null,
                httpCode = null,
                statusDetail = "Not tested"
            )
        }
        items to testingUrl
    }

    private data class TestProgressSnapshot(
        val isRunningAll: Boolean,
        val progress: Float,
        val label: String,
        val singleFeatureId: String?,
        val isRefreshing: Boolean
    )

    private var latestLogs: List<AppLogEntity> = emptyList()
    private var latestHealth: List<FeatureHealthEntity> = emptyList()

    val uiState: StateFlow<LogScreenState> = combine(
        appLogger.featureHealth,
        appLogger.logs,
        filterInputs,
        testRunnerInputs,
        radioStreamInputs
    ) { healthEntities, logEntities, (quickFilter, featureTag, query), testSnap, (radioStreams, testingStreamUrl) ->
        latestHealth = healthEntities
        latestLogs = logEntities

        val byId = healthEntities.associateBy { it.featureId }
        val rows = AppFeature.entries.map { feat ->
            val entity = byId[feat.id]
            FeatureHealthUiRow(
                feature = feat,
                status = entity?.status ?: FeatureHealthStatus.NOT_TESTED,
                lastSuccessMillis = entity?.lastSuccessMillis,
                lastErrorMessage = entity?.lastErrorMessage
            )
        }
        val working = rows.count { it.status == FeatureHealthStatus.WORKING }
        val fallback = rows.count { it.status == FeatureHealthStatus.FALLBACK }
        val failed = rows.count { it.status == FeatureHealthStatus.FAILED }
        val notSupported = rows.count { it.status == FeatureHealthStatus.NOT_SUPPORTED }
        val notTested = rows.count { it.status == FeatureHealthStatus.NOT_TESTED }

        val trimmedQuery = query.trim().lowercase()
        val filtered = logEntities.filter { entry ->
            val levelOk = when (quickFilter) {
                LogQuickFilter.ALL -> true
                LogQuickFilter.ERRORS -> entry.level == LogLevel.ERROR
                LogQuickFilter.WARNINGS -> entry.level == LogLevel.WARNING
            }
            val tagOk = featureTag == null || entry.featureTag.equals(featureTag, ignoreCase = true)
            val searchOk = trimmedQuery.isEmpty() ||
                entry.message.lowercase().contains(trimmedQuery) ||
                entry.featureTag.lowercase().contains(trimmedQuery) ||
                (entry.stackTrace?.lowercase()?.contains(trimmedQuery) == true)
            levelOk && tagOk && searchOk
        }

        val featureTags = logEntities.map { it.featureTag }.distinct().sorted()

        LogScreenState(
            summaryLine = appLogger.buildSummaryLine(healthEntities),
            workingCount = working,
            fallbackCount = fallback,
            failedCount = failed,
            notSupportedCount = notSupported,
            notTestedCount = notTested,
            healthRows = rows,
            radioStreams = radioStreams,
            testingStreamUrl = testingStreamUrl,
            filteredLogs = filtered,
            totalLogCount = logEntities.size,
            availableFeatureTags = featureTags,
            quickFilter = quickFilter,
            selectedFeatureTag = featureTag,
            searchQuery = query,
            isRunningAllTests = testSnap.isRunningAll,
            testProgress = testSnap.progress,
            currentTestingLabel = testSnap.label,
            testingFeatureId = testSnap.singleFeatureId,
            isRefreshing = testSnap.isRefreshing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LogScreenState()
    )

    init {
        appLogger.logDeviceCapabilitiesOnce()
    }

    fun setQuickFilter(filter: LogQuickFilter) {
        _quickFilter.value = filter
    }

    fun setFeatureTagFilter(tag: String?) {
        _selectedFeatureTag.value = if (_selectedFeatureTag.value == tag) null else tag
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            appLogger.forceTrim()
            delay(250L)
            _isRefreshing.value = false
        }
    }

    fun runAllTests() {
        if (_isRunningAllTests.value || _testingFeatureId.value != null) return
        viewModelScope.launch {
            _isRunningAllTests.value = true
            _testProgress.value = 0f
            _currentTestingLabel.value = "Starting…"
            try {
                selfTestRunner.runAllTests { progress, featureName ->
                    _testProgress.value = progress
                    _currentTestingLabel.value = featureName
                }
                _events.tryEmit("Self-test suite finished: ${uiState.value.summaryLine}")
            } catch (e: Exception) {
                appLogger.error("self_test", "Run all tests failed: ${e.message}", e)
                _events.tryEmit("Self-test error: ${e.message}")
            } finally {
                _isRunningAllTests.value = false
                _testProgress.value = 0f
                _currentTestingLabel.value = ""
            }
        }
    }

    fun runSingleTest(feature: AppFeature) {
        if (_isRunningAllTests.value || _testingFeatureId.value != null) return
        viewModelScope.launch {
            _testingFeatureId.value = feature.id
            try {
                selfTestRunner.runTestFor(feature)
            } catch (e: Exception) {
                appLogger.reportFailure(feature, "Test error: ${e.message}", e)
            } finally {
                _testingFeatureId.value = null
            }
        }
    }

    fun testSingleRadioStream(url: String) {
        if (_isRunningAllTests.value || _testingFeatureId.value != null || _testingStreamUrl.value != null) return
        viewModelScope.launch {
            _testingStreamUrl.value = url
            try {
                val res = selfTestRunner.testSingleRadioStream(url)
                val statusWord = if (res.isReachable == true) "Reachable" else "Unreachable"
                _events.tryEmit("$statusWord (${res.statusDetail}): $url")
            } catch (e: Exception) {
                appLogger.warn(AppFeature.SPIRIT_BOX_RADIO, "Stream test error ($url): ${e.message}")
                _events.tryEmit("Stream test error: ${e.message}")
            } finally {
                _testingStreamUrl.value = null
            }
        }
    }

    fun editRadioStream(oldUrl: String, newRawUrl: String) {
        val cleaned = newRawUrl.trim()
        if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
            _events.tryEmit("Stream URL must start with http:// or https://")
            return
        }
        viewModelScope.launch {
            val current = settingsDataStore.settingsFlow.first().spiritBoxStreamUrls
            val updated = current.map { if (it == oldUrl) cleaned else it }.distinct()
            settingsDataStore.setSpiritBoxStreamUrls(updated)
            selfTestRunner.pruneRemovedStreamResults(updated)
            appLogger.info(AppFeature.SPIRIT_BOX_RADIO, "Edited radio stream: $oldUrl -> $cleaned")
            _events.tryEmit("Updated stream URL. Testing reachability…")
            _testingStreamUrl.value = cleaned
            try {
                selfTestRunner.testSingleRadioStream(cleaned)
            } finally {
                _testingStreamUrl.value = null
            }
        }
    }

    fun removeRadioStream(url: String) {
        viewModelScope.launch {
            val current = settingsDataStore.settingsFlow.first().spiritBoxStreamUrls
            val updated = current.filterNot { it == url }
            settingsDataStore.setSpiritBoxStreamUrls(updated)
            selfTestRunner.pruneRemovedStreamResults(updated)
            appLogger.info(AppFeature.SPIRIT_BOX_RADIO, "Removed radio stream: $url")
            _events.tryEmit("Removed stream from Spirit Box list.")
        }
    }

    fun removeUnreachableStreams() {
        viewModelScope.launch {
            val results = selfTestRunner.radioStreamResults.value
            val current = settingsDataStore.settingsFlow.first().spiritBoxStreamUrls
            val unreachableSet = current.filter { results[it]?.isReachable == false }.toSet()
            if (unreachableSet.isEmpty()) {
                _events.tryEmit("No unreachable streams to remove.")
                return@launch
            }
            val updated = current.filterNot { it in unreachableSet }
            settingsDataStore.setSpiritBoxStreamUrls(updated)
            selfTestRunner.pruneRemovedStreamResults(updated)
            appLogger.info(
                AppFeature.SPIRIT_BOX_RADIO,
                "Removed ${unreachableSet.size} unreachable radio stream(s)"
            )
            _events.tryEmit("Removed ${unreachableSet.size} unreachable stream(s).")
        }
    }

    fun copyLogToClipboard() {
        viewModelScope.launch {
            runCatching {
                val text = appLogger.formatAsPlainText(latestLogs, latestHealth)
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("FaintRec Log", text))
                _events.tryEmit("Copied diagnostic report & ${latestLogs.size} log entries to clipboard.")
            }.onFailure { err ->
                _events.tryEmit("Copy failed: ${err.message}")
            }
        }
    }

    fun shareLogAsTxt() {
        viewModelScope.launch {
            runCatching {
                val file = appLogger.exportLogFile(asJson = false)
                StorageHelper.shareFile(context, file, "text/plain", "Share FaintRec Diagnostic Log")
                _events.tryEmit("Saved & sharing ${file.name}")
            }.onFailure { err ->
                appLogger.error("log_export", "Share .txt failed: ${err.message}", err)
                _events.tryEmit("Share log failed: ${err.message}")
            }
        }
    }

    fun exportLogAsJson() {
        viewModelScope.launch {
            runCatching {
                val file = appLogger.exportLogFile(asJson = true)
                StorageHelper.shareFile(context, file, "application/json", "Export FaintRec Log JSON")
                _events.tryEmit("Exported JSON to Documents/FaintRec/${file.name}")
            }.onFailure { err ->
                appLogger.error("log_export", "Export JSON failed: ${err.message}", err)
                _events.tryEmit("Export JSON failed: ${err.message}")
            }
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            appLogger.clearLogs()
            _events.tryEmit("Event log cleared.")
        }
    }
}
