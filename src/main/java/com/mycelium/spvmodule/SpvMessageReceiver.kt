package com.mycelium.spvmodule

import android.content.Context
import android.content.Intent
import android.util.Log
import com.mycelium.modularizationtools.ModuleMessageReceiver
import com.mycelium.spvmodule.SpvModuleApplication.Companion.getMbwModuleName
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.ContextPropagatingThreadFactory
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class SpvMessageReceiver(private val context: Context) : ModuleMessageReceiver {
    @Synchronized
    override fun onMessage(callingPackageName: String, intent: Intent) {
        // while sub modules might talk to each other, for now we assume that spvmodule will only ever talk to mbw:
        if(callingPackageName != getMbwModuleName()) {
            Log.e(LOG_TAG, "Ignoring unexpected package $callingPackageName calling with intent $intent.")
            return
        }
        Log.d(LOG_TAG, "onMessage($callingPackageName, ${intent.action})")
        org.bitcoinj.core.Context.propagate(Constants.CONTEXT)
        val clone = intent.clone() as Intent
        clone.setClass(context, SpvService::class.java)
        when (intent.action) {
            IntentContract.WaitingIntents.ACTION -> {
                val accountId = intent.getIntExtra(IntentContract.ACCOUNT_INDEX_EXTRA, -1)
                val waitingActions = arrayListOf<String>()
                for (queueIntent in SpvService.intentsQueue) {
                    val queueIntentAccountId = queueIntent.getIntExtra(IntentContract.ACCOUNT_INDEX_EXTRA, -1)
                    if (accountId == queueIntentAccountId) {
                        waitingActions.add(queueIntent.action)
                    }
                }
                val resultIntent = IntentContract.WaitingIntents.createResultIntent(accountId, waitingActions.toTypedArray())
                context.sendBroadcast(resultIntent)
                return
            }
            IntentContract.SendFunds.ACTION -> {
                clone.action = SpvService.ACTION_SEND_FUNDS
            }

            IntentContract.SendFundsSingleAddress.ACTION -> {
                clone.action = SpvService.ACTION_SEND_FUNDS_SINGLE_ADDRESS
            }

            IntentContract.CreateUnsignedTransaction.ACTION -> {
                clone.action = SpvService.ACTION_CREATE_UNSIGNED_TRANSACTION
            }

            IntentContract.BroadcastTransaction.ACTION -> {
                val config = SpvModuleApplication.getApplication().configuration!!
                val txBytes = intent.getByteArrayExtra(IntentContract.BroadcastTransaction.TX_EXTRA)
                if (config.broadcastUsingWapi) {
                    asyncWapiBroadcast(txBytes)
                    return
                } else {
                    clone.action = SpvService.ACTION_BROADCAST_TRANSACTION
                }
            }

            IntentContract.SendSignedTransactionToSPV.ACTION -> {
                val txBytes = intent.getByteArrayExtra(IntentContract.SendSignedTransactionToSPV.TX_EXTRA)
                val accountIndex = intent.getIntExtra(IntentContract.ACCOUNT_INDEX_EXTRA, -1)
                SpvModuleApplication.getApplication()
                        .broadcastTransaction(Transaction(Constants.NETWORK_PARAMETERS, txBytes), accountIndex)
            }

            IntentContract.SendSignedTransactionSingleAddressToSPV.ACTION -> {
                val txBytes = intent.getByteArrayExtra(IntentContract.SendSignedTransactionToSPV.TX_EXTRA)
                val accountGuid = intent.getStringExtra(IntentContract.SINGLE_ADDRESS_ACCOUNT_GUID)
                SpvModuleApplication.getApplication()
                        .broadcastTransactionSingleAddress(Transaction(Constants.NETWORK_PARAMETERS, txBytes), accountGuid)
            }


            IntentContract.ReceiveTransactions.ACTION -> {
                clone.action = SpvService.ACTION_RECEIVE_TRANSACTIONS
            }

            IntentContract.ReceiveTransactionsSingleAddress.ACTION -> {
                clone.action = SpvService.ACTION_RECEIVE_TRANSACTIONS_SINGLE_ADDRESS
            }
            IntentContract.RequestAccountLevelKeysToSPV.ACTION -> {
                val accountIndexes = intent.getIntegerArrayListExtra(IntentContract.ACCOUNT_INDEXES_EXTRA)
                val accountKeys = intent.getStringArrayListExtra(IntentContract.RequestAccountLevelKeysToSPV.ACCOUNT_KEYS)
                if (accountIndexes.isEmpty() || accountKeys.isEmpty()) {
                    Log.e(LOG_TAG, "no account specified. Skipping ${intent.action}.")
                    return
                } /* else if (SpvModuleApplication.getApplication().doesWalletAccountExist(accountIndex)) {
                    Log.i(LOG_TAG, "Trying to create an account / wallet with accountIndex " +
                            "$accountIndex that already exists.")
                    return
                } */
                val creationTimeSeconds = intent.getLongExtra(
                        IntentContract.RequestAccountLevelKeysToSPV
                                .CREATION_TIME_SECONDS_EXTRA, 0)
                SpvModuleApplication.getApplication()
                        .createAccounts(accountIndexes, accountKeys, creationTimeSeconds)
                return
            }

            IntentContract.RequestSingleAddressPrivateKeyToSPV.ACTION -> {
                var guid = intent.getStringExtra(IntentContract.RequestSingleAddressPrivateKeyToSPV.SINGLE_ADDRESS_GUID)
                var privateKey = intent.getByteArrayExtra(IntentContract.RequestSingleAddressPrivateKeyToSPV.PRIVATE_KEY)
                SpvModuleApplication.getApplication().addSingleAddressAccountWithPrivateKey(guid, privateKey)
            }

            IntentContract.RemoveHdWalletAccount.ACTION -> {
                val accountIndex = intent.getIntExtra(IntentContract.ACCOUNT_INDEX_EXTRA, -1)
                SpvModuleApplication.getApplication().removeHdAccount(accountIndex)
            }

            IntentContract.RemoveSingleAddressWalletAccount.ACTION -> {
                val guid = intent.getStringExtra(IntentContract.SINGLE_ADDRESS_ACCOUNT_GUID)
                SpvModuleApplication.getApplication().removeSingleAddressAccount(guid)
            }

            IntentContract.ForceCacheClean.ACTION -> {
                SpvModuleApplication.getApplication().clearAllAccounts()
            }
        }
        Log.d(LOG_TAG, "Will start Service $clone")
        // start service to check for new transactions and maybe to broadcast a transaction
        val executorService = Executors.newSingleThreadExecutor(
                ContextPropagatingThreadFactory("SpvMessageReceiverThreadFactory"))
        executorService.execute {
            Log.d(LOG_TAG, "Starting Service $clone")
            context.startService(clone)
        }
    }

    private fun asyncWapiBroadcast(tx: ByteArray) {
        Thread(Runnable {
            try {
                val url = URL("https://${if (Constants.TEST) "testnet." else "" }blockexplorer.com/api/tx/send")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestMethod("POST")
                conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.setDoOutput(true)
                conn.setDoInput(true)

                val jsonParam = JSONObject()
                jsonParam.put("rawtx", tx.map {String.format("%02X", it)}.joinToString(""))

                Log.i("JSON", jsonParam.toString())
                val os = DataOutputStream(conn.getOutputStream())
                os.writeBytes(jsonParam.toString())

                os.flush()
                os.close()

                val transaction = Transaction(Constants.NETWORK_PARAMETERS, tx)
                val intent = Intent("com.mycelium.wallet.broadcaststatus")
                intent.putExtra("tx", transaction.hash)
                intent.putExtra("result", if(conn.responseCode == 200) "success" else "failure")
                SpvModuleApplication.sendMbw(intent)

                conn.disconnect()
            } catch (e: Exception) {
                Log.e(LOG_TAG, e.localizedMessage, e)
            }
        }).start()
    }

    private val LOG_TAG: String = this.javaClass.simpleName
}
