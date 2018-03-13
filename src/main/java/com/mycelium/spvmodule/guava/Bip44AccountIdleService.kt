package com.mycelium.spvmodule.guava

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.content.Context
import android.net.ConnectivityManager
import android.os.AsyncTask
import android.os.Looper
import android.os.PowerManager
import android.support.v4.content.LocalBroadcastManager
import android.text.format.DateUtils
import android.util.Log
import android.widget.Toast
import com.google.common.base.Optional
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.AbstractScheduledService
import com.mrd.bitlib.StandardTransactionBuilder
import com.mrd.bitlib.crypto.PublicKey
import com.mrd.bitlib.crypto.PublicKeyRing
import com.mrd.bitlib.model.OutPoint
import com.mrd.bitlib.model.ScriptOutput
import com.mrd.bitlib.model.UnspentTransactionOutput
import com.mycelium.spvmodule.*
import com.mycelium.spvmodule.currency.ExactBitcoinValue
import com.mycelium.spvmodule.model.TransactionDetails
import com.mycelium.spvmodule.model.TransactionSummary
import com.mycelium.spvmodule.providers.TransactionContract
import org.bitcoinj.core.*
import org.bitcoinj.core.Context.propagate
import org.bitcoinj.core.listeners.DownloadProgressTracker
import org.bitcoinj.core.listeners.PeerConnectedEventListener
import org.bitcoinj.core.listeners.PeerDisconnectedEventListener
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.net.discovery.MultiplexingDiscovery
import org.bitcoinj.net.discovery.PeerDiscovery
import org.bitcoinj.net.discovery.PeerDiscoveryException
import org.bitcoinj.store.BlockStore
import org.bitcoinj.store.SPVBlockStore
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.*
import java.io.*
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class Bip44AccountIdleService : AbstractScheduledService() {
    private val singleAddressAccountsMap:ConcurrentHashMap<String, Wallet> = ConcurrentHashMap()
    private val walletsAccountsMap: ConcurrentHashMap<Int, Wallet> = ConcurrentHashMap()
    private var downloadProgressTracker: DownloadProgressTracker? = null
    private val connectivityReceiver = ConnectivityReceiver()

    private var wakeLock: PowerManager.WakeLock? = null
    private var peerGroup: PeerGroup? = null

    private val spvModuleApplication = SpvModuleApplication.getApplication()
    private val sharedPreferences: SharedPreferences = spvModuleApplication.getSharedPreferences(
            SHARED_PREFERENCES_FILE_NAME, Context.MODE_PRIVATE)
    //Read list of accounts indexes
    private val accountIndexStrings: ConcurrentSkipListSet<String> = ConcurrentSkipListSet<String>().apply {
        addAll(sharedPreferences.getStringSet(ACCOUNT_INDEX_STRING_SET_PREF, emptySet()))
    }
    //List of single address accounts guids
    private val singleAddressAccountGuidStrings: ConcurrentSkipListSet<String> = ConcurrentSkipListSet<String>().apply {
        addAll(sharedPreferences.getStringSet(SINGLE_ADDRESS_ACCOUNT_GUID_SET_PREF, emptySet()))
    }
    //List of accounts indexes
    private val configuration = spvModuleApplication.configuration!!
    private val peerConnectivityListener: PeerConnectivityListener = PeerConnectivityListener()
    private val notificationManager = spvModuleApplication.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private lateinit var blockStore: BlockStore
    private var counterCheckImpediments: Int = 0
    private var countercheckIfDownloadIsIdling: Int = 0
    @Volatile
    private var chainDownloadPercentDone : Int = 0

    private val semaphore : Semaphore = Semaphore(WRITE_THREADS_LIMIT)

    fun getSyncProgress(): Int {
        return if (chainDownloadPercentDone == 0) {
            sharedPreferences.getInt(SYNC_PROGRESS_PREF, 0)
        } else {
            chainDownloadPercentDone
        }
    }

    fun setSyncProgress(value: Int) {
        chainDownloadPercentDone = value
        sharedPreferences.edit().putInt(SYNC_PROGRESS_PREF, value).apply()
    }

    override fun shutDown() {
        Log.d(LOG_TAG, "shutDown")
        stopPeergroup()
    }

    override fun scheduler(): Scheduler =
            AbstractScheduledService.Scheduler.newFixedRateSchedule(0, 1, TimeUnit.MINUTES)

    override fun runOneIteration() {
        Log.d(LOG_TAG, "runOneIteration")
        if (walletsAccountsMap.isNotEmpty() || singleAddressAccountsMap.isNotEmpty()) {
            propagate(Constants.CONTEXT)
            counterCheckImpediments++
            if (counterCheckImpediments.rem(2) == 0 || counterCheckImpediments == 1) {
                //We do that every two minutes
                checkImpediments()
            }

            countercheckIfDownloadIsIdling++
            if (countercheckIfDownloadIsIdling.rem(2) == 0) {
                //We do that every two minutes
                checkIfDownloadIsIdling()
            }
        }
    }

    override fun startUp() {
        Log.d(LOG_TAG, "startUp")
        INSTANCE = this
        propagate(Constants.CONTEXT)
        val intentFilter = IntentFilter().apply {
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
            addAction(Intent.ACTION_DEVICE_STORAGE_OK)
        }
        spvModuleApplication.applicationContext.registerReceiver(connectivityReceiver, intentFilter)

        blockStore = SPVBlockStore(Constants.NETWORK_PARAMETERS, getBlockchainFile())
        blockStore.chainHead // detect corruptions as early as possible
        initializeWalletsAccounts()
        shareCurrentWalletState()
        initializePeergroup()
    }

    private fun getBlockchainFile() : File {
        return File(spvModuleApplication.getDir("blockstore", Context.MODE_PRIVATE),
                Constants.Files.BLOCKCHAIN_FILENAME+"-BCH")
    }

    fun resetBlockchainState() {
        val blockchainFile = getBlockchainFile()
        sharedPreferences.edit()
                .remove(SYNC_PROGRESS_PREF)
                .apply()
        if (blockchainFile.exists())
            blockchainFile.delete()
    }

    private fun shareCurrentWalletState() {
        (walletsAccountsMap.values + singleAddressAccountsMap.values).forEach {
            notifyTransactions(it.getTransactions(true), it.unspents.toSet())
        }
    }

    private fun initializeWalletAccountsListeners() {
        Log.d(LOG_TAG, "initializeWalletAccountsListeners, number of HD accounts = ${walletsAccountsMap.values.size}")
        walletsAccountsMap.values.forEach {
            it.addChangeEventListener(Threading.SAME_THREAD, walletEventListener)
            it.addCoinsReceivedEventListener(Threading.SAME_THREAD, walletEventListener)
            it.addCoinsSentEventListener(Threading.SAME_THREAD, walletEventListener)
        }
        Log.d(LOG_TAG, "initializeWalletAccountsListeners, number of SA accounts = ${singleAddressAccountsMap.values.size}")
        singleAddressAccountsMap.values.forEach {
            it.addChangeEventListener(Threading.SAME_THREAD, singleAddressWalletEventListener)
            it.addCoinsReceivedEventListener(Threading.SAME_THREAD, singleAddressWalletEventListener)
            it.addCoinsSentEventListener(Threading.SAME_THREAD, singleAddressWalletEventListener)
        }
    }

    private fun initializeWalletsAccounts() {
        Log.d(LOG_TAG, "initializeWalletsAccounts, number of accounts = ${accountIndexStrings.size}")
        var shouldInitializeCheckpoint = true
        for (accountIndexString in accountIndexStrings) {
            val accountIndex: Int = accountIndexString.toInt()
            val walletAccount = getAccountWallet(accountIndex)
            if (walletAccount != null) {
                walletsAccountsMap[accountIndex] = walletAccount
                if (walletAccount.lastBlockSeenHeight >= 0 && shouldInitializeCheckpoint == true) {
                    shouldInitializeCheckpoint = false
                }
            }
            notifyCurrentReceiveAddress()
        }

        for (guid in singleAddressAccountGuidStrings) {
            val walletAccount = getSingleAddressAccountWallet(guid)
            if (walletAccount != null) {
                singleAddressAccountsMap.put(guid, walletAccount)
                if (walletAccount.lastBlockSeenHeight >= 0 && shouldInitializeCheckpoint == true) {
                    shouldInitializeCheckpoint = false
                }
            }
            notifyCurrentReceiveAddress()
        }

        if (shouldInitializeCheckpoint) {
            val earliestKeyCreationTime = initializeEarliestKeyCreationTime()
            if (earliestKeyCreationTime > 0L) {
                initializeCheckpoint(earliestKeyCreationTime)
            }
        }
        blockChain = BlockChain(Constants.NETWORK_PARAMETERS, (walletsAccountsMap.values + singleAddressAccountsMap.values).toList(),
                blockStore)
        initializeWalletAccountsListeners()
    }

    private fun initializeEarliestKeyCreationTime(): Long {
        Log.d(LOG_TAG, "initializeEarliestKeyCreationTime")
        var earliestKeyCreationTime = 0L
        for (walletAccount in (walletsAccountsMap.values + singleAddressAccountsMap.values)) {
            if (earliestKeyCreationTime != 0L) {
                if (walletAccount.earliestKeyCreationTime < earliestKeyCreationTime) {
                    earliestKeyCreationTime = walletAccount.earliestKeyCreationTime
                }
            } else {
                earliestKeyCreationTime = walletAccount.earliestKeyCreationTime
            }
        }
        return earliestKeyCreationTime
    }

    private fun initializeCheckpoint(earliestKeyCreationTime: Long) {
        Log.d(LOG_TAG, "initializeCheckpoint, earliestKeyCreationTime = $earliestKeyCreationTime")
        try {
            val start = System.currentTimeMillis()
            val checkpointsInputStream = spvModuleApplication.assets.open(Constants.Files.CHECKPOINTS_FILENAME)
            //earliestKeyCreationTime = 1477958400L //Should be earliestKeyCreationTime, testing something.
            CheckpointManager.checkpoint(Constants.NETWORK_PARAMETERS, checkpointsInputStream,
                    blockStore, earliestKeyCreationTime)
            Log.i(LOG_TAG, "checkpoints loaded from '${Constants.Files.CHECKPOINTS_FILENAME}',"
                    + " took ${System.currentTimeMillis() - start}ms, "
                    + "earliestKeyCreationTime = '$earliestKeyCreationTime'")
        } catch (x: IOException) {
            Log.e(LOG_TAG, "problem reading checkpoints, continuing without", x)
        }
    }

    private fun initializePeergroup() {
        Log.d(LOG_TAG, "initializePeergroup")
        val customPeers = (configuration.trustedPeerHost ?: "").split(",")
        peerGroup = PeerGroup(Constants.NETWORK_PARAMETERS, blockChain)
        peerGroup!!.setDownloadTxDependencies(0) // recursive implementation causes StackOverflowError

        peerGroup!!.setUserAgent(Constants.USER_AGENT, spvModuleApplication.packageInfo!!.versionName)

        peerGroup!!.addConnectedEventListener(peerConnectivityListener)
        peerGroup!!.addDisconnectedEventListener(peerConnectivityListener)

        peerGroup!!.maxConnections = when(configuration.peerHostConfig) {
            "mycelium" -> configuration.myceliumPeerHosts.size
            "custom" -> customPeers.size
            "random" -> spvModuleApplication.maxConnectedPeers()
            else -> throw RuntimeException("unknown peerHostConfig ${configuration.peerHostConfig}")
        }
        peerGroup!!.setConnectTimeoutMillis(Constants.PEER_TIMEOUT_MS)
        peerGroup!!.setPeerDiscoveryTimeoutMillis(Constants.PEER_DISCOVERY_TIMEOUT_MS.toLong())

        peerGroup!!.addPeerDiscovery(object : PeerDiscovery {
            private val normalPeerDiscovery = MultiplexingDiscovery.forServices(Constants.NETWORK_PARAMETERS, 0)

            @Throws(PeerDiscoveryException::class)
            override fun getPeers(services: Long, timeoutValue: Long, timeoutUnit: TimeUnit)
                    : Array<InetSocketAddress> {
                propagate(Constants.CONTEXT)
                val peers = when(configuration.peerHostConfig) {
                    "mycelium" -> peersFromUrls(configuration.myceliumPeerHosts)
                    "custom" -> peersFromUrls(customPeers)
                    "random" -> normalPeerDiscovery.getPeers(services, timeoutValue, timeoutUnit)
                    else -> throw RuntimeException("unknown peerHostConfig ${configuration.peerHostConfig}")
                }

                if(peers.isEmpty()) {
                    Log.e(LOG_TAG, "No valid peers available!")
                }
                Log.d(LOG_TAG, "Using peers ${peers.joinToString(", ")}")
                return peers
            }

            private fun peersFromUrls(urls: Collection<String>) = urls.map {
                val serverWithPort = it.replace("tcp://", "")
                val parts = serverWithPort.split(":")
                val server = parts[0]
                val port = if (parts.size == 2) {
                    Integer.parseInt(parts[1])
                } else {
                    Constants.NETWORK_PARAMETERS.port
                }
                InetSocketAddress(server, port)
            }.toTypedArray()

            override fun shutdown() {
                normalPeerDiscovery.shutdown()
            }
        })
        //Starting peerGroup;
        Log.i(LOG_TAG, "initializePeergroup, peergroup startAsync")
        peerGroup!!.startAsync()
    }

    private fun stopPeergroup() {
        Log.d(LOG_TAG, "stopPeergroup")
        propagate(Constants.CONTEXT)
        if (peerGroup != null) {
            if (peerGroup!!.isRunning) {
                peerGroup!!.stopAsync()
            }
            peerGroup!!.removeDisconnectedEventListener(peerConnectivityListener)
            peerGroup!!.removeConnectedEventListener(peerConnectivityListener)
            for (walletAccount in (walletsAccountsMap.values + singleAddressAccountsMap.values)) {
                peerGroup!!.removeWallet(walletAccount)
            }
        }

        try {
            spvModuleApplication.unregisterReceiver(connectivityReceiver)
        } catch (e: IllegalArgumentException) {
            //Receiver not registered.
            //Log.e(LOG_TAG, e.localizedMessage, e)
        } catch (e: UninitializedPropertyAccessException) {
        }

        peerConnectivityListener.stop()

        for (idWallet in walletsAccountsMap) {
            idWallet.value.run {
                saveWalletAccountToFile(this, walletFile(idWallet.key))
                removeChangeEventListener(walletEventListener)
                removeCoinsReceivedEventListener(walletEventListener)
                removeCoinsSentEventListener(walletEventListener)
            }
        }

        for (saWallet in singleAddressAccountsMap) {
            saWallet.value.run {
                saveWalletAccountToFile(this, singleAddressWalletFile(saWallet.key))
                removeChangeEventListener(singleAddressWalletEventListener)
                removeCoinsReceivedEventListener(singleAddressWalletEventListener)
                removeCoinsSentEventListener(singleAddressWalletEventListener)
            }
        }

        blockStore.close()
        Log.d(LOG_TAG, "stopPeergroup DONE")
    }

    @Synchronized
    internal fun checkImpediments() {
        counterCheckImpediments = 0
        //Second condition (downloadProgressTracker) prevent the case where the peergroup is
        // currently downloading the blockchain.
        if (peerGroup != null && peerGroup!!.isRunning
                && (downloadProgressTracker == null || downloadProgressTracker!!.future.isDone)) {
            if (wakeLock == null) {
                // if we still hold a wakelock, we don't leave it dangling to block until later.
                val powerManager = spvModuleApplication.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "${spvModuleApplication.packageName} blockchain sync")
            }
            if (!wakeLock!!.isHeld) {
                wakeLock!!.acquire()
            }
            for (walletAccount in walletsAccountsMap.values + singleAddressAccountsMap.values) {
                peerGroup!!.addWallet(walletAccount)
            }
            if (impediments.isEmpty() && peerGroup != null) {
                downloadProgressTracker = DownloadProgressTrackerExt()

                //Start download blockchain
                Log.i(LOG_TAG, "checkImpediments, peergroup startBlockChainDownload")
                try {
                    peerGroup!!.startBlockChainDownload(downloadProgressTracker)
                } catch (t : Throwable) {
                    Log.e(LOG_TAG, t.localizedMessage, t)
                    SpvModuleApplication.getApplication().restartBip44AccountIdleService(false)
                }
            } else {
                Log.i(LOG_TAG, "checkImpediments, impediments size is ${impediments.size} && peergroup is $peerGroup")
                for (walletAccount in walletsAccountsMap.values + singleAddressAccountsMap.values) {
                    peerGroup!!.removeWallet(walletAccount)
                }
            }
            //Release wakelock
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                wakeLock = null
            }
            broadcastBlockchainState()
        }
    }

    private fun getAccountWallet(accountIndex: Int): Wallet? {
        var wallet: Wallet? = walletsAccountsMap[accountIndex]
        if (wallet != null) {
            return wallet
        }
        val walletFile = walletFile(accountIndex)
        if (walletFile.exists()) {
            wallet = loadWalletFromProtobuf(accountIndex, walletFile)
            afterLoadWallet(wallet, accountIndex)
            cleanupFiles(accountIndex)
        }
        return wallet
    }

    internal fun getWalletAccount(accountIndex: Int): Wallet {
        var wallet: Wallet? = walletsAccountsMap[accountIndex]
        if (wallet != null) {
            return wallet
        }
        val walletFile = walletFile(accountIndex)
        if (walletFile.exists()) {
            wallet = loadWalletFromProtobuf(accountIndex, walletFile)
            afterLoadWallet(wallet, accountIndex)
            cleanupFiles(accountIndex)
        }
        return wallet!!
    }

    private fun getSingleAddressAccountWallet(guid: String): Wallet? {
        var wallet: Wallet? = singleAddressAccountsMap[guid]
        if (wallet != null) {
            return wallet
        }
        val walletFile = singleAddressWalletFile(guid)
        if (walletFile.exists()) {
            wallet = loadSingleAddressWalletFromProtobuf(guid, walletFile)
            afterLoadSingleAddressWallet(wallet, guid)
            cleanupSingleAddressFiles(guid)
        }
        return wallet
    }

    private fun loadWalletFromProtobuf(accountIndex: Int, walletAccountFile: File): Wallet {
        semaphore.acquire(WRITE_THREADS_LIMIT)
        var wallet = FileInputStream(walletAccountFile).use { walletStream ->
            try {
                Wallet.loadFromFileStream(walletStream)
            } catch (x: FileNotFoundException) {
                Log.e(LOG_TAG, "problem loading wallet", x)
                Looper.prepare()
                Toast.makeText(spvModuleApplication, x.javaClass.name, Toast.LENGTH_LONG).show()
                restoreWalletFromBackup(accountIndex)
            } catch (x: UnreadableWalletException) {
                Log.e(LOG_TAG, "problem loading wallet", x)
                Looper.prepare()
                Toast.makeText(spvModuleApplication, x.javaClass.name, Toast.LENGTH_LONG).show()
                restoreWalletFromBackup(accountIndex)
            }
        }
        semaphore.release(WRITE_THREADS_LIMIT)

        if (!wallet!!.isConsistent) {
            Toast.makeText(spvModuleApplication, "inconsistent wallet: " + walletAccountFile, Toast.LENGTH_LONG).show()
            wallet = restoreWalletFromBackup(accountIndex)
        }

        if (wallet.params != Constants.NETWORK_PARAMETERS) {
            throw Error("bad wallet network parameters: ${wallet.params.id}")
        }
        return wallet
    }

    private fun loadSingleAddressWalletFromProtobuf(guid: String, walletAccountFile: File): Wallet {
        semaphore.acquire(WRITE_THREADS_LIMIT)
        var wallet = FileInputStream(walletAccountFile).use { walletStream ->
            try {
                Wallet.loadFromFileStream(walletStream)
            } catch (x: FileNotFoundException) {
                Log.e(LOG_TAG, "problem loading wallet", x)
                Toast.makeText(spvModuleApplication, x.javaClass.name, Toast.LENGTH_LONG).show()
                restoreSingleAddressWalletFromBackup(guid)
            } catch (x: UnreadableWalletException) {
                Log.e(LOG_TAG, "problem loading wallet", x)
                Toast.makeText(spvModuleApplication, x.javaClass.name, Toast.LENGTH_LONG).show()
                restoreSingleAddressWalletFromBackup(guid)
            }
        }
        semaphore.release(WRITE_THREADS_LIMIT)

        if (!wallet!!.isConsistent) {
            Toast.makeText(spvModuleApplication, "inconsistent wallet: " + walletAccountFile, Toast.LENGTH_LONG).show()
            wallet = restoreSingleAddressWalletFromBackup(guid)
        }

        if (wallet.params != Constants.NETWORK_PARAMETERS) {
            throw Error("bad wallet network parameters: ${wallet.params.id}")
        }
        return wallet
    }

    private fun restoreWalletFromBackup(accountIndex: Int): Wallet {
        backupFileInputStream(accountIndex).use { stream ->
            val walletAccount = WalletProtobufSerializer().readWallet(stream, true, null)
            if (!walletAccount.isConsistent) {
                throw Error("inconsistent backup")
            }
            //TODO : Reset Blockchain ?
            Log.i(LOG_TAG, "wallet/account restored from backup: "
                    + "'${backupFileName(accountIndex)}'")
            return walletAccount
        }
    }

    private fun restoreSingleAddressWalletFromBackup(guid: String): Wallet {
        backupSingleAddressFileInputStream(guid).use { stream ->
            val walletAccount = WalletProtobufSerializer().readWallet(stream, true, null)
            if (!walletAccount.isConsistent) {
                throw Error("inconsistent backup")
            }
            //TODO : Reset Blockchain ?
            Log.i(LOG_TAG, "wallet/account restored from backup: "
                    + "'${backupSingleAddressFileName(guid)}'")
            return walletAccount
        }
    }

    private fun afterLoadWallet(walletAccount: Wallet, accountIndex: Int) {
        Log.d(LOG_TAG, "afterLoadWallet, accountIndex = $accountIndex, walletAccount.lastBlockSeenTimeSecs ="
                + " ${walletAccount.lastBlockSeenTimeSecs}, "
                + "walletAccount.earliestKeyCreationTime = ${walletAccount.earliestKeyCreationTime}")
        walletAccount.autosaveToFile(walletFile(accountIndex), 10, TimeUnit.SECONDS, WalletAutosaveEventListener())
        // clean up spam
        walletAccount.cleanup()
        migrateBackup(walletAccount, accountIndex)
    }

    private fun afterLoadSingleAddressWallet(walletAccount: Wallet, guid: String) {
        Log.d(LOG_TAG, "afterLoadSingleAddressWallet, accountIndex = $guid, walletAccount.lastBlockSeenTimeSecs = ${walletAccount.lastBlockSeenTimeSecs}")
        walletAccount.autosaveToFile(singleAddressWalletFile(guid), 10, TimeUnit.SECONDS, WalletAutosaveEventListener())
        // clean up spam
        walletAccount.cleanup()
        migrateSingleAddressBackup(walletAccount, guid)
    }

    private fun migrateBackup(walletAccount: Wallet, accountIndex: Int) {
        if (!backupFile(accountIndex).exists()) {
            Log.i(LOG_TAG, "migrating automatic backup to protobuf")
            // make sure there is at least one recent backup
            backupWallet(walletAccount, accountIndex)
        }
    }

    private fun migrateSingleAddressBackup(walletAccount: Wallet, guid: String) {
        if (!backupSingleAddressFile(guid).exists()) {
            Log.i(LOG_TAG, "migrating automatic backup to protobuf")
            // make sure there is at least one recent backup
            backupSingleAddressWallet(walletAccount, guid)
        }
    }

    private fun backupWallet(walletAccount: Wallet, accountIndex: Int) {
        val builder = WalletProtobufSerializer().walletToProto(walletAccount).toBuilder()

        // strip redundant
        builder.clearTransaction()
        builder.clearLastSeenBlockHash()
        builder.lastSeenBlockHeight = -1
        builder.clearLastSeenBlockTimeSecs()
        val walletProto = builder.build()

        semaphore.acquire()
        backupFileOutputStream(accountIndex).use {
            try {
                walletProto.writeTo(it)
            } catch (x: IOException) {
                Log.e(LOG_TAG, "problem writing key backup", x)
            }
        }
        semaphore.release()
    }

    private fun backupSingleAddressWallet(walletAccount: Wallet, guid: String) {
        val builder = WalletProtobufSerializer().walletToProto(walletAccount).toBuilder()

        // strip redundant
        builder.clearTransaction()
        builder.clearLastSeenBlockHash()
        builder.lastSeenBlockHeight = -1
        builder.clearLastSeenBlockTimeSecs()
        val walletProto = builder.build()

        semaphore.acquire()
        backupSingleAddressFileOutputStream(guid).use {
            try {
                walletProto.writeTo(it)
            } catch (x: IOException) {
                Log.e(LOG_TAG, "problem writing key backup", x)
            }
        }
        semaphore.release()
    }

    private fun cleanupFiles(accountIndex: Int) {
        semaphore.acquire(WRITE_THREADS_LIMIT)
        for (filename in spvModuleApplication.fileList()) {
            if (filename.startsWith(Constants.Files.WALLET_KEY_BACKUP_BASE58)
                    || filename.startsWith(backupFileName(accountIndex) + '.')
                    || filename.endsWith(".tmp")) {
                val file = File(spvModuleApplication.filesDir, filename)
                Log.i(LOG_TAG, "removing obsolete file: '$file'")
                file.delete()
            }
        }
        semaphore.release(WRITE_THREADS_LIMIT)
    }

    private fun cleanupSingleAddressFiles(guid: String) {
        semaphore.acquire(WRITE_THREADS_LIMIT)
        for (filename in spvModuleApplication.fileList()) {
            if (filename.startsWith(Constants.Files.WALLET_KEY_BACKUP_BASE58)
                    || filename.startsWith(backupSingleAddressFileName(guid) + '.')
                    || filename.endsWith(".tmp")) {
                val file = File(spvModuleApplication.filesDir, filename)
                Log.i(LOG_TAG, "removing obsolete file: '$file'")
                file.delete()
            }
        }
        semaphore.release(WRITE_THREADS_LIMIT)
    }

    private var blockChain: BlockChain? = null

    var peerCount: Int = 0

    private inner class PeerConnectivityListener internal constructor()
        : PeerConnectedEventListener, PeerDisconnectedEventListener,
            SharedPreferences.OnSharedPreferenceChangeListener {
        private val stopped = AtomicBoolean(false)

        init {
            configuration.registerOnSharedPreferenceChangeListener(this)
        }

        internal fun stop() {
            stopped.set(true)

            configuration.unregisterOnSharedPreferenceChangeListener(this)
            notificationManager.cancel(Constants.NOTIFICATION_ID_CONNECTED)
        }

        override fun onPeerConnected(peer: Peer, peerCount: Int) = onPeerChanged(peerCount)

        override fun onPeerDisconnected(peer: Peer, peerCount: Int) = onPeerChanged(peerCount)

        private fun onPeerChanged(peerCount: Int) {
            propagate(Constants.CONTEXT)
            this@Bip44AccountIdleService.peerCount = peerCount
            changed()
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
            propagate(Constants.CONTEXT)
            if (Configuration.PREFS_KEY_CONNECTIVITY_NOTIFICATION == key) {
                changed()
            }
        }

        private fun changed() {
            if (!stopped.get()) {
                AsyncTask.execute {
                    propagate(Constants.CONTEXT)
                    this@Bip44AccountIdleService.changed()
                }
            }
        }
    }

    private val impediments = EnumSet.noneOf(BlockchainState.Impediment::class.java)

    private val blockchainState: BlockchainState
        get() {
            val chainHead = blockChain!!.chainHead
            val bestChainDate = chainHead.header.time
            val bestChainHeight = chainHead.height
            val replaying = chainHead.height < configuration.bestChainHeightEver

            return BlockchainState(bestChainDate, bestChainHeight, replaying, chainDownloadPercentDone, impediments)
        }

    private fun broadcastPeerState(numPeers: Int) {
        val broadcast = Intent(SpvService.ACTION_PEER_STATE)
        broadcast.`package` = spvModuleApplication.packageName
        broadcast.putExtra(SpvService.ACTION_PEER_STATE_NUM_PEERS, numPeers)

        LocalBroadcastManager.getInstance(spvModuleApplication).sendBroadcast(broadcast)
    }

    private fun changed() {
        val connectivityNotificationEnabled = configuration.connectivityNotificationEnabled

        if (!connectivityNotificationEnabled || peerCount == 0) {
            notificationManager.cancel(Constants.NOTIFICATION_ID_CONNECTED)
        } else {
            val notification = Notification.Builder(spvModuleApplication)
            notification.setSmallIcon(R.drawable.stat_sys_peers, if (peerCount > 4) 4 else peerCount)
            notification.setContentTitle(spvModuleApplication.getString(R.string.app_name))
            var contentText = spvModuleApplication.getString(R.string.notification_peers_connected_msg, peerCount)
            val daysBehind = (Date().time - blockchainState.bestChainDate.time) / DateUtils.DAY_IN_MILLIS
            if (daysBehind > 1) {
                contentText += " " + spvModuleApplication.getString(R.string.notification_chain_status_behind, daysBehind)
            }
            if (blockchainState.impediments.size > 0) {
                // TODO: this is potentially unreachable as the service stops when offline.
                // Not sure if impediment STORAGE ever shows. Probably both should show.
                val impedimentsString = blockchainState.impediments.joinToString { it.toString() }
                contentText += " " + spvModuleApplication.getString(R.string.notification_chain_status_impediment, impedimentsString)
            }
            notification.setStyle(Notification.BigTextStyle().bigText(contentText))
            notification.setContentText(contentText)

            notification.setContentIntent(PendingIntent.getActivity(spvModuleApplication, 0,
                    Intent(spvModuleApplication, PreferenceActivity::class.java), 0))
            notification.setWhen(System.currentTimeMillis())
            notification.setOngoing(true)
            notificationManager.notify(Constants.NOTIFICATION_ID_CONNECTED, notification.build())
        }

        // send broadcast
        broadcastPeerState(peerCount)
    }

    @Synchronized
    fun addWalletAccount(creationTimeSeconds: Long,
                         accountIndex: Int) {
        Log.d(LOG_TAG, "addWalletAccount, accountIndex = $accountIndex," +
                " creationTimeSeconds = $creationTimeSeconds")
        propagate(Constants.CONTEXT)
        createMissingAccounts(creationTimeSeconds)
    }

    @Synchronized
    fun addSingleAddressAccount(guid: String, publicKey: ByteArray) {
        val ecKey = ECKey.fromPublicOnly(publicKey)
        val walletAccount = Wallet.fromKeys(Constants.NETWORK_PARAMETERS, arrayListOf(ecKey))
        saveWalletAccountToFile(walletAccount, singleAddressWalletFile(guid))

        singleAddressAccountGuidStrings.add(guid)
        sharedPreferences.edit()
                .putStringSet(SINGLE_ADDRESS_ACCOUNT_GUID_SET_PREF, singleAddressAccountGuidStrings)
                .apply()

        singleAddressAccountsMap.put(guid, walletAccount)
    }

    fun removeHdAccount(accountIndex: Int) {
        walletsAccountsMap.remove(accountIndex)
    }

    fun removeSingleAddressAccount(guid: String) {
        singleAddressAccountsMap.remove(guid)
    }

    fun removeAllAccounts() {
        singleAddressAccountsMap.clear()
        walletsAccountsMap.clear()
        sharedPreferences.edit()
                .remove(ACCOUNT_INDEX_STRING_SET_PREF)
                .remove(SINGLE_ADDRESS_ACCOUNT_GUID_SET_PREF)
                .apply()
    }

    private fun createMissingAccounts(creationTimeSeconds: Long) {
        var maxIndexWithActivity = -1
        for (accountIndexString in accountIndexStrings) {
            val accountIndex = accountIndexString.toInt()
            val walletAccount = walletsAccountsMap[accountIndex]
            if (walletAccount?.getTransactions(false)?.isEmpty() == false) {
                maxIndexWithActivity = Math.max(accountIndex, maxIndexWithActivity)
            }
        }
        var listAccountsToCreate : MutableList<Int> = mutableListOf()
        for (i in maxIndexWithActivity + 1..maxIndexWithActivity + ACCOUNT_LOOKAHEAD) {
            if (walletsAccountsMap[i] == null) {
                listAccountsToCreate.add(i)
                SpvMessageSender.requestAccountLevelKeys(listAccountsToCreate, creationTimeSeconds)
            }
        }
    }

    fun createAccounts(accountIndexes: ArrayList<Int>, accountKeyStrings: ArrayList<String>, creationTimeSeconds: Long) {
        val accountIndexesIterator = accountIndexes.iterator()
        val accountKeyStringsIterator = accountKeyStrings.iterator()
        check(accountIndexes.size == accountKeyStrings.size)
        while (accountIndexesIterator.hasNext()) {
            val accountIndex = accountIndexesIterator.next()
            val accountKeyString = accountKeyStringsIterator.next()
            createOneAccount(accountIndex, DeterministicKey.deserializeB58(accountKeyString,
                    NetworkParameters.fromID(NetworkParameters.ID_TESTNET)), creationTimeSeconds)
        }
        SpvModuleApplication.getApplication().restartBip44AccountIdleService(false)
    }

    private fun createOneAccount(accountIndex: Int, accountLevelKey: DeterministicKey, creationTimeSeconds: Long) {
        Log.d(LOG_TAG, "createOneAccount, accountLevelKey = $accountLevelKey")
        propagate(Constants.CONTEXT)
        //val walletAccount = Wallet.fromSpendingKey(Constants.NETWORK_PARAMETERS,
        //    DeterministicKey.deserializeB58(spendingKeyB58, Constants.NETWORK_PARAMETERS))


        /*
        val coinTypeKey = DeterministicKey.deserializeB58(spendingKeyB58, Constants.NETWORK_PARAMETERS)

        coinTypeKey.creationTimeSeconds = creationTimeSeconds
        val accountLevelKey = HDKeyDerivation.deriveChildKey(coinTypeKey,
                ChildNumber(accountIndex, true), creationTimeSeconds)
                 */
        val walletAccount = Wallet.fromWatchingKeyB58(Constants.NETWORK_PARAMETERS,
                accountLevelKey.serializePubB58(Constants.NETWORK_PARAMETERS),
                creationTimeSeconds, accountLevelKey.path)
        /*val walletAccount = Wallet.fromSeed(
                Constants.NETWORK_PARAMETERS,
                DeterministicSeed(bip39Passphrase, null, "", creationTimeSeconds),
                ImmutableList.of(ChildNumber(44, true), ChildNumber(1, true),
                        ChildNumber(accountIndex, true)))*/
        walletAccount.keyChainGroupLookaheadSize = 20
        accountIndexStrings.add(accountIndex.toString())
        sharedPreferences.edit()
                .putStringSet(ACCOUNT_INDEX_STRING_SET_PREF, accountIndexStrings)
                .apply()
        configuration.maybeIncrementBestChainHeightEver(walletAccount.lastBlockSeenHeight)

        saveWalletAccountToFile(walletAccount, walletFile(accountIndex))
    }

    fun getPrivateKeysCount(accountIndex : Int) : Int {
        //If we don't have an account with corresponding index
        if (walletsAccountsMap.get(accountIndex) == null)
            return 0
        val walletAccount = walletsAccountsMap.get(accountIndex)
        return walletAccount!!.activeKeyChain.issuedExternalKeys
    }

    fun getSingleAddressWalletAccount(guid: String) : Wallet {
        return singleAddressAccountsMap.get(guid)!!
    }

    @Synchronized
    fun broadcastTransaction(transaction: Transaction, accountIndex: Int) {
        propagate(Constants.CONTEXT)
        val wallet = walletsAccountsMap[accountIndex]!!
        wallet.commitTx(transaction)
        saveWalletAccountToFile(wallet, walletFile(accountIndex))
        peerGroup!!.broadcastTransaction(transaction)
    }

    @Synchronized
    fun broadcastTransactionSingleAddress(transaction: Transaction, guid: String) {
        propagate(Constants.CONTEXT)
        val wallet = singleAddressAccountsMap[guid]!!
        wallet.commitTx(transaction)
        saveWalletAccountToFile(wallet, singleAddressWalletFile(guid))
        peerGroup!!.broadcastTransaction(transaction)
    }

    fun createUnsignedTransaction(operationId: String, sendRequest: SendRequest, accountIndex: Int) {

        val publicKeyRing = PublicKeyRing()
        val networkParameters : com.mrd.bitlib.model.NetworkParameters =
                if (Constants.NETWORK_PARAMETERS == org.bitcoinj.core.NetworkParameters
                                .fromID(org.bitcoinj.core.NetworkParameters.ID_TESTNET)) {
                    com.mrd.bitlib.model.NetworkParameters.testNetwork
                } else {
                    com.mrd.bitlib.model.NetworkParameters.productionNetwork
                }
        for (key in getWalletAccount(accountIndex).activeKeyChain.leafKeys) {
            publicKeyRing.addPublicKey(PublicKey(key.pubKey), networkParameters)
        }
        publicKeyRing.addPublicKey(PublicKey(getWalletAccount(accountIndex).watchingKey.pubKey), networkParameters)

        val transactionOutputs : MutableList<com.mrd.bitlib.model.TransactionOutput> = mutableListOf()
        val unspentTransactionOutputs : MutableList<UnspentTransactionOutput> = mutableListOf()

        sendRequest.useForkId = true
        sendRequest.missingSigsMode = Wallet.MissingSigsMode.USE_OP_ZERO
        walletsAccountsMap[accountIndex]?.completeTx(sendRequest)

        for (output in sendRequest.tx.outputs) {
            transactionOutputs.add(com.mrd.bitlib.model.TransactionOutput(output.value.value,
                    ScriptOutput.fromScriptBytes(output.scriptBytes)))
        }

        for (input in sendRequest.tx.inputs) {
            unspentTransactionOutputs.add(
                    UnspentTransactionOutput(
                            OutPoint(com.mrd.bitlib.util.Sha256Hash(input.outpoint.hash.bytes),
                                    input.outpoint.index.toInt()),
                            input.connectedOutput!!.parentTransactionDepthInBlocks,
                            input.connectedOutput!!.value.value,
                            ScriptOutput.fromScriptBytes(
                                    input.connectedOutput!!.scriptBytes)))
        }

        val unsignedTransaction : com.mrd.bitlib.StandardTransactionBuilder.UnsignedTransaction =
                StandardTransactionBuilder.UnsignedTransaction(transactionOutputs,
                        unspentTransactionOutputs, publicKeyRing, networkParameters)

        /*
        sendRequest.useForkId = true
        sendRequest.missingSigsMode = Wallet.MissingSigsMode.USE_DUMMY_SIG
        walletsAccountsMap[accountIndex]?.completeTx(sendRequest)
        val chainHeight = walletsAccountsMap[accountIndex]?.lastBlockSeenHeight
        val addresses = mutableListOf<String>()
        //val transactionOutputs : MutableList<Wallet.FreeStandingTransactionOutput> = mutableListOf()
        for (input in sendRequest.tx.inputs){
            addresses.add(input.connectedOutput!!
                    .getAddressFromP2SH(Constants.NETWORK_PARAMETERS).toString())
            transactionOutputs.add(
                    walletsAccountsMap[accountIndex]?.getFreeStandingTransactionOutput(input.outpoint)!!)
            input.connectedOutput!!
        }

        sendUnsignedTransactionToMbw(operationId, sendRequest.tx, accountIndex, addresses)
        */
        sendUnsignedTransactionToMbw(operationId, unsignedTransaction, accountIndex)
    }

    fun createUnsignedTransactionSingleAddress(operationId: String, sendRequest: SendRequest, guid: String) {
        sendRequest.useForkId = true
        sendRequest.missingSigsMode = Wallet.MissingSigsMode.USE_OP_ZERO
        singleAddressAccountsMap[guid]?.completeTx(sendRequest)

        val walletAccount = getSingleAddressAccountWallet(guid)

        val publicKeyRing = PublicKeyRing()
        val networkParameters : com.mrd.bitlib.model.NetworkParameters =
                if (Constants.NETWORK_PARAMETERS == org.bitcoinj.core.NetworkParameters
                                .fromID(org.bitcoinj.core.NetworkParameters.ID_TESTNET)) {
                    com.mrd.bitlib.model.NetworkParameters.testNetwork
                } else {
                    com.mrd.bitlib.model.NetworkParameters.productionNetwork
                }
        publicKeyRing.addPublicKey(PublicKey(walletAccount!!.importedKeys[0].pubKey), networkParameters)

        val transactionOutputs : MutableList<com.mrd.bitlib.model.TransactionOutput> = mutableListOf()
        val unspentTransactionOutputs : MutableList<UnspentTransactionOutput> = mutableListOf()

        val height = walletAccount!!.lastBlockSeenHeight
        for (output in sendRequest.tx.outputs) {
            transactionOutputs.add(com.mrd.bitlib.model.TransactionOutput(output.value.value,
                    ScriptOutput.fromScriptBytes(output.scriptBytes)))
        }

        for (input in sendRequest.tx.inputs) {
            unspentTransactionOutputs.add(UnspentTransactionOutput(
                    OutPoint(com.mrd.bitlib.util.Sha256Hash(input.outpoint.hash.bytes),
                            input.outpoint.index.toInt()),
                    input.connectedOutput!!.parentTransactionDepthInBlocks,
                    input.value!!.value, ScriptOutput.fromScriptBytes(
                    input.connectedOutput!!.scriptBytes)))
        }

        val unsignedTransaction : com.mrd.bitlib.StandardTransactionBuilder.UnsignedTransaction =
                StandardTransactionBuilder.UnsignedTransaction(transactionOutputs,
                        unspentTransactionOutputs, publicKeyRing, networkParameters)

        sendUnsignedTransactionToMbwSingleAddress(operationId, unsignedTransaction, guid)
    }

    fun broadcastTransaction(sendRequest: SendRequest, accountIndex: Int) {
        propagate(Constants.CONTEXT)
        sendRequest.useForkId = true
        walletsAccountsMap[accountIndex]?.completeTx(sendRequest)
        broadcastTransaction(sendRequest.tx, accountIndex)
    }

    fun broadcastTransactionSingleAddress(sendRequest: SendRequest, guid: String) {
        propagate(Constants.CONTEXT)
        singleAddressAccountsMap[guid]?.completeTx(sendRequest)
        broadcastTransactionSingleAddress(sendRequest.tx, guid)
    }

    private fun broadcastBlockchainState() {
        val localBroadcast = Intent(SpvService.ACTION_BLOCKCHAIN_STATE)
        localBroadcast.`package` = spvModuleApplication.packageName
        blockchainState.putExtras(localBroadcast)
        LocalBroadcastManager.getInstance(spvModuleApplication).sendBroadcast(localBroadcast)

        Intent("com.mycelium.wallet.blockchainState").run {
            blockchainState.putExtras(this)
            SpvModuleApplication.sendMbw(this)
        }
    }

    private fun sendUnsignedTransactionToMbw(operationId: String,
                                             unsignedTransaction: StandardTransactionBuilder.UnsignedTransaction,
                                             accountIndex: Int) {
        SpvMessageSender.sendUnsignedTransactionToMbw(operationId, unsignedTransaction, accountIndex)
    }

    private fun sendUnsignedTransactionToMbw(operationId: String, transaction: Transaction,
                                             accountIndex: Int, addressesList : List<String>) {
        SpvMessageSender.sendUnsignedTransactionToMbw(operationId, transaction, accountIndex,
                addressesList)
    }

    private fun sendUnsignedTransactionToMbwSingleAddress(operationId: String, unsignedTransaction: StandardTransactionBuilder.UnsignedTransaction, guid: String) {
        SpvMessageSender.sendUnsignedTransactionToMbwSingleAddress(operationId, unsignedTransaction, guid)
    }

    private val transactionsReceived = AtomicInteger()

    private val walletEventListener = object : ThrottlingWalletChangeListener(APPWIDGET_THROTTLE_MS) {
        override fun onCoinsReceived(walletAccount: Wallet?, transaction: Transaction?,
                                     prevBalance: Coin?, newBalance: Coin?) {
            transactionsReceived.incrementAndGet()
            addMoreAccountsToLookAhead(walletAccount)
            for (key in walletsAccountsMap.keys()) {
                if(walletsAccountsMap.get(key) == walletAccount) {
                    notifySatoshisReceived(transaction!!.getValue(walletAccount).value, 0L, key)
                }
            }
        }

        override fun onCoinsSent(walletAccount: Wallet?, transaction: Transaction?,
                                 prevBalance: Coin?, newBalance: Coin?) {
            transactionsReceived.incrementAndGet()
            addMoreAccountsToLookAhead(walletAccount)
        }

        // If we found at least one transaction on the latest account in the map,
        // it is possible that some funds may exist on the next account.
        // So we should take the next account into work
        private fun addMoreAccountsToLookAhead(walletAccount: Wallet?) {
            if (walletAccount!!.getRecentTransactions(1, true).size == 1) {
                var accountIndex = 0

                // Find an HD account index in the accounts' map
                walletsAccountsMap.entries.filter {
                    walletAccount == it.value
                }.forEach {
                    accountIndex = it.key
                }

                if (accountIndex == walletsAccountsMap.size - 1) {
                    val listenableFuture = peerGroup!!.stopAsync()
                    listenableFuture.addListener(
                            Runnable {
                                spvModuleApplication.addWalletAccountWithExtendedKey(walletAccount.lastBlockSeenTimeSecs + 1,
                                        accountIndex + 1)
                            },
                            Executors.newSingleThreadExecutor())
                }
            }
        }

        override fun onChanged(walletAccount: Wallet) {
            notifyTransactions(walletAccount.getTransactions(true), walletAccount.unspents.toSet())
        }
    }

    private val singleAddressWalletEventListener = object : ThrottlingWalletChangeListener(APPWIDGET_THROTTLE_MS) {
        override fun onCoinsReceived(walletAccount: Wallet?, transaction: Transaction?,
                                     prevBalance: Coin?, newBalance: Coin?) {
            transactionsReceived.incrementAndGet()
            for (key in singleAddressAccountsMap.keys()) {
                if(singleAddressAccountsMap.get(key) == walletAccount) {
                    notifySingleAddressSatoshisReceived(transaction!!.getValue(walletAccount).value, 0L, key)
                }
            }
        }

        override fun onCoinsSent(walletAccount: Wallet?, transaction: Transaction?,
                                 prevBalance: Coin?, newBalance: Coin?) {
            transactionsReceived.incrementAndGet()
        }

        override fun onChanged(walletAccount: Wallet) {
            notifyTransactions(walletAccount.getTransactions(true), walletAccount.unspents.toSet())
        }
    }

    private fun notifySatoshisReceived(satoshisReceived: Long, satoshisSent: Long, accountIndex: Int) {
        SpvMessageSender.notifySatoshisReceived(satoshisReceived, satoshisSent, accountIndex)
        notifyCurrentReceiveAddress()
    }

    private fun notifySingleAddressSatoshisReceived(satoshisReceived: Long, satoshisSent: Long, guid: String) {
        SpvMessageSender.notifySingleAddressSatoshisReceived(satoshisReceived, satoshisSent, guid)
        notifyCurrentReceiveAddress()
    }

    private fun notifyCurrentReceiveAddress() {
        val application = SpvModuleApplication.getApplication()
        val contentUri = TransactionContract.CurrentReceiveAddress.CONTENT_URI(application.packageName)
        application.contentResolver.notifyChange(contentUri, null)
    }

    @Synchronized
    private fun notifyTransactions(transactions: Set<Transaction>, utxos: Set<TransactionOutput>) {
        if (!transactions.isEmpty()) {
            // send the new transaction and the *complete* utxo set of the account
            SpvMessageSender.sendTransactions(transactions, utxos)
        }
    }

    fun sendTransactions(accountIndex: Int) {
        val walletAccount = walletsAccountsMap.get(accountIndex) ?: return
        notifyTransactions(walletAccount.getTransactions(true), walletAccount.unspents.toSet())
    }

    fun sendTransactionsSingleAddress(guid: String) {
        val walletAccount = singleAddressAccountsMap.get(guid)
        if (walletAccount != null) {
            notifyTransactions(walletAccount.getTransactions(true), walletAccount.unspents.toSet())
        }
    }

    private val activityHistory = LinkedList<ActivityHistoryEntry>()

    private fun checkIfDownloadIsIdling() {
        if ((downloadProgressTracker != null && !downloadProgressTracker!!.future.isDone)) {
            Log.d(LOG_TAG, "checkIfDownloadIsIdling, activityHistory.size = ${activityHistory.size}")
            // determine if block and transaction activity is idling
            var isIdle = false
            if (activityHistory.isEmpty()) {
                isIdle = true
            } else {
                for (i in activityHistory.indices) {
                    val entry = activityHistory[i]
                    /* Log.d(LOG_TAG, "checkIfDownloadIsIdling, activityHistory indice is $i, " +
                    "entry.numBlocksDownloaded = ${entry.numBlocksDownloaded}, " +
                    "entry.numTransactionsReceived = ${entry.numTransactionsReceived}") */
                    if (entry.numBlocksDownloaded == 0) {
                        isIdle = true
                        break
                    }
                }
                activityHistory.clear()
            }
            // if idling, shutdown service
            if (isIdle) {
                Log.i(LOG_TAG, "Idling is detected, restart the $LOG_TAG")
                // AbstractScheduledService#shutDown is guaranteed not to run concurrently
                // with {@link AbstractScheduledService#runOneIteration}. Se we restart the service in
                // an AsyncTask
                AsyncTask.execute({ spvModuleApplication.restartBip44AccountIdleService() })
            } else {
                countercheckIfDownloadIsIdling = 0
            }
        }
    }

    fun getTransactionsSummary(walletAccount : Wallet): List<TransactionSummary> {
        val transactionsSummary = mutableListOf<TransactionSummary>()
        val transactions = walletAccount.getTransactions(false).sortedWith(kotlin.Comparator { o1, o2 -> o2.updateTime.compareTo(o1.updateTime) })

        for (transactionBitcoinJ in transactions) {
            // Outputs
            val toAddresses = java.util.ArrayList<Address>()
            var destAddress: Address? = null

            for (transactionOutput in transactionBitcoinJ.outputs) {

                val toAddress = transactionOutput.scriptPubKey.getToAddress(walletAccount.networkParameters)

                if (!transactionOutput.isMine(walletAccount)) {
                    destAddress = toAddress
                }

                toAddresses.add(toAddress)
            }

            val confirmations: Int = transactionBitcoinJ.confidence.depthInBlocks
            //val isQueuedOutgoing = (transactionBitcoinJ.isPending
            //       || transactionBitcoinJ.confidence == TransactionConfidence.ConfidenceType.BUILDING)
            val isQueuedOutgoing = false //TODO Change the UI so MBW understand BitcoinJ confidence type.
            val destAddressOptional: Optional<Address> = if (destAddress != null) {
                Optional.of(destAddress)
            } else {
                Optional.absent()
            }
            val bitcoinJValue = transactionBitcoinJ.getValue(walletAccount)
            val isIncoming = bitcoinJValue.isPositive

            val height = transactionBitcoinJ.confidence.depthInBlocks
            if (height <= 0) {
                //continue
            }

            val bitcoinValue =  ExactBitcoinValue.from(Math.abs(bitcoinJValue.value))

            val transactionSummary = TransactionSummary(transactionBitcoinJ.hash,
                    bitcoinValue,
                    isIncoming,
                    transactionBitcoinJ.updateTime.time / 1000,
                    height,
                    confirmations, isQueuedOutgoing, null, destAddressOptional, toAddresses)
            //Log.d(LOG_TAG, "getTransactionsSummary, accountIndex = $accountIndex, " +
            //       "transactionSummary = ${transactionSummary.toString()} ")
            transactionsSummary.add(transactionSummary)
        }
        return transactionsSummary.toList()

    }

    fun getTransactionsSummary(accountIndex: Int): List<TransactionSummary> {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "getTransactionsSummary, accountIndex = $accountIndex")

        val walletAccount = walletsAccountsMap.get(accountIndex) ?: return mutableListOf()
        return getTransactionsSummary(walletAccount)
    }

    fun getTransactionsSummary(guid: String): List<TransactionSummary> {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "getTransactionsSummary, guid = $guid")

        if (!singleAddressAccountsMap.containsKey(guid))
            return mutableListOf<TransactionSummary>()

        return getTransactionsSummary(singleAddressAccountsMap.get(guid)!!)
    }

    fun getTransactionDetails(accountIndex: Int, hash: String): TransactionDetails? {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "getTransactionDetails, accountIndex = $accountIndex, hash = $hash")
        val walletAccount = walletsAccountsMap.get(accountIndex) ?: return null
        val transactionBitcoinJ = walletAccount.getTransaction(Sha256Hash.wrap(hash))!!
        val inputs: MutableList<TransactionDetails.Item> = mutableListOf()

        for (input in transactionBitcoinJ.inputs) {
            val connectedOutput = input.outpoint.connectedOutput
            if (connectedOutput == null) {
                /*   inputs.add(TransactionDetails.Item(Address.getNullAddress(networkParametersBitlib),
                           if (input.value != null) {
                               input.value!!.value
                           } else {
                               0
                           }, input.isCoinBase))*/
            } else {
                val addressBitcoinJ = connectedOutput.scriptPubKey.getToAddress(walletAccount.networkParameters)
                inputs.add(TransactionDetails.Item(addressBitcoinJ, input.value!!.value, input.isCoinBase))
            }
        }

        val outputs: MutableList<TransactionDetails.Item> = mutableListOf()

        for (output in transactionBitcoinJ.outputs) {
            val addressBitcoinJ = output.scriptPubKey.getToAddress(walletAccount.networkParameters)
            //val addressBitLib: Address = Address.fromString(addressBitcoinJ.toBase58(), networkParametersBitlib)
            outputs.add(TransactionDetails.Item(addressBitcoinJ, output.value!!.value, false))
        }

        val height = transactionBitcoinJ.confidence.depthInBlocks
        val transactionDetails = TransactionDetails(Sha256Hash.wrap(hash),
                height,
                (transactionBitcoinJ.updateTime.time / 1000).toInt(), inputs.toTypedArray(),
                outputs.toTypedArray(), transactionBitcoinJ.optimalEncodingMessageSize)
        return transactionDetails
    }

    fun getAccountIndices(): List<Int> = walletsAccountsMap.keys.toList()

    fun getAccountBalance(accountIndex: Int): Long {
        propagate(Constants.CONTEXT)
        val walletAccount = walletsAccountsMap.get(accountIndex)
        Log.d(LOG_TAG, "getAccountBalance, accountIndex = $accountIndex")
        return walletAccount?.getBalance(Wallet.BalanceType.ESTIMATED)?.getValue()?: 0
    }

    fun getAccountReceiving(accountIndex: Int): Long {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "getAccountReceiving, accountIndex = $accountIndex")
        val walletAccount = walletsAccountsMap.get(accountIndex)?: return 0
        var receiving = 0L
        walletAccount.pendingTransactions.forEach {
            val sent = it.getValueSentFromMe(walletAccount)
            val netReceived = it.getValueSentToMe(walletAccount).minus(sent)
            receiving += if(netReceived.isPositive) netReceived.value else 0
        }
        return receiving
    }

    fun getAccountSending(accountIndex: Int): Long {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "getAccountSending, accountIndex = $accountIndex")
        val walletAccount = walletsAccountsMap.get(accountIndex)?: return 0
        var sending = 0L
        walletAccount.pendingTransactions.forEach {
            val received = it.getValueSentToMe(walletAccount)
            val netSent = it.getValueSentFromMe(walletAccount).minus(received)
            sending += if(netSent.isPositive) netSent.value else 0
        }
        return sending
    }

    fun getSingleAddressAccountBalance(guid: String): Long {
        propagate(Constants.CONTEXT)
        val walletAccount = singleAddressAccountsMap.get(guid)
        Log.d(LOG_TAG, "getAccountBalance, guid = $guid")
        return walletAccount?.getBalance(Wallet.BalanceType.ESTIMATED)?.getValue()?: 0
    }

    fun getSingleAddressAccountReceiving(guid: String): Long {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "getAccountReceiving, guid = $guid")
        val walletAccount = singleAddressAccountsMap.get(guid)?: return 0
        var receiving = 0L
        walletAccount.pendingTransactions.forEach {
            val sent = it.getValueSentFromMe(walletAccount)
            val netReceived = it.getValueSentToMe(walletAccount).minus(sent)
            receiving += if(netReceived.isPositive) netReceived.value else 0
        }
        return receiving
    }

    fun getSingleAddressAccountSending(guid: String): Long {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "getAccountSending, guid = $guid")
        val walletAccount = singleAddressAccountsMap.get(guid)?: return 0
        var sending = 0L
        walletAccount.pendingTransactions.forEach {
            val received = it.getValueSentToMe(walletAccount)
            val netSent = it.getValueSentFromMe(walletAccount).minus(received)
            sending += if(netSent.isPositive) netSent.value else 0
        }
        return sending
    }

    fun getAccountCurrentReceiveAddress(accountIndex: Int): org.bitcoinj.core.Address? {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "getAccountCurrentReceiveAddress, accountIndex = $accountIndex")
        val walletAccount = walletsAccountsMap.get(accountIndex)?: return null
        return walletAccount.currentReceiveAddress() ?: walletAccount.freshReceiveAddress()
    }

    fun isValid(qrCode :String): Boolean {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "isValid, qrCode = $qrCode")
        try {
            // FIXME very basic validation (should be similar to com.mycelium.wallet.BitcoinUri.parse(content, networkParameters))
            if (qrCode.startsWith("bitcoin:")) {
                val rawAddress = qrCode.removePrefix("bitcoin:")
                org.bitcoinj.core.Address.fromBase58(Constants.NETWORK_PARAMETERS, rawAddress)
                return true
            }
        } catch (ex :Exception) {
            // ignore
        }
        return false
    }

    fun calculateMaxSpendableAmount(accountIndex: Int, txFee: TransactionFee, txFeeFactor: Float): Coin? {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "calculateMaxSpendableAmount, accountIndex = $accountIndex, txFee = $txFee, txFeeFactor = $txFeeFactor")
        val walletAccount = walletsAccountsMap[accountIndex] ?: return null
        val balance = walletAccount.balance
        return balance.subtract(Constants.minerFeeValue(txFee, txFeeFactor))
    }

    fun checkSendAmount(accountIndex: Int, txFee: TransactionFee, txFeeFactor: Float, amountToSend: Long): TransactionContract.CheckSendAmount.Result? {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "checkSendAmount, accountIndex = $accountIndex, minerFee = $txFee, txFeeFactor = $txFeeFactor, amountToSend = $amountToSend")
        val walletAccount = walletsAccountsMap[accountIndex] ?: return null
        val address = getNullAddress(Constants.NETWORK_PARAMETERS)
        val amount = Coin.valueOf(amountToSend)
        val sendRequest = SendRequest.to(address, amount)
        sendRequest.feePerKb = Constants.minerFeeValue(txFee, txFeeFactor)

        try {
            walletAccount.completeTx(sendRequest)
            return TransactionContract.CheckSendAmount.Result.RESULT_OK
        } catch (ex :InsufficientMoneyException) {
            return TransactionContract.CheckSendAmount.Result.RESULT_NOT_ENOUGH_FUNDS
        } catch (ex :Exception) {
            return TransactionContract.CheckSendAmount.Result.RESULT_INVALID
        }
    }

    fun getNullAddress(network: org.bitcoinj.core.NetworkParameters): org.bitcoinj.core.Address {
        val numAddressBytes = 20
        val bytes = ByteArray(numAddressBytes)
        return org.bitcoinj.core.Address(network, bytes)
    }

    private fun saveWalletAccountToFile(walletAccount: Wallet, file: File) {
        semaphore.acquire()
        walletAccount.saveToFile(file)
        semaphore.release()
    }

    inner class DownloadProgressTrackerExt : DownloadProgressTracker() {
        override fun onChainDownloadStarted(peer: Peer?, blocksLeft: Int) {
            Log.d(LOG_TAG, "onChainDownloadStarted(), Blockchain's download is starting. " +
                    "Blocks left to download is $blocksLeft, peer = $peer")
            super.onChainDownloadStarted(peer, blocksLeft)
        }

        private val lastMessageTime = AtomicLong(0)

        private var lastChainHeight = 0

        override fun onBlocksDownloaded(peer: Peer, block: Block, filteredBlock: FilteredBlock?,
                                        blocksLeft: Int) {
            val now = System.currentTimeMillis()

            updateActivityHistory()

            if (now - lastMessageTime.get() > BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS
                    || blocksLeft == 0) {
                AsyncTask.execute(reportProgress)
            }
            super.onBlocksDownloaded(peer, block, filteredBlock, blocksLeft)
        }

        override fun progress(pct: Double, blocksSoFar: Int, date: Date) {
            setSyncProgress(getDownloadPercentDone())
            broadcastBlockchainState()
            Log.d(LOG_TAG, String.format(Locale.US, "Chain download %d%% done with %d blocks to go, block date %s", pct.toInt(), blocksSoFar,
                    Utils.dateTimeFormat(date)))
        }

        private fun getDownloadPercentDone(): Int {
            val downloadedHeight = blockchainState.bestChainHeight
            val mostCommonChainHeight = peerGroup?.mostCommonChainHeight
            if (mostCommonChainHeight != null) {
                return Math.floor(100 * (downloadedHeight * 1.0 / mostCommonChainHeight)).toInt()
            }
            return 0
        }

        override fun startDownload(blocks: Int) {
            Log.d(LOG_TAG, "Downloading block chain of size " + blocks + ". " +
                    if (blocks > 1000) "This may take a while." else "")
            setSyncProgress(getDownloadPercentDone())
        }

        private fun updateActivityHistory() {
            val chainHeight = blockChain!!.bestChainHeight
            val numBlocksDownloaded = chainHeight - lastChainHeight
            val numTransactionsReceived = transactionsReceived.getAndSet(0)

            // push history
            activityHistory.add(0, ActivityHistoryEntry(numTransactionsReceived, numBlocksDownloaded))
            lastChainHeight = chainHeight

            // trim
            while (activityHistory.size > MAX_HISTORY_SIZE) {
                activityHistory.removeAt(activityHistory.size - 1)
            }
        }

        override fun doneDownload() {
            setSyncProgress(100)
            Log.d(LOG_TAG, "doneDownload(), Blockchain is fully downloaded.")
            for (walletAccount in (walletsAccountsMap.values + singleAddressAccountsMap.values)) {
                peerGroup!!.removeWallet(walletAccount)
            }
            super.doneDownload()
            /*
            if(peerGroup!!.isRunning) {
                Log.i(LOG_TAG, "doneDownload(), stopping peergroup")
                peerGroup!!.stopAsync()
            }
            */
        }

        private val reportProgress = {
            lastMessageTime.set(System.currentTimeMillis())
            configuration.maybeIncrementBestChainHeightEver(blockChain!!.chainHead.height)
            broadcastBlockchainState()
            changed()
        }
    }

    inner class ConnectivityReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ConnectivityManager.CONNECTIVITY_ACTION -> {
                    val hasConnectivity = !intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false)
                    Log.i(LOG_TAG, "ConnectivityReceiver, network is " + if (hasConnectivity) "up" else "down")
                    if (hasConnectivity) {
                        impediments.remove(BlockchainState.Impediment.NETWORK)
                    } else {
                        impediments.add(BlockchainState.Impediment.NETWORK)
                    }
                }
                Intent.ACTION_DEVICE_STORAGE_LOW -> {
                    Log.i(LOG_TAG, "ConnectivityReceiver, device storage low")
                    impediments.add(BlockchainState.Impediment.STORAGE)
                }
                Intent.ACTION_DEVICE_STORAGE_OK -> {
                    Log.i(LOG_TAG, "ConnectivityReceiver, device storage ok")

                    impediments.remove(BlockchainState.Impediment.STORAGE)
                }
            }
        }
    }

    private inner class WalletAutosaveEventListener : WalletFiles.Listener {
        override fun onBeforeAutoSave(file: File) {
            semaphore.acquire()
        }

        override fun onAfterAutoSave(file: File) {
            semaphore.release()
        }
    }

    private class ActivityHistoryEntry(val numTransactionsReceived: Int, val numBlocksDownloaded: Int) {
        override fun toString(): String = "$numTransactionsReceived / $numBlocksDownloaded"
    }

    private fun backupFileOutputStream(accountIndex: Int): FileOutputStream =
            spvModuleApplication.openFileOutput(backupFileName(accountIndex), Context.MODE_PRIVATE)

    private fun backupSingleAddressFileOutputStream(guid: String): FileOutputStream =
            spvModuleApplication.openFileOutput(backupSingleAddressFileName(guid), Context.MODE_PRIVATE)

    private fun backupFileInputStream(accountIndex: Int): FileInputStream =
            spvModuleApplication.openFileInput(backupFileName(accountIndex))

    private fun backupSingleAddressFileInputStream(guid: String): FileInputStream =
            spvModuleApplication.openFileInput(backupSingleAddressFileName(guid))

    private fun backupFile(accountIndex: Int): File =
            spvModuleApplication.getFileStreamPath(backupFileName(accountIndex))

    private fun backupSingleAddressFile(guid: String): File =
            spvModuleApplication.getFileStreamPath(backupSingleAddressFileName(guid))

    private fun backupFileName(accountIndex: Int): String =
            Constants.Files.WALLET_KEY_BACKUP_PROTOBUF + "_$accountIndex"

    private fun backupSingleAddressFileName(guid: String): String =
            Constants.Files.WALLET_KEY_BACKUP_PROTOBUF + "_$guid"

    private fun walletFile(accountIndex: Int): File =
            spvModuleApplication.getFileStreamPath(walletFileName(accountIndex))

    private fun walletFileName(accountIndex: Int): String =
            Constants.Files.WALLET_FILENAME_PROTOBUF + "_$accountIndex"

    private fun singleAddressWalletFile(guid: String): File =
            spvModuleApplication.getFileStreamPath(singleAddressWalletFileName(guid))

    private fun singleAddressWalletFileName(guid: String): String =
            Constants.Files.WALLET_FILENAME_PROTOBUF + "_$guid"

    companion object {
        private var INSTANCE: Bip44AccountIdleService? = null
        fun getInstance(): Bip44AccountIdleService? = INSTANCE
        private val LOG_TAG = Bip44AccountIdleService::class.java.simpleName
        private val BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS = DateUtils.SECOND_IN_MILLIS
        private val APPWIDGET_THROTTLE_MS = DateUtils.SECOND_IN_MILLIS
        private val MAX_HISTORY_SIZE = 10
        private val SHARED_PREFERENCES_FILE_NAME = "com.mycelium.spvmodule.PREFERENCE_FILE_KEY"
        private val ACCOUNT_INDEX_STRING_SET_PREF = "account_index_stringset"
        private val SINGLE_ADDRESS_ACCOUNT_GUID_SET_PREF = "single_address_account_guid_set"
        private val SYNC_PROGRESS_PREF = "syncprogress"
        private val ACCOUNT_LOOKAHEAD = 3
        // Wallet class is synchronised inside, so we should not care about writing wallet files to storage ourselves,
        // but we should prevent competing with reading and files cleaning ourselves.
        private val WRITE_THREADS_LIMIT = 100
    }

    fun doesWalletAccountExist(accountIndex: Int): Boolean =
            null != walletsAccountsMap.get(accountIndex)

    fun doesSingleAddressWalletAccountExist(guid: String) : Boolean =
            null != singleAddressAccountsMap.get(guid)
}
