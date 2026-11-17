package me.rerere.rikkahub.plugins.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rikkahub.plugins.protocol.PluginManifest
import dev.rikkahub.plugins.runtime.PluginHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.plugins.PluginHostLoad
import me.rerere.rikkahub.plugins.RikkaPluginManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.compose.koinInject

@Composable
internal fun PluginHostContent(content: @Composable (PluginHost) -> Unit) {
    val manager: RikkaPluginManager = koinInject()
    val state by manager.state.collectAsStateWithLifecycle()
    LaunchedEffect(manager) {
        try {
            manager.host()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
        }
    }
    when (val current = state) {
        PluginHostLoad.Loading -> Text(stringResource(R.string.plugin_host_loading))
        is PluginHostLoad.Failed -> Text(current.message)
        is PluginHostLoad.Ready -> content(current.host)
    }
}

@Composable
internal fun PluginPageFrame(title: String, content: @Composable () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { BackButton() }) }) { insets ->
        Column(
            Modifier.padding(insets).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) { content() }
    }
}

@Composable
internal fun PermissionList(manifest: PluginManifest) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${manifest.name} · ${manifest.version}")
        Text(manifest.id)
        for (capability in manifest.capabilities.sorted()) {
            val text = when (capability) {
                "context.read" -> R.string.plugin_host_read_context
                "context.write" -> R.string.plugin_host_write_context
                "artifacts.write" -> R.string.plugin_host_write_artifacts
                "tools.register" -> R.string.plugin_host_tools
                "ui.actions" -> R.string.plugin_host_actions
                "ui.overlay.app" -> R.string.plugin_host_app_overlay
                else -> null
            }
            Text("• ${text?.let { stringResource(it) } ?: capability}")
        }
    }
}

@Composable
internal fun PluginCard(title: String, content: @Composable () -> Unit) {
    CardGroup {
        formItem(headlineContent = { Text(title) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}
