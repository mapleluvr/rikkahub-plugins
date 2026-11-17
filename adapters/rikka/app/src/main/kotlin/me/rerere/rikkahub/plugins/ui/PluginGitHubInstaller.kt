package me.rerere.rikkahub.plugins.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.rikkahub.plugins.core.GitHubPluginSource
import dev.rikkahub.plugins.core.PluginReleaseDiscovery
import dev.rikkahub.plugins.runtime.PluginHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.plugins.RikkaPluginManager
import org.koin.compose.koinInject

@Composable
internal fun PluginGitHubInstaller(
    host: PluginHost,
    repository: String,
    enabled: Boolean,
    prepared: (PluginHost.Candidate) -> Unit
) {
    val manager: RikkaPluginManager = koinInject()
    val scope = rememberCoroutineScope()
    var source by remember(repository) { mutableStateOf(repository) }
    var prerelease by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var credentials by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<PluginReleaseDiscovery?>(null) }
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
    PluginCard(stringResource(R.string.plugin_host_github)) {
        OutlinedTextField(
            value = source,
            onValueChange = {
                source = it
                result = null
            },
            label = { Text(stringResource(R.string.plugin_host_repository)) },
            singleLine = true,
            enabled =
            enabled && !busy
        )
        Column {
            Text(stringResource(R.string.plugin_host_prerelease))
            Checkbox(checked = prerelease, onCheckedChange = {
                prerelease = it
                result = null
            }, enabled =
                enabled && !busy)
        }
        Button(
            onClick = {
                action {
                    result = null
                    result =
                        manager.github.discover(GitHubPluginSource.parse(source), prerelease)
                }
            },
            enabled = enabled && !busy && source.isNotBlank()
        ) {
            Text(stringResource(R.string.plugin_host_find_releases))
        }
        if (busy) Text(stringResource(R.string.plugin_host_downloading))
        error?.let { Text(it) }
        result?.let { found ->
            Text(stringResource(R.string.plugin_host_scan_count, found.releasesChecked))
            if (found.truncated) Text(stringResource(R.string.plugin_host_scan_truncated))
            if (found.candidates.isEmpty()) Text(stringResource(R.string.plugin_host_no_release))
            found.rejected.forEach { Text(it) }
            for (candidate in found.candidates) {
                Text("${candidate.entry.id} · ${candidate.entry.version}")
                if (candidate.notes.isNotBlank()) Text(candidate.notes)
                Text(
                    candidate.origin.publisherKey?.let { stringResource(R.string.plugin_host_signature, it) }
                        ?: stringResource(R.string.plugin_host_unsigned)
                )
                OutlinedButton(onClick = {
                    action {
                        prepared(host.prepareRelease(manager.github.download(candidate), candidate))
                    }
                }, enabled = enabled && !busy) { Text(stringResource(R.string.plugin_host_download)) }
            }
        }
        TextButton(onClick = { credentials = !credentials }) { Text(stringResource(R.string.plugin_host_credentials)) }
        if (credentials) {
            OutlinedTextField(
                value = token,
                onValueChange = {
                    token = it
                    saved = false
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = {
                    Text(stringResource(R.string.plugin_host_token))
                }
            )
            OutlinedButton(onClick = {
                action {
                    manager.githubCredentials.save(token)
                    token = ""
                    saved = true
                }
            }, enabled = !busy) {
                Text(stringResource(R.string.plugin_host_save_token))
            }
            if (saved) Text(stringResource(R.string.plugin_host_token_saved))
        }
    }
}
