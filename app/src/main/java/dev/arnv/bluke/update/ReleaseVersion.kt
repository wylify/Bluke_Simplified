package dev.arnv.bluke.update

/**
 * Numeric parts of a version or tag such as "v1.2.0" or "1.2.0-beta", or null when it has none.
 * Anything after a '-' or '+' is ignored; GitHub's latest-release endpoint never returns pre-releases.
 */
fun parseVersion(raw: String): List<Int>? {
    val core = raw.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
    if (core.isEmpty()) return null
    val parts = core.split('.').map { it.toIntOrNull() ?: return null }
    return parts.ifEmpty { null }
}

/** True when [candidate] is a strictly newer version than [current]. Unreadable versions are never newer. */
fun isNewerVersion(candidate: String, current: String): Boolean {
    val new = parseVersion(candidate) ?: return false
    val old = parseVersion(current) ?: return false
    for (i in 0 until maxOf(new.size, old.size)) {
        val a = new.getOrElse(i) { 0 }
        val b = old.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}

/** "v1.2.0" -> "1.2.0", for showing to people and for comparing skipped versions. */
fun displayVersion(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")
