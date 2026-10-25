package app.serene.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.serene.browser.TabManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsScreen(tabs: TabManager, onDone: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${tabs.tabs.size} tabs") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Filled.Close, "Back to page") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { tabs.open(); onDone() }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New tab") })
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(tabs.tabs, key = { it.id }) { tab ->
                val selected = tab == tabs.current
                ListItem(
                    headlineContent = { Text(tab.title.ifBlank { "New tab" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(tab.url.ifBlank { "" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { IconButton(onClick = { tabs.close(tab) }) { Icon(Icons.Filled.Close, "Close tab") } },
                    colors = if (selected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
                    modifier = Modifier.clickable { tabs.select(tab); onDone() },
                )
            }
        }
    }
}
