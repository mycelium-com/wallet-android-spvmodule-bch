package com.mycelium.spvmodule

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.StrictMode
import android.preference.PreferenceManager
import android.support.multidex.MultiDexApplication
import android.util.Log
import com.mycelium.modularizationtools.CommunicationManager
import com.mycelium.modularizationtools.ModuleMessageReceiver
import com.mycelium.spvmodule.guava.Bip44AccountIdleService
import org.bitcoinj.core.*
import org.bitcoinj.core.Context.enableStrictMode
import org.bitcoinj.core.Context.propagate
import org.bitcoinj.crypto.LinuxSecureRandom
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet

class SpvModuleApplication : MultiDexApplication(), ModuleMessageReceiver {
    var configuration: Configuration? = null
        private set
    private var activityManager: ActivityManager? = null

    private var blockchainServiceCancelCoinsReceivedIntent: Intent? = null
    var packageInfo: PackageInfo? = null
        private set
    private val spvMessageReceiver: SpvMessageReceiver = SpvMessageReceiver(this)

    override fun onMessage(callingPackageName: String, intent: Intent) = spvMessageReceiver.onMessage(callingPackageName, intent)

    override fun onCreate() {
        INSTANCE = if (INSTANCE != null && INSTANCE !== this) {
            throw Error("Application was instanciated more than once?")
        } else {
            this
        }

        LinuxSecureRandom() // init proper random number generator

        StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().
                        detectAll().
                        permitDiskReads().
                        permitDiskWrites().
                        penaltyLog().
                        build())

        Threading.throwOnLockCycles()
        enableStrictMode()
        propagate(Constants.CONTEXT)

        Log.i(LOG_TAG, "=== starting app using configuration: ${if (BuildConfig.IS_TESTNET) "test" else "prod"}, ${Constants.NETWORK_PARAMETERS.id}")

        super.onCreate()

        packageInfo = packageInfoFromContext(this)

