// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import com.weather.obviouslyweather.ui.WeatherApp
import com.weather.obviouslyweather.ui.WithAppHaptics
import com.weather.obviouslyweather.ui.theme.ObviouslyweatherTheme

class MainActivity : ComponentActivity() {
    private lateinit var model: WeatherViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        applyPlatformAppearance(this, WeatherStore(this).appearance())
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RainNotifications.createChannel(this)
        model = ViewModelProvider(this)[WeatherViewModel::class.java]
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            ObviouslyweatherTheme(appearance = state.appearance) {
                val lightBars = MaterialTheme.colorScheme.background.luminance() > 0.5f
                LaunchedEffect(lightBars) {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                WithAppHaptics(state.interaction) { WeatherApp(state, model) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::model.isInitialized) {
            model.setForegroundActive(true)
            model.refreshIfOld()
        }
    }

    override fun onStop() {
        if (::model.isInitialized) model.setForegroundActive(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::model.isInitialized && intent.action == RainNotifications.OPEN_WEATHER_ACTION) {
            model.closeSheet()
            model.refreshIfOld()
        }
    }
}
