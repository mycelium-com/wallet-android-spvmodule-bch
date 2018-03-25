package com.mycelium.spvmodule.guava

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.AsyncTask
import android.os.PowerManager
import android.support.v4.content.LocalBroadcastManager
import android.text.format.DateUtils
import android.util.Log
import com.mycelium.spvmodule.BlockchainState
import com.mycelium.spvmodule.SpvModuleApplication
import com.mycelium.spvmodule.SpvService
import org.bitcoinj.core.*
import org.bitcoinj.core.listeners.DownloadProgressTracker
import java.util.*
import java.util.concurrent.atomic.AtomicLong

class Bip44DownloadProgressTracker(private val blockChain: BlockChain, private val impediments: Set<BlockchainState.Impediment>) : DownloadProgressTracker() {
    private val activityHistory = LinkedList<ActivityHistoryEntry>()
    private val configuration = spvModuleApplication.configuration!!
    private val lastMessageTime = AtomicLong(0)
    private var lastChainHeight = 0
    private var maxChainHeight = 0L
    var wakeLock : PowerManager.WakeLock? = null

    private val blockchainState: BlockchainState
        get() {
            val chainHead = blockChain.chainHead
            val bestChainDate = chainHead.header.time
            val bestChainHeight = chainHead.height
            val replaying = chainHead.height < configuration.bestChainHeightEver

            return BlockchainState(bestChainDate, bestChainHeight, replaying, chainDownloadPercentDone, impediments)
        }

    override fun onChainDownloadStarted(peer: Peer?, blocksLeft: Int) {
        Log.d(LOG_TAG, "onChainDownloadStarted(), Blockchain's download is starting. " +
                "Blocks left to download is $blocksLeft, peer = $peer")
        maybeUpdateMaxChainHeight(peer!!.bestHeight)
        super.onChainDownloadStarted(peer, blocksLeft)
    }

    override fun onBlocksDownloaded(peer: Peer, block: Block, filteredBlock: FilteredBlock?,
                                    blocksLeft: Int) {
        val now = System.currentTimeMillis()
        maybeUpdateMaxChainHeight(peer.bestHeight)
        updateActivityHistory()

        if (now - lastMessageTime.get() > BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS
                || blocksLeft == 0) {
            AsyncTask.execute(reportProgress)
        }
        super.onBlocksDownloaded(peer, block, filteredBlock, blocksLeft)
    }

    private fun maybeUpdateMaxChainHeight(height : Long) {
        if (height > maxChainHeight) {
            maxChainHeight = height
        }
    }

    override fun progress(pct: Double, blocksSoFar: Int, date: Date) {
        setSyncProgress(getDownloadPercentDone())
        broadcastBlockchainState()
        Log.d(LOG_TAG, String.format(Locale.US, "Chain download %d%% done with %d blocks to go, block date %s", pct.toInt(), blocksSoFar,
                Utils.dateTimeFormat(date)))
    }

    private fun getDownloadPercentDone(): Float {
        val downloadedHeight = blockchainState.bestChainHeight
        return 100F * downloadedHeight / maxChainHeight
    }

    override fun startDownload(blocks: Int) {
        Log.d(LOG_TAG, "Downloading block chain of size " + blocks + ". " +
                if (blocks > 1000) "This may take a while." else "")
        if (blocks > 1000) {
            if (wakeLock == null) {
                val powerManager = spvModuleApplication.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${spvModuleApplication.packageName} blockchain sync")
            }
            if (!wakeLock!!.isHeld) {
                wakeLock!!.acquire()
            }
        }
        setSyncProgress(getDownloadPercentDone())
    }

    private fun updateActivityHistory() {
        val chainHeight = blockChain.bestChainHeight
        val numBlocksDownloaded = chainHeight - lastChainHeight

        // push history
        activityHistory.add(0, ActivityHistoryEntry(numBlocksDownloaded))
        lastChainHeight = chainHeight

        // trim
        while (activityHistory.size > MAX_HISTORY_SIZE) {
            activityHistory.removeAt(activityHistory.size - 1)
        }
    }

    override fun doneDownload() {
        setSyncProgress(100f)
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        Log.d(LOG_TAG, "doneDownload(), Blockchain is fully downloaded.")
        super.doneDownload()
    }

    private val reportProgress = {
        lastMessageTime.set(System.currentTimeMillis())
        configuration.maybeIncrementBestChainHeightEver(blockChain.chainHead.height)
        broadcastBlockchainState()
    }

    fun broadcastBlockchainState() {
        val localBroadcast = Intent(SpvService.ACTION_BLOCKCHAIN_STATE)
        localBroadcast.`package` = spvModuleApplication.packageName
        blockchainState.putExtras(localBroadcast)
        LocalBroadcastManager.getInstance(spvModuleApplication).sendBroadcast(localBroadcast)

        Intent("com.mycelium.wallet.blockchainState").run {
            blockchainState.putExtras(this)
            SpvModuleApplication.sendMbw(this)
        }
    }

    private fun setSyncProgress(value: Float) {
        chainDownloadPercentDone = value
        sharedPreferences.edit().putFloat(Bip44AccountIdleService.SYNC_PROGRESS_PREF, value).apply()
    }

    fun checkIfDownloadIsIdling() {
        if (!future.isDone) {
            Log.d(LOG_TAG, "checkIfDownloadIsIdling, activityHistory.size = ${activityHistory.size}")
            // determine if block and transaction activity is idling
            val isIdle: Boolean
            if (activityHistory.isEmpty()) {
                isIdle = true
            } else {
                isIdle = activityHistory.any { it.numBlocksDownloaded == 0 }
                activityHistory.clear()
            }
            // if idling, shutdown service
            if (isIdle) {
                Log.i(LOG_TAG, "Idling is detected, restart the ${Bip44AccountIdleService::class.java.simpleName}")
                // AbstractScheduledService#shutDown is guaranteed not to run concurrently
                // with {@link AbstractScheduledService#runOneIteration}. Se we restart the service in
                // an AsyncTask
                AsyncTask.execute({ spvModuleApplication.restartBip44AccountIdleService() })
            }
        }
    }

    private class ActivityHistoryEntry(val numBlocksDownloaded: Int) {
        override fun toString(): String = "$numBlocksDownloaded"
    }

    companion object {
        private val LOG_TAG = Bip44DownloadProgressTracker::class.java.simpleName
        private const val BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS = DateUtils.SECOND_IN_MILLIS
        private const val MAX_HISTORY_SIZE = 10
        private const val SHARED_PREFERENCES_FILE_NAME = "com.mycelium.spvmodule.PREFERENCE_FILE_KEY"
        private val spvModuleApplication = SpvModuleApplication.getApplication()
        private val sharedPreferences: SharedPreferences = spvModuleApplication.getSharedPreferences(
                SHARED_PREFERENCES_FILE_NAME, Context.MODE_PRIVATE)

        @Volatile
        var chainDownloadPercentDone : Float = 0f

        fun getSyncProgress(): Float {
            return if (chainDownloadPercentDone == 0f) {
                sharedPreferences.getFloat(Bip44AccountIdleService.SYNC_PROGRESS_PREF, 0f)
            } else {
                chainDownloadPercentDone
            }
        }
    }
}