package com.ians.korno

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ians.korno.core.designsystem.theme.KornoTheme
import org.koin.androidx.compose.koinViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // ViewModel поднят сюда, а не оставлен внутри KornoApp: выбор палитры нужен
            // теме, то есть уровнем выше самого приложения.
            val viewModel: KornoAppViewModel = koinViewModel()
            val appState by viewModel.state.collectAsStateWithLifecycle()
            KornoTheme(dynamicColor = appState.dynamicColorEnabled) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KornoApp(viewModel = viewModel)
                }
            }
        }
    }
}
