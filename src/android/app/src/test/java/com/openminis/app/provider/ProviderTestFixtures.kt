package com.openminis.app.provider

import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockWebServer

/** Avoid MockWebServer's reverse-DNS canonical hostname on Windows. */
internal fun MockWebServer.loopbackUrl(path: String): HttpUrl =
    url(path).newBuilder().host("127.0.0.1").build()
