package me.rerere.rikkahub.plugins.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rikkahub.plugins.core.PluginArchive
import dev.rikkahub.plugins.core.PluginVersion
import dev.rikkahub.plugins.core.updatePolicy
import dev.rikkahub.plugins.runtime.PluginHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.plugins.SessionPackageStatus
import me.rerere.rikkahub.plugins.packageAvailability
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun PluginManagementPage(conversationId: String? = null) {
    PluginPageFrame(stringResource(R.string.plugin_host_title)) {
        val service: ChatService = koinInject()
        val conversation = if (conversationId != null) {
            val id = remember(conversationId) { Uuid.parse(conversationId) }
            DisposableEffect(id, service) {
                service.addConversationReference(id)
                onDispose { service.removeConversationReference(id) }
            }
            var loaded by remember(id) { mutableStateOf(false) }
            LaunchedEffect(id, service) {
                service.initializeConversation(id)
                loaded = true
            }
            val current by service.getConversationFlow(id).collectAsStateWithLifecycle()
            current.takeIf { loaded }
        } else {
            null
        }
        if (conversationId != null && conversation == null) {
            Text(stringResource(R.string.plugin_host_loading))
        } else {
            if (conversation != null) {
                var showHistory by remember(conversation.id) { mutableStateOf(false) }
                OutlinedButton(onClick = { showHistory = !showHistory }) {
                    Text(stringResource(R.string.plugin_host_history))
                }
                if (showHistory) PluginReceipts(conversation, null, service)
                val scope = rememberCoroutineScope()
                var error by remember { mutableStateOf<String?>(null) }
                OutlinedButton(onClick = {
                    scope.launch {
                        try {
                            service.clearConversationPlugins(conversation.id)
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            error = failure.message
                        }
                    }
                }) { Text(stringResource(R.string.plugin_host_clear_selection)) }
                error?.let { Text(it) }
            }
            PluginHostContent { host -> PluginManagement(host, conversation) }
        }
    }
}

@Composable
private fun PluginManagement(host: PluginHost, conversation: Conversation?) {
    val conversationId = conversation?.id?.toString()
    val catalog by host.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navigator = LocalNavController.current
    val scope = rememberCoroutineScope()
    var candidate by remember { mutableStateOf<PluginHost.Candidate?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var repository by remember { mutableStateOf("") }
    val service: ChatService = koinInject()
    val availability = conversation?.packageAvailability(catalog)
    val selection = availability?.filter { it.status == SessionPackageStatus.AVAILABLE }
        ?.mapNotNull { it.lease }?.associateBy { it.pluginId }
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
    PluginGitHubInstaller(host, repository, !busy && candidate == null) { candidate = it }
    if (conversationId != null) {
        OutlinedButton(onClick = {
            action { service.adoptCurrentPlugins(Uuid.parse(conversationId)) }
        }, enabled = !busy) {
            Text(stringResource(R.string.plugin_host_adopt))
        }
    }
    error?.let { Text(it) }
    for (required in availability.orEmpty().filter { it.status != SessionPackageStatus.AVAILABLE }) {
        PluginCard(required.pluginId) {
            Text(required.hash)
            Text(stringResource(if (required.status == SessionPackageStatus.MISSING) {
                R.string.plugin_host_missing_package
            } else {
                R.string.plugin_host_package_not_authorized
            }))
        }
    }
    if (catalog.packages.isEmpty()) Text(stringResource(R.string.plugin_host_empty))
    for ((id, versions) in catalog.packages.values.groupBy { it.manifest.id }.toSortedMap()) {
        val selected = (selection?.get(id)?.packageHash ?: catalog.preferred[id])?.let { catalog.packages[it] }
            ?: versions.maxBy { it.lease.activationEpoch }
        val sessionEnabled = selection == null || id in selection
        val displayedLease = selection?.get(id) ?: selected.lease
        PluginCard("${selected.manifest.name} · ${selected.manifest.version}") {
            Text(id)
            catalog.updatePolicies[id]?.repository?.let { source ->
                OutlinedButton(onClick = {
                    repository = source
                }) { Text(stringResource(R.string.plugin_host_check_updates)) }
            }
            for (version in versions.filter { it.lease != selected.lease }) {
                OutlinedButton(onClick = {
                    action { candidate = host.prepareInstalled(version.lease.packageHash) }
                }, enabled = !busy) {
                    Text(stringResource(R.string.plugin_host_use_version, version.manifest.version))
                }
            }
            if (selected.enabled) {
                OutlinedButton(onClick = { action { host.disable(id) } }, enabled = !busy) {
                    Text(stringResource(R.string.plugin_host_disable))
                }
                for (panel in selected.manifest.panels.filter { sessionEnabled }) {
                    OutlinedButton(onClick = {
                        navigator.navigate(Screen.PluginPanel(
                            id,
                            displayedLease.packageHash,
                            displayedLease.activationEpoch,
                            panel.id,
                            conversationId,
                            displayedLease.issuerId
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
        val policy = catalog.updatePolicy(ready.pkg.manifest.id)
        val rollback = policy?.let {
            PluginVersion.parse(ready.pkg.manifest.version) < PluginVersion.parse(it.highWaterVersion)
        } == true
        val sourceChanged = policy != null && (policy.repositoryId != ready.origin?.repositoryId ||
            policy.publisherKey != ready.origin?.publisherKey)
        var approveRollback by remember(ready) { mutableStateOf(false) }
        var approveSource by remember(ready) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) candidate = null },
            title = { Text(stringResource(R.string.plugin_host_consent)) },
            text = {
                Column {
                    PermissionList(ready.pkg.manifest)
                    Text(ready.origin?.repository ?: stringResource(R.string.plugin_host_local))
                    Text(
                        ready.origin?.publisherKey?.let { stringResource(R.string.plugin_host_signature, it) }
                            ?: stringResource(R.string.plugin_host_unsigned)
                    )
                    if (rollback) {
                        Text(stringResource(R.string.plugin_host_rollback))
                        Checkbox(checked = approveRollback, onCheckedChange = { approveRollback = it })
                    }
                    if (sourceChanged) {
                        Text(stringResource(R.string.plugin_host_source_change))
                        Checkbox(checked = approveSource, onCheckedChange = { approveSource = it })
                    }
                    error?.let { Text(it) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && (!rollback || approveRollback) && (!sourceChanged || approveSource),
                    onClick = {
                        action {
                            host.activate(
                                ready,
                                ready.pkg.manifest.capabilities,
                                allowRollback = approveRollback,
                                allowSourceChange = approveSource
                            )
                            candidate = null
                        }
                    }
                ) { Text(stringResource(R.string.plugin_host_approve)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { candidate = null }) {
                    Text(stringResource(R.string.plugin_host_cancel))
                }
            },
        )
    }
}
