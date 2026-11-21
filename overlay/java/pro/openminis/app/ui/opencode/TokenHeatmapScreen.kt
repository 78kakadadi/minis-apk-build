package pro.openminis.app.ui.opencode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pro.openminis.app.ext.opencode.api.TokenUsage
import pro.openminis.app.ext.usage.TokenHeatmap
import pro.openminis.app.ext.usage.UsageCenter
import java.time.LocalDate

/**
 * Token 使用热力图（规格 §6）。
 *
 * 数据来自 [UsageCenter] 的真实采集：免费通道由 bridge 在收到 opencode 的
 * `session.usage.updated` 时记账，群聊里绑定已存 API 的成员由群聊引擎记账。
 * 每一格都是真实调用，不再提供演示数据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokenHeatmapScreen(
    onBack: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    val context = androidx.compose.ui.platform.LocalContext.current
    remember(context) { pro.openminis.app.ext.usage.UsageCenter.ensureInit(context) }
    val heatmap = remember { UsageCenter.heatmap() }
    // 聚合结果是普通对象，用版本号触发重组
    var version by remember { mutableStateOf(0) }
    var range by remember { mutableStateOf(TokenHeatmap.Range.ALL) }

    val buckets = remember(version, range) { heatmap.rangeBuckets(range, today) }
    // Canvas 只关心每天的 token 数，这里把 DayBucket 投影成 Long，避免 UI 层持有聚合对象
    val columns = remember(version) {
        heatmap.columns(today).map { week -> week.map { it.tokens } }
    }
    val windowTotals = remember(version) { heatmap.window(today) }
    val totalUsage = remember(version) { windowTotals.fold(TokenUsage.ZERO) { a, b -> a + b.usage } }
    val activeDays = remember(version) { windowTotals.count { it.tokens > 0 } }

    // 页面开着时后台还在跑对话：新事件只改内存聚合，不会触发重组。
    // 轮询 updatedAt，有新记账就 +1 版本号，格子才会实时变色。
    var seenUpdatedAt by remember { mutableStateOf(heatmap.updatedAt) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1500)
            val at = heatmap.updatedAt
            if (at != seenUpdatedAt) {
                seenUpdatedAt = at
                version += 1
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Token 使用热力图") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExtCard(
                title = "近 26 周",
                subtitle = "免费通道与群聊成员的真实调用用量，逐次记账",
            ) {
                HeatmapCanvas(columns = columns)
                Spacer(Modifier.height(10.dp))
                HeatmapLegend()
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        UsageCenter.clearAll()
                        version += 1
                    }) {
                        Text("清空")
                    }
                }
            }

            ExtCard(title = "区间汇总") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TokenHeatmap.Range.entries.forEach { r ->
                        FilterChip(
                            selected = range == r,
                            onClick = { range = r },
                            label = { Text(rangeLabel(r)) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                ExtRow("输入", formatCount(buckets.sumOf { it.usage.input }))
                ExtRow("输出", formatCount(buckets.sumOf { it.usage.output }))
                ExtRow("缓存读", formatCount(buckets.sumOf { it.usage.cacheRead }))
                ExtRow("缓存写", formatCount(buckets.sumOf { it.usage.cacheWrite }))
                ExtRow("请求数", buckets.sumOf { it.requests }.toString())
                if (buckets.any { it.hasUntrackedChannel }) {
                    Spacer(Modifier.height(6.dp))
                    ExtCode("注意：含未上报 token 用量的通道，这部分数值按字符估算", tint = androidx.compose.ui.graphics.Color(0xFFFF9F0A))
                }
            }

            ExtCard(title = "累计") {
                ExtRow("总 Token", formatCount(totalUsage.total))
                ExtRow("活跃天数", "$activeDays / 182")
                ExtRow("已知模型", heatmap.knownModels().joinToString("、").ifBlank { "—" })
                ExtRow(
                    label = "最近记账",
                    value = heatmap.updatedAt.takeIf { it > 0 }?.let {
                        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(it))
                    } ?: "—",
                )
            }

            ExtCard(
                title = "按模型拆分",
                subtitle = "占比以当前区间总用量为分母",
            ) {
                val byModel = remember(version, range) {
                    val map = LinkedHashMap<String, Long>()
                    buckets.forEach { b ->
                        b.byModel.forEach { (m, u) -> map[m] = (map[m] ?: 0L) + u.total }
                    }
                    map.entries.sortedByDescending { it.value }
                }
                if (byModel.isEmpty()) {
                    Text(
                        text = "暂无数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val sum = byModel.sumOf { it.value }.coerceAtLeast(1)
                    byModel.forEach { (model, tokens) ->
                        val pct = (tokens * 100.0 / sum)
                        ExtRow(
                            label = model,
                            value = "${formatCount(tokens)}  ${String.format("%.1f", pct)}%",
                            mono = true,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun rangeLabel(r: TokenHeatmap.Range): String = when (r) {
    TokenHeatmap.Range.TODAY -> "今日"
    TokenHeatmap.Range.WEEK -> "本周"
    TokenHeatmap.Range.MONTH -> "本月"
    TokenHeatmap.Range.ALL -> "累计"
}

/** 千分位，避免长数字读不出量级。 */
internal fun formatCount(n: Long): String {
    if (n < 1000) return n.toString()
    val s = n.toString()
    val sb = StringBuilder()
    s.reversed().forEachIndexed { i, c ->
        if (i > 0 && i % 3 == 0) sb.append(',')
        sb.append(c)
    }
    return sb.reverse().toString()
}
