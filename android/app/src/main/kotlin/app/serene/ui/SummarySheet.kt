package app.serene.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.serene.SereneApp
import app.serene.reader.ReaderPage
import app.serene.reader.Summary
import app.serene.reader.SummaryState

/**
 * The page's key points from the on-device model, filling in as it writes. Closing the sheet
 * stops the model. Says plainly that a 0.6B model can get things wrong.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummarySheet(app: SereneApp, page: ReaderPage, onDismiss: () -> Unit, onOpenSettings: () -> Unit, onReader: (() -> Unit)?) {
    val context = LocalContext.current
    val summarizer = remember { app.summarizer() }
    var state by remember(page) { mutableStateOf(SummaryState()) }
    LaunchedEffect(summarizer, page) {
        summarizer?.summarize(page)?.collect { state = it }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text("Summary", style = MaterialTheme.typography.titleLarge)
            Text(page.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))

            if (summarizer == null) {
                Text("Summaries are written on this phone by Serene's model, and it isn't downloaded yet. It is the same model that sorts out the pop-ups Serene can't place on its own (about 600 MB).", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenSettings) { Text("Download it in Settings") }
                return@Column
            }

            if (!state.done) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(status(state), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }

            val shown = if (state.step == Summary.Step.MERGE || state.done) state.points else state.points + state.writing
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                shown.forEach { p ->
                    Row {
                        Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(p, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text("The model stopped: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (state.done && state.error == null && state.points.isEmpty()) {
                Text("The model wrote nothing usable for this page.", style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(16.dp))
            val notes = buildList {
                add("Written on this phone by ${app.models.spec.name}, a small model: it can leave out what matters or mix up who did what. Check anything important on the page.")
                if (state.skippedParts > 0) add("Covers the first ${state.parts} of ${state.parts + state.skippedParts} parts of this long page.")
                if (!page.article) add("This page doesn't look like an article, so the summary is of all its text.")
                if (state.done && state.ms > 0) add("${"%.1f".format(state.ms / 1000.0)} s on the ${state.backend}.")
            }
            Text(notes.joinToString(" "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (state.done && state.points.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = {
                        val text = (listOf(page.title, page.url, "") + state.points.map { "- $it" }).joinToString("\n")
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Summary", text))
                        Toast.makeText(context, "Summary copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy") }
                    if (onReader != null) Button(onClick = onReader) { Text("Read the page") }
                }
            }
        }
    }
}

private fun status(s: SummaryState): String = when {
    s.loading -> "Loading the model…"
    s.step == Summary.Step.MERGE -> "Keeping the most important points…"
    s.parts > 1 -> "Reading part ${s.part} of ${s.parts}…"
    else -> "Reading the page…"
}
