package com.danielbrog.activitystats

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.danielbrog.activitystats.navigation.AppNavigation
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme

class MainActivity : ComponentActivity() {
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
