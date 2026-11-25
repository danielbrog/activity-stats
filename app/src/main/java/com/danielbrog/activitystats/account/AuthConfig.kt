package com.danielbrog.activitystats.account

import com.danielbrog.activitystats.BuildConfig
import java.net.URI
import java.net.URLEncoder

/**
 * Where the app signs in and syncs: the Activity Stats backend's Cognito sign-in page, the
 * app's client id there, and the sync API. Read from local.properties at build time (see
 * secrets.defaults.properties). None of it is secret.
 */
data class AuthConfig(
    /** The hosted sign-in page's base address, like https://x.auth.us-east-2.amazoncognito.com. */
    val domain: String,
    val clientId: String,
    /** The sync API's base address. */
    val apiUrl: String,
) {
    val authorizationEndpoint: String get() = "$domain/oauth2/authorize"
    val tokenEndpoint: String get() = "$domain/oauth2/token"
    val revokeEndpoint: String get() = "$domain/oauth2/revoke"

    /** Clears Cognito's own sign-in cookie, so the next sign-in asks for an account again. */
    val logoutEndpoint: String get() = "$domain/logout"

    /** The query Cognito's logout page needs to send the user back to the app. */
    val logoutParameters: Map<String, String>
        get() = mapOf("client_id" to clientId, "logout_uri" to SIGN_OUT_REDIRECT)

    /** The full logout address, for tests and logs. */
    fun logoutUrl(): String = logoutEndpoint + "?" + logoutParameters.entries.joinToString("&") { (k, v) ->
        "$k=${URLEncoder.encode(v, Charsets.UTF_8.name())}"
    }

    companion object {
        /** Must match SIGN_IN_CALLBACK and SIGN_OUT_CALLBACK in backend/lib/activity-stats-stack.ts. */
        const val REDIRECT = "com.danielbrog.activitystats://auth/callback"
        const val SIGN_OUT_REDIRECT = "com.danielbrog.activitystats://auth/signout"
        val SCOPES = listOf("openid", "email", "profile")

        /** This build's settings, or null when sign-in isn't set up in it. */
        val current: AuthConfig? by lazy {
            from(BuildConfig.COGNITO_DOMAIN, BuildConfig.COGNITO_CLIENT_ID, BuildConfig.SYNC_API_URL)
        }

        /**
         * Checks the three settings, and returns null if any is missing or isn't usable: blank,
         * a placeholder, or an address that isn't https. Trailing slashes are dropped.
         */
        fun from(domain: String?, clientId: String?, apiUrl: String?): AuthConfig? {
            val d = httpsBase(domain) ?: return null
            val api = httpsBase(apiUrl) ?: return null
            val id = clientId?.trim().orEmpty()
            if (!CLIENT_ID.matches(id)) return null
            return AuthConfig(d, id, api)
        }

        private val CLIENT_ID = Regex("^[A-Za-z0-9]{1,128}$")

        private fun httpsBase(value: String?): String? {
            val text = value?.trim()?.trimEnd('/') ?: return null
            if (text.isEmpty()) return null
            val uri = try {
                URI(text)
            } catch (_: Exception) {
                return null
            }
            if (uri.scheme != "https" || uri.host.isNullOrEmpty()) return null
            if (uri.rawQuery != null || uri.rawFragment != null) return null
            return text
        }
    }
}
