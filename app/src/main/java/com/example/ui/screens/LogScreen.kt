package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotInterested
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.AppFeature
import com.example.data.AppLogEntity
import com.example.data.FeatureHealthStatus
import com.example.data.LogLevel
import com.example.ui.viewmodel.FeatureHealthUiRow
import com.example.ui.viewmodel.LogQuickFilter
import com.example.ui.viewmodel.LogViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    onShowSnackbar: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LogViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var dashboardExpanded by remember { mutableStateOf(true) }
    var selectedLogEntry by remember { mutableStateOf<AppLogEntity?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val fullDateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { msg -> onShowSnackbar(msg) }
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = { viewModel.refresh() },
        modifier = modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("log_screen_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1) Top Report Summary & Actions Card
            item(key = "report_summary") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.FactCheck,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = stringResource(R.string.log_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "${state.totalLogCount}/2000",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Report summary line ("X working, Y failed, Z not supported")
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("log_report_summary")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.log_report_label) + ": " + state.summaryLine,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StatusCountDot(MaterialTheme.colorScheme.tertiary, "${state.workingCount} OK")
                                    StatusCountDot(MaterialTheme.colorScheme.error, "${state.failedCount} Fail")
                                    StatusCountDot(MaterialTheme.colorScheme.outline, "${state.notSupportedCount} N/A")
                                    StatusCountDot(MaterialTheme.colorScheme.secondary, "${state.notTestedCount} Untested")
                                }
                            }
                        }

                        // Run all tests button + live progress bar
                        Button(
                            onClick = { viewModel.runAllTests() },
                            enabled = !state.isRunningAllTests && state.testingFeatureId == null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("run_all_tests_button")
                        ) {
                            if (state.isRunningAllTests) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Testing: ${state.currentTestingLabel}")
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.log_run_all_tests))
                            }
                        }

                        if (state.isRunningAllTests) {
                            LinearProgressIndicator(
                                progress = { state.testProgress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                        }

                        // Action Buttons Row: Copy, Share .txt, Export JSON, Clear
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { viewModel.copyLogToClipboard() },
                                modifier = Modifier.testTag("log_copy_button")
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.log_action_copy))
                            }
                            FilledTonalButton(
                                onClick = { viewModel.shareLogAsTxt() },
                                modifier = Modifier.testTag("log_share_txt_button")
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.log_action_share_txt))
                            }
                            FilledTonalButton(
                                onClick = { viewModel.exportLogAsJson() },
                                modifier = Modifier.testTag("log_export_json_button")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.log_action_export_json))
                            }
                            OutlinedButton(
                                onClick = { showClearConfirm = true },
                                modifier = Modifier.testTag("log_clear_button")
                            ) {
                                Icon(
                                    Icons.Default.DeleteSweep,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.log_action_clear),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // 2) Feature Status Dashboard Header
            item(key = "dashboard_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { dashboardExpanded = !dashboardExpanded }
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.log_feature_dashboard) + " (${state.healthRows.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = if (dashboardExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (dashboardExpanded) "Collapse dashboard" else "Expand dashboard"
                    )
                }
            }

            // Feature Status Dashboard Items
            if (dashboardExpanded) {
                items(
                    items = state.healthRows,
                    key = { "feat_${it.feature.id}" }
                ) { row ->
                    FeatureHealthRowCard(
                        row = row,
                        timeFmt = timeFmt,
                        isTestingThis = state.testingFeatureId == row.feature.id,
                        anyTestRunning = state.isRunningAllTests || state.testingFeatureId != null,
                        onRunTest = { viewModel.runSingleTest(row.feature) }
                    )
                }
            }

            // 3) Event Log Header + Search + Filters
            item(key = "event_log_controls") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = stringResource(R.string.log_event_log_title) + " (${state.filteredLogs.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("log_search_input"),
                        placeholder = { Text(stringResource(R.string.log_search_hint)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (state.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true
                    )

                    // Level filter chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = state.quickFilter == LogQuickFilter.ALL,
                            onClick = { viewModel.setQuickFilter(LogQuickFilter.ALL) },
                            label = { Text(stringResource(R.string.log_filter_all)) },
                            modifier = Modifier.testTag("log_filter_all")
                        )
                        FilterChip(
                            selected = state.quickFilter == LogQuickFilter.ERRORS,
                            onClick = { viewModel.setQuickFilter(LogQuickFilter.ERRORS) },
                            label = { Text(stringResource(R.string.log_filter_errors)) },
                            modifier = Modifier.testTag("log_filter_errors")
                        )
                        FilterChip(
                            selected = state.quickFilter == LogQuickFilter.WARNINGS,
                            onClick = { viewModel.setQuickFilter(LogQuickFilter.WARNINGS) },
                            label = { Text(stringResource(R.string.log_filter_warnings)) },
                            modifier = Modifier.testTag("log_filter_warnings")
                        )
                        if (state.selectedFeatureTag != null) {
                            FilterChip(
                                selected = true,
                                onClick = { viewModel.setFeatureTagFilter(null) },
                                label = { Text("Tag: ${state.selectedFeatureTag} ✕") }
                            )
                        }
                    }

                    // Per-feature tag chips
                    if (state.availableFeatureTags.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            state.availableFeatureTags.forEach { tag ->
                                val featLabel = AppFeature.fromId(tag)?.displayName ?: tag
                                FilterChip(
                                    selected = state.selectedFeatureTag == tag,
                                    onClick = { viewModel.setFeatureTagFilter(tag) },
                                    label = { Text(featLabel, maxLines = 1) }
                                )
                            }
                        }
                    }
                }
            }

            // Event Log Entries
            if (state.filteredLogs.isEmpty()) {
                item(key = "empty_logs") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.log_empty_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(
                    items = state.filteredLogs,
                    key = { "log_${it.id}" }
                ) { entry ->
                    LogEntryCard(
                        entry = entry,
                        timeFmt = timeFmt,
                        onClick = { selectedLogEntry = entry }
                    )
                }
            }
        }
    }

    // Full log entry details dialog
    selectedLogEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { selectedLogEntry = null },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LevelBadge(entry.level)
                    Text(
                        text = AppFeature.fromId(entry.featureTag)?.displayName ?: entry.featureTag,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = fullDateFmt.format(Date(entry.timestampMillis)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = entry.message,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (!entry.stackTrace.isNullOrBlank()) {
                        HorizontalDivider()
                        Text(
                            text = "Stack Trace",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLowest
                        ) {
                            Text(
                                text = entry.stackTrace,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedLogEntry = null }) {
                    Text("Close")
                }
            }
        )
    }

    // Clear confirmation dialog
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.log_clear_confirm_title)) },
            text = { Text(stringResource(R.string.log_clear_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearLogs()
                    }
                ) {
                    Text(stringResource(R.string.log_action_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.converter_cancel))
                }
            }
        )
    }
}

