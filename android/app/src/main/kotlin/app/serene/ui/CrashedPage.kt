package app.serene.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.serene.browser.Tab

/**
 * In place of a page whose renderer went while it was on screen (Chrome's "Aw, Snap!"). Reload
 * brings it back with its history; so do back, forward and a new address.
 */
@Composable
fun CrashedPage(crashed: Tab.Unloaded, onReload: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(16.dp))
            Text(if (crashed.crash) "This page crashed" else "This page was closed", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                if (crashed.crash) "Something went wrong while showing it. Reload to try again."
                else "Android closed it to free up memory. Reload to open it again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onReload) {
                Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Reload")
            }
            Spacer(Modifier.weight(1.4f))
        }
    }
}
