package com.danielbrog.activitystats

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.danielbrog.activitystats.location.GPSDisplayScreen
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ActivityStatsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    GPSDisplayScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}
