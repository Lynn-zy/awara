package me.rerere.awara.util

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private val iwaraIdentifier = Regex("[A-Za-z0-9._~-]+")

internal fun parseIwaraRoute(url: String): String? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
    if (uri.host?.lowercase() !in setOf("iwara.tv", "www.iwara.tv")) return null
    if (uri.rawUserInfo != null || uri.port !in setOf(-1, 80, 443)) return null
    val segments = uri.rawPath.orEmpty().trim('/').split('/')
    val identifier =
        segments
            .getOrNull(1)
            ?.let {
                runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrNull()
            }
            ?.takeIf { it.matches(iwaraIdentifier) && it != "." && it != ".." } ?: return null
    return when (segments.firstOrNull()) {
        "video" -> "video/$identifier"
        "image" -> "image/$identifier"
        "playlist" -> "playlist/$identifier"
        "profile",
        "user" ->
            if (segments.getOrNull(2) == "playlists") "user/$identifier/playlists"
            else "user/$identifier"
        else -> null
    }
}
