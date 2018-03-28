package com.mycelium.spvmodule.guava

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.support.v4.content.LocalBroadcastManager
import android.text.format.DateUtils
import com.mycelium.spvmodule.*
import java.util.*

class Bip44NotificationManager {
    private val spvModuleApplication = SpvModuleApplication.getApplication()
    private val configuration = spvModuleApplication.configuration!!
    private val notificationManager = spvModuleApplication.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var peerCount = 0
    private var blockchainState : BlockchainState? = null
    private val chainStateBroadcastReceiver = ChainStateBroadcastReceiver()
    private val peerCountBroadcastReceiver = PeerCountBroadcastReceiver()

    private val localBroadcastManager = LocalBroadcastManager.getInstance(spvModuleApplication)

    init {
        localBroadcastManager.registerReceiver(chainStateBroadcastReceiver, IntentFilter(SpvService.ACTION_BLOCKCHAIN_STATE))
        localBroadcastManager.registerReceiver(peerCountBroadcastReceiver, IntentFilter(SpvService.ACTION_PEER_STATE))
    }

    protected fun finalize(){
        localBroadcastManager.unregisterReceiver(chainStateBroadcastReceiver)
        localBroadcastManager.unregisterReceiver(peerCountBroadcastReceiver)
    }

    private fun changed() {
        val connectivityNotificationEnabled = configuration.connectivityNotificationEnabled

        if (!connectivityNotificationEnabled || peerCount == 0 || blockchainState == null) {
            notificationManager.cancel(Constants.NOTIFICATION_ID_CONNECTED)
        } else {
            val notification = Notification.Builder(spvModuleApplication).apply {
                setSmallIcon(R.drawable.stat_sys_peers, if (peerCount > 4) 4 else peerCount)
                setContentTitle(spvModuleApplication.getString(R.string.app_name))
                var contentText = spvModuleApplication.getString(R.string.notification_peers_connected_msg, peerCount)
                val daysBehind = (Date().time - blockchainState!!.bestChainDate.time) / DateUtils.DAY_IN_MILLIS
                if (daysBehind > 1) {
                    contentText += " " + spvModuleApplication.getString(R.string.notification_chain_status_behind, daysBehind)
                }
                if (blockchainState!!.impediments.size > 0) {
                    // TODO: this is potentially unreachable as the service stops when offline.
                    // Not sure if impediment STORAGE ever shows. Probably both should show.
                    val impedimentsString = blockchainState!!.impediments.joinToString { it.toString() }
                    contentText += " " + spvModuleApplication.getString(R.string.notification_chain_status_impediment, impedimentsString)
                }
                setStyle(Notification.BigTextStyle().bigText(contentText))
                setContentText(contentText)

                setContentIntent(PendingIntent.getActivity(spvModuleApplication, 0,
                        Intent(spvModuleApplication, PreferenceActivity::class.java), 0))
                setWhen(System.currentTimeMillis())
                setOngoing(true)
            }
            notificationManager.notify(Constants.NOTIFICATION_ID_CONNECTED, notification.build())
        }
    }

    inner class ChainStateBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            blockchainState = BlockchainState(intent!!)
            changed()
        }
    }

    inner class PeerCountBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            peerCount = intent!!.getIntExtra(SpvService.ACTION_PEER_STATE_NUM_PEERS, 0)
            changed()
        }
    }
}