package me.rerere.rikkahub.plugins.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.protocol.AttemptStatus
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.protocol.PluginUiNode
import dev.rikkahub.plugins.runtime.PluginHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.plugins.executionLedger
import me.rerere.rikkahub.plugins.operationCall
import me.rerere.rikkahub.plugins.operationDefinitions
import me.rerere.rikkahub.plugins.selectedPackage
import me.rerere.rikkahub.plugins.sourceStamp
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.richtext.MaterialMarkdown
import me.rerere.rikkahub.ui.components.ui.FloatingWindow
import me.rerere.rikkahub.utils.JsonInstantPretty
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun PluginPanelPage(
    pluginId: String,
    hash: String,
    epoch: Long,
    panelId: String,
    conversationId: String?,
    issuerId: String = ""
) {
    PluginPageFrame(stringResource(R.string.plugin_host_title)) {
        PluginHostContent { host ->
            val lease = remember(pluginId, hash, epoch, issuerId) { PackageLease(pluginId, hash, epoch, issuerId) }
            val service: ChatService = koinInject()
            if (conversationId == null) {
                PluginPanel(host, lease, panelId, null, service)
            } else {
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
                val conversation by service.getConversationFlow(id).collectAsStateWithLifecycle()
                if (loaded) {
                    PluginPanel(host, lease, panelId, conversation, service)
                } else {
                    Text(stringResource(R.string.plugin_host_loading))
                }
            }
        }
    }
}

@Composable
private fun PluginPanel(
    host: PluginHost,
    lease: PackageLease,
    panelId: String,
    conversation: Conversation?,
    service: ChatService
) {
    val catalog by host.state.collectAsStateWithLifecycle()
    val installed = catalog.packages[lease.packageHash]
    val valid = installed?.let { host.authorized(lease, it.manifest.capabilities) } == true &&
        conversation?.selectedPackage(lease) != false
    val panel = installed?.manifest?.panels?.find { it.id == panelId }
    var tree by remember(lease, panelId) { mutableStateOf<PluginUiNode?>(null) }
    var error by remember(lease, panelId) { mutableStateOf<String?>(null) }
    var floating by remember(lease, panelId) { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The rendered buttons retain the exact history snapshot that produced the panel.
    val source = remember(lease, panelId, conversation?.id, tree) { conversation }
    LaunchedEffect(lease, panelId, valid) {
        if (valid && panel != null) {
            try {
                host.withInstance(lease) { client ->
                    tree = PluginContract.ui(installed!!.manifest, client.invoke("panel", panelId, JsonNull))
                    awaitCancellation()
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message
            }
        }
    }
    if (!valid || panel == null) {
        Text(stringResource(R.string.plugin_host_expired))
        PluginReceipts(conversation, lease.pluginId, service)
        return
    }
    Text("${installed!!.manifest.name} · ${installed.manifest.version} · ${panel.title}")
    if (conversation == null) Text(stringResource(R.string.plugin_host_no_conversation))
    error?.let { Text(it) }
    if (accepted) Text(stringResource(R.string.plugin_host_accepted))
    val onAction: (PluginUiNode) -> Unit = { node ->
        val target = source
        if (target != null) {
            scope.launch {
                try {
                    val definition = host.operationDefinitions().single {
                        it.lease == lease && it.id == "${lease.pluginId}/${node.operation}"
                    }
                    service.submitPluginOperation(target.operationCall(definition, node.input))
                    accepted = true
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = failure.message
                }
            }
        }
    }
    if (floating) {
        FloatingWindow("plugin-${conversation?.id}-${lease.packageHash}-${lease.activationEpoch}-$panelId") {
            Surface(Modifier.widthIn(max = 340.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(panel.title)
                    tree?.let { PluginNode(it, source != null, onAction) }
                    TextButton(onClick = { floating = false }) { Text(stringResource(R.string.plugin_host_close)) }
                }
            }
        }
    } else {
        tree?.let { PluginNode(it, source != null, onAction) }
        if (panel.overlay) {
            OutlinedButton(onClick = { floating = true }) {
                Text(stringResource(R.string.plugin_host_overlay))
            }
        }
    }
    PluginReceipts(conversation, lease.pluginId, service)
}

@Composable
internal fun PluginReceipts(conversation: Conversation?, pluginId: String?, service: ChatService) {
    val scope = rememberCoroutineScope()
    conversation?.let { current ->
        val frame = current.currentMessages.map { it.sourceStamp() }
        val records = current.executionLedger().operations.values.filter {
            (pluginId == null || it.call.handler.pluginId == pluginId) &&
                frame.take(it.call.frame.size).map { stamp -> stamp.id } == it.call.frame.map { stamp -> stamp.id }
        }.reversed()
        var page by rememberSaveable(current.id, pluginId) { mutableIntStateOf(0) }
        val lastPage = maxOf(0, (records.size - 1) / 10)
        val currentPage = page.coerceIn(0, lastPage)
        PluginCard(stringResource(R.string.plugin_host_receipts)) {
            for (record in records.drop(currentPage * 10).take(10)) {
                val label = when (record.status) {
                    AttemptStatus.PREPARED -> R.string.plugin_host_prepared
                    AttemptStatus.RUNNING -> R.string.plugin_host_running
                    AttemptStatus.UNKNOWN -> R.string.plugin_host_unknown
                    AttemptStatus.SETTLED -> R.string.plugin_host_settled
                    AttemptStatus.CANCELLED -> R.string.plugin_host_cancelled
                }
                Text(record.call.type)
                Text(record.call.handler.packageHash)
                Text(stringResource(label))
                if (frame.take(record.call.frame.size) != record.call.frame) {
                    Text(stringResource(R.string.plugin_host_source_changed))
                }
                if (record.status == AttemptStatus.UNKNOWN) {
                    Text(stringResource(R.string.plugin_host_verify_result))
                }
                record.result?.artifacts?.forEach { Text(it.content.toString()) }
                var expanded by remember(record.call.id) { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(R.string.plugin_host_receipt_details))
                }
                if (expanded) Text(JsonInstantPretty.encodeToString(record))
            }
            Text(stringResource(R.string.plugin_host_history_page, currentPage + 1, lastPage + 1, records.size))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { page = currentPage - 1 }, enabled = currentPage > 0) {
                    Text(stringResource(R.string.plugin_host_history_previous))
                }
                TextButton(onClick = { page = currentPage + 1 }, enabled = currentPage < lastPage) {
                    Text(stringResource(R.string.plugin_host_history_next))
                }
            }
            OutlinedButton(onClick = { scope.launch { service.stopGeneration(current.id) } }) {
                Text(stringResource(R.string.plugin_host_stop))
            }
        }
    }
}

@Composable
private fun PluginNode(node: PluginUiNode, enabled: Boolean, action: (PluginUiNode) -> Unit) {
    when (node.type) {
        "column" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children.forEach { PluginNode(it, enabled, action) }
        }

        "button" -> Button(onClick = { action(node) }, enabled = enabled) { Text(node.text) }

        "markdown" -> MaterialMarkdown(node.text)

        else -> Text(node.text)
    }
}
