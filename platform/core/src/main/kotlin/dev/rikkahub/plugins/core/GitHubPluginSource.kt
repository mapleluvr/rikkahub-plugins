package dev.rikkahub.plugins.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Only GitHub repository and release references are accepted; credentials and arbitrary download URLs are not. */
data class GitHubPluginSource(val repository: String, val tag: String? = null) {
    val apiPath: String get() = "repos/$repository"

    companion object {
        fun parse(text: String): GitHubPluginSource {
            val value = text.trim()
            require(value.length in 3..1024)
            val parts = if (value.startsWith("https://")) {
                val uri = URI(value)
                require(uri.host == "github.com" && uri.userInfo == null && uri.port == -1 &&
                    uri.query == null && uri.fragment == null) { "Use a GitHub repository or release URL" }
                uri.rawPath.removePrefix("/").removeSuffix("/").split('/')
            } else {
                require(!value.contains(":") && !value.contains('?') && !value.contains('#'))
                value.removeSuffix("/").split('/')
            }
            require(parts.size == 2 || parts.size >= 5 && parts[2] == "releases" && parts[3] == "tag") {
                "Use owner/repository or a GitHub release URL"
            }
            val owner = parts[0]
            val repo = parts[1].removeSuffix(".git")
            require(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}").matches(owner))
            require(Regex("[A-Za-z0-9_.-]{1,100}").matches(repo) && repo !in setOf(".", ".."))
            val tag = if (parts.size >
                2) {
                URLDecoder.decode(parts.drop(4).joinToString("/").replace("+", "%2B"), "UTF-8")
            } else {
                null
            }
            require(tag == null || tag.isNotBlank() && tag.length <= 200 && tag.none { it.isISOControl() })
            return GitHubPluginSource("$owner/$repo", tag)
        }

        internal fun encodeSegment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