@Composable
private fun StatusCountDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FeatureHealthRowCard(
    row: FeatureHealthUiRow,
    timeFmt: SimpleDateFormat,
    isTestingThis: Boolean,
    anyTestRunning: Boolean,
    onRunTest: () -> Unit
) {
    val (badgeColor, badgeLabel, badgeIcon) = when (row.status) {
        FeatureHealthStatus.WORKING -> Triple(
            MaterialTheme.colorScheme.tertiary,
            stringResource(R.string.log_status_working),
            Icons.Default.CheckCircle
        )
        FeatureHealthStatus.FAILED -> Triple(
            MaterialTheme.colorScheme.error,
            stringResource(R.string.log_status_failed),
            Icons.Default.Error
        )
        FeatureHealthStatus.NOT_SUPPORTED -> Triple(
            MaterialTheme.colorScheme.outline,
            stringResource(R.string.log_status_not_supported),
            Icons.Default.NotInterested
        )
        FeatureHealthStatus.NOT_TESTED -> Triple(
            MaterialTheme.colorScheme.secondary,
            stringResource(R.string.log_status_not_tested),
            Icons.Default.HelpOutline
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("feature_row_${row.feature.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = badgeIcon,
                contentDescription = badgeLabel,
                tint = badgeColor,
                modifier = Modifier.size(22.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = row.feature.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeColor.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = badgeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                val okText = row.lastSuccessMillis?.let { "Last OK: ${timeFmt.format(Date(it))}" }
                    ?: "Last OK: —"
                Text(
                    text = okText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!row.lastErrorMessage.isNullOrBlank()) {
                    Text(
                        text = row.lastErrorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.status == FeatureHealthStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            FilledTonalButton(
                onClick = onRunTest,
                enabled = !anyTestRunning,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("test_button_${row.feature.id}")
            ) {
                if (isTestingThis) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.log_test_button))
                }
            }
        }
    }
}

@Composable
private fun LogEntryCard(
    entry: AppLogEntity,
    timeFmt: SimpleDateFormat,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("log_entry_${entry.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        border = BorderStroke(
            width = 1.dp,
            color = when (entry.level) {
                LogLevel.ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
                LogLevel.WARNING -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)
                LogLevel.INFO -> MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LevelBadge(entry.level)
                    Text(
                        text = entry.featureTag,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = timeFmt.format(Date(entry.timestampMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (!entry.stackTrace.isNullOrBlank()) {
                Text(
                    text = "Tap to view stack trace",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun LevelBadge(level: LogLevel) {
    val (color, icon, label) = when (level) {
        LogLevel.INFO -> Triple(MaterialTheme.colorScheme.primary, Icons.Default.Info, "INFO")
        LogLevel.WARNING -> Triple(MaterialTheme.colorScheme.secondary, Icons.Default.Warning, "WARN")
        LogLevel.ERROR -> Triple(MaterialTheme.colorScheme.error, Icons.Default.Error, "ERROR")
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.18f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
