package de.tankradar.app

import android.content.Context
import androidx.core.content.edit

/**
 * Stores the address of the TankRadar server this device should talk to.
 *
 * The app is a client for a TankRadar instance the user runs themselves (a PC on
 * the same network, or a small server), so the address cannot be compiled in.
 */
object ServerConfig {

    private const val PREFS = "tankradar_prefs"
    private const val KEY_SERVER_URL = "server_url"

    /** The stored base URL, or null while the app has never been set up. */
    fun getServerUrl(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, null)
            ?.takeIf { it.isNotBlank() }

    fun setServerUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_SERVER_URL, url) }
    }

    /**
     * Turn what the user typed into a base URL, or return null if it cannot be one.
     *
     * Accepts "192.168.1.20:8050", "http://192.168.1.20:8050", "tankradar.local"
     * and "https://tankradar.example.com". A missing scheme becomes http:// and a
     * missing port becomes the Dash default 8050, because that is what
     * TANKRADAR_DASH_PORT defaults to.
     */
    fun normalize(rawInput: String): String? {
        var value = rawInput.trim().trimEnd('/')
        if (value.isEmpty()) return null

        val hasScheme = value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        if (!hasScheme) {
            value = "http://$value"
        }

        val separator = value.indexOf("://") + 3
        val authority = value.substring(separator)
        if (authority.isEmpty() || authority.startsWith(":")) return null
        // Reject anything with a path, query or fragment: this is a base address.
        if (authority.any { it == '/' || it == '?' || it == '#' }) return null

        val isHttps = value.startsWith("https://", ignoreCase = true)
        val hostAndPort = if (authority.contains(':')) {
            val port = authority.substringAfterLast(':').toIntOrNull()
            if (port == null || port !in 1..65535) return null
            authority
        } else if (isHttps) {
            authority
        } else {
            "$authority:$DEFAULT_PORT"
        }

        return value.substring(0, separator) + hostAndPort
    }

    const val DEFAULT_PORT = 8050
}
