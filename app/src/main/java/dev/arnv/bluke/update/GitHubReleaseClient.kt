package dev.arnv.bluke.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

const val UPDATE_REPOSITORY = "wylify/Bluke_Simplified"
private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$UPDATE_REPOSITORY/releases/latest"
private const val TIMEOUT_MILLIS = 10_000

data class GitHubRelease(
    /** Version without the leading "v", e.g. "1.2.0". */
    val version: String,
    /** The release page on github.com. */
    val pageUrl: String,
    /** Direct link to the release's .apk, if it has one. */
    val apkUrl: String?,
    val notes: String,
) {
    val downloadUrl: String get() = apkUrl ?: pageUrl
}

fun interface ReleaseSource {
    /** The latest published release, or null when it could not be fetched or read. */
    suspend fun fetchLatest(): GitHubRelease?
}

/** Reads the body of GitHub's "latest release" response. Returns null when required fields are missing. */
fun parseGitHubRelease(json: String): GitHubRelease? = try {
    val root = JSONObject(json)
    val tag = root.optString("tag_name").takeIf { it.isNotBlank() && parseVersion(it) != null }
    val page = root.optString("html_url").takeIf { it.startsWith("https://") }
    if (tag == null || page == null) {
        null
    } else {
        val assets = root.optJSONArray("assets")
        var apk: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val url = asset.optString("browser_download_url")
                if (asset.optString("name").endsWith(".apk", ignoreCase = true) && url.startsWith("https://")) {
                    apk = url
                    break
                }
            }
        }
        GitHubRelease(
            version = displayVersion(tag),
            pageUrl = page,
            apkUrl = apk,
            notes = if (root.isNull("body")) "" else root.optString("body"),
        )
    }
} catch (_: org.json.JSONException) {
    null
}

/** Turns the release's Markdown body into short plain text for the update dialog. */
fun plainReleaseNotes(markdown: String): String =
    markdown.lineSequence()
        .map { line ->
            line.trim()
                .replace(Regex("""\[([^]]*)]\([^)]*\)"""), "$1")
                .replace("**", "")
                .replace("__", "")
                .replace(Regex("""^#+\s*"""), "")
                .replace(Regex("""^[-*]\s+"""), "• ")
        }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

/** Asks the GitHub API for this fork's latest release. Sends no data besides the request itself. */
object GitHubReleaseClient : ReleaseSource {
    override suspend fun fetchLatest(): GitHubRelease? = withContext(Dispatchers.IO) {
        val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "SimplyBluke-UpdateCheck")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            parseGitHubRelease(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
