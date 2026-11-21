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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pro.openminis.app.ext.opencode.api.TokenUsage
import pro.openminis.app.ext.usage.TokenHeatmap
import pro.openminis.app.ext.usage.UsageEvent
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * Token 使用热力图（规格 §6）。
 *
 * 数据层是 [TokenHeatmap] 的真实聚合，不是画一张假图：
 * 方格颜色由 `level()` 的对数分档决定，明细按模型拆分并算占比。
 *
 * 由于当前还没有把 Agent 调用的 usage 接到这里（那要改 ChatViewModel 的调用点），
 * 提供「载入演示数据」只是为了验证渲染与聚合正确，数据本身明确标注为演示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokenHeatmapScreen(
    onBack: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    val heatmap = remember { TokenHeatmap() }
    // 聚合结果是普通对象，用版本号触发重组
    var version by remember { mutableStateOf(0) }
    var range by remember { mutableStateOf(TokenHeatmap.Range.ALL) }
    var demoLoaded by remember { mutableStateOf(false) }

    val buckets = remember(version, range) { heatmap.rangeBuckets(range, today) }
    // Canvas 只关心每天的 token 数，这里把 DayBucket 投影成 Long，避免 UI 层持有聚合对象
    val columns = remember(version) {
        heatmap.columns(today).map { week -> week.map { it.tokens } }
    }
    val windowTotals = remember(version) { heatmap.window(today) }
    val totalUsage = remember(version) { windowTotals.fold(TokenUsage.ZERO) { a, b -> a + b.usage } }
    val activeDays = remember(version) { windowTotals.count { it.tokens > 0 } }

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
                subtitle = if (demoLoaded) {
                    "当前为演示数据，用于验证聚合与色阶渲染"
                } else {
                    "尚无真实调用数据。接入 Agent 调用点后此处自动填充。"
                },
            ) {
                HeatmapCanvas(columns = columns)
                Spacer(Modifier.height(10.dp))
                HeatmapLegend()
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        loadDemo(heatmap, today)
                        demoLoaded = true
                        version += 1
                    }) {
                        Text("载入演示数据")
                    }
                    Button(onClick = {
                        heatmap.clear()
                        demoLoaded = false
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
                    ExtCode("注意：含 web-anon 通道，该通道未提供 token 用量", tint = androidx.compose.ui.graphics.Color(0xFFFF9F0A))
                }
            }

            ExtCard(title = "累计") {
                ExtRow("总 Token", formatCount(totalUsage.total))
                ExtRow("活跃天数", "$activeDays / 182")
                ExtRow("已知模型", heatmap.knownModels().joinToString("、").ifBlank { "—" })
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

/**
 * 生成可复现的演示数据：固定种子，同一天总是同样的量，方便目视校验色阶。
 * 刻意让周末偏少，这样色阶的层次才看得出来。
 */
private fun loadDemo(heatmap: TokenHeatmap, today: LocalDate) {
    heatmap.clear()
    val rnd = Random(20261120)
    val models = listOf("deepseek-v4-flash-free", "big-pickle", "claude-sonnet-4")
    for (i in 0 until 182) {
        val date = today.minusDays(i.toLong())
        val weekday = date.dayOfWeek.value
        if (weekday >= 6 && rnd.nextInt(100) < 55) continue
        if (rnd.nextInt(100) < 12) continue
        val calls = rnd.nextInt(1, 6)
        repeat(calls) {
            val model = models[rnd.nextInt(models.size)]
            val input = rnd.nextLong(200, 12_000)
            val output = rnd.nextLong(100, 4_000)
            val cacheRead = if (rnd.nextBoolean()) rnd.nextLong(0, 6_000) else 0L
            heatmap.record(
                UsageEvent(
                    provider = "opencode-local",
                    model = model,
                    usage = TokenUsage(input = input, output = output, cacheRead = cacheRead),
                    timestamp = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    sessionId = "demo",
                ),
            )
        }
    }
}
