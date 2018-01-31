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
import com.google.common.util.concurrent.AbstractScheduledService
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class Bip44AccountIdleService : AbstractScheduledService() {
    /*
        singleAddressAccountsMap contains pairs of single address account' GUIDs and corresponding bitcoincashj.Wallet objects
    */
    private val singleAddressAccountsMap:ConcurrentHashMap<String, Wallet> = ConcurrentHashMap()

    /*
        singleAddressAccountsMap contains pairs of HD account GUIDs united with indexes and corresponding bitcoincashj.Wallet objects

        Example key: e4fbfc3b-0f93-31ea-9f59-761e40977022_0

        Here, "e4fbfc3b-0f93-31ea-9f59-761e40977022" value is HD account's GUID, 0 is the account index
    */
    private val hdWalletAccountsMap: ConcurrentHashMap<String, Wallet> = ConcurrentHashMap()
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
    private var spendingKeyB58 = sharedPreferences.getString(SPENDINGKEYB58_PREF, "")
    private var counterCheckImpediments: Int = 0
    private var countercheckIfDownloadIsIdling: Int = 0
    private var chainDownloadPercentDone : Int = 0

    fun getSyncProgress() : Int {
        return chainDownloadPercentDone
    }
    override fun shutDown() {
        Log.d(LOG_TAG, "shutDown")
        stopPeergroup()
    }

    override fun scheduler(): Scheduler =
            AbstractScheduledService.Scheduler.newFixedRateSchedule(0, 1, TimeUnit.MINUTES)

    override fun runOneIteration() {
        Log.d(LOG_TAG, "runOneIteration")
        if (hdWalletAccountsMap.isNotEmpty() || singleAddressAccountsMap.isNotEmpty()) {
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

        val blockChainFile = File(spvModuleApplication.getDir("blockstore", Context.MODE_PRIVATE),
                Constants.Files.BLOCKCHAIN_FILENAME+"-BCH")
        blockStore = SPVBlockStore(Constants.NETWORK_PARAMETERS, blockChainFile)
        blockStore.chainHead // detect corruptions as early as possible
        initializeWalletsAccounts()
        shareCurrentWalletState()
        initializePeergroup()
    }

    private fun shareCurrentWalletState() {
        (hdWalletAccountsMap.values + singleAddressAccountsMap.values).forEach {
            notifyTransactions(it.getTransactions(true), it.unspents.toSet())
        }
    }

    private fun initializeWalletAccountsListeners() {
        Log.d(LOG_TAG, "initializeWalletAccountsListeners, number of HD accounts = ${hdWalletAccountsMap.values.size}")
        hdWalletAccountsMap.values.forEach {
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
            val walletAccount = getAccountWallet(accountIndexString)
            if (walletAccount != null) {
                hdWalletAccountsMap[accountIndexString] = walletAccount
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
                // TODO Return checkpoint initialization after putting correct checkpoints.txt to assets directory
                // initializeCheckpoint(earliestKeyCreationTime)
            }
        }
        blockChain = BlockChain(Constants.NETWORK_PARAMETERS, (hdWalletAccountsMap.values + singleAddressAccountsMap.values).toList(),
                blockStore)
        initializeWalletAccountsListeners()
    }

    private fun initializeEarliestKeyCreationTime(): Long {
        Log.d(LOG_TAG, "initializeEarliestKeyCreationTime")
        var earliestKeyCreationTime = 0L
        for (walletAccount in (hdWalletAccountsMap.values + singleAddressAccountsMap.values)) {
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
        peerGroup = PeerGroup(Constants.NETWORK_PARAMETERS, blockChain)
        peerGroup!!.setDownloadTxDependencies(0) // recursive implementation causes StackOverflowError

        peerGroup!!.setUserAgent(Constants.USER_AGENT, spvModuleApplication.packageInfo!!.versionName)

        peerGroup!!.addConnectedEventListener(peerConnectivityListener)
        peerGroup!!.addDisconnectedEventListener(peerConnectivityListener)

        val trustedPeerHost = configuration.trustedPeerHost
        val hasTrustedPeer = trustedPeerHost != null

        val connectTrustedPeerOnly = hasTrustedPeer && configuration.trustedPeerOnly
        peerGroup!!.maxConnections = if (connectTrustedPeerOnly) 1 else spvModuleApplication.maxConnectedPeers()
        peerGroup!!.setConnectTimeoutMillis(Constants.PEER_TIMEOUT_MS)
        peerGroup!!.setPeerDiscoveryTimeoutMillis(Constants.PEER_DISCOVERY_TIMEOUT_MS.toLong())

        peerGroup!!.addPeerDiscovery(object : PeerDiscovery {
            private val normalPeerDiscovery = MultiplexingDiscovery.forServices(Constants.NETWORK_PARAMETERS, 0)

            @Throws(PeerDiscoveryException::class)
            override fun getPeers(services: Long, timeoutValue: Long, timeoutUnit: TimeUnit)
                    : Array<InetSocketAddress> {
                propagate(Constants.CONTEXT)
                val peers = LinkedList<InetSocketAddress>()

                var needsTrimPeersWorkaround = false

                if (hasTrustedPeer) {
                    Log.i(LOG_TAG, "check(), trusted peer '$trustedPeerHost' " +
                            if (connectTrustedPeerOnly) " only." else "")

                    val addr = InetSocketAddress(trustedPeerHost, Constants.NETWORK_PARAMETERS.port)
                    if (addr.address != null) {
                        peers.add(addr)
                        needsTrimPeersWorkaround = true
                    }
                }

                if (!connectTrustedPeerOnly) {
                    peers.addAll(Arrays.asList(*normalPeerDiscovery.getPeers(services, timeoutValue, timeoutUnit)))
                }

                // workaround because PeerGroup will shuffle peers
                if (needsTrimPeersWorkaround) {
                    while (peers.size >= spvModuleApplication.maxConnectedPeers()) {
                        peers.removeAt(peers.size - 1)
                    }
                }

                return peers.toTypedArray()
            }

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
            for (walletAccount in (hdWalletAccountsMap.values + singleAddressAccountsMap.values)) {
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

        for (idWallet in hdWalletAccountsMap) {
            idWallet.value.run {
                saveToFile(walletFile(idWallet.key))
                removeChangeEventListener(walletEventListener)
                removeCoinsReceivedEventListener(walletEventListener)
                removeCoinsSentEventListener(walletEventListener)
            }
        }

        for (saWallet in singleAddressAccountsMap) {
            saWallet.value.run {
                saveToFile(singleAddressWalletFile(saWallet.key))
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
            for (walletAccount in hdWalletAccountsMap.values + singleAddressAccountsMap.values) {
                peerGroup!!.addWallet(walletAccount)
            }
            if (impediments.isEmpty() && peerGroup != null) {
                downloadProgressTracker = DownloadProgressTrackerExt()

                //Start download blockchain
                Log.i(LOG_TAG, "checkImpediments, peergroup startBlockChainDownload")
                peerGroup!!.startBlockChainDownload(downloadProgressTracker)
                //Release wakelock
                if (wakeLock != null && wakeLock!!.isHeld) {
                    wakeLock!!.release()
                    wakeLock = null
                }
            } else {
                Log.i(LOG_TAG, "checkImpediments, impediments size is ${impediments.size} && peergroup is $peerGroup")
            }
            broadcastBlockchainState()
        }
    }

    private fun getAccountWallet(accountId: String): Wallet? {
        var wallet: Wallet? = hdWalletAccountsMap[accountId]
        if (wallet != null) {
            return wallet
        }
        val walletFile = walletFile(accountId)
        if (walletFile.exists()) {
            wallet = loadWalletFromProtobuf(accountId, walletFile)
            afterLoadWallet(wallet, accountId)
            cleanupFiles(accountId)
        }
        return wallet
    }

    private fun getSingleAddressAccountWallet(guid: String): Wallet? {
        var wallet: Wallet? = singleAddressAccountsMap[guid]
        if (wallet != null) {
            return wallet
        }
        val walletFile = singleAddressWalletFile(guid)
        if (walletFile.exists()) {
            wallet = loadWalletFromProtobuf(guid, walletFile)
            afterLoadWallet(wallet, guid)
            cleanupFiles(guid)
        }
        return wallet
    }

    private fun loadWalletFromProtobuf(accountId: String, walletAccountFile: File): Wallet {
        var wallet = FileInputStream(walletAccountFile).use { walletStream ->
            try {
                WalletProtobufSerializer().readWallet(walletStream).apply {
                    if (params != Constants.NETWORK_PARAMETERS) {
                        throw UnreadableWalletException("bad wallet network parameters: ${params.id}")
                    }
                }
            } catch (x: FileNotFoundException) {
                Log.e(LOG_TAG, "problem loading wallet", x)
                Looper.prepare()
                Toast.makeText(spvModuleApplication, x.javaClass.name, Toast.LENGTH_LONG).show()
                restoreWalletFromBackup(accountId)
            } catch (x: UnreadableWalletException) {
                Log.e(LOG_TAG, "problem loading wallet", x)
                Looper.prepare()
                Toast.makeText(spvModuleApplication, x.javaClass.name, Toast.LENGTH_LONG).show()
                restoreWalletFromBackup(accountId)
            }
        }

        if (!wallet!!.isConsistent) {
            Toast.makeText(spvModuleApplication, "inconsistent wallet: " + walletAccountFile, Toast.LENGTH_LONG).show()
            wallet = restoreWalletFromBackup(accountId)
        }

        if (wallet.params != Constants.NETWORK_PARAMETERS) {
            throw Error("bad wallet network parameters: ${wallet.params.id}")
        }
        return wallet
    }

    private fun restoreWalletFromBackup(accountId: String): Wallet {
        backupFileInputStream(accountId).use { stream ->
            val walletAccount = WalletProtobufSerializer().readWallet(stream, true, null)
            if (!walletAccount.isConsistent) {
                throw Error("inconsistent backup")
            }
            //TODO : Reset Blockchain ?
            Log.i(LOG_TAG, "wallet/account restored from backup: "
                    + "'${backupFileName(accountId)}'")
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

    private fun afterLoadWallet(walletAccount: Wallet, accountId: String) {
        Log.d(LOG_TAG, "afterLoadWallet, accountId = $accountId")
        walletAccount.autosaveToFile(walletFile(accountId), 10, TimeUnit.SECONDS, WalletAutosaveEventListener())
        // clean up spam
        walletAccount.cleanup()
        migrateBackup(walletAccount, accountId)
    }

    private fun migrateBackup(walletAccount: Wallet, accountId: String) {
        if (!backupFile(accountId).exists()) {
            Log.i(LOG_TAG, "migrating automatic backup to protobuf")
            // make sure there is at least one recent backup
            backupWallet(walletAccount, accountId)
        }
    }

    private fun backupWallet(walletAccount: Wallet, accountId: String) {
        val builder = WalletProtobufSerializer().walletToProto(walletAccount).toBuilder()

        // strip redundant
        builder.clearTransaction()
        builder.clearLastSeenBlockHash()
        builder.lastSeenBlockHeight = -1
        builder.clearLastSeenBlockTimeSecs()
        val walletProto = builder.build()

        backupFileOutputStream(accountId).use {
            try {
                walletProto.writeTo(it)
            } catch (x: IOException) {
                Log.e(LOG_TAG, "problem writing key backup", x)
            }
        }
    }


    private fun cleanupFiles(accountId: String) {
        for (filename in spvModuleApplication.fileList()) {
            if (filename.startsWith(Constants.Files.WALLET_KEY_BACKUP_BASE58)
                    || filename.startsWith(backupFileName(accountId) + '.')
                    || filename.endsWith(".tmp")) {
                val file = File(spvModuleApplication.filesDir, filename)
                Log.i(LOG_TAG, "removing obsolete file: '$file'")
                file.delete()
            }
        }
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
    fun addWalletAccount(spendingKeyB58: String, creationTimeSeconds: Long,
                         accountIndex: String) {
        Log.d(LOG_TAG, "addWalletAccount, accountIndex = $accountIndex," +
                " creationTimeSeconds = $creationTimeSeconds")
        propagate(Constants.CONTEXT)
        this.spendingKeyB58 = spendingKeyB58
        sharedPreferences.edit()
                .putString(SPENDINGKEYB58_PREF, spendingKeyB58)
                .apply()
        createMissingAccounts(spendingKeyB58, creationTimeSeconds, accountIndex)
    }

    @Synchronized
    fun addSingleAddressAccount(guid: String, privateKey: ByteArray) {
        val ecKey = ECKey.fromPrivate(privateKey)
        val walletAccount = Wallet.fromKeys(Constants.NETWORK_PARAMETERS, arrayListOf(ecKey))
        walletAccount.saveToFile(singleAddressWalletFile(guid))

        singleAddressAccountGuidStrings.add(guid)
        sharedPreferences.edit()
                .putStringSet(SINGLE_ADDRESS_ACCOUNT_GUID_SET_PREF, singleAddressAccountGuidStrings)
                .apply()

        singleAddressAccountsMap.put(guid, walletAccount)
    }

    fun removeSingleAddressAccount(guid: String) {
        singleAddressAccountsMap.remove(guid)
    }

    private fun createMissingAccounts(spendingKeyB58: String, creationTimeSeconds: Long, accountId: String) {
        val accountGuid :String = parseHDWalletId(accountId).first
        var maxIndexWithActivity = -1
        for (accountIndexString in accountIndexStrings) {
            val accGuid : String = parseHDWalletId(accountIndexString).first
            if (!accGuid.equals(accountGuid))
                continue

            val accountIndex : Int = parseHDWalletId(accountIndexString).second
            val walletAccount = hdWalletAccountsMap[accountIndexString]
            if (walletAccount?.getTransactions(false)?.isEmpty() == false) {
                maxIndexWithActivity = Math.max(accountIndex, maxIndexWithActivity)
            }
        }
        for (i in maxIndexWithActivity + 1..maxIndexWithActivity + ACCOUNT_LOOKAHEAD) {
            if (hdWalletAccountsMap[accountGuid] == null) {
                createOneAccount(spendingKeyB58, creationTimeSeconds, accountGuid, i)
            }
        }
    }

    private fun createOneAccount(spendingKeyB58: String, creationTimeSeconds: Long, accountGuid: String, accountIndex: Int) {
        Log.d(LOG_TAG, "createOneAccount, accountIndex = $accountIndex," +
                " creationTimeSeconds = $creationTimeSeconds")
        propagate(Constants.CONTEXT)
        //val walletAccount = Wallet.fromSpendingKey(Constants.NETWORK_PARAMETERS,
        //    DeterministicKey.deserializeB58(spendingKeyB58, Constants.NETWORK_PARAMETERS))
        val coinTypeKey = DeterministicKey.deserializeB58(spendingKeyB58, Constants.NETWORK_PARAMETERS)
        coinTypeKey.creationTimeSeconds = creationTimeSeconds
        val accountLevelKey = HDKeyDerivation.deriveChildKey(coinTypeKey,
                ChildNumber(accountIndex, true), creationTimeSeconds)
        val walletAccount = Wallet.fromSpendingKey(Constants.NETWORK_PARAMETERS, accountLevelKey)
        /*val walletAccount = Wallet.fromSeed(
                Constants.NETWORK_PARAMETERS,
                DeterministicSeed(bip39Passphrase, null, "", creationTimeSeconds),
                ImmutableList.of(ChildNumber(44, true), ChildNumber(1, true),
                        ChildNumber(accountIndex, true)))*/
        walletAccount.keyChainGroupLookaheadSize = 20
        accountIndexStrings.add(makeHDWalletId(accountGuid, accountIndex))
        sharedPreferences.edit()
                .putStringSet(ACCOUNT_INDEX_STRING_SET_PREF, accountIndexStrings)
                .apply()
        configuration.maybeIncrementBestChainHeightEver(walletAccount.lastBlockSeenHeight)

        walletAccount.saveToFile(walletFile(makeHDWalletId(accountGuid, accountIndex)))
    }

    @Synchronized
    fun broadcastTransaction(transaction: Transaction, accountId: String) {
        propagate(Constants.CONTEXT)
        val wallet = hdWalletAccountsMap[accountId]!!
        wallet.commitTx(transaction)
        wallet.saveToFile(walletFile(accountId))
        val transactionBroadcast = peerGroup!!.broadcastTransaction(transaction)
        val future = transactionBroadcast.future()
        future.get()
    }

    @Synchronized
    fun broadcastTransactionSingleAddress(transaction: Transaction, guid: String) {
        propagate(Constants.CONTEXT)
        val wallet = singleAddressAccountsMap[guid]!!
        wallet.commitTx(transaction)
        wallet.saveToFile(singleAddressWalletFile(guid))
        val transactionBroadcast = peerGroup!!.broadcastTransaction(transaction)
        val future = transactionBroadcast.future()
        future.get()
    }

    fun broadcastTransaction(sendRequest: SendRequest, accountId: String) {
        propagate(Constants.CONTEXT)
        hdWalletAccountsMap[accountId]?.completeTx(sendRequest)
        broadcastTransaction(sendRequest.tx, accountId)
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

    private val transactionsReceived = AtomicInteger()

    private val walletEventListener = object : ThrottlingWalletChangeListener(APPWIDGET_THROTTLE_MS) {
        override fun onCoinsReceived(walletAccount: Wallet?, transaction: Transaction?,
                                     prevBalance: Coin?, newBalance: Coin?) {
            transactionsReceived.incrementAndGet()
            checkIfFirstTransaction(walletAccount)
            for (key in hdWalletAccountsMap.keys()) {
                if(hdWalletAccountsMap.get(key) == walletAccount) {
                    val index: Int = parseHDWalletId(key).second
                    notifySatoshisReceived(transaction!!.getValue(walletAccount).value, 0L, index)
                }
            }
        }

        override fun onCoinsSent(walletAccount: Wallet?, transaction: Transaction?,
                                 prevBalance: Coin?, newBalance: Coin?) {
            transactionsReceived.incrementAndGet()
            checkIfFirstTransaction(walletAccount)
        }

        private fun checkIfFirstTransaction(walletAccount: Wallet?) {
            //If this is the first transaction found on that wallet/account, stop the download of the blockchain.
            if (walletAccount!!.getRecentTransactions(2, true).size == 1) {
                var accountId = "";
                for (key in hdWalletAccountsMap.keys()) {
                    if (hdWalletAccountsMap.get(key)!!.currentReceiveAddress() ==
                            walletAccount.currentReceiveAddress()) {
                        accountId = key
                    }
                }
                var accountIdParsed = parseHDWalletId(accountId)
                var accountGuid = accountIdParsed.first
                val accountIndex = accountIdParsed.second
                val newAccountIndex = accountIndex + 1
                if (doesWalletAccountExist(makeHDWalletId(accountGuid, newAccountIndex))) {
                    return
                }
                Log.d(LOG_TAG, "walletEventListener, checkIfFirstTransaction, first transaction " +
                        "found on that wallet/account with accountIndex = $accountIndex," +
                        " stop the download of the blockchain")
                //TODO Investigate why it is stuck while stopping.
                val listenableFuture = peerGroup!!.stopAsync()
                listenableFuture.addListener(
                        Runnable {
                            Log.d(LOG_TAG, "walletEventListener, checkIfFirstTransaction, will try to " +
                                    "addWalletAccountWithExtendedKey with newAccountIndex = $newAccountIndex")
                            spvModuleApplication.addWalletAccountWithExtendedKey(spendingKeyB58,
                                    walletAccount.lastBlockSeenTimeSecs + 1,
                                    makeHDWalletId(accountGuid, newAccountIndex))
                        },
                        Executors.newSingleThreadExecutor())
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
        application.contentResolver.notifyChange(contentUri, null);
    }

    @Synchronized
    private fun notifyTransactions(transactions: Set<Transaction>, utxos: Set<TransactionOutput>) {
        if (!transactions.isEmpty()) {
            // send the new transaction and the *complete* utxo set of the account
            SpvMessageSender.sendTransactions(transactions, utxos)
        }
    }

    fun sendTransactions(accountId: String) {
        val walletAccount = hdWalletAccountsMap.get(accountId) ?: return
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

    fun getTransactionsSummary(accountId : String): List<TransactionSummary> {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "getTransactionsSummary, accountIndex = $accountId")

        val walletAccount = hdWalletAccountsMap.get(accountId) ?: return mutableListOf()
        return getTransactionsSummary(walletAccount)
    }

    fun getTransactionsSummarySingleAddress(guid: String): List<TransactionSummary> {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "getTransactionsSummary, guid = $guid")

        if (!singleAddressAccountsMap.containsKey(guid))
            return mutableListOf<TransactionSummary>()

        return getTransactionsSummary(singleAddressAccountsMap.get(guid)!!)
    }

    fun getTransactionDetails(accountId: String, hash: String): TransactionDetails? {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "getTransactionDetails, accountIndex = $accountId, hash = $hash")
        val walletAccount = hdWalletAccountsMap.get(accountId) ?: return null
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

    fun getAccountIndices(): List<String> = hdWalletAccountsMap.keys.toList()

    fun getAccountBalance(accountId: String): Long {
        propagate(Constants.CONTEXT)
        val walletAccount = hdWalletAccountsMap.get(accountId)
        Log.d(LOG_TAG, "getAccountBalance, accountIndex = $accountId")
        return walletAccount?.getBalance(Wallet.BalanceType.ESTIMATED)?.getValue()?: 0
    }

    fun getAccountReceiving(accountId: String): Long {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "getAccountReceiving, accountIndex = $accountId")
        val walletAccount = hdWalletAccountsMap.get(accountId)?: return 0
        var receiving = 0L
        walletAccount.pendingTransactions.forEach {
            val sent = it.getValueSentFromMe(walletAccount)
            val netReceived = it.getValueSentToMe(walletAccount).minus(sent)
            receiving += if(netReceived.isPositive) netReceived.value else 0
        }
        return receiving
    }

    fun getAccountSending(accountId: String): Long {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "getAccountSending, accountIndex = $accountId")
        val walletAccount = hdWalletAccountsMap.get(accountId)?: return 0
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

    fun getAccountCurrentReceiveAddress(accountId: String): org.bitcoinj.core.Address? {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "getAccountCurrentReceiveAddress, accountIndex = $accountId")
        val walletAccount = hdWalletAccountsMap.get(accountId)?: return null
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

    fun calculateMaxSpendableAmount(accountId: String, txFee: TransactionFee, txFeeFactor: Float): Coin? {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "calculateMaxSpendableAmount, accountIndex = $accountId, txFee = $txFee, txFeeFactor = $txFeeFactor")
        val walletAccount = hdWalletAccountsMap[accountId] ?: return null
        val balance = walletAccount.balance;
        return balance.subtract(Constants.minerFeeValue(txFee, txFeeFactor))
    }

    fun checkSendAmount(accountId: String, txFee: TransactionFee, txFeeFactor: Float, amountToSend: Long): TransactionContract.CheckSendAmount.Result? {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "checkSendAmount, accountIndex = $accountId, minerFee = $txFee, txFeeFactor = $txFeeFactor, amountToSend = $amountToSend")
        val walletAccount = hdWalletAccountsMap[accountId] ?: return null
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
            chainDownloadPercentDone = pct.toInt()
            Log.d(LOG_TAG, String.format(Locale.US, "Chain download %d%% done with %d blocks to go, block date %s", pct.toInt(), blocksSoFar,
                    Utils.dateTimeFormat(date)))
        }

        override fun startDownload(blocks: Int) {
            Log.d(LOG_TAG, "Downloading block chain of size " + blocks + ". " +
                    if (blocks > 1000) "This may take a while." else "")
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
            Log.d(LOG_TAG, "doneDownload(), Blockchain is fully downloaded.")
            for (walletAccount in (hdWalletAccountsMap.values + singleAddressAccountsMap.values)) {
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

    private class WalletAutosaveEventListener : WalletFiles.Listener {
        override fun onBeforeAutoSave(file: File) {}

        override fun onAfterAutoSave(file: File) {}
    }

    private class ActivityHistoryEntry(val numTransactionsReceived: Int, val numBlocksDownloaded: Int) {
        override fun toString(): String = "$numTransactionsReceived / $numBlocksDownloaded"
    }

    private fun backupFileOutputStream(accountId : String): FileOutputStream =
            spvModuleApplication.openFileOutput(backupFileName(accountId), Context.MODE_PRIVATE)

    private fun backupFileInputStream(accountId: String): FileInputStream =
            spvModuleApplication.openFileInput(backupFileName(accountId))

    private fun backupSingleAddressFileInputStream(guid: String): FileInputStream =
            spvModuleApplication.openFileInput(backupSingleAddressFileName(guid))

    private fun backupFile(accountId: String): File =
            spvModuleApplication.getFileStreamPath(backupFileName(accountId))

    private fun backupFileName(accountId: String): String =
            Constants.Files.WALLET_KEY_BACKUP_PROTOBUF + "_$accountId"

    private fun backupSingleAddressFileName(guid: String): String =
            Constants.Files.WALLET_KEY_BACKUP_PROTOBUF + "_$guid"

    private fun walletFile(accountId: String): File =
            spvModuleApplication.getFileStreamPath(walletFileName(accountId))

    private fun walletFileName(accountId: String): String =
            Constants.Files.WALLET_FILENAME_PROTOBUF + "_$accountId"

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
        private val PASSPHRASE_PREF = "bip39Passphrase"
        private val SPENDINGKEYB58_PREF = "spendingKeyB58"
        private val ACCOUNT_LOOKAHEAD = 3

        //Parse HD wallet account ID and return the pair (GUID, account index)
        fun parseHDWalletId(accountId : String) : Pair<String, Int> {
            val index = accountId.indexOf("_")
            val accountGuid = accountId.substring(0, index)
            val accountIndex = Integer.parseInt(accountId.substring(index + 1))
            return Pair(accountGuid, accountIndex)
        }

        //Makes full HD wallet account id by combining GUID and account index
        fun makeHDWalletId(accountGuid: String, index : Int) : String {
            return accountGuid + "_" + index.toString()
        }
    }

    fun doesWalletAccountExist(accountId: String): Boolean =
            null != hdWalletAccountsMap.get(accountId)

    fun doesSingleAddressWalletAccountExist(guid: String) : Boolean =
            null != singleAddressAccountsMap.get(guid)
}
