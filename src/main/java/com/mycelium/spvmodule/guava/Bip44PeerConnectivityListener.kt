package com.mycelium.spvmodule.guava

import android.content.Intent
import android.content.SharedPreferences
import android.os.AsyncTask
import android.support.v4.content.LocalBroadcastManager
import com.mycelium.spvmodule.Configuration
import com.mycelium.spvmodule.Constants
import com.mycelium.spvmodule.SpvModuleApplication
import com.mycelium.spvmodule.SpvService
import org.bitcoinj.core.Context
import org.bitcoinj.core.Peer
import org.bitcoinj.core.listeners.PeerConnectedEventListener
import org.bitcoinj.core.listeners.PeerDisconnectedEventListener
import java.util.concurrent.atomic.AtomicBoolean

class Bip44PeerConnectivityListener internal constructor()
    : PeerConnectedEventListener, PeerDisconnectedEventListener,
        SharedPreferences.OnSharedPreferenceChangeListener {
    var peerCount: Int = 0
    private val stopped = AtomicBoolean(false)
    private val spvModuleApplication = SpvModuleApplication.getApplication()
    private val configuration = spvModuleApplication.configuration!!

    init {
        configuration.registerOnSharedPreferenceChangeListener(this)
    }

    internal fun stop() {
        stopped.set(true)

        configuration.unregisterOnSharedPreferenceChangeListener(this)
        broadcastPeerState(0)
    }

    override fun onPeerConnected(peer: Peer, peerCount: Int) = onPeerChanged(peerCount)

    override fun onPeerDisconnected(peer: Peer, peerCount: Int) = onPeerChanged(peerCount)

    private fun onPeerChanged(peerCount: Int) {
        Context.propagate(Constants.CONTEXT)
        this.peerCount = peerCount
        changed()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        Context.propagate(Constants.CONTEXT)
        if (Configuration.PREFS_KEY_CONNECTIVITY_NOTIFICATION == key) {
            changed()
        }
    }

    private fun changed() {
        if (!stopped.get()) {
            AsyncTask.execute {
                Context.propagate(Constants.CONTEXT)
                broadcastPeerState(peerCount)
            }
        }
    }

    private fun broadcastPeerState(numPeers: Int) {
        val broadcast = Intent(SpvService.ACTION_PEER_STATE)
        broadcast.`package` = spvModuleApplication.packageName
        broadcast.putExtra(SpvService.ACTION_PEER_STATE_NUM_PEERS, numPeers)

        LocalBroadcastManager.getInstance(spvModuleApplication).sendBroadcast(broadcast)
    }
}