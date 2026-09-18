package com.jusika.app

import org.junit.Assert.*
import org.junit.Test

class AgentEndpointTest {
    @Test fun productionAllowsOnlyHttpsOrigin() {
        assertEquals("https://agent.example.com", AgentEndpoint.validate("https://agent.example.com/", false))
    }
    @Test fun debugHttpIsLoopbackOnly() {
        assertEquals("http://127.0.0.1:8000", AgentEndpoint.validate("http://127.0.0.1:8000", true))
        assertEquals("http://10.0.2.2:8000", AgentEndpoint.validate("http://10.0.2.2:8000", true))
    }
    @Test fun dangerousOrAmbiguousOriginsAreRejected() {
        listOf("http://agent.example.com", "https://user:secret@agent.example.com", "https://agent.example.com/path",
            "https://agent.example.com?key=secret", "https://agent.example.com#fragment", "file:///tmp/a",
            "http://192.168.1.2:8000", "https://agent.example.com:99999").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { AgentEndpoint.validate(it, true) }
        }
        assertThrows(IllegalArgumentException::class.java) { AgentEndpoint.validate("http://127.0.0.1:8000", false) }
    }
}
