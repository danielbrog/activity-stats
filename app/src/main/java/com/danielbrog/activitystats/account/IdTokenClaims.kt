package com.danielbrog.activitystats.account

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Who is signed in, read from the ID token Cognito returned. The token isn't verified here:
 * it came straight from Cognito's token endpoint over HTTPS, and it's only used for showing
 * the account and tagging rides. The backend verifies every token it receives.
 */
data class IdTokenClaims(
    /** The account's permanent id; the same value the backend files rides under. */
    val userId: String,
    val email: String?,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Reads the claims from a JWT, or returns null if it isn't one or has no subject. */
        fun parse(idToken: String?): IdTokenClaims? {
            val parts = idToken?.split('.') ?: return null
            if (parts.size != 3) return null
            val payload: JsonObject = try {
                val text = String(Base64.getUrlDecoder().decode(parts[1].trimEnd('=')), Charsets.UTF_8)
                json.parseToJsonElement(text).jsonObject
            } catch (_: Exception) {
                return null
            }
            val sub = payload.string("sub")?.takeIf { it.isNotBlank() } ?: return null
            return IdTokenClaims(sub, payload.string("email")?.takeIf { it.isNotBlank() })
        }

        private fun JsonObject.string(key: String): String? = try {
            this[key]?.jsonPrimitive?.contentOrNull
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
