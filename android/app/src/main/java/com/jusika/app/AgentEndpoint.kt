package com.jusika.app

import java.net.URI

object AgentEndpoint {
    fun validate(raw: String, debug: Boolean): String {
        val uri = URI(raw.trim())
        require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(uri.path.isNullOrEmpty() || uri.path == "/")
        require(uri.port == -1 || uri.port in 1..65535)
        val local = uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
        require(uri.scheme == "https" || (debug && local && uri.scheme == "http"))
        return raw.trim().trimEnd('/')
    }
}
