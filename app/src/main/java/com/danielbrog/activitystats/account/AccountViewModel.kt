package com.danielbrog.activitystats.account

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.danielbrog.activitystats.ActivityStatsApp
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService

/** Whether the backend accepts the sign-in, checked when the Account screen opens. */
sealed interface Connection {
    data object Checking : Connection
    data object Connected : Connection
    data class Failed(val message: String) : Connection
}

/** The Account screen: sign in, sign out, and a check that the backend accepts the sign-in. */
class AccountViewModel(application: Application) : AndroidViewModel(application) {

    private val auth = (application as ActivityStatsApp).authRepository

    val account: StateFlow<AccountState> = auth.state

    private val _busy = MutableStateFlow(false)
    /** True while signing in or out, so the buttons can't start a second one. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _connection = MutableStateFlow<Connection?>(null)
    val connection: StateFlow<Connection?> = _connection.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** A one-off message for a snackbar; call [messageShown] once it is on screen. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Opens the sign-in and logout pages in a Chrome tab. Made on first use. */
    private val service by lazy { AuthorizationService(application) }
    private var serviceMade = false

    init {
        viewModelScope.launch {
            auth.state
                .distinctUntilChangedBy { (it as? AccountState.SignedIn)?.userId }
                .collect { state -> if (state is AccountState.SignedIn) checkConnection() else _connection.value = null }
        }
    }

    /** The intent that opens Cognito's sign-in page, or null (with a message) if it can't. */
    fun signInIntent(): Intent? = try {
        serviceMade = true
        service.getAuthorizationRequestIntent(auth.authorizationRequest())
    } catch (e: SignInException) {
        _message.value = e.message
        null
    } catch (e: ActivityNotFoundException) {
        _message.value = NO_BROWSER
        null
    } catch (e: RuntimeException) {
        Log.e(TAG, "Couldn't open the sign-in page", e)
        _message.value = NO_BROWSER
        null
    }

    /** Handles what the sign-in page sent back. */
    fun onSignInResult(data: Intent?) {
        val response = data?.let { AuthorizationResponse.fromIntent(it) }
        val error = data?.let { AuthorizationException.fromIntent(it) }
        if (response == null && error == null) return // closed without an answer
        _busy.value = true
        viewModelScope.launch {
            try {
                val claimed = auth.completeSignIn(response, error) ?: return@launch
                _message.value = when (claimed) {
                    0 -> "Signed in."
                    1 -> "Signed in. 1 ride on this phone is now linked to your account."
                    else -> "Signed in. $claimed rides on this phone are now linked to your account."
                }
            } catch (e: SignInException) {
                Log.w(TAG, "Sign-in failed", e)
                _message.value = e.message
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Signs out, then hands [onLogoutPage] the intent that opens Cognito's logout page (to
     * clear its sign-in cookie), or null if it can't be opened. Signed out either way.
     */
    fun signOut(onLogoutPage: (Intent?) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val request = auth.endSessionRequest()
            auth.signOut()
            _busy.value = false
            _message.value = "Signed out. Your rides stay on this phone."
            val intent = request?.let {
                try {
                    serviceMade = true
                    service.getEndSessionRequestIntent(it)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Couldn't open the logout page", e)
                    null
                }
            }
            onLogoutPage(intent)
        }
    }

    /** Calls the sync API with the sign-in, to show whether the backend accepts it. */
    fun checkConnection() {
        val config = AuthConfig.current ?: return
        if (_connection.value == Connection.Checking) return
        _connection.value = Connection.Checking
        viewModelScope.launch {
            _connection.value = try {
                val token = auth.freshAccessToken()
                if (token == null) {
                    null // signed out meanwhile
                } else {
                    AccountHttp.checkConnection(config, token)
                    Connection.Connected
                }
            } catch (e: AccountHttp.HttpException) {
                Log.w(TAG, "Connection check failed", e)
                Connection.Failed(
                    if (e.code == 401 || e.code == 403) "The server didn't accept your sign-in."
                    else "The server had a problem (error ${e.code})."
                )
            } catch (e: IOException) {
                Log.w(TAG, "Connection check failed", e)
                Connection.Failed("Couldn't reach the server. Check your connection.")
            }
        }
    }

    fun messageShown() {
        _message.value = null
    }

    override fun onCleared() {
        if (serviceMade) service.dispose()
    }

    private companion object {
        const val TAG = "Account"
        const val NO_BROWSER = "Couldn't open the sign-in page. Is a web browser installed?"
    }
}
