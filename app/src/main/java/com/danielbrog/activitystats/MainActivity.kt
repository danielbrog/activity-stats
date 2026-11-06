package com.danielbrog.activitystats

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.danielbrog.activitystats.live.LiveScreen
import com.danielbrog.activitystats.location.GPSDisplayScreen
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ActivityStatsTheme {
                // A simple two-screen switch; proper navigation comes with Phase 2.
                var showGpsDetails by rememberSaveable { mutableStateOf(false) }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val padded = Modifier.padding(innerPadding)
                    if (showGpsDetails) {
                        BackHandler { showGpsDetails = false }
                        GPSDisplayScreen(onBack = { showGpsDetails = false }, modifier = padded)
                    } else {
                        LiveScreen(onOpenGpsDetails = { showGpsDetails = true }, modifier = padded)
                    }
                }
            }
        }
    }
}
