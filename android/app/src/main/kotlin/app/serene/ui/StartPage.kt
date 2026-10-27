package app.serene.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.serene.R
import app.serene.SereneApp
import app.serene.stats.CATEGORY_NAMES
import app.serene.stats.Totals
import java.text.NumberFormat

/** A blank tab: what Helmi is, and the totals of what it has cleared so far. */
@Composable
fun StartPage(app: SereneApp) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(painterResource(R.drawable.ic_helm), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(12.dp))
            Text("Helmi", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Cookie banners, newsletter pop-ups and other overlays are cleared for you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
                StatsPanel(app.stats.totals)
            }
            Spacer(Modifier.height(24.dp))
            val model = if (app.models.readyFile() != null && app.settings.modelEnabled) "on-device model ready" else "filter lists and heuristics (no model)"
            Text("Engine: $model", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StatsPanel(t: Totals) {
    val ctx = LocalContext.current
    val n = NumberFormat.getIntegerInstance()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (t.annoyances == 0L && t.requestsBlocked == 0L) {
        Text(
            "As you browse, the totals of what Helmi clears show up here.",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    Text(
        "Since ${DateUtils.formatDateTime(ctx, t.since, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)}",
        style = MaterialTheme.typography.labelLarge,
        color = muted,
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(n.format(t.annoyances), "annoyances cleared", Modifier.weight(1f))
        Tile(n.format(t.requestsBlocked), "requests blocked", Modifier.weight(1f))
    }

    val categories = t.cleared.entries.filter { it.value > 0 }.sortedByDescending { it.value }
    if (categories.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        categories.forEach { (label, count) -> Line(CATEGORY_NAMES[label] ?: label, n.format(count)) }
    }
    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    if (t.consentRejected > 0) Line("Tracking refused on cookie pop-ups", n.format(t.consentRejected))
    Line("Pages visited with Helmi on", n.format(t.pages))
    if (t.modelCalls > 0) {
        Line("On-device model decisions", "${n.format(t.modelCalls)} · %.1f s each".format(t.modelMs / 1000.0 / t.modelCalls))
    }
    val sites = t.busiestSites(3)
    if (sites.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Most cleaned-up sites", style = MaterialTheme.typography.labelLarge, color = muted)
        sites.forEach { (site, count) -> Line(site, n.format(count)) }
    }
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Line(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
