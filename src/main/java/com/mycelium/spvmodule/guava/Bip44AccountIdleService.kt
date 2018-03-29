package com.mycelium.spvmodule.guava

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.Looper
import android.os.PowerManager
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
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.net.discovery.MultiplexingDiscovery
import org.bitcoinj.net.discovery.PeerDiscovery
import org.bitcoinj.net.discovery.PeerDiscoveryException
import org.bitcoinj.script.Script
import org.bitcoinj.store.BlockStore
import org.bitcoinj.store.SPVBlockStore
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.*
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener
import org.bitcoinj.wallet.listeners.WalletCoinsSentEventListener
import org.spongycastle.util.encoders.Hex
import java.io.*
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.*

class Bip44AccountIdleService : AbstractScheduledService() {
    private val singleAddressAccountsMap:ConcurrentHashMap<String, Wallet> = ConcurrentHashMap()
    private val walletsAccountsMap: ConcurrentHashMap<String, Wallet> = ConcurrentHashMap()
    private var downloadProgressTracker: Bip44DownloadProgressTracker? = null
    private val impediments = EnumSet.noneOf(BlockchainState.Impediment::class.java)
    private val connectivityReceiver = Bip44ConnectivityReceiver(impediments)

    private val initializingMonitor = Object()
    @Volatile
    private var ready = false

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
    private val peerConnectivityListener: Bip44PeerConnectivityListener = Bip44PeerConnectivityListener()
    private lateinit var blockStore: BlockStore

    private val semaphore : Semaphore = Semaphore(WRITE_THREADS_LIMIT)

    fun waitUntilInitialized() {
        synchronized(initializingMonitor){
            while (!ready) {
                try {
                    initializingMonitor.wait()
                } catch (ignore: InterruptedException) {
                }
            }
        }
    }

    fun getSyncProgress(): Float = Bip44DownloadProgressTracker.getSyncProgress()

    override fun shutDown() {
        Log.d(LOG_TAG, "shutDown")
        stopPeergroup()
    }

    override fun scheduler(): Scheduler =
            AbstractScheduledService.Scheduler.newFixedRateSchedule(2, 2, TimeUnit.MINUTES)

    override fun runOneIteration() {
        //We do that every two minutes
        Log.d(LOG_TAG, "runOneIteration")
        if (walletsAccountsMap.isNotEmpty() || singleAddressAccountsMap.isNotEmpty()) {
            propagate(Constants.CONTEXT)
            checkImpediments()
            downloadProgressTracker!!.checkIfDownloadIsIdling()
        }
    }