        configuration = Configuration(PreferenceManager.getDefaultSharedPreferences(this))
        activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        blockchainServiceCancelCoinsReceivedIntent = Intent(SpvService.ACTION_CANCEL_COINS_RECEIVED, null, this,
                SpvService::class.java)
        val serviceIntent = Intent(this, Bip44AccountIdleService::class.java)
        startService(serviceIntent)
        CommunicationManager.getInstance(this).requestPair(getMbwModulePackage())
    }

    fun stopBlockchainService() {
        restartBip44AccountIdleService()
    }

    fun waitUntilInitialized() = Bip44AccountIdleService.waitUntilInitialized()

    @Synchronized
    fun addWalletAccountWithExtendedKey(creationTimeSeconds: Long,
                                        accountIndex: Int) {
        Log.d(LOG_TAG, "addWalletAccountWithExtendedKey, accountIndex = $accountIndex, " +
                "doesWalletAccountExist for accountIndex ${accountIndex + 3} " +
                "is ${doesWalletAccountExist(accountIndex + 3)}.")
        if(doesWalletAccountExist(accountIndex + 3)) {
            return
        }

        Bip44AccountIdleService.getInstance()!!.addWalletAccount(creationTimeSeconds, accountIndex)
    }

    @Synchronized
    fun addSingleAddressAccountWithPublicKey(guid: String, publicKey: ByteArray) {
        Bip44AccountIdleService.getInstance()!!.addSingleAddressAccountWithPublicKey(guid, publicKey)
        restartBip44AccountIdleService(true)
    }

    @Synchronized
    fun addSingleAddressAccount(guid: String, address: ByteArray) {
        Bip44AccountIdleService.getInstance()!!.addSingleAddressAccountWithAddress(guid, address)
        restartBip44AccountIdleService(true)
    }

    fun removeHdAccount(accountIndex: Int) {
        Bip44AccountIdleService.getInstance()!!.removeHdAccount(accountIndex)
        restartBip44AccountIdleService()
    }

    fun removeSingleAddressAccount(guid: String) {
        Bip44AccountIdleService.getInstance()!!.removeSingleAddressAccount(guid)
        restartBip44AccountIdleService()
    }

    @Synchronized
    fun clearAllAccounts() {
        Bip44AccountIdleService.getInstance()!!.removeAllAccounts()
        restartBip44AccountIdleService(true)
    }

    internal fun restartBip44AccountIdleService(rescan: Boolean = false) {
        Log.d(LOG_TAG, "restartBip44AccountIdleService, stopAsync")
        val serviceIntent = Intent(this, Bip44AccountIdleService::class.java)
        try {
            stopService(serviceIntent)
        } catch (e : Throwable) {
            Log.e(LOG_TAG, e.localizedMessage, e)
        } finally {
            if (rescan) {
                serviceIntent.putExtra(IntentContract.RESET_BLOCKCHAIN_STATE, true)
            }
            Log.d(LOG_TAG, "restartBip44AccountIdleService, startAsync")
            startService(serviceIntent)
            Log.d(LOG_TAG, "restartBip44AccountIdleService, DONE")
        }
    }

    fun getSingleAddressWalletAccount(guid: String) : Wallet =
            Bip44AccountIdleService.getInstance()!!.getSingleAddressWalletAccount(guid)

    fun getWalletAccount(accountIndex: Int): Wallet {
        return Bip44AccountIdleService.getInstance()!!.getWalletAccount(accountIndex)
    }

    fun broadcastTransaction(tx: Transaction, accountIndex: Int) {
        Bip44AccountIdleService.getInstance()!!.broadcastTransaction(tx, accountIndex)
    }

    fun broadcastTransactionSingleAddress(tx: Transaction, guid: String) {
        Bip44AccountIdleService.getInstance()!!.broadcastTransactionSingleAddress(tx, guid)
    }

    fun createUnsignedTransaction(operationId: String, sendRequest: SendRequest, accountIndex: Int) {
        Bip44AccountIdleService.getInstance()!!.createUnsignedTransaction(operationId, sendRequest, accountIndex)
    }

    fun createUnsignedTransactionSingleAddress(operationId: String, sendRequest: SendRequest, guid: String) {
        Bip44AccountIdleService.getInstance()!!.createUnsignedTransactionSingleAddress(operationId, sendRequest, guid)
    }

    fun launchBlockchainScanIfNecessary() {
        Bip44AccountIdleService.getInstance()!!.checkImpediments()
    }

    fun maxConnectedPeers(max :Int): Int {
        val maxConnectedPeers = maxConnectedPeers()
        return if (max < maxConnectedPeers) max else maxConnectedPeers
    }

    fun maxConnectedPeers(): Int =
            if (activityManager!!.memoryClass <= Constants.MEMORY_CLASS_LOWEND) {
                4
            } else {
                6
            }

    internal fun doesWalletAccountExist(accountIndex: Int): Boolean =
            Bip44AccountIdleService.getInstance()!!.doesWalletAccountExist(accountIndex)

    internal fun doesSingleAddressWalletAccountExist(guid: String): Boolean =
            Bip44AccountIdleService.getInstance()!!.doesSingleAddressWalletAccountExist(guid)

    companion object {
        private var INSTANCE: SpvModuleApplication? = null

        fun getApplication(): SpvModuleApplication = INSTANCE!!

        fun packageInfoFromContext(context: Context): PackageInfo {
            try {
                return context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (x: PackageManager.NameNotFoundException) {
                throw RuntimeException(x)
            }
        }

        private val LOG_TAG: String? = this::class.java.simpleName

        // TODO: move this to build.gradle. For now, moving it there by adding
        // buildConfigField "String", "DEBUG_STRING", "\".debug/\"" to buildTypes and
        // buildConfigField "String", "MBW_MODULE_PACKAGE", "\"com.mycelium.wallet/com.testnet.wallet\" + DEBUG_STRING"
        // to productFlavors failed, as BuildConfig doesn't reliably get refreshed on build variant changes!?!?
        fun getMbwModulePackage(): String = when (BuildConfig.APPLICATION_ID) {
            "com.mycelium.module.spvbch" -> "com.mycelium.wallet"
            "com.mycelium.module.spvbch.debug" -> "com.mycelium.wallet.debug"
            "com.mycelium.module.spvbch.testnet" -> "com.mycelium.testnetwallet"
            "com.mycelium.module.spvbch.testnet.debug" -> "com.mycelium.testnetwallet.debug"
            else -> throw RuntimeException("No mbw module defined for BuildConfig " + BuildConfig.APPLICATION_ID)
        }

        fun isMbwInstalled(context: Context): Boolean =
                context.packageManager.getInstalledPackages(0).any { packageInfo ->
                    packageInfo.packageName == getMbwModulePackage()
                }

        fun sendMbw(intent: Intent) {
            CommunicationManager.getInstance(getApplication()).send(getMbwModulePackage(), intent)
        }

        fun doesWalletAccountExist(accountIndex: Int): Boolean =
                INSTANCE!!.doesWalletAccountExist(accountIndex)

        fun doesSingleAddressWalletAccountExist(guid: String): Boolean =
                INSTANCE!!.doesSingleAddressWalletAccountExist(guid)
    }

    fun createAccounts(accountIndexes: ArrayList<Int>, accountKeys: ArrayList<String>,
                       creationTimeSeconds: Long) {
        Bip44AccountIdleService.getInstance()!!.createAccounts(accountIndexes, accountKeys, creationTimeSeconds)
    }
}
