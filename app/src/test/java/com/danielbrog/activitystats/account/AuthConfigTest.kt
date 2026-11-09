package com.danielbrog.activitystats.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AuthConfigTest {

    private val domain = "https://activity-stats-123.auth.us-east-2.amazoncognito.com"
    private val api = "https://abc123.execute-api.us-east-2.amazonaws.com"
    private val client = "1example2client3id4abc"

    @Test
    fun `valid settings give the Cognito endpoints`() {
        val config = AuthConfig.from(domain, client, api)!!
        assertEquals("$domain/oauth2/authorize", config.authorizationEndpoint)
        assertEquals("$domain/oauth2/token", config.tokenEndpoint)
        assertEquals("$domain/oauth2/revoke", config.revokeEndpoint)
        assertEquals("$domain/logout", config.logoutEndpoint)
        assertEquals(api, config.apiUrl)
    }

    @Test
    fun `trailing slashes and spaces are dropped`() {
        val config = AuthConfig.from(" $domain/ ", " $client ", "$api/")!!
        assertEquals(domain, config.domain)
        assertEquals(client, config.clientId)
        assertEquals(api, config.apiUrl)
    }

    @Test
    fun `missing or blank settings mean sign-in isn't set up`() {
        assertNull(AuthConfig.from("", client, api))
        assertNull(AuthConfig.from(domain, "", api))
        assertNull(AuthConfig.from(domain, client, ""))
        assertNull(AuthConfig.from(null, null, null))
        // A copied placeholder isn't a real client id.
        assertNull(AuthConfig.from(domain, "DEFAULT_API_KEY", api))
    }

    @Test
    fun `only https addresses without a query are accepted`() {
        assertNull(AuthConfig.from("http://example.com", client, api))
        assertNull(AuthConfig.from(domain, client, "ftp://example.com"))
        assertNull(AuthConfig.from("not a url", client, api))
        assertNull(AuthConfig.from("$domain?x=1", client, api))
        assertNull(AuthConfig.from(domain, client, "$api#top"))
    }

    @Test
    fun `client ids with odd characters are refused`() {
        assertNull(AuthConfig.from(domain, "abc def", api))
        assertNull(AuthConfig.from(domain, "abc&x=1", api))
        assertNotNull(AuthConfig.from(domain, "ABCdef123", api))
    }

    @Test
    fun `logout sends the user back to the app`() {
        val config = AuthConfig.from(domain, client, api)!!
        assertEquals(
            "$domain/logout?client_id=$client&logout_uri=com.danielbrog.activitystats%3A%2F%2Fauth%2Fsignout",
            config.logoutUrl(),
        )
    }

    @Test
    fun `redirects match the backend's allowed callbacks`() {
        // Must equal SIGN_IN_CALLBACK and SIGN_OUT_CALLBACK in backend/lib/activity-stats-stack.ts.
        assertEquals("com.danielbrog.activitystats://auth/callback", AuthConfig.REDIRECT)
        assertEquals("com.danielbrog.activitystats://auth/signout", AuthConfig.SIGN_OUT_REDIRECT)
    }
}
