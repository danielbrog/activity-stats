package com.danielbrog.activitystats.account

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The backend calls the Account screen makes. Blocking work runs on the IO dispatcher. */
object AccountHttp {

    private const val TIMEOUT_MS = 15_000

    /** A call that reached the server but didn't succeed. */
    class HttpException(val code: Int) : IOException("HTTP $code")

    /**
     * Asks the sync API for rides changed from now on, which is always an empty list. It
     * succeeding shows the sign-in token is accepted end to end. Throws on any failure.
     */
    suspend fun checkConnection(config: AuthConfig, accessToken: String, nowMillis: Long = System.currentTimeMillis()) {
        withContext(Dispatchers.IO) {
            val connection = URL("${config.apiUrl}/activities?since=$nowMillis").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                val code = connection.responseCode
                if (code !in 200..299) throw HttpException(code)
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * Asks Cognito to cancel a refresh token (and the access tokens made from it), so it is
     * useless even if a copy survived somewhere. Best effort: throws on failure.
     */
    suspend fun revoke(config: AuthConfig, refreshToken: String) {
        withContext(Dispatchers.IO) {
            val body = "token=${enc(refreshToken)}&client_id=${enc(config.clientId)}".toByteArray()
            val connection = URL(config.revokeEndpoint).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                if (code !in 200..299) throw HttpException(code)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
}
