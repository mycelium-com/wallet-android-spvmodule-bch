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
        clone.action = when (intent.action) {
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

            IntentContract.SendFunds.ACTION -> SpvService.ACTION_SEND_FUNDS
            IntentContract.SendFundsUnrelated.ACTION -> SpvService.ACTION_SEND_FUNDS_UNRELATED
            IntentContract.CreateUnsignedTransaction.ACTION -> SpvService.ACTION_CREATE_UNSIGNED_TRANSACTION
            //TODO Investigate if this should still be called. Maybe IntentContract.SendSignedTransactionToSPV.ACTION should be called in all cases related to broadcasting transactions.
            IntentContract.BroadcastTransaction.ACTION -> SpvService.ACTION_BROADCAST_TRANSACTION
            IntentContract.SendSignedTransactionToSPV.ACTION -> SpvService.ACTION_BROADCAST_SIGNEDTRANSACTION
            IntentContract.SendSignedTransactionUnrelatedToSPV.ACTION -> SpvService.ACTION_BROADCAST_SIGNEDTRANSACTION_UNRELATED
            IntentContract.ReceiveTransactions.ACTION -> SpvService.ACTION_RECEIVE_TRANSACTIONS
            IntentContract.ReceiveTransactionsUnrelated.ACTION -> SpvService.ACTION_RECEIVE_TRANSACTIONS_UNRELATED
            IntentContract.RequestAccountLevelKeysToSPV.ACTION -> SpvService.ACTION_REQUEST_ACCOUNT_LEVEL_KEYS
            IntentContract.SendUnrelatedPublicKeyToSPV.ACTION -> SpvService.ACTION_REQUEST_UNRELATED_PUBLIC_KEY
            IntentContract.RemoveHdWalletAccount.ACTION -> SpvService.ACTION_REMOVE_HD_ACCOUNT
            IntentContract.RemoveUnrelatedAccount.ACTION -> SpvService.ACTION_REMOVE_UNRELATED_ACCOUNT
            IntentContract.ForceCacheClean.ACTION -> SpvService.ACTION_FORCE_CACHE_CLEAN
            else -> {
                Log.e(LOG_TAG, "Unhandled intent action ${intent.action}")
                return
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
