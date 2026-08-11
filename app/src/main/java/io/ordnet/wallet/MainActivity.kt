package io.ordnet.wallet

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import io.ordnet.wallet.ui.OrdplugTheme
import io.ordnet.wallet.ui.RootView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val store = (application as OrdplugApplication).store
        setContent {
            OrdplugTheme {
                RootView(store = store, activity = this)
            }
        }
    }
}
