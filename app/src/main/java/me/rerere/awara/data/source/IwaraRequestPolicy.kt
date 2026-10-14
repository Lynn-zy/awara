package me.rerere.awara.data.source

import okhttp3.HttpUrl

fun HttpUrl.isIwaraSourceEndpoint(): Boolean =
    isHttps &&
        username.isEmpty() &&
        password.isEmpty() &&
        host in setOf("files.iwara.tv", "filesq.iwara.tv") &&
        pathSegments.size == 2 &&
        pathSegments[0] == "file" &&
        pathSegments[1].isNotBlank()

fun HttpUrl.canReceiveIwaraAccessToken(): Boolean =
    isHttps &&
        username.isEmpty() &&
        password.isEmpty() &&
        (host == "api.iwara.tv" || isIwaraSourceEndpoint())
