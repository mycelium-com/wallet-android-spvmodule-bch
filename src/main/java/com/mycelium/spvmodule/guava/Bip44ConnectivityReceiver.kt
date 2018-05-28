package com.mycelium.spvmodule.guava

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import com.mycelium.spvmodule.BlockchainState
import com.mycelium.spvmodule.SpvModuleApplication
import java.util.*

class Bip44ConnectivityReceiver(private val impediments: EnumSet<BlockchainState.Impediment>) : BroadcastReceiver() {
    private val LOG_TAG = Bip44ConnectivityReceiver::class.java.simpleName
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ConnectivityManager.CONNECTIVITY_ACTION -> {
                val hasConnectivity = !intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false)
                Log.i(LOG_TAG, "Bip44ConnectivityReceiver, network is " + if (hasConnectivity) "up" else "down")
                if (hasConnectivity) {
                    if (impediments.contains(BlockchainState.Impediment.NETWORK)) {
                        impediments.remove(BlockchainState.Impediment.NETWORK)
                        SpvModuleApplication.getApplication().restartBip44AccountIdleService()
                    }
                } else {
                    impediments.add(BlockchainState.Impediment.NETWORK)
                }
            }
            Intent.ACTION_DEVICE_STORAGE_LOW -> {
                Log.i(LOG_TAG, "Bip44ConnectivityReceiver, device storage low")
                impediments.add(BlockchainState.Impediment.STORAGE)
            }
            Intent.ACTION_DEVICE_STORAGE_OK -> {
                Log.i(LOG_TAG, "Bip44ConnectivityReceiver, device storage ok")
                if (impediments.contains(BlockchainState.Impediment.STORAGE)) {
                    impediments.remove(BlockchainState.Impediment.STORAGE)
                    SpvModuleApplication.getApplication().restartBip44AccountIdleService()
                }
            }
        }
    }
}