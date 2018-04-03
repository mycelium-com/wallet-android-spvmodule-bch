package com.mycelium.spvmodule

import android.content.Context
import android.content.Intent
import android.util.Log
import com.mycelium.modularizationtools.ModuleMessageReceiver
import com.mycelium.spvmodule.SpvModuleApplication.Companion.getMbwModulePackage
import org.bitcoinj.utils.ContextPropagatingThreadFactory
import java.util.concurrent.Executors

class SpvMessageReceiver(private val context: Context) : ModuleMessageReceiver {
    @Synchronized
    override fun onMessage(callingPackageName: String, intent: Intent) {
        //Wait until the application is initialized
        SpvModuleApplication.getApplication().waitUntilInitialized()

        // while sub modules might talk to each other, for now we assume that spvmodule will only ever talk to mbw:
        if(callingPackageName != getMbwModulePackage()) {
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
                //TODO Investigate if this should still be called. I think IntentContract.SendSignedTransactionToSPV.ACTION should be called in all cases related to broadcasting transactions.
                clone.action = SpvService.ACTION_BROADCAST_TRANSACTION
            }

            IntentContract.SendSignedTransactionToSPV.ACTION -> {
                clone.action = SpvService.ACTION_BROADCAST_SIGNEDTRANSACTION
            }

            IntentContract.SendSignedTransactionSingleAddressToSPV.ACTION -> {
                clone.action = SpvService.ACTION_BROADCAST_SIGNEDTRANSACTION_SINGLE_ADDRESS
            }

            IntentContract.ReceiveTransactions.ACTION -> {
                clone.action = SpvService.ACTION_RECEIVE_TRANSACTIONS
            }

            IntentContract.ReceiveTransactionsSingleAddress.ACTION -> {
                clone.action = SpvService.ACTION_RECEIVE_TRANSACTIONS_SINGLE_ADDRESS
            }

            IntentContract.RequestAccountLevelKeysToSPV.ACTION -> {
                clone.action = SpvService.ACTION_REQUEST_ACCOUNT_LEVEL_KEYS
            }

            IntentContract.SendSingleAddressPublicKeyToSPV.ACTION -> {
                clone.action = SpvService.ACTION_REQUEST_SINGLE_ADDRESS_PUBLIC_KEY
            }

            IntentContract.SendSingleAddressToSPV.ACTION -> {
                clone.action = SpvService.ACTION_REQUEST_SINGLE_ADDRESS
            }

            IntentContract.RemoveHdWalletAccount.ACTION -> {
                clone.action = SpvService.ACTION_REMOVE_HD_ACCOUNT
            }

            IntentContract.RemoveSingleAddressWalletAccount.ACTION -> {
                clone.action = SpvService.ACTION_REMOVE_SINGLE_ADDRESS_ACCOUNT
            }

            IntentContract.ForceCacheClean.ACTION -> {
                clone.action = SpvService.ACTION_FORCE_CACHE_CLEAN
            }
        }
        Log.d(LOG_TAG, "Will start Service $clone with action ${clone.action}")
        // start service to check for new transactions and maybe to broadcast a transaction
        val executorService = Executors.newSingleThreadExecutor(
                ContextPropagatingThreadFactory("SpvMessageReceiverThreadFactory"))
        executorService.execute {
            Log.d(LOG_TAG, "Starting Service $clone with action ${clone.action}")
            context.startService(clone)
        }
    }

    private val LOG_TAG: String = this.javaClass.simpleName
}
