package com.mycelium.spvmodule.guava

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.support.v4.content.LocalBroadcastManager
import com.mycelium.spvmodule.*

class Bip44NotificationManager(private val bip44IdleServiceInstance: Bip44AccountIdleService?) {
    private val spvModuleApplication = SpvModuleApplication.getApplication()
    private val configuration = spvModuleApplication.configuration!!
    private val notificationManager = spvModuleApplication.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var peerCount = 0
    private var blockchainState: BlockchainState? = null
    private val chainStateBroadcastReceiver = ChainStateBroadcastReceiver()
    private val peerCountBroadcastReceiver = PeerCountBroadcastReceiver()
    private var notification: Notification? = null

    private val localBroadcastManager = LocalBroadcastManager.getInstance(spvModuleApplication)

    init {
        localBroadcastManager.registerReceiver(chainStateBroadcastReceiver, IntentFilter(SpvService.ACTION_BLOCKCHAIN_STATE))
        localBroadcastManager.registerReceiver(peerCountBroadcastReceiver, IntentFilter(SpvService.ACTION_PEER_STATE))
        changed()
        if (notification != null && Bip44DownloadProgressTracker.getSyncProgress() < 99.9F) {
            bip44IdleServiceInstance!!.startForeground(Constants.NOTIFICATION_ID_CONNECTED, notification)
        }
    }

    protected fun finalize() {
        localBroadcastManager.unregisterReceiver(chainStateBroadcastReceiver)
        localBroadcastManager.unregisterReceiver(peerCountBroadcastReceiver)
    }

    private var oldNotificationBasics = ""
    private fun changed() {
        val connectivityNotificationEnabled = configuration.connectivityNotificationEnabled

        //We need to check fo 100 to prevent not partial sync on first run.
        if (Bip44DownloadProgressTracker.getSyncProgress() == 100F) {
            this.bip44IdleServiceInstance?.stopForeground(false)
            if (!connectivityNotificationEnabled) {
                notificationManager.cancel(Constants.NOTIFICATION_ID_CONNECTED)
                return
            }
        }

        val downloadPercentDone = if (blockchainState != null) {
            blockchainState!!.chainDownloadPercentDone
        } else {
            100F
        }
        val notificationBasics = "$peerCount,$downloadPercentDone,${blockchainState?.impediments?.size
                ?: "nope"}"
        if (notificationBasics == oldNotificationBasics) {
            return
        }
        oldNotificationBasics = notificationBasics
        notification = buildNotification()
        notificationManager.notify(Constants.NOTIFICATION_ID_CONNECTED, notification)
    }

    private fun buildNotification(): Notification? {
        return Notification.Builder(spvModuleApplication).apply {
            setSmallIcon(R.drawable.stat_sys_peers, if (peerCount > 4) 4 else peerCount)
            setContentTitle(spvModuleApplication.getString(R.string.app_name))
            var contentText = spvModuleApplication.resources.getQuantityString(R.plurals.notification_peers_connected_msg, peerCount, peerCount)
            if (blockchainState != null) {
                val downloadPercentDone = blockchainState!!.chainDownloadPercentDone
                contentText += " " + if (downloadPercentDone < 100) {
                    spvModuleApplication.getString(R.string.notification_chain_status, downloadPercentDone)
                } else {
                    spvModuleApplication.getString(R.string.notification_chain_status_synchronized)
                }
                if (downloadPercentDone < 100) {
                    setProgress(100, Math.round(downloadPercentDone), false)
                }
                if (blockchainState!!.impediments.size > 0) {
                    // TODO: this is potentially unreachable as the service stops when offline.
                    // Not sure if impediment STORAGE ever shows. Probably both should show.
                    val impedimentsString = blockchainState!!.impediments.joinToString { it.toString() }
                    contentText += " " + spvModuleApplication.getString(R.string.notification_chain_status_impediment, impedimentsString)
                }
            }
            setStyle(Notification.BigTextStyle().bigText(contentText))
            setContentText(contentText)

            setContentIntent(PendingIntent.getActivity(spvModuleApplication, 0,
                    Intent(spvModuleApplication, PreferenceActivity::class.java), 0))
            setWhen(System.currentTimeMillis())
            setOngoing(true)
        }.build()
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