package io.ordnet.wallet

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.ordnet.wallet.core.WalletEngine
import io.ordnet.wallet.core.WalletStore

class OrdplugApplication : Application() {

    lateinit var store: WalletStore
        private set

    override fun onCreate() {
        super.onCreate()
        // boot the crypto engine (bsv.min.js + wallet-core.js) on the main thread
        WalletEngine.init(this)
        store = WalletStore(this)

        // auto-lock bookkeeping, the counterpart of iOS scenePhase
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                store.sceneBackgrounded()
            }
            override fun onStart(owner: LifecycleOwner) {
                store.sceneActivated()
            }
        })
    }
}
