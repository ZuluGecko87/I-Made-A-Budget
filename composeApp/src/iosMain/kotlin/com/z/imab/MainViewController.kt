package com.z.imab

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import com.z.imab.data.BudgetStore
import com.z.imab.platform.Native
import com.z.imab.platform.NativeServices
import com.z.imab.ui.App
import com.z.imab.ui.NavState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIViewController

private val scope = MainScope()
private var store: BudgetStore? = null
private val nav = NavState()

/** The app's one screen, for the Swift app to show. [services] supplies CryptoKit and the Keychain. */
fun MainViewController(services: NativeServices): UIViewController {
    Native.services = services
    val s = store ?: BudgetStore(IosStorage(), scope).also {
        store = it
        scope.launch { it.start() }
        // Save straight away when the app goes to the background; iOS may end it without warning.
        NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, null) { _ ->
            scope.launch { it.flush() }
        }
    }
    val pickers = IosPickers(::topViewController)
    return ComposeUIViewController {
        // The status bar and notch at the top, and the keyboard. The bottom tab bar runs on down
        // behind the home indicator itself, as iPhone apps' tab bars do.
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).imePadding()) {
            App(s, pickers, nav)
        }
    }
}
