package com.jusika.app

import java.net.URI
import java.net.URLDecoder

/** Local QR payload only; never a deep link, HTTP request, log message or saved activity state. */
class MobileSetup private constructor(val endpoint: String, val token: String) {
    override fun toString() = "MobileSetup(credentials=redacted)"

    companion object {
        fun parse(raw: String): MobileSetup {
            require(raw.length in 1..2048)
            val uri = URI(raw)
            require(uri.scheme == "jusika" && uri.host == "setup" && uri.userInfo == null &&
                uri.port == -1 && uri.fragment == null && uri.path.isNullOrEmpty())
            val pairs = requireNotNull(uri.rawQuery).split('&').map { field ->
                val pair = field.split('=', limit = 2)
                require(pair.size == 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
            }
            require(pairs.size == 3 && pairs.map { it.first }.toSet() == setOf("v", "endpoint", "token"))
            val values = pairs.toMap()
            require(values["v"] == "1")
            // Registration QR never permits cleartext, even in debug builds.
            val endpoint = AgentEndpoint.validate(requireNotNull(values["endpoint"]), debug = false)
            val token = requireNotNull(values["token"])
            require(Regex("[a-zA-Z0-9_-]{32,256}").matches(token))
            return MobileSetup(endpoint, token)
        }
    }
}
