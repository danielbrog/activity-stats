package com.danielbrog.activitystats.account

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.danielbrog.activitystats.R
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme

/**
 * The Account screen, opened from History: sign in or create an account on Cognito's page,
 * see who is signed in and whether the backend accepts it, and sign out.
 */
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountViewModel = viewModel(),
) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        viewModel.messageShown()
        snackbarHostState.showSnackbar(text)
    }

    // The sign-in page reports back here. The logout page's answer doesn't matter: the app
    // is already signed out by the time it opens.
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onSignInResult(result.data)
    }
    val logout = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    Box(modifier = modifier.fillMaxSize()) {
        AccountContent(
            account = account,
            busy = busy,
            connection = connection,
            onBack = onBack,
            onSignIn = { viewModel.signInIntent()?.let(signIn::launch) },
            onSignOut = { viewModel.signOut { intent -> intent?.let(logout::launch) } },
            onRetryConnection = viewModel::checkConnection,
        )
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun AccountContent(
    account: AccountState,
    busy: Boolean,
    connection: Connection?,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onRetryConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
            }
            Text("Account", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(16.dp))
        when (account) {
            AccountState.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            AccountState.NotConfigured -> Explanation(
                "Sign-in isn't set up in this build. Add the backend's settings to local.properties " +
                    "(see secrets.defaults.properties) and rebuild."
            )
            AccountState.SignedOut -> SignedOutContent(busy, onSignIn)
            is AccountState.SignedIn -> SignedInContent(account, busy, connection, onSignOut, onRetryConnection)
        }
    }
}

@Composable
private fun Explanation(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SignedOutContent(busy: Boolean, onSignIn: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Explanation(
            "Sign in to back up your rides and keep them in step across your phones. " +
                "Recording works the same either way."
        )
        Button(onClick = onSignIn, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Signing in…")
            } else {
                Text("Sign in or create account")
            }
        }
    }
}

@Composable
private fun SignedInContent(
    account: AccountState.SignedIn,
    busy: Boolean,
    connection: Connection?,
    onSignOut: () -> Unit,
    onRetryConnection: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Signed in as", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    account.email ?: "your account",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                ConnectionLine(connection, onRetryConnection)
            }
        }
        Explanation("Rides recorded while signed in belong to this account. Syncing them is coming next.")
        OutlinedButton(onClick = onSignOut, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out")
        }
    }
}

@Composable
private fun ConnectionLine(connection: Connection?, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (connection) {
            null, Connection.Checking -> {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Checking connection…", style = MaterialTheme.typography.bodyMedium)
            }
            Connection.Connected -> Text(
                "✓ Connected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            is Connection.Failed -> {
                Text(
                    connection.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SignedOutPreview() {
    ActivityStatsTheme {
        AccountContent(AccountState.SignedOut, busy = false, connection = null, {}, {}, {}, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun SignedInPreview() {
    ActivityStatsTheme {
        AccountContent(
            AccountState.SignedIn("user", "rider@example.com"),
            busy = false,
            connection = Connection.Connected,
            {}, {}, {}, {},
        )
    }
}
