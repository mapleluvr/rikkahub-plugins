package me.rerere.rikkahub.plugins

import android.content.Context
import dev.rikkahub.plugins.runtime.PluginHost
import dev.rikkahub.plugins.core.GitHubPluginResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

sealed interface PluginHostLoad {
    data object Loading : PluginHostLoad
    data class Ready(val host: PluginHost) : PluginHostLoad
    data class Failed(val message: String) : PluginHostLoad
}

/** Application-owned initialization survives page disposal; failed package loading leaves ordinary chat usable. */
class RikkaPluginManager(context: Context, scope: CoroutineScope) {
    val githubCredentials = PluginGitHubCredentials(context)
    val github = GitHubPluginResolver(GitHubPluginHttp(githubCredentials::read))
    private val mutable = MutableStateFlow<PluginHostLoad>(PluginHostLoad.Loading)
    val state = mutable.asStateFlow()
    private val initialization = scope.async(start = CoroutineStart.LAZY) {
        try {
            PluginHost.open(context, File(context.filesDir, "plugin-host")).also {
                mutable.value = PluginHostLoad.Ready(it)
            }
        } catch (error: Exception) {
            mutable.value = PluginHostLoad.Failed(error.message ?: "Plugin host unavailable")
            throw error
        }
    }

    suspend fun host(): PluginHost = initialization.await()
}
