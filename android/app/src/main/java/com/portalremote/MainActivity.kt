package com.portalremote

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    // The same instance Compose resolves with viewModel(), since both are scoped to
    // this activity's store — so an ACTION_SEND intent can be handed straight over
    // without threading it down through the composition.
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A session is one long stretch on a single surface, and the status/nav bars
        // are ~90dp of chrome nobody reads while driving a PC. Hidden, not removed:
        // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE brings them back with an edge swipe
        // without shoving the layout around, so the clock and back gesture stay
        // reachable. Display cutouts are still padded around — see RemoteScreen.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { PortalRemoteApp(viewModel) }

        // Cold start *from* the share sheet. The pairing usually isn't up yet at this
        // point; AppViewModel.shareFromIntent waits for it rather than dropping the
        // payload.
        viewModel.shareFromIntent(intent)
    }

    /** A share into an already-open session — `singleTop` in the manifest routes it
     *  here instead of building a second copy of the activity. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.shareFromIntent(intent)
    }
}
