package it.borsawatch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ChangeChip(chg: Double?, modifier: Modifier = Modifier) {
    val fg = when {
        chg == null -> MaterialTheme.colorScheme.onSurfaceVariant
        chg > 0 -> Up
        chg < 0 -> Down
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val bg = when {
        chg == null -> MaterialTheme.colorScheme.surfaceVariant
        chg > 0 -> UpSoft
        chg < 0 -> DownSoft
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = bg, shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Text(
            Fmt.pct(chg), color = fg, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun TickerCard(t: Tick, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.sym, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (t.alerts.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Notifications, contentDescription = "Ha un avviso", tint = Blue, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (t.last == null) "–" else Fmt.price(t.last) + (if (t.cur.isNullOrBlank()) "" else " " + t.cur),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                ChangeChip(t.chg)
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleMedium, color = BlueDeep,
        modifier = modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
fun Banner(text: String, modifier: Modifier = Modifier, warn: Boolean = false) {
    Surface(
        color = if (warn) WarnSoft else BlueSoft,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text, style = MaterialTheme.typography.bodyMedium, color = if (warn) Warn else Color(0xFF062B66),
            modifier = Modifier.padding(14.dp)
        )
    }
}

@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true, enabled: Boolean = true) {
    if (filled) {
        Button(
            onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Blue)
        ) { Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
    } else {
        OutlinedButton(
            onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)
        ) { Text(text, style = MaterialTheme.typography.labelLarge, color = Blue, textAlign = TextAlign.Center) }
    }
}

@Composable
fun EmptyState(title: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = BlueDeep, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun StatusDot(online: Boolean?, modifier: Modifier = Modifier) {
    val c = when (online) {
        true -> Color(0xFF7CF0A6)
        false -> Color(0xFFFFB4B4)
        null -> Color(0xFFCBD5E1)
    }
    Box(modifier.size(10.dp).clip(CircleShape).background(c))
}

/** Grafico semplice a linea: verde se il periodo è in rialzo, rosso se in calo. */
@Composable
fun PriceChart(points: List<Pair<Long, Double>>, modifier: Modifier = Modifier) {
    if (points.size < 2) {
        Box(modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            Text("Il grafico si forma con le prime letture", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val up = points.last().second >= points.first().second
    val color = if (up) Up else Down
    val lo = points.minOf { it.second }
    val hi = points.maxOf { it.second }
    val span = if (hi - lo < 1e-9) 1.0 else hi - lo
    Canvas(modifier.fillMaxWidth().height(180.dp)) {
        val w = size.width
        val h = size.height
        val pad = 8f
        val t0 = points.first().first.toDouble()
        val tSpan = (points.last().first - points.first().first).toDouble().coerceAtLeast(1.0)
        fun x(t: Long) = (pad + ((t - t0) / tSpan) * (w - 2 * pad)).toFloat()
        fun y(v: Double) = (h - pad - ((v - lo) / span) * (h - 2 * pad)).toFloat()
        val line = Path()
        points.forEachIndexed { i, p -> if (i == 0) line.moveTo(x(p.first), y(p.second)) else line.lineTo(x(p.first), y(p.second)) }
        val fill = Path().apply {
            addPath(line)
            lineTo(x(points.last().first), h)
            lineTo(x(points.first().first), h)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.0f))))
        drawPath(line, color, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, 8f, Offset(x(points.last().first), y(points.last().second)))
    }
}

@Composable
fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp), modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun Clickable(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(modifier.clickable(onClick = onClick)) { content() }
}

@Suppress("unused")
private val keepSize = Size(0f, 0f)
