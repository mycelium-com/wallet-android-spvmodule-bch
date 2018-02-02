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

import android.app.IntentService
import android.app.NotificationManager
import android.content.*
import android.util.Log
import com.mycelium.modularizationtools.CommunicationManager
import org.bitcoinj.core.*
import org.bitcoinj.core.Context.propagate
import org.bitcoinj.wallet.SendRequest
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class SpvService : IntentService("SpvService") {
    private val application = SpvModuleApplication.getApplication()
    private var notificationManager: NotificationManager? = null
    private var serviceCreatedAtMillis = System.currentTimeMillis()
    private var accountId: String = ""
    private var singleAddressAccountGuid: String = ""

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
                    accountId = getAccountId(intent) ?: return
                    notificationManager!!.cancel(Constants.NOTIFICATION_ID_COINS_RECEIVED)
                }
                ACTION_BROADCAST_TRANSACTION -> {
                    accountId = getAccountId(intent) ?: return
                    val transactionByteArray = intent.getByteArrayExtra("TX")
                    val transaction = Transaction(Constants.NETWORK_PARAMETERS, transactionByteArray)
                    Log.i(LOG_TAG, "onHandleIntent: ACTION_BROADCAST_TRANSACTION,  TX = " + transaction)
                    transaction.confidence.source = TransactionConfidence.Source.SELF
                    transaction.purpose = Transaction.Purpose.USER_PAYMENT
                    application.broadcastTransaction(transaction, accountId)
                }
                ACTION_BROADCAST_TRANSACTION_SINGLE_ADDRESS -> {
                    singleAddressAccountGuid = intent.getStringExtra(IntentContract.SINGLE_ADDRESS_ACCOUNT_GUID)
                    val transactionByteArray = intent.getByteArrayExtra("TX")
                    val transaction = Transaction(Constants.NETWORK_PARAMETERS, transactionByteArray)
                    Log.i(LOG_TAG, "onHandleIntent: ACTION_BROADCAST_TRANSACTION_SINGLE_ADDRESS,  TX = " + transaction)
                    transaction.confidence.source = TransactionConfidence.Source.SELF
                    transaction.purpose = Transaction.Purpose.USER_PAYMENT
                    application.broadcastTransactionSingleAddress(transaction, singleAddressAccountGuid)
                }
                ACTION_SEND_FUNDS -> {
                    accountId = getAccountId(intent) ?: return
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

                    application.broadcastTransaction(sendRequest, accountId)
                }
                ACTION_SEND_FUNDS_SINGLE_ADDRESS -> {
                    singleAddressAccountGuid = intent.getStringExtra(IntentContract.SINGLE_ADDRESS_ACCOUNT_GUID)
                    val rawAddress = intent.getStringExtra(IntentContract.SendFundsSingleAddress.ADDRESS_EXTRA)
                    val rawAmount = intent.getLongExtra(IntentContract.SendFundsSingleAddress.AMOUNT_EXTRA, -1)
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

                    application.broadcastTransactionSingleAddress(sendRequest, singleAddressAccountGuid)
                }
                ACTION_RECEIVE_TRANSACTIONS -> {
                    accountId = getAccountId(intent) ?: return
                    if (!SpvModuleApplication.doesWalletAccountExist(accountId)) {
                        // Ask for private Key
                        SpvMessageSender.requestPrivateKey(accountId)
                        return
                    } else {
                        application.launchBlockchainScanIfNecessary()
                        application.sendTransactions(accountId)
                    }
                }
                ACTION_RECEIVE_TRANSACTIONS_SINGLE_ADDRESS -> {
                    singleAddressAccountGuid = intent.getStringExtra(IntentContract.SINGLE_ADDRESS_ACCOUNT_GUID)

                    if (!SpvModuleApplication.doesSingleAddressWalletAccountExist(singleAddressAccountGuid)) {
                        // Ask for private Key
                        SpvMessageSender.requestPrivateKeySingleaddress(singleAddressAccountGuid)
                        return
                    } else {
                        application.launchBlockchainScanIfNecessary()
                        application.sendTransactionsSingleAddress(singleAddressAccountGuid)
                    }
                }
                else -> {
                    Log.e(LOG_TAG,
                            "Unhandled action was ${intent.action}. Initializing blockchain " +
                                    "for account $accountId.")
                }
            }
        } else {
            Log.w(LOG_TAG, "onHandleIntent: service restart, although it was started as non-sticky")
        }
    }

    fun getAccountId(intent: Intent): String? {
        val accountId = intent.getStringExtra(IntentContract.ACCOUNT_ID)
        if (accountId.isEmpty()) {
            Log.e(LOG_TAG, "no account specified. Skipping ${intent.action}.")
            return null
        }
        return accountId
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
        val ACTION_ADD_ACCOUNT = PACKAGE_NAME + ".reset_blockchain"
        val ACTION_BROADCAST_TRANSACTION = PACKAGE_NAME + ".broadcast_transaction"
        val ACTION_BROADCAST_TRANSACTION_SINGLE_ADDRESS = PACKAGE_NAME + ".broadcast_transaction_single_address"
        val ACTION_RECEIVE_TRANSACTIONS = PACKAGE_NAME + ".receive_transactions"
        val ACTION_RECEIVE_TRANSACTIONS_SINGLE_ADDRESS = PACKAGE_NAME + ".receive_transactions_single_address"
        val ACTION_SEND_FUNDS = PACKAGE_NAME + ".send_funds"
        val ACTION_SEND_FUNDS_SINGLE_ADDRESS = PACKAGE_NAME + ".send_funds_single_address"

        val intentsQueue: Queue<Intent> = ConcurrentLinkedQueue<Intent>()
    }
}
