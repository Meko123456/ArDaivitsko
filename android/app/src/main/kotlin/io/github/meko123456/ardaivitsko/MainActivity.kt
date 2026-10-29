package io.github.meko123456.ardaivitsko

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.github.meko123456.ardaivitsko.ui.theme.ArDaivitskoTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ArDaivitskoTheme { Surface(Modifier.fillMaxSize()) { } } }
    }

    override fun onStart() {
        super.onStart()
        // The safety net: everything is scheduled again whenever the app opens.
        val container = appContainer
        container.appScope.launch { container.service.reconcile() }
    }
}
