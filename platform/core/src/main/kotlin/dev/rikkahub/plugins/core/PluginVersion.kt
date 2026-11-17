package dev.rikkahub.plugins.core

/** Strict SemVer precedence. Numeric components are compared without a machine-integer limit. */
class PluginVersion private constructor(private val core: List<String>, private val pre: List<String>,) :
    Comparable<PluginVersion> {
    val prerelease: Boolean get() = pre.isNotEmpty()

    override fun compareTo(other: PluginVersion): Int {
        core.zip(other.core).forEach { (a, b) -> numeric(a, b).let { if (it != 0) return it } }
        if (pre.isEmpty() || other.pre.isEmpty()) {
            return when {
                pre.isEmpty() && other.pre.isEmpty() -> 0
                pre.isEmpty() -> 1
                else -> -1
            }
        }
        pre.zip(other.pre).forEach { (a, b) ->
            val aNumber = a.all(Char::isDigit)
            val bNumber = b.all(Char::isDigit)
            val order = when {
                aNumber && bNumber -> numeric(a, b)
                aNumber -> -1
                bNumber -> 1
                else -> a.compareTo(b)
            }
            if (order != 0) return order
        }
        return pre.size.compareTo(other.pre.size)
    }

    companion object {
        private val syntax =
            Regex(
                "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
            )
        fun parse(value: String): PluginVersion {
            require(value.length in 1..80) { "Invalid plugin version" }
            val match = requireNotNull(syntax.matchEntire(value)) { "Invalid plugin version" }
            val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            require(pre.none { it.length > 1 && it.all(Char::isDigit) && it.startsWith('0') }) {
                "Noncanonical prerelease version"
            }
            return PluginVersion(match.groupValues.subList(1, 4), pre)
        }
        private fun numeric(a: String, b: String): Int =
            a.length.compareTo(b.length).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
