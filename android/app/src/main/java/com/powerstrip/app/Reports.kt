package com.powerstrip.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fmtTime(ts: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ts * 1000))

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReportsScreen(snapshot: Snapshot?) {
    val strips = snapshot?.strips.orEmpty()
    var mac by remember { mutableStateOf<String?>(null) }
    var period by remember { mutableStateOf("today") }
    var outlet by remember { mutableIntStateOf(1) }
    var report by remember { mutableStateOf<PowerReport?>(null) }
    var points by remember { mutableStateOf<List<HistoryPoint>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val strip = strips.firstOrNull { it.mac == mac } ?: strips.firstOrNull()
    LaunchedEffect(strip?.mac) {
        mac = strip?.mac
        outlet = 1
    }

    suspend fun load() {
        val m = strip?.mac ?: return
        loading = true
        error = null
        try {
            report = Api.report(Server.base, Server.token, m, period)
            points = Api.history(Server.base, Server.token, m, outlet, period)
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            report = null
            points = emptyList()
        }
        loading = false
    }

    LaunchedEffect(strip?.mac, period, outlet, Server.host, Server.port, Server.token) {
        load()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                stringResource(R.string.reports_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (!Server.configured) {
            item {
                Text(
                    stringResource(R.string.no_server_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@LazyColumn
        }

        if (strips.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.no_strip_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@LazyColumn
        }

        if (strips.size > 1) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    strips.forEach { s ->
                        FilterChip(
                            selected = s.mac == strip?.mac,
                            onClick = { mac = s.mac },
                            label = { Text(s.name) },
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PeriodChip("today", period, stringResource(R.string.per_day)) { period = it }
                PeriodChip("week", period, stringResource(R.string.per_week)) { period = it }
                PeriodChip("month", period, stringResource(R.string.per_month)) { period = it }
            }
        }

        if (loading && report == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                    CircularWavyProgressIndicator(Modifier.padding(18.dp).size(34.dp))
                }
            }
            return@LazyColumn
        }

        error?.let {
            item {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }

        val rep = report
        if (rep == null || rep.totalSamples == 0) {
            item {
                Text(
                    stringResource(R.string.rep_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@LazyColumn
        }

        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricRow(stringResource(R.string.rep_total), "%.3f kWh".format(rep.totalKwh))
                    MetricRow(
                        stringResource(R.string.rep_peak),
                        "%.1f W · %s".format(rep.peakW, rep.peakT?.let { fmtTime(it, "dd MMM HH:mm") } ?: "–"),
                    )
                    MetricRow(stringResource(R.string.rep_lowest), "%.1f W".format(rep.minW))
                    MetricRow(stringResource(R.string.rep_avg), "%.1f W".format(rep.avgW))
                }
            }
        }

        item {
            Text(
                stringResource(R.string.rep_by_outlet),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        items(rep.outlets) { o ->
            ElevatedCard(onClick = { outlet = o.n }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        strip?.outlet(o.n)?.name?.ifBlank { null } ?: "${o.n}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "%.3f kWh".format(o.kwh),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.rep_power_curve),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { scope.launch { load() } }) {
                    Text(stringResource(R.string.refresh))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..4).forEach { n ->
                    val nm = strip?.outlet(n)?.name?.ifBlank { "$n" } ?: "$n"
                    FilterChip(selected = outlet == n, onClick = { outlet = n }, label = { Text(nm) })
                }
            }
        }
        item {
            PowerChart(points = points, selectedOutlet = outlet)
        }
    }
}

@Composable
private fun PeriodChip(value: String, current: String, label: String, onPick: (String) -> Unit) {
    FilterChip(selected = value == current, onClick = { onPick(value) }, label = { Text(label) })
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** Minimal dependency-free power curve: line + filled area + peak dot. */
@Composable
private fun PowerChart(points: List<HistoryPoint>, selectedOutlet: Int) {
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    if (points.size < 2) {
        Text(
            stringResource(R.string.rep_no_data),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }
    val maxW = (points.maxOf { it.powerW }).coerceAtLeast(0.5)
    val t0 = points.first().t
    val t1 = points.last().t
    Canvas(
        Modifier.fillMaxWidth().height(190.dp).padding(top = 4.dp),
    ) {
        val w = size.width
        val h = size.height - 24.dp.toPx()
        fun x(t: Long): Float = if (t1 == t0) 0f else w * (t - t0).toFloat() / (t1 - t0).toFloat()
        fun y(p: Double): Float = h - (h * (p / maxW).toFloat()).coerceIn(0f, h.toFloat())
        // gridlines
        for (f in listOf(0.25f, 0.5f, 0.75f)) {
            drawLine(scheme.surfaceContainerHighest, Offset(0f, h * f), Offset(w, h * f), strokeWidth = 1.dp.toPx())
        }
        val line = Path()
        val area = Path()
        points.forEachIndexed { i, pt ->
            val px = x(pt.t)
            val py = y(pt.powerW)
            if (i == 0) {
                line.moveTo(px, py)
                area.moveTo(px, h)
                area.lineTo(px, py)
            } else {
                line.lineTo(px, py)
                area.lineTo(px, py)
            }
        }
        area.lineTo(x(points.last().t), h)
        area.close()
        drawPath(area, scheme.primaryContainer.copy(alpha = 0.55f))
        drawPath(line, scheme.primary, style = Stroke(width = 2.5.dp.toPx()))
        // peak dot
        val peak = points.maxBy { it.powerW }
        drawCircle(scheme.primary, radius = 4.dp.toPx(), center = Offset(x(peak.t), y(peak.powerW)))
        // axis labels
        drawText(measurer, "%.0f W".format(maxW), topLeft = Offset(0f, 0f), style = labelStyle)
        drawText(measurer, fmtTime(t0, "HH:mm"), topLeft = Offset(0f, h + 4.dp.toPx()), style = labelStyle)
        val endLabel = fmtTime(t1, "HH:mm")
        drawText(
            measurer, endLabel,
            topLeft = Offset(w - measurer.measure(endLabel, labelStyle).size.width, h + 4.dp.toPx()),
            style = labelStyle,
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.rep_curve_hint, selectedOutlet),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
