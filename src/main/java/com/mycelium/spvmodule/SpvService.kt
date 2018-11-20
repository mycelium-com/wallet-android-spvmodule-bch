/*
 * Copyright 2011-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mycelium.spvmodule

import android.app.*
import android.content.*
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.support.v4.app.NotificationCompat
import android.util.Log
import org.bitcoinj.core.*
import org.bitcoinj.core.Context.propagate
import org.bitcoinj.wallet.SendRequest
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class SpvService : IntentService("SpvService") {
    private val application = SpvModuleApplication.getApplication()
    private var notificationManager: NotificationManager? = null
    private var serviceCreatedAtMillis = System.currentTimeMillis()
    private var accountIndex: Int = -1
    private var unrelatedAccountGuid: String = ""

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val CHANNEL_ID = "spv service"
            val channel = NotificationChannel(CHANNEL_ID, "SPV Service",
                    NotificationManager.IMPORTANCE_DEFAULT)

            service.createNotificationChannel(channel)

            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                    .setSmallIcon(com.mycelium.spvmodule.R.drawable.ic_launcher)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setContentTitle("Title title")
                    .setContentText("Text text")
                    .build()

            startForeground(51554, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intentsQueue.offer(intent)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onHandleIntent(intent: Intent?) {
        Log.i(LOG_TAG, "onHandleIntent: ${intent?.action}")
        intentsQueue.remove()
        propagate(Constants.CONTEXT)
        if (intent != null) {
            when (intent.action) {
                ACTION_CANCEL_COINS_RECEIVED -> {
                    accountIndex = getAccountIndex(intent) ?: return
                    notificationManager!!.cancel(Constants.NOTIFICATION_ID_COINS_RECEIVED)
                }
                ACTION_BROADCAST_TRANSACTION -> {
                    accountIndex = getAccountIndex(intent) ?: return
                    val transactionByteArray = intent.getByteArrayExtra("TX")
                    val transaction = Transaction(Constants.NETWORK_PARAMETERS, transactionByteArray)
                    Log.i(LOG_TAG, "onHandleIntent: ACTION_BROADCAST_TRANSACTION,  TX = " + transaction)
                    transaction.confidence.source = TransactionConfidence.Source.SELF
                    transaction.purpose = Transaction.Purpose.USER_PAYMENT
                    application.broadcastTransaction(transaction, accountIndex)
                }
                ACTION_BROADCAST_SIGNEDTRANSACTION -> {
                    accountIndex = getAccountIndex(intent) ?: return
                    val operationId = intent.getStringExtra(IntentContract.OPERATION_ID)
                    val txBytes = intent.getByteArrayExtra(IntentContract.SendSignedTransactionToSPV.TX_EXTRA)
                    val tx = Transaction(Constants.NETWORK_PARAMETERS, txBytes)
                    Log.i(LOG_TAG, "onHandleIntent: ACTION_BROADCAST_TRANSACTION,  TX = " + tx)
                    application.broadcastTransaction(tx, accountIndex)
                    SpvMessageSender.notifyBroadcastTransactionBroadcastCompleted(operationId,
                            tx.hashAsString, true, "")
                }
                ACTION_BROADCAST_SIGNEDTRANSACTION_UNRELATED -> {
                    val operationId = intent.getStringExtra(IntentContract.OPERATION_ID)
                    val txBytes = intent.getByteArrayExtra(IntentContract.SendSignedTransactionUnrelatedToSPV.TX_EXTRA)
                    val accountGuid = intent.getStringExtra(IntentContract.SendSignedTransactionUnrelatedToSPV.UNRELATED_ACCOUNT_GUID)
                    val tx = Transaction(Constants.NETWORK_PARAMETERS, txBytes)
                    SpvModuleApplication.getApplication()
                            .broadcastTransactionSingleAddress(tx, accountGuid)
                    SpvMessageSender.notifyBroadcastTransactionBroadcastCompleted(operationId,
                            tx.hashAsString, true, "")
                }
                ACTION_SEND_FUNDS -> {
                    accountIndex = getAccountIndex(intent) ?: return
                    val operationId = intent.getStringExtra(IntentContract.SendFunds.OPERATION_ID)
                    val rawAddress = intent.getStringExtra(IntentContract.SendFunds.ADDRESS_EXTRA)
                    val rawAmount = intent.getLongExtra(IntentContract.SendFunds.AMOUNT_EXTRA, -1)
                    val txFeeStr = intent.getStringExtra(IntentContract.SendFunds.FEE_EXTRA)
                    val txFeeFactor = intent.getFloatExtra(IntentContract.SendFunds.FEE_FACTOR_EXTRA, 0.0f)
                    if (rawAddress.isEmpty() || rawAmount < 0 || txFeeStr == null || txFeeFactor == 0.0f) {
                        Log.e(LOG_TAG, "Could not send funds with parameters rawAddress $rawAddress, "
                                + "rawAmount $rawAmount, feePerKb $txFeeStr and feePerFactor $txFeeFactor.")
                        return
                    }
                    val address = Address.fromBase58(Constants.NETWORK_PARAMETERS, rawAddress)
                    val amount = Coin.valueOf(rawAmount)
                    val sendRequest = SendRequest.to(address, amount)
                    val txFee = TransactionFee.valueOf(txFeeStr)
                    sendRequest.feePerKb = Constants.minerFeeValue(txFee, txFeeFactor)
                    sendRequest.signInputs = false
                    try {
                        application.createUnsignedTransaction(operationId, sendRequest, accountIndex)
                    } catch (ex : Exception) {
                        SpvMessageSender.notifyBroadcastTransactionBroadcastCompleted(operationId, "", false, ex.message!!)
                    }
                }
                ACTION_SEND_FUNDS_UNRELATED -> {
                    val operationId = intent.getStringExtra(IntentContract.SendFundsUnrelated.OPERATION_ID)
                    unrelatedAccountGuid = intent.getStringExtra(IntentContract.UNRELATED_ACCOUNT_GUID)
                    val rawAddress = intent.getStringExtra(IntentContract.SendFundsUnrelated.ADDRESS_EXTRA)
                    val rawAmount = intent.getLongExtra(IntentContract.SendFundsUnrelated.AMOUNT_EXTRA, -1)
                    val txFeeStr = intent.getStringExtra(IntentContract.SendFundsUnrelated.FEE_EXTRA)
                    val txFeeFactor = intent.getFloatExtra(IntentContract.SendFundsUnrelated.FEE_FACTOR_EXTRA, 0.0f)
                    val unrelatedAccountType = intent.getIntExtra(IntentContract.SendFundsUnrelated.ACCOUNT_TYPE, -1)
                    if (rawAddress.isEmpty() || rawAmount < 0 || txFeeStr == null || txFeeFactor == 0.0f) {
                        Log.e(LOG_TAG, "Could not send funds with parameters rawAddress $rawAddress, "
                                + "rawAmount $rawAmount, feePerKb $txFeeStr and feePerFactor $txFeeFactor.")
                        return
                    }

                    val wallet = application.getSingleAddressWalletAccount(unrelatedAccountGuid)
                    val address = Address.fromBase58(Constants.NETWORK_PARAMETERS, rawAddress)
                    val amount = Coin.valueOf(rawAmount)
                    val sendRequest = SendRequest.to(address, amount)

                    //We need to specify the change address manually only in the case of Single Address unrelated account
                    if (unrelatedAccountType == IntentContract.UNRELATED_ACCOUNT_TYPE_SA) {
                        if (wallet.importedKeys.size > 0) {
                            sendRequest.changeAddress = wallet.importedKeys.get(0).toAddress(Constants.NETWORK_PARAMETERS)
                        } else if (wallet.watchedAddresses.size > 0){
                            sendRequest.changeAddress = wallet.watchedAddresses.get(0)
                        }
                    }

                    val txFee = TransactionFee.valueOf(txFeeStr)
                    sendRequest.feePerKb = Constants.minerFeeValue(txFee, txFeeFactor)

                    try {
                        application.createUnsignedTransactionSingleAddress(operationId, sendRequest, unrelatedAccountGuid)
                    } catch (ex : Exception) {
                        SpvMessageSender.notifyBroadcastTransactionBroadcastCompleted(operationId, "", false, ex.message!!)
                    }
                }
                ACTION_RECEIVE_TRANSACTIONS -> {
                    accountIndex = getAccountIndex(intent) ?: return
                    if (!SpvModuleApplication.doesWalletAccountExist(accountIndex)) {
                        // Ask for private Key
                        SpvMessageSender.requestAccountLevelKeys(mutableListOf(accountIndex),
                                Date().time / 1000)
                        return
                    } else {
                        application.launchBlockchainScanIfNecessary()
                    }
                }
                ACTION_RECEIVE_TRANSACTIONS_UNRELATED -> {
                    unrelatedAccountGuid = intent.getStringExtra(IntentContract.UNRELATED_ACCOUNT_GUID)
                    val unrelatedAccountType = intent.getIntExtra(IntentContract.UNRELATED_ACCOUNT_TYPE, -1)
                    if (!SpvModuleApplication.doesUnrelatedAccountExist(unrelatedAccountGuid)) {
                        // Ask for private Key
                        SpvMessageSender.requestPublicKeyUnrelated(unrelatedAccountGuid, unrelatedAccountType)
                        return
                    } else {
                        application.launchBlockchainScanIfNecessary()
                    }
                }
                ACTION_REQUEST_ACCOUNT_LEVEL_KEYS -> {
                    val accountIndexes = intent.getIntegerArrayListExtra(IntentContract.ACCOUNT_INDEXES_EXTRA)
                    val accountKeys = intent.getStringArrayListExtra(IntentContract.RequestAccountLevelKeysToSPV.ACCOUNT_KEYS)
                    if (accountIndexes.isEmpty() || accountKeys.isEmpty()) {
                        Log.e(LOG_TAG, "no account specified. Skipping ${intent.action}.")
                        return
                    }
                    val creationTimeSeconds = intent.getLongExtra(
                            IntentContract.RequestAccountLevelKeysToSPV
                                    .CREATION_TIME_SECONDS_EXTRA, 0)
                    SpvModuleApplication.getApplication()
                            .createAccounts(accountIndexes, accountKeys, creationTimeSeconds)
                }
                ACTION_REQUEST_UNRELATED_PUBLIC_KEY -> {
                    val guid = intent.getStringExtra(IntentContract.SendUnrelatedPublicKeyToSPV.UNRELATED_ACCOUNT_GUID)
                    val publicKey = intent.getStringExtra(IntentContract.SendUnrelatedPublicKeyToSPV.PUBLIC_KEY_B58)
                    val accountType = intent.getIntExtra(IntentContract.UNRELATED_ACCOUNT_TYPE, -1)
                    SpvModuleApplication.getApplication().addUnrelatedAccountWithPublicKey(guid, publicKey, accountType)
                }

                ACTION_REQUEST_UNRELATED_WATCH_ADDRESS -> {
                    val guid = intent.getStringExtra(IntentContract.SendUnrelatedWatchedAddressToSPV.UNRELATED_ACCOUNT_GUID)
                    val address = intent.getStringExtra(IntentContract.SendUnrelatedWatchedAddressToSPV.ADDRESS)
                    SpvModuleApplication.getApplication().addUnrelatedAccountWithAddress(guid, address)
                }

                ACTION_REMOVE_HD_ACCOUNT -> {
                    accountIndex = getAccountIndex(intent) ?: return
                    SpvModuleApplication.getApplication().removeHdAccount(accountIndex)
                }
                ACTION_REMOVE_UNRELATED_ACCOUNT -> {
                    val guid = intent.getStringExtra(IntentContract.UNRELATED_ACCOUNT_GUID)
                    SpvModuleApplication.getApplication().removeSingleAddressAccount(guid)
                }
                ACTION_FORCE_CACHE_CLEAN -> {
                    SpvModuleApplication.getApplication().clearAllAccounts()
                }
                else -> {
                    Log.e(LOG_TAG,
                            "Unhandled action was ${intent.action}. Initializing blockchain " +
                                    "for account $accountIndex.")
                }
            }
        } else {
            Log.w(LOG_TAG, "onHandleIntent: service restart, although it was started as non-sticky")
        }
    }

    fun getAccountIndex(intent: Intent): Int? {
        val index = intent.getIntExtra(IntentContract.ACCOUNT_INDEX_EXTRA, -1)
        if (index == -1) {
            Log.e(LOG_TAG, "no account specified. Skipping ${intent.action}.")
            return null
        }
        return index
    }

    override fun onDestroy() {
        Log.d(LOG_TAG, ".onDestroy()")

        intentsQueue.clear()
        super.onDestroy()
        Log.i(LOG_TAG, "service was up for ${(System.currentTimeMillis() - serviceCreatedAtMillis) / 1000 }s")
    }

    override fun onTrimMemory(level: Int) {
        Log.i(LOG_TAG, "onTrimMemory($level)")

        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            // TODO: stop service
            Log.e(LOG_TAG, "low memory detected, not stopping service")
        }
    }

    companion object {
        private val LOG_TAG = SpvService::class.java.simpleName
        private val PACKAGE_NAME = SpvService::class.java.`package`.name
        val ACTION_PEER_STATE = PACKAGE_NAME + ".peer_state"
        val ACTION_PEER_STATE_NUM_PEERS = "num_peers"
        val ACTION_BLOCKCHAIN_STATE = PACKAGE_NAME + ".blockchain_state"
        val ACTION_CANCEL_COINS_RECEIVED = PACKAGE_NAME + ".cancel_coins_received"
        val ACTION_BROADCAST_TRANSACTION = PACKAGE_NAME + ".broadcast_transaction"
        val ACTION_BROADCAST_SIGNEDTRANSACTION = PACKAGE_NAME + ".broadcast_signedtransaction"
        val ACTION_BROADCAST_SIGNEDTRANSACTION_UNRELATED = PACKAGE_NAME + ".broadcast_signedtransaction_single_address"
        val ACTION_RECEIVE_TRANSACTIONS = PACKAGE_NAME + ".receive_transactions"
        val ACTION_RECEIVE_TRANSACTIONS_UNRELATED = PACKAGE_NAME + ".receive_transactions_unrelated"
        val ACTION_SEND_FUNDS = PACKAGE_NAME + ".send_funds"
        val ACTION_SEND_FUNDS_UNRELATED = PACKAGE_NAME + ".send_funds_unrelated"
        val ACTION_CREATE_UNSIGNED_TRANSACTION = PACKAGE_NAME + ".create_unsigned_transaction"
        val ACTION_REQUEST_ACCOUNT_LEVEL_KEYS = PACKAGE_NAME + ".request_account_level_keys"
        val ACTION_REQUEST_UNRELATED_PUBLIC_KEY = PACKAGE_NAME + ".request_unrelated_public_key"
        var ACTION_REQUEST_UNRELATED_WATCH_ADDRESS = PACKAGE_NAME + ".request_unrelated_watch_address"
        val ACTION_REMOVE_HD_ACCOUNT = PACKAGE_NAME + ".remove_hd_account"
        val ACTION_REMOVE_UNRELATED_ACCOUNT = PACKAGE_NAME + ".remove_unrelated_account"
        val ACTION_FORCE_CACHE_CLEAN = PACKAGE_NAME + ".force_cache_clean"

        val intentsQueue: Queue<Intent> = ConcurrentLinkedQueue<Intent>()
    }
}
