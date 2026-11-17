package me.rerere.rikkahub.plugins.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rikkahub.plugins.core.PluginArchive
import dev.rikkahub.plugins.runtime.PluginHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController

@Composable
fun PluginManagementPage(conversationId: String? = null) {
    PluginPageFrame(stringResource(R.string.plugin_host_title)) {
        PluginHostContent { host -> PluginManagement(host, conversationId) }
    }
}

@Composable
private fun PluginManagement(host: PluginHost, conversationId: String?) {
    val catalog by host.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navigator = LocalNavController.current
    val scope = rememberCoroutineScope()
    var candidate by remember { mutableStateOf<PluginHost.Candidate?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun action(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message
            } finally {
                busy = false
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            action {
                val bytes = withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)).use {
                        PluginArchive.readBounded(it, PluginArchive.MAX_ARCHIVE_BYTES)
                    }
                }
                candidate = host.prepare(bytes)
            }
        }
    }
    Button(onClick = {
        picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }, enabled = !busy) {
        Text(stringResource(R.string.plugin_host_import))
    }
    error?.let { Text(it) }
    if (catalog.packages.isEmpty()) Text(stringResource(R.string.plugin_host_empty))
    for ((id, versions) in catalog.packages.values.groupBy { it.manifest.id }.toSortedMap()) {
        val selected = catalog.preferred[id]?.let { catalog.packages[it] }
            ?: versions.maxBy { it.lease.activationEpoch }
        PluginCard("${selected.manifest.name} · ${selected.manifest.version}") {
            Text(id)
            if (selected.enabled) {
                OutlinedButton(onClick = { action { host.disable(id) } }, enabled = !busy) {
                    Text(stringResource(R.string.plugin_host_disable))
                }
                for (panel in selected.manifest.panels) {
                    OutlinedButton(onClick = {
                        navigator.navigate(Screen.PluginPanel(
                            id,
                            selected.lease.packageHash,
                            selected.lease.activationEpoch,
                            panel.id,
                            conversationId
                        ))
                    }) { Text(panel.title) }
                }
            } else {
                OutlinedButton(onClick = {
                    action { candidate = host.prepareInstalled(selected.lease.packageHash) }
                }, enabled = !busy) {
                    Text(stringResource(R.string.plugin_host_activate))
                }
            }
        }
    }
    candidate?.let { ready ->
        AlertDialog(
            onDismissRequest = { if (!busy) candidate = null },
            title = { Text(stringResource(R.string.plugin_host_consent)) },
            text = {
                Column {
                    PermissionList(ready.pkg.manifest)
                    error?.let { Text(it) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    action {
                        host.activate(ready, ready.pkg.manifest.capabilities)
                        candidate = null
                    }
                }) { Text(stringResource(R.string.plugin_host_approve)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { candidate = null }) {
                    Text(stringResource(R.string.plugin_host_cancel))
                }
            },
        )
    }
}
