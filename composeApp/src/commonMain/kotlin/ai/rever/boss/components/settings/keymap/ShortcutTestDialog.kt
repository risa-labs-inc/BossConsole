package ai.rever.boss.components.settings.keymap

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ai.rever.boss.keymap.model.KeymapSettings
import kotlinx.coroutines.launch

/**
 * Dialog for testing all keyboard shortcuts at once.
 * Provides visual feedback and filtering capabilities.
 */
@Composable
fun ShortcutTestDialog(
    keymapSettings: KeymapSettings,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedFilter by remember { mutableStateOf(TestStatusFilter.ALL) }

    val testResults by ShortcutTestRunner.testResults.collectAsState()
    val testProgress by ShortcutTestRunner.testProgress.collectAsState()
    val currentTesting by ShortcutTestRunner.currentTesting.collectAsState()

    // Filter results based on selection
    val filteredResults = remember(testResults, selectedFilter) {
        when (selectedFilter) {
            TestStatusFilter.ALL -> testResults.values.toList()
            TestStatusFilter.SUCCESS -> testResults.values.filter { it.status == TestStatus.SUCCESS }
            TestStatusFilter.FAILED -> testResults.values.filter { it.status == TestStatus.FAILED }
            TestStatusFilter.SKIPPED -> testResults.values.filter { it.status == TestStatus.SKIPPED }
        }.sortedBy { it.binding.category + it.binding.description }
    }

    // Calculate stats
    val stats = remember(testResults) {
        TestStats(
            total = keymapSettings.shortcuts.size,
            success = testResults.values.count { it.status == TestStatus.SUCCESS },
            failed = testResults.values.count { it.status == TestStatus.FAILED },
            skipped = testResults.values.count { it.status == TestStatus.SKIPPED },
            notTested = keymapSettings.shortcuts.size - testResults.size
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(900.dp)
                .height(700.dp),
            shape = RoundedCornerShape(12.dp),
            elevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Keyboard Shortcut Tester",
                            style = MaterialTheme.typography.h5,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (currentTesting != null) {
                            Text(
                                text = "Testing ${testProgress.completed} of ${testProgress.total}...",
                                style = MaterialTheme.typography.body2,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                            )
                        } else if (testResults.isNotEmpty()) {
                            Text(
                                text = "Test complete: ${stats.success} success, ${stats.failed} failed, ${stats.skipped} skipped",
                                style = MaterialTheme.typography.body2,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Progress bar
                if (currentTesting != null) {
                    LinearProgressIndicator(
                        progress = testProgress.percentage,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                ShortcutTestRunner.clearResults()
                                ShortcutTestRunner.testAllShortcuts(keymapSettings)
                            }
                        },
                        enabled = currentTesting == null
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test All Shortcuts")
                    }

                    OutlinedButton(
                        onClick = {
                            ShortcutTestRunner.clearResults()
                        },
                        enabled = testResults.isNotEmpty() && currentTesting == null
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear Results")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Filter chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        label = "All (${stats.total})",
                        selected = selectedFilter == TestStatusFilter.ALL,
                        onClick = { selectedFilter = TestStatusFilter.ALL }
                    )
                    FilterChip(
                        label = "Success (${stats.success})",
                        selected = selectedFilter == TestStatusFilter.SUCCESS,
                        onClick = { selectedFilter = TestStatusFilter.SUCCESS },
                        color = BossTheme.colors.ok
                    )
                    FilterChip(
                        label = "Failed (${stats.failed})",
                        selected = selectedFilter == TestStatusFilter.FAILED,
                        onClick = { selectedFilter = TestStatusFilter.FAILED },
                        color = MaterialTheme.colors.error
                    )
                    FilterChip(
                        label = "Skipped (${stats.skipped})",
                        selected = selectedFilter == TestStatusFilter.SKIPPED,
                        onClick = { selectedFilter = TestStatusFilter.SKIPPED },
                        color = BossTheme.colors.warn
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Divider()

                Spacer(modifier = Modifier.height(16.dp))

                // Results list
                if (testResults.isEmpty()) {
                    // Empty state
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.3f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Click 'Test All Shortcuts' to begin",
                                style = MaterialTheme.typography.h6,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Tests are non-destructive and check lifecycle conditions",
                                style = MaterialTheme.typography.body2,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.3f)
                            )
                        }
                    }
                } else {
                    // Results table
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredResults) { result ->
                            TestResultItem(result = result)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Individual test result item.
 */
@Composable
private fun TestResultItem(result: ShortcutTestResult) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = when (result.status) {
            TestStatus.SUCCESS -> BossTheme.colors.ok.copy(alpha = 0.05f)
            TestStatus.FAILED -> MaterialTheme.colors.error.copy(alpha = 0.05f)
            TestStatus.SKIPPED -> BossTheme.colors.warn.copy(alpha = 0.05f)
            TestStatus.TESTING -> MaterialTheme.colors.primary.copy(alpha = 0.05f)
            TestStatus.NOT_TESTED -> MaterialTheme.colors.surface
        },
        elevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status icon
            Icon(
                imageVector = when (result.status) {
                    TestStatus.SUCCESS -> Icons.Default.CheckCircle
                    TestStatus.FAILED -> Icons.Default.Cancel
                    TestStatus.SKIPPED -> Icons.Default.Warning
                    TestStatus.TESTING -> Icons.Default.Info
                    TestStatus.NOT_TESTED -> Icons.Default.Info
                },
                contentDescription = result.status.name,
                tint = when (result.status) {
                    TestStatus.SUCCESS -> BossTheme.colors.ok
                    TestStatus.FAILED -> MaterialTheme.colors.error
                    TestStatus.SKIPPED -> BossTheme.colors.warn
                    TestStatus.TESTING -> MaterialTheme.colors.primary
                    TestStatus.NOT_TESTED -> MaterialTheme.colors.onSurface.copy(alpha = 0.3f)
                },
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Description and details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.binding.description,
                    style = MaterialTheme.typography.body1,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = result.binding.category,
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.primary
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.3f)
                    )
                    Text(
                        text = result.message,
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Key combination
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colors.primary.copy(alpha = 0.1f)
            ) {
                Text(
                    text = result.binding.displayString(),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.primary
                )
            }
        }
    }
}

/**
 * Filter chip for status filtering.
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    color: Color = MaterialTheme.colors.primary
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) color.copy(alpha = 0.2f) else MaterialTheme.colors.surface,
        border = if (selected) null else ButtonDefaults.outlinedBorder,
        elevation = if (selected) 2.dp else 0.dp
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.body2,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) color else MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
        )
    }
}

/**
 * Filter options for test results.
 */
private enum class TestStatusFilter {
    ALL, SUCCESS, FAILED, SKIPPED
}

/**
 * Statistics about test results.
 */
private data class TestStats(
    val total: Int,
    val success: Int,
    val failed: Int,
    val skipped: Int,
    val notTested: Int
)
