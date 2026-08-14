package de.tankradar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ServerConfig.normalize] has no Android dependencies, so the address parsing
 * that decides whether the app can reach the user's server is covered by a plain
 * JVM test: run with `./gradlew :app:testDebugUnitTest`.
 */
class ServerConfigTest {

    @Test
    fun `bare host and port gets an http scheme`() {
        assertEquals("http://192.168.1.20:8050", ServerConfig.normalize("192.168.1.20:8050"))
    }

    @Test
    fun `missing port falls back to the dash default`() {
        assertEquals("http://192.168.1.20:8050", ServerConfig.normalize("192.168.1.20"))
    }

    @Test
    fun `https without a port keeps the implicit 443`() {
        assertEquals("https://tankradar.example.com", ServerConfig.normalize("https://tankradar.example.com"))
    }

    @Test
    fun `existing scheme and port are preserved`() {
        assertEquals("http://tankradar.local:9000", ServerConfig.normalize("http://tankradar.local:9000"))
    }

    @Test
    fun `surrounding whitespace and trailing slashes are ignored`() {
        assertEquals("http://192.168.1.20:8050", ServerConfig.normalize("  192.168.1.20:8050/  "))
    }

    @Test
    fun `empty input is rejected`() {
        assertNull(ServerConfig.normalize("   "))
    }

    @Test
    fun `a path is rejected because this is a base address`() {
        assertNull(ServerConfig.normalize("192.168.1.20:8050/dashboard"))
    }

    @Test
    fun `a non-numeric port is rejected`() {
        assertNull(ServerConfig.normalize("192.168.1.20:abc"))
    }

    @Test
    fun `an out-of-range port is rejected`() {
        assertNull(ServerConfig.normalize("192.168.1.20:70000"))
    }

    @Test
    fun `a missing host is rejected`() {
        assertNull(ServerConfig.normalize("http://:8050"))
    }
}
