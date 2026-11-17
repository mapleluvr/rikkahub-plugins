package me.rerere.rikkahub.plugins.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.plugins.PluginHostLoad
import me.rerere.rikkahub.plugins.RikkaPluginManager
import me.rerere.rikkahub.plugins.pluginToolName
import me.rerere.rikkahub.ui.components.message.tools.DefaultToolPreview
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRenderer
import org.koin.compose.koinInject

/** Catalog changes invalidate pending cards; saved results remain readable without executing JS. */
@Composable
fun rememberPluginToolRenderer(tool: UIMessagePart.Tool): ToolUIRenderer? {
    if (!tool.toolName.startsWith("p_")) return null
    val saved = tool.details
    val savedTitle = (saved?.get("title") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    if (savedTitle != null) {
        return remember(tool.toolName, saved) {
            PluginToolRenderer(tool.toolName, savedTitle, saved)
        }
    }
    val manager: RikkaPluginManager = koinInject()
    val state by manager.state.collectAsStateWithLifecycle()
    LaunchedEffect(manager) {
        try {
            manager.host()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
        }
    }
    val host = (state as? PluginHostLoad.Ready)?.host ?: return null
    val catalog by host.state.collectAsStateWithLifecycle()
    val declared = catalog.preferred.values.asSequence().mapNotNull { catalog.packages[it] }
        .filter { it.enabled }.flatMap { installed ->
            installed.manifest.tools.asSequence().map { installed.manifest.id to it }
        }.firstOrNull { (id, declaration) -> pluginToolName(id, declaration.id) == tool.toolName }?.second
    val title = declared?.title
    return remember(tool.toolName, title, saved, catalog.revision) {
        title?.let { PluginToolRenderer(tool.toolName, it, saved) }
    }
}

private class PluginToolRenderer(
    override val toolName: String,
    private val label: String,
    private val saved: JsonObject?,
) : ToolUIRenderer {
    @Composable override fun title(context: ToolUIContext): String = label
    override fun hasSummary(context: ToolUIContext): Boolean = context.tool.isExecuted && saved != null

    @Composable override fun Summary(context: ToolUIContext) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            context.tool.output.filterIsInstance<UIMessagePart.Text>().forEach { Text(it.text) }
        }
    }

    @Composable override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        DefaultToolPreview(context)
    }
}
