package com.portalremote

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    // The same instance Compose resolves with viewModel(), since both are scoped to
    // this activity's store — so an ACTION_SEND intent can be handed straight over
    // without threading it down through the composition.
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Bar visibility itself is reactive (fullscreen changes per session, theme
        // can change under the app) — see ui/theme/SystemBars.kt's `SystemBars()`,
        // called from RemoteScreen and PairScreen.
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
