package com.danielbrog.activitystats.account

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdTokenClaimsTest {

    private fun b64(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    /** A JWT-shaped token with [payload]; the signature isn't checked, so any will do. */
    private fun token(payload: String) = "${b64("""{"alg":"RS256"}""")}.${b64(payload)}.c2ln"

    @Test
    fun `reads the account id and email`() {
        val claims = IdTokenClaims.parse(token("""{"sub":"11111111-aaaa","email":"rider@example.com","aud":"x"}"""))
        assertEquals(IdTokenClaims("11111111-aaaa", "rider@example.com"), claims)
    }

    @Test
    fun `email is optional`() {
        assertEquals(IdTokenClaims("abc", null), IdTokenClaims.parse(token("""{"sub":"abc"}""")))
        assertEquals(IdTokenClaims("abc", null), IdTokenClaims.parse(token("""{"sub":"abc","email":""}""")))
    }

    @Test
    fun `handles padding and non-ASCII characters`() {
        val padded = "${b64("{}")}.${Base64.getUrlEncoder().encodeToString("""{"sub":"é"}""".toByteArray())}.x"
        assertEquals("é", IdTokenClaims.parse(padded)?.userId)
    }

    @Test
    fun `anything that isn't a token with a subject gives null`() {
        assertNull(IdTokenClaims.parse(null))
        assertNull(IdTokenClaims.parse(""))
        assertNull(IdTokenClaims.parse("a.b"))
        assertNull(IdTokenClaims.parse("a.!!!.c"))
        assertNull(IdTokenClaims.parse(token("not json")))
        assertNull(IdTokenClaims.parse(token("""["sub"]""")))
        assertNull(IdTokenClaims.parse(token("""{"email":"rider@example.com"}""")))
        assertNull(IdTokenClaims.parse(token("""{"sub":""}""")))
        assertNull(IdTokenClaims.parse(token("""{"sub":{"x":1}}""")))
    }
}
