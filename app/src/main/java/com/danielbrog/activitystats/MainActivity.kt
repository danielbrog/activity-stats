package com.danielbrog.activitystats

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.work.ExistingWorkPolicy
import com.danielbrog.activitystats.navigation.AppNavigation
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme

class MainActivity : ComponentActivity() {

    /**
     * Syncs whenever the app comes to the screen: a fresh start, or back from the background
     * while Android still has it in memory. KEEP adds nothing if a sync is already queued or
     * running (say, from sign-in loading, or a rotation a moment ago), since that one downloads
     * anyway.
     */
    override fun onStart() {
        super.onStart()
        (application as ActivityStatsApp).authRepository.requestSyncIfSignedIn(ExistingWorkPolicy.KEEP)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ActivityStatsTheme {
                AppNavigation()
            }
        }
    }
}
