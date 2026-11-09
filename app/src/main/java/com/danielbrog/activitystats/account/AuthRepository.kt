package com.danielbrog.activitystats.account

import android.content.Context
import androidx.core.net.toUri
import android.util.Log
import com.danielbrog.activitystats.data.ActivityRepository
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.EndSessionRequest
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse

/** Whether, and as whom, the app is signed in. */
sealed interface AccountState {
    /** This build has no backend settings (see secrets.defaults.properties). */
    data object NotConfigured : AccountState
    /** Reading the saved sign-in at start-up. */
    data object Loading : AccountState
    data object SignedOut : AccountState
    data class SignedIn(val userId: String, val email: String?) : AccountState
}

/** A sign-in that failed, with a message fit to show. */
class SignInException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Signing in and out with the backend's Cognito sign-in page, using AppAuth (authorization
 * code flow with PKCE), and keeping the tokens fresh. One per app; the tokens are saved
 * encrypted by [AuthStateStore].
 *
 * Rides follow the account: signing in claims every ride on the phone with no owner yet, and
 * while signed in new rides are tagged with the account (see [ActivityRepository]).
 */
class AuthRepository(
    context: Context,
    private val config: AuthConfig?,
    private val store: AuthStateStore,
    private val rides: () -> ActivityRepository,
    scope: CoroutineScope,
) {
    private val context = context.applicationContext

    /** Guards [authState]: AppAuth's AuthState isn't safe to use from two places at once. */
    private val mutex = Mutex()
    private var authState: AuthState? = null

    private val _state = MutableStateFlow(if (config == null) AccountState.NotConfigured else AccountState.Loading)
    val state: StateFlow<AccountState> = _state.asStateFlow()

    /** The signed-in account's id, for tagging new rides; null when signed out. */
    val signedInUserId: String? get() = (_state.value as? AccountState.SignedIn)?.userId

    init {
        if (config != null) scope.launch {
            mutex.withLock {
                authState = store.load()
                    ?.let { runCatching { AuthState.jsonDeserialize(it) }.getOrNull() }
                    ?.takeIf { it.refreshToken != null }
                _state.value = stateOf(authState)
            }
            // Rides started before the saved sign-in finished loading still belong to this account.
            claimRides()
        }
    }

    /** What to send the user to Cognito's sign-in page with. */
    fun authorizationRequest(): AuthorizationRequest {
        val c = config ?: throw SignInException("Sign-in isn't set up in this build.")
        return AuthorizationRequest.Builder(serviceConfiguration(c), c.clientId, ResponseTypeValues.CODE, AuthConfig.REDIRECT.toUri())
            .setScopes(AuthConfig.SCOPES)
            // PKCE and state already protect the code flow. Without a nonce, AppAuth's ID token
            // check can't trip on refreshed tokens, which Cognito may issue with or without one.
            .setNonce(null)
            .build()
    }

    /**
     * What to send the user to Cognito's logout page with, which clears its sign-in cookie so
     * the next sign-in asks for an account. Null when sign-in isn't set up.
     */
    fun endSessionRequest(): EndSessionRequest? {
        val c = config ?: return null
        return EndSessionRequest.Builder(serviceConfiguration(c))
            .setPostLogoutRedirectUri(AuthConfig.SIGN_OUT_REDIRECT.toUri())
            .setAdditionalParameters(c.logoutParameters)
            // Cognito's logout doesn't send a state back, so don't expect one.
            .setState(null)
            .build()
    }

    /**
     * Finishes a sign-in from what the sign-in page returned: trades the code for tokens,
     * saves them and claims this phone's unowned rides. Returns how many rides were claimed,
     * or null if the user closed the page. Throws [SignInException] on failure.
     */
    suspend fun completeSignIn(response: AuthorizationResponse?, error: AuthorizationException?): Int? {
        if (response == null) {
            if (error == null || error.code == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code) return null
            throw SignInException(messageFor(error), error)
        }
        val tokens: TokenResponse = try {
            withService { service ->
                suspendCancellableCoroutine<TokenResponse> { cont ->
                    service.performTokenRequest(response.createTokenExchangeRequest()) { result, ex ->
                        if (result != null) cont.resume(result) else cont.resumeWithException(ex ?: IOException("No tokens"))
                    }
                }
            }
        } catch (e: AuthorizationException) {
            throw SignInException(messageFor(e), e)
        } catch (e: IOException) {
            throw SignInException(NETWORK_MESSAGE, e)
        }
        val claims = IdTokenClaims.parse(tokens.idToken)
            ?: throw SignInException("Sign-in didn't return an account. Please try again.")

        mutex.withLock {
            val signedIn = AuthState(response, tokens, null)
            store.save(signedIn.jsonSerializeString())
            authState = signedIn
            _state.value = AccountState.SignedIn(claims.userId, claims.email)
        }
        return claimRides()
    }

    /**
     * A valid access token for calling the sync API, refreshed first if it is about to
     * expire, or null when signed out. If Cognito no longer accepts the sign-in (it expired
     * after 30 days unused, or was revoked), signs out. Throws IOException when offline.
     */
    suspend fun freshAccessToken(): String? = mutex.withLock {
        val current = authState ?: return@withLock null
        try {
            val token = withService { service ->
                suspendCancellableCoroutine<String> { cont ->
                    current.performActionWithFreshTokens(service) { accessToken, _, ex ->
                        if (accessToken != null) cont.resume(accessToken) else cont.resumeWithException(ex ?: IOException("No token"))
                    }
                }
            }
            store.save(current.jsonSerializeString())
            token
        } catch (e: AuthorizationException) {
            if (e.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR) {
                Log.i(TAG, "Sign-in no longer accepted; signing out", e)
                forget()
                null
            } else {
                throw IOException(messageFor(e), e)
            }
        }
    }

    /**
     * Signs out on this phone and asks Cognito to cancel the sign-in. Rides stay on the phone,
     * still filed under the account. The caller should then open [endSessionRequest].
     */
    suspend fun signOut() {
        val refreshToken = mutex.withLock {
            authState?.refreshToken.also { forget() }
        }
        val c = config ?: return
        if (refreshToken != null) {
            try {
                AccountHttp.revoke(c, refreshToken)
            } catch (e: IOException) {
                // Signed out here regardless; the token would expire on its own in 30 days.
                Log.w(TAG, "Couldn't cancel the sign-in with Cognito", e)
            }
        }
    }

    /** Clears the sign-in. Call with [mutex] held. */
    private suspend fun forget() {
        store.clear()
        authState = null
        _state.value = AccountState.SignedOut
    }

    /** Claims the phone's unowned rides for the signed-in account. Returns how many. */
    private suspend fun claimRides(): Int {
        val userId = signedInUserId ?: return 0
        return try {
            rides().claimUnowned(userId)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't link rides to the account", e)
            0
        }
    }

    private inline fun <T> withService(block: (AuthorizationService) -> T): T {
        val service = AuthorizationService(context)
        try {
            return block(service)
        } finally {
            service.dispose()
        }
    }

    private companion object {
        const val TAG = "AuthRepository"
        const val NETWORK_MESSAGE = "Couldn't reach the sign-in page. Check your connection and try again."

        fun serviceConfiguration(c: AuthConfig) = AuthorizationServiceConfiguration(
            c.authorizationEndpoint.toUri(),
            c.tokenEndpoint.toUri(),
            null,
            c.logoutEndpoint.toUri(),
        )

        fun stateOf(authState: AuthState?): AccountState {
            val claims = IdTokenClaims.parse(authState?.idToken) ?: return AccountState.SignedOut
            return AccountState.SignedIn(claims.userId, claims.email)
        }

        fun messageFor(e: AuthorizationException): String = when (e.type) {
            AuthorizationException.TYPE_GENERAL_ERROR ->
                if (e.code == AuthorizationException.GeneralErrors.NETWORK_ERROR.code) NETWORK_MESSAGE
                else "Sign-in didn't finish. Please try again."
            else -> "Sign-in didn't finish (${e.error ?: "error ${e.code}"}). Please try again."
        }
    }
}
