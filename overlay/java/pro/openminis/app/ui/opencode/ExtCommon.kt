package pro.openminis.app.ui.opencode

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 扩展能力页的共用零件。
 *
 * 抽出来是因为 5 个新页面要用同一套卡片/标题/状态行样式，
 * 各自复制一份会在主题调整时漏改。
 */

/** 页面内的分区卡片。 */
@Composable
internal fun ExtCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** 左标签右值的状态行。 */
@Composable
internal fun ExtRow(
    label: String,
    value: String,
    mono: Boolean = false,
    valueColor: Color? = null,
    labelWidth: Int = 92,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            modifier = Modifier.width(labelWidth.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 深色等宽代码块。 */
@Composable
internal fun ExtCode(text: String, tint: Color = Color(0xFF32D74B)) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = tint,
        )
    }
}

/** 小标签（用于等级、状态）。 */
@Composable
internal fun ExtChip(text: String, tint: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 5 档色阶，从浅到深。绿系与 GitHub 贡献图一致，深浅色主题下都能分辨。 */
internal fun heatmapPalette(): List<Color> = listOf(
    Color(0xFF9BE9A8),
    Color(0xFF40C463),
    Color(0xFF30A14E),
    Color(0xFF216E39),
    Color(0xFF0E4429),
)

/**
 * 26 周 × 7 天的贡献图。
 *
 * 用 Canvas 直接画，不引入图表库：格子数量固定（182），
 * 任何图表库的初始化开销都比直接 drawRoundRect 大。
 *
 * 规格要求「窄屏横向滚动」，所以外层横向滚动，Canvas 用固定宽度。
 */
@Composable
internal fun HeatmapCanvas(
    /** 每列是一个自然周，7 个元素（周一…周日）。 */
    columns: List<List<Long>>,
    modifier: Modifier = Modifier,
) {
    val cell = 11.dp
    val gap = 3.dp
    val step = cell + gap
    val emptyColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val palette = heatmapPalette()
    val scroll = rememberScrollState()

    Row(modifier = modifier.horizontalScroll(scroll)) {
        Canvas(
            modifier = Modifier
                .width(step * columns.size)
                .height(step * 7),
        ) {
            val c = cell.toPx()
            val g = gap.toPx()
            columns.forEachIndexed { ci, week ->
                week.forEachIndexed { di, tokens ->
                    val color = when {
                        tokens <= 0L -> emptyColor
                        tokens < 100L -> palette[0]
                        tokens < 1_000L -> palette[1]
                        tokens < 10_000L -> palette[2]
                        tokens < 100_000L -> palette[3]
                        else -> palette[4]
                    }
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(ci * (c + g), di * (c + g)),
                        size = Size(c, c),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
            }
        }
    }
}

/** 图例：较少 → 较多。 */
@Composable
internal fun HeatmapLegend() {
    val palette = heatmapPalette()
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "较少",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (listOf(empty) + palette).forEach { c ->
                Canvas(modifier = Modifier.width(11.dp).height(11.dp)) {
                    drawRoundRect(color = c, size = size, cornerRadius = CornerRadius(2.dp.toPx()))
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "较多",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