    override fun startUp() {
        ready = false
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
        initializePeergroup()

        synchronized (initializingMonitor) {
            ready = true
            initializingMonitor.notifyAll()
        }
        Bip44NotificationManager()
        checkImpediments()
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

    private fun initializeWalletAccountsListeners() {
        Log.d(LOG_TAG, "initializeWalletAccountsListeners, number of HD accounts = ${walletsAccountsMap.values.size}")
        walletsAccountsMap.values.forEach {
            it.addCoinsReceivedEventListener(Threading.SAME_THREAD, walletEventListener)
            it.addCoinsSentEventListener(Threading.SAME_THREAD, walletEventListener)
        }
        Log.d(LOG_TAG, "initializeWalletAccountsListeners, number of SA accounts = ${singleAddressAccountsMap.values.size}")
        singleAddressAccountsMap.values.forEach {
            it.addCoinsReceivedEventListener(Threading.SAME_THREAD, singleAddressWalletEventListener)
        }
    }

    private fun initializeWalletsAccounts() {
        Log.d(LOG_TAG, "initializeWalletsAccounts, number of accounts = ${accountIndexStrings.size}")
        var shouldInitializeCheckpoint = true
        for (accountIndexString in accountIndexStrings) {
            val walletAccount = getAccountWallet(accountIndexString)
            if (walletAccount != null) {
                walletsAccountsMap[accountIndexString] = walletAccount
                if (walletAccount.lastBlockSeenHeight >= 0 && shouldInitializeCheckpoint) {
                    shouldInitializeCheckpoint = false
                }
            }
            notifyCurrentReceiveAddress()
        }

        for (guid in singleAddressAccountGuidStrings) {
            val walletAccount = getSingleAddressAccountWallet(guid)
            if (walletAccount != null) {
                singleAddressAccountsMap[guid] = walletAccount
                if (walletAccount.lastBlockSeenHeight >= 0 && shouldInitializeCheckpoint) {
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
        val customPeers = (configuration.trustedPeerHost).split(",")
        peerGroup = PeerGroup(Constants.NETWORK_PARAMETERS, blockChain).apply {
            setDownloadTxDependencies(0) // recursive implementation causes StackOverflowError

            setUserAgent(Constants.USER_AGENT, spvModuleApplication.packageInfo!!.versionName)

            addConnectedEventListener(peerConnectivityListener)
            addDisconnectedEventListener(peerConnectivityListener)

            maxConnections = when (configuration.peerHostConfig) {
                "mycelium" -> spvModuleApplication.maxConnectedPeers(configuration.myceliumPeerHosts.size)
                "custom" -> spvModuleApplication.maxConnectedPeers(customPeers.size)
                "random" -> spvModuleApplication.maxConnectedPeers()
                else -> throw RuntimeException("unknown peerHostConfig ${configuration.peerHostConfig}")
            }
            setConnectTimeoutMillis(Constants.PEER_TIMEOUT_MS)
            setPeerDiscoveryTimeoutMillis(Constants.PEER_DISCOVERY_TIMEOUT_MS.toLong())

            addPeerDiscovery(object : PeerDiscovery {
                private val normalPeerDiscovery = MultiplexingDiscovery.forServices(Constants.NETWORK_PARAMETERS, 0)

                @Throws(PeerDiscoveryException::class)
                override fun getPeers(services: Long, timeoutValue: Long, timeoutUnit: TimeUnit)
                        : Array<InetSocketAddress> {
                    propagate(Constants.CONTEXT)
                    val peers = when (configuration.peerHostConfig) {
                        "mycelium" -> peersFromUrls(configuration.myceliumPeerHosts)
                        "custom" -> peersFromUrls(customPeers)
                        "random" -> normalPeerDiscovery.getPeers(services, timeoutValue, timeoutUnit)
                        else -> throw RuntimeException("unknown peerHostConfig ${configuration.peerHostConfig}")
                    }

                    if (peers.isEmpty()) {
                        Log.e(LOG_TAG, "No valid peers available!")
                    }
                    Log.d(LOG_TAG, "Using peers ${peers.joinToString(", ")}")
                    return peers
                }

                private fun peersFromUrls(urls: Collection<String>) = urls.map {
                    val serverWithPort = it.replace("tcp://", "").replace("tcp-tls://", "")
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
            startAsync()
        }
    }

    private fun stopPeergroup() {
        Log.d(LOG_TAG, "stopPeergroup")
        propagate(Constants.CONTEXT)
        peerGroup?.apply {
            if (isRunning) {
                stopAsync()
            }
            removeDisconnectedEventListener(peerConnectivityListener)
            removeConnectedEventListener(peerConnectivityListener)
            for (walletAccount in (walletsAccountsMap.values + singleAddressAccountsMap.values)) {
                removeWallet(walletAccount)
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
                removeCoinsReceivedEventListener(walletEventListener)
                removeCoinsSentEventListener(walletEventListener)
            }
        }

        for (saWallet in singleAddressAccountsMap) {
            saWallet.value.run {
                saveWalletAccountToFile(this, walletFile(saWallet.key))
                removeCoinsReceivedEventListener(singleAddressWalletEventListener)
            }
        }

        blockStore.close()
        Log.d(LOG_TAG, "stopPeergroup DONE")
    }

    @Synchronized
    internal fun checkImpediments() {
        //Second condition (downloadProgressTracker) prevent the case where the peergroup is
        // currently downloading the blockchain.
        peerGroup?.apply {
            if (isRunning && downloadProgressTracker?.future?.isDone != false) {
                val powerManager = spvModuleApplication.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "${spvModuleApplication.packageName} blockchain sync init")
                // TODO: implement logic to both shut down the service every x seconds and acquire the wakeLock for only x + 5 seconds
                wakeLock.acquire()
                for (walletAccount in walletsAccountsMap.values + singleAddressAccountsMap.values) {
                    try {
                        addWallet(walletAccount)
                    } catch(e: Exception) {}
                }
                if (impediments.isEmpty()) {
                    downloadProgressTracker = Bip44DownloadProgressTracker(blockChain!!, impediments)

                    //Start download blockchain
                    Log.i(LOG_TAG, "checkImpediments, peergroup startBlockChainDownload")
                    try {
                        startBlockChainDownload(downloadProgressTracker)
                    } catch (t: Throwable) {
                        Log.e(LOG_TAG, t.localizedMessage, t)
                        SpvModuleApplication.getApplication().restartBip44AccountIdleService(false)
                    }
                } else {
                    Log.i(LOG_TAG, "checkImpediments, impediments size is ${impediments.size} && peergroup is $peerGroup")
                    for (walletAccount in walletsAccountsMap.values + singleAddressAccountsMap.values) {
                        removeWallet(walletAccount)
                    }
                }
                //Release wakelock
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                downloadProgressTracker?.broadcastBlockchainState()
            }
        }
    }

    private fun getAccountWallet(compositeId: String): Wallet? {
        var wallet: Wallet? = walletsAccountsMap[compositeId]
        if (wallet != null) {
            return wallet
        }
        val walletFile = walletFile(compositeId)
        if (walletFile.exists()) {
            wallet = loadWalletFromProtobuf(compositeId, walletFile)
            afterLoadWallet(wallet, compositeId)
            cleanupFiles(compositeId)
        }
        return wallet
    }

    internal fun getWalletAccount(compositeId: String): Wallet {
        var wallet: Wallet? = walletsAccountsMap[compositeId]
        if (wallet != null) {
            return wallet
        }
        val walletFile = walletFile(compositeId)
        if (walletFile.exists()) {
            wallet = loadWalletFromProtobuf(compositeId, walletFile)
            afterLoadWallet(wallet, compositeId)
            cleanupFiles(compositeId)
        }
        return wallet!!
    }

    private fun getSingleAddressAccountWallet(guid: String): Wallet? {
        var wallet: Wallet? = singleAddressAccountsMap[guid]
        if (wallet != null) {
            return wallet
        }
        val walletFile = walletFile(guid)
        if (walletFile.exists()) {
            wallet = loadWalletFromProtobuf(guid, walletFile)
            afterLoadSingleAddressWallet(wallet, guid)
            cleanupFiles(guid)
        }
        return wallet
    }

    private fun loadWalletFromProtobuf(accountIndex: String, walletAccountFile: File): Wallet {
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
            Toast.makeText(spvModuleApplication, "inconsistent wallet: $walletAccountFile", Toast.LENGTH_LONG).show()
            wallet = restoreWalletFromBackup(accountIndex)
        }

        if (wallet.params != Constants.NETWORK_PARAMETERS) {
            throw Error("bad wallet network parameters: ${wallet.params.id}")
        }
        return wallet
    }

    private fun restoreWalletFromBackup(accountIndex: String): Wallet =
            restoreWalletFromStream(backupFileInputStream(accountIndex))

    private fun restoreWalletFromStream(stream: FileInputStream): Wallet {
        stream.use {
            val walletAccount = WalletProtobufSerializer().readWallet(it, true, null)
            if (!walletAccount.isConsistent) {
                //TODO : Reset Blockchain ?
                throw Error("inconsistent backup")
            }
            return walletAccount
        }
    }

    private fun afterLoadWallet(walletAccount: Wallet, accountIndex: String) {
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
        walletAccount.autosaveToFile(walletFile(guid), 10, TimeUnit.SECONDS, WalletAutosaveEventListener())
        // clean up spam
        walletAccount.cleanup()
        migrateBackup(walletAccount, guid)
    }

    private fun migrateBackup(walletAccount: Wallet, accountIndex: String) {
        if (!backupFile(accountIndex).exists()) {
            Log.i(LOG_TAG, "migrating automatic backup to protobuf")
            // make sure there is at least one recent backup
            backupWallet(walletAccount, accountIndex)
        }
    }

    private fun backupWallet(walletAccount: Wallet, accountIndex: String) {
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

    private fun cleanupFiles(accountIndex: String) {
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

    private var blockChain: BlockChain? = null

    @Synchronized
    fun addWalletAccount(creationTimeSeconds: Long,
                         guid: String,
                         accountIndex: Int) {
        Log.d(LOG_TAG, "addWalletAccount, accountIndex = $accountIndex," +
                " creationTimeSeconds = $creationTimeSeconds")
        propagate(Constants.CONTEXT)
        createMissingAccounts(guid, creationTimeSeconds)
    }

    @Synchronized
    fun addSingleAddressAccount(guid: String, publicKey: ByteArray) {
        val ecKey = ECKey.fromPublicOnly(publicKey)
        val walletAccount = Wallet.fromKeys(Constants.NETWORK_PARAMETERS, arrayListOf(ecKey))
        saveWalletAccountToFile(walletAccount, walletFile(guid))

        singleAddressAccountGuidStrings.add(guid)
        sharedPreferences.edit()
                .putStringSet(SINGLE_ADDRESS_ACCOUNT_GUID_SET_PREF, singleAddressAccountGuidStrings)
                .apply()

        singleAddressAccountsMap[guid] = walletAccount
    }

    fun removeHdAccount(guid: String, accountIndex: Int) {
        walletsAccountsMap.remove(makeHdAccountCompositeId(guid, accountIndex))
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

    private fun createMissingAccounts(guid: String, creationTimeSeconds: Long) {
        var maxIndexWithActivity = -1
        for (accountIndexString in accountIndexStrings) {
            val accountIndexInfo = parseHdAccountCompositeId(accountIndexString)
            if (accountIndexInfo.first != guid)
                continue
            val walletAccount = walletsAccountsMap[accountIndexString]
            if (walletAccount?.getTransactions(false)?.isEmpty() == false) {
                maxIndexWithActivity = Math.max(accountIndexInfo.second, maxIndexWithActivity)
            }
        }
        val listAccountsToCreate : MutableList<Int> = mutableListOf()
        for (i in maxIndexWithActivity + 1..maxIndexWithActivity + ACCOUNT_LOOKAHEAD) {
            if (walletsAccountsMap[makeHdAccountCompositeId(guid, i)] == null) {
                listAccountsToCreate.add(i)
                SpvMessageSender.requestAccountLevelKeys(guid, listAccountsToCreate, creationTimeSeconds)
            }
        }
    }

    fun createAccounts(guid: String, accountIndexes: ArrayList<Int>, accountKeyStrings: ArrayList<String>, creationTimeSeconds: Long) {
        val accountIndexesIterator = accountIndexes.iterator()
        val accountKeyStringsIterator = accountKeyStrings.iterator()
        check(accountIndexes.size == accountKeyStrings.size)
        while (accountIndexesIterator.hasNext()) {
            val accountIndex = accountIndexesIterator.next()
            val accountKeyString = accountKeyStringsIterator.next()
            createOneAccount(guid, accountIndex, DeterministicKey.deserializeB58(accountKeyString,
                    Constants.NETWORK_PARAMETERS), creationTimeSeconds)
        }
        SpvModuleApplication.getApplication().restartBip44AccountIdleService(false)
    }

    private fun createOneAccount(guid: String, accountIndex: Int, accountLevelKey: DeterministicKey, creationTimeSeconds: Long) {
        Log.d(LOG_TAG, "createOneAccount, accountLevelKey = $accountLevelKey")
        propagate(Constants.CONTEXT)
        val walletAccount = Wallet.fromWatchingKeyB58(Constants.NETWORK_PARAMETERS,
                accountLevelKey.serializePubB58(Constants.NETWORK_PARAMETERS),
                creationTimeSeconds, accountLevelKey.path)
        walletAccount.keyChainGroupLookaheadSize = 20
        val compositeId = makeHdAccountCompositeId(guid, accountIndex)
        accountIndexStrings.add(compositeId)
        sharedPreferences.edit()
                .putStringSet(ACCOUNT_INDEX_STRING_SET_PREF, accountIndexStrings)
                .apply()
        configuration.maybeIncrementBestChainHeightEver(walletAccount.lastBlockSeenHeight)

        saveWalletAccountToFile(walletAccount, walletFile(compositeId))
    }

    fun getPrivateKeysCount(guid: String, accountIndex : Int) : Int {
        return walletsAccountsMap[makeHdAccountCompositeId(guid, accountIndex)]?.activeKeyChain?.issuedExternalKeys
                //If we don't have an account with corresponding index
                ?: return 0
    }

    fun getSingleAddressWalletAccount(guid: String) : Wallet = singleAddressAccountsMap[guid]!!

    @Synchronized
    fun broadcastTransaction(transaction: Transaction, guid: String, accountIndex: Int) {
        propagate(Constants.CONTEXT)
        val compositeIndex = makeHdAccountCompositeId(guid, accountIndex)
        val wallet = walletsAccountsMap[compositeIndex]!!
        wallet.commitTx(transaction)
        saveWalletAccountToFile(wallet, walletFile(compositeIndex))
        peerGroup!!.broadcastTransaction(transaction)
    }

    @Synchronized
    fun broadcastTransactionSingleAddress(transaction: Transaction, guid: String) {
        propagate(Constants.CONTEXT)
        val wallet = singleAddressAccountsMap[guid]!!
        wallet.commitTx(transaction)
        saveWalletAccountToFile(wallet, walletFile(guid))
        peerGroup!!.broadcastTransaction(transaction)
    }

    fun createUnsignedTransaction(operationId: String, sendRequest: SendRequest, guid: String, accountIndex: Int) {
        sendRequest.useForkId = true
        sendRequest.missingSigsMode = Wallet.MissingSigsMode.USE_OP_ZERO
        sendRequest.signInputs = false
        val compositeIndex= makeHdAccountCompositeId(guid, accountIndex)
        walletsAccountsMap[compositeIndex]?.completeTx(sendRequest)
        val networkParameters = walletsAccountsMap[compositeIndex]?.networkParameters
        val utxosHex = getUtxosHex(sendRequest.tx.inputs, networkParameters)
        sendUnsignedTransactionToMbw(operationId, sendRequest.tx, accountIndex,
                utxosHex)
    }

    fun createUnsignedTransactionSingleAddress(operationId: String, sendRequest: SendRequest, guid: String) {
        sendRequest.useForkId = true
        sendRequest.missingSigsMode = Wallet.MissingSigsMode.USE_OP_ZERO
        sendRequest.signInputs = false
        singleAddressAccountsMap[guid]?.completeTx(sendRequest)
        val networkParameters = singleAddressAccountsMap[guid]?.networkParameters
        val utxosHex = getUtxosHex(sendRequest.tx.inputs, networkParameters)
        sendUnsignedTransactionToMbwSingleAddress(operationId, sendRequest.tx, utxosHex, guid)
    }

    private fun getUtxosHex(inputs: List<TransactionInput>, networkParameters: NetworkParameters?): ArrayList<String> {
        val utxosHex = ArrayList<String>()
        inputs.map {
            it.connectedOutput!!.apply {
                val utxo = UTXO(parentTransactionHash,
                        index.toLong(),
                        value,
                        parentTransaction!!.confidence.appearedAtChainHeight,
                        parentTransaction!!.isCoinBase,
                        Script(scriptBytes),
                        getAddressFromP2PKHScript(networkParameters)!!.toBase58())
                val bos = ByteArrayOutputStream()
                utxo.serializeToStream(bos)
                utxosHex.add(Hex.toHexString(bos.toByteArray()))
            }
        }
        return utxosHex
    }

    private fun sendUnsignedTransactionToMbw(operationId: String, transaction: Transaction,
                                             accountIndex: Int, utxosHex: List<String>) {
        SpvMessageSender.sendUnsignedTransactionToMbw(operationId, transaction, accountIndex,
                utxosHex)
    }

    private fun sendUnsignedTransactionToMbwSingleAddress(operationId: String, unsignedTransaction: Transaction, txOutputHex: List<String>, guid: String) {
        SpvMessageSender.sendUnsignedTransactionToMbwSingleAddress(operationId, unsignedTransaction, txOutputHex, guid)
    }

    private val walletEventListener = object : WalletCoinsReceivedEventListener, WalletCoinsSentEventListener {
        override fun onCoinsReceived(walletAccount: Wallet?, transaction: Transaction?,
                                     prevBalance: Coin?, newBalance: Coin?) {
            addMoreAccountsToLookAhead(walletAccount)
            for (key in walletsAccountsMap.keys()) {
                if(walletsAccountsMap[key] == walletAccount) {
                    val compositeId = parseHdAccountCompositeId(key)
                    notifySatoshisReceived(transaction!!.getValue(walletAccount).value, 0L, compositeId.first, compositeId.second)
                }
            }
        }

        override fun onCoinsSent(walletAccount: Wallet?, transaction: Transaction?,
                                 prevBalance: Coin?, newBalance: Coin?) {
            addMoreAccountsToLookAhead(walletAccount)
        }

        // If we found at least one transaction on the latest account in the map,
        // it is possible that some funds may exist on the next account.
        // So we should take the next account into work
        private fun addMoreAccountsToLookAhead(walletAccount: Wallet?) {
            if (walletAccount!!.getRecentTransactions(1, true).size == 1) {
                var accountCompositeIdStr = ""

                // Find an HD account index in the accounts' map
                walletsAccountsMap.entries.filter {
                    walletAccount == it.value
                }.forEach {
                    accountCompositeIdStr = it.key
                }

                val accountCompositeId = parseHdAccountCompositeId(accountCompositeIdStr)

                //Find number of accounts with the same guid
                var accountsNumber = 0
                walletsAccountsMap.entries.filter {
                    it.key.contains(accountCompositeId.first)
                }.forEach {
                    accountsNumber++
                }

                if (accountCompositeId.second == accountsNumber) {
                    val listenableFuture = peerGroup!!.stopAsync()
                    listenableFuture.addListener(
                            Runnable {
                                spvModuleApplication.addWalletAccountWithExtendedKey(accountCompositeId.first,
                                        walletAccount.lastBlockSeenTimeSecs + 1,
                                        accountCompositeId.second + 1)
                            },
                            Executors.newSingleThreadExecutor())
                }
            }
        }
    }

    private val singleAddressWalletEventListener = WalletCoinsReceivedEventListener { walletAccount, transaction, _, _ ->
        for (key in singleAddressAccountsMap.keys()) {
            if(singleAddressAccountsMap[key] == walletAccount) {
                notifySingleAddressSatoshisReceived(transaction!!.getValue(walletAccount).value, 0L, key)
            }
        }
    }

    private fun notifySatoshisReceived(satoshisReceived: Long, satoshisSent: Long, guid: String, accountIndex: Int) {
        SpvMessageSender.notifySatoshisReceived(satoshisReceived, satoshisSent, guid, accountIndex)
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

    private fun getTransactionsSummary(walletAccount : Wallet): List<TransactionSummary> {
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
            val destAddressOptional: Optional<Address> = Optional.fromNullable(destAddress)
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
            transactionsSummary.add(transactionSummary)
        }
        return transactionsSummary.toList()

    }

    fun makeHdAccountCompositeId(guid: String, accountIndex: Int): String {
        return guid + "_" + accountIndex
    }

    fun parseHdAccountCompositeId(strIndex : String) : Pair<String, Int> {
        val delimiterIndex = strIndex.indexOf('_')
        return Pair(strIndex.substring(0, delimiterIndex), strIndex.substring(delimiterIndex + 1).toInt())
    }

    fun getTransactionsSummary(guid: String, accountIndex: Int): List<TransactionSummary> {
        propagate(Constants.CONTEXT)
        val ix = makeHdAccountCompositeId(guid, accountIndex)
        Log.d(LOG_TAG, "getTransactionsSummary, accountIndex = $ix")

        val walletAccount = walletsAccountsMap[ix] ?: return mutableListOf()
        return getTransactionsSummary(walletAccount)
    }

    fun getTransactionsSummary(guid: String): List<TransactionSummary> {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "getTransactionsSummary, guid = $guid")

        val walletAccount = singleAddressAccountsMap[guid]
                ?: return mutableListOf()
        return getTransactionsSummary(walletAccount)
    }

    fun getTransactionDetails(guid: String, accountIndex: Int, hash: String): TransactionDetails? {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "getTransactionDetails, accountIndex = $accountIndex, hash = $hash")
        val walletAccount = walletsAccountsMap[makeHdAccountCompositeId(guid, accountIndex)] ?: return null
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
        return TransactionDetails(Sha256Hash.wrap(hash),
                height,
                (transactionBitcoinJ.updateTime.time / 1000).toInt(), inputs.toTypedArray(),
                outputs.toTypedArray(), transactionBitcoinJ.optimalEncodingMessageSize)
    }

    fun getAccountIndices(guid: String): List<Int> {
        return walletsAccountsMap.filter { it.key.contains(guid) }.map { parseHdAccountCompositeId(it.key).second }
    }

    fun getAccountBalance(guid: String, accountIndex: Int): Long {
        propagate(Constants.CONTEXT)
        val walletAccount = walletsAccountsMap[makeHdAccountCompositeId(guid, accountIndex)]
        return walletAccount?.getBalance(Wallet.BalanceType.ESTIMATED)?.getValue()?: 0
    }

    fun getAccountReceiving(guid: String, accountIndex: Int): Long {
        propagate(Constants.CONTEXT)
        val walletAccount = walletsAccountsMap[makeHdAccountCompositeId(guid, accountIndex)] ?: return 0
        return walletAccount.pendingTransactions.sumByLong {
            val sent = it.getValueSentFromMe(walletAccount)
            val netReceived = it.getValueSentToMe(walletAccount).minus(sent)
            if(netReceived.isPositive) netReceived.value else 0
        }
    }

    fun getAccountSending(guid: String, accountIndex: Int): Long {
        propagate(Constants.CONTEXT)
        val walletAccount = walletsAccountsMap[makeHdAccountCompositeId(guid, accountIndex)] ?: return 0
        return walletAccount.pendingTransactions.sumByLong {
            val received = it.getValueSentToMe(walletAccount)
            val netSent = it.getValueSentFromMe(walletAccount).minus(received)
            if(netSent.isPositive) netSent.value else 0
        }
    }

    fun getSingleAddressAccountBalance(guid: String): Long {
        propagate(Constants.CONTEXT)
        val walletAccount = singleAddressAccountsMap[guid]
        return walletAccount?.getBalance(Wallet.BalanceType.ESTIMATED)?.getValue()?: 0
    }

    fun getSingleAddressAccountReceiving(guid: String): Long {
        propagate(Constants.CONTEXT)
        val walletAccount = singleAddressAccountsMap[guid] ?: return 0
        return walletAccount.pendingTransactions.sumByLong {
            val sent = it.getValueSentFromMe(walletAccount)
            val netReceived = it.getValueSentToMe(walletAccount).minus(sent)
            if(netReceived.isPositive) netReceived.value else 0
        }
    }

    fun getSingleAddressAccountSending(guid: String): Long {
        propagate(Constants.CONTEXT)
        val walletAccount = singleAddressAccountsMap[guid] ?: return 0
        return walletAccount.pendingTransactions.sumByLong {
            val received = it.getValueSentToMe(walletAccount)
            val netSent = it.getValueSentFromMe(walletAccount).minus(received)
            if(netSent.isPositive) netSent.value else 0
        }
    }

    fun getAccountCurrentReceiveAddress(guid: String, accountIndex: Int): org.bitcoinj.core.Address? {
        propagate(Constants.CONTEXT)
        val walletAccount = walletsAccountsMap[makeHdAccountCompositeId(guid, accountIndex)] ?: return null
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

    fun calculateMaxSpendableAmount(guid: String, accountIndex: Int, txFee: TransactionFee, txFeeFactor: Float): Coin? {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "calculateMaxSpendableAmount, accountIndex = $accountIndex, txFee = $txFee, txFeeFactor = $txFeeFactor")
        val walletAccount = walletsAccountsMap[makeHdAccountCompositeId(guid, accountIndex)] ?: return null
        val balance = walletAccount.balance
        return balance.subtract(Constants.minerFeeValue(txFee, txFeeFactor))
    }

    fun checkSendAmount(guid: String, accountIndex: Int, txFee: TransactionFee, txFeeFactor: Float, amountToSend: Long): TransactionContract.CheckSendAmount.Result? {
        propagate(Constants.CONTEXT)
        Log.d(LOG_TAG, "checkSendAmount, accountIndex = $accountIndex, minerFee = $txFee, txFeeFactor = $txFeeFactor, amountToSend = $amountToSend")
        val walletAccount = walletsAccountsMap[makeHdAccountCompositeId(guid, accountIndex)] ?: return null
        val address = getNullAddress(Constants.NETWORK_PARAMETERS)
        val amount = Coin.valueOf(amountToSend)
        val sendRequest = SendRequest.to(address, amount)
        sendRequest.feePerKb = Constants.minerFeeValue(txFee, txFeeFactor)

        return try {
            walletAccount.completeTx(sendRequest)
            TransactionContract.CheckSendAmount.Result.RESULT_OK
        } catch (ex :InsufficientMoneyException) {
            TransactionContract.CheckSendAmount.Result.RESULT_NOT_ENOUGH_FUNDS
        } catch (ex :Exception) {
            TransactionContract.CheckSendAmount.Result.RESULT_INVALID
        }
    }

    private fun getNullAddress(network: org.bitcoinj.core.NetworkParameters): org.bitcoinj.core.Address {
        val numAddressBytes = 20
        val bytes = ByteArray(numAddressBytes)
        return org.bitcoinj.core.Address(network, bytes)
    }

    private fun saveWalletAccountToFile(walletAccount: Wallet, file: File) {
        semaphore.acquire()
        walletAccount.saveToFile(file)
        semaphore.release()
    }

    private inner class WalletAutosaveEventListener : WalletFiles.Listener {
        override fun onBeforeAutoSave(file: File) {
            semaphore.acquire()
        }

        override fun onAfterAutoSave(file: File) {
            semaphore.release()
        }
    }

    private fun backupFileOutputStream(accountIndex: String): FileOutputStream =
            spvModuleApplication.openFileOutput(backupFileName(accountIndex), Context.MODE_PRIVATE)

    private fun backupFileInputStream(accountIndex: String): FileInputStream =
            spvModuleApplication.openFileInput(backupFileName(accountIndex))


    private fun backupFile(accountIndex: String): File =
            spvModuleApplication.getFileStreamPath(backupFileName(accountIndex))

    private fun backupFileName(accountIndex: String): String =
            Constants.Files.WALLET_KEY_BACKUP_PROTOBUF + "_$accountIndex"

    private fun walletFile(accountIndex: String): File =
            spvModuleApplication.getFileStreamPath(walletFileName(accountIndex))

    private fun walletFileName(accountIndex: String): String =
            Constants.Files.WALLET_FILENAME_PROTOBUF + "_$accountIndex"

    companion object {
        private var INSTANCE: Bip44AccountIdleService? = null
        fun getInstance(): Bip44AccountIdleService? = INSTANCE
        private val LOG_TAG = Bip44AccountIdleService::class.java.simpleName
        private const val SHARED_PREFERENCES_FILE_NAME = "com.mycelium.spvmodule.PREFERENCE_FILE_KEY"
        private const val ACCOUNT_INDEX_STRING_SET_PREF = "account_index_stringset"
        private const val SINGLE_ADDRESS_ACCOUNT_GUID_SET_PREF = "single_address_account_guid_set"
        private const val ACCOUNT_LOOKAHEAD = 3
        // Wallet class is synchronised inside, so we should not care about writing wallet files to storage ourselves,
        // but we should prevent competing with reading and files cleaning ourselves.
        private const val WRITE_THREADS_LIMIT = 100

        const val SYNC_PROGRESS_PREF = "syncprogress"
    }

    fun doesWalletAccountExist(guid: String, accountIndex: Int): Boolean =
            null != walletsAccountsMap[makeHdAccountCompositeId(guid, accountIndex)]

    fun doesSingleAddressWalletAccountExist(guid: String) : Boolean =
            null != singleAddressAccountsMap[guid]
}

// TODO: in bitcoin we are often dealing with long, where kotlin decided to only provide int, so we
// might want to pack more of these handy functions into the right place.
// https://stackoverflow.com/questions/37537049/why-doesnt-sumbyselector-return-long#37537228
private inline fun <T> Iterable<T>.sumByLong(selector: (T) -> Long): Long {
    var sum = 0L
    for (element in this) {
        sum += selector(element)
    }
    return sum
}
