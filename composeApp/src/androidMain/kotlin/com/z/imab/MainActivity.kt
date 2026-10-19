package com.z.imab

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import com.z.imab.data.BudgetStore
import com.z.imab.ui.App
import com.z.imab.ui.NavState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class ImabApp : Application() {
    private val scope = MainScope()
    lateinit var store: BudgetStore
        private set

    /** Survives activity recreation so navigation isn't reset on rotation. */
    val nav = NavState()

    override fun onCreate() {
        super.onCreate()
        store = BudgetStore(AndroidStorage(this), scope)
        scope.launch { store.start() }
    }

    fun flush() {
        scope.launch { store.flush() }
    }
}

class MainActivity : ComponentActivity() {
    private val app get() = application as ImabApp

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val pickers = AndroidPickers(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!app.nav.back()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        setContent {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                App(app.store, pickers, app.nav)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.store.onResume()
    }

    override fun onStop() {
        super.onStop()
        app.flush()
    }
}
