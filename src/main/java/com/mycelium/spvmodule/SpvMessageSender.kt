package com.mycelium.spvmodule

import android.content.Intent
import android.util.Log
import org.bitcoinj.core.Transaction

class SpvMessageSender {
    companion object {
        private val LOG_TAG: String = SpvMessageSender::class.java.simpleName

        private fun send(intent: Intent) {
            SpvModuleApplication.sendMbw(intent)
        }

        fun requestAccountLevelKeys(accountIndexList: List<Int>, creationTimeSeconds : Long) {
            Log.d(LOG_TAG, "requestAccountLevelKeys, accountIndexList = $accountIndexList, " +
                    "creationTimeSeconds = $creationTimeSeconds")
            Intent("com.mycelium.wallet.requestAccountLevelKeysToMBW").apply {
                putExtra(IntentContract.ACCOUNT_INDEXES_EXTRA, accountIndexList.toIntArray())
                putExtra(IntentContract.CREATIONTIMESECONDS, creationTimeSeconds)
                send(this)
            }
        }

        fun requestPublicKeyUnrelated(guid: String, accountType: Int) {
            Log.d(LOG_TAG, "requestPrivateKeySingleAddress for " + guid)
            Intent("com.mycelium.wallet.requestPublicKeyUnrelatedToMBW").apply {
                putExtra(IntentContract.UNRELATED_ACCOUNT_GUID, guid)
                putExtra(IntentContract.UNRELATED_ACCOUNT_TYPE, accountType)
                send(this)
            }
        }

        fun notifySatoshisReceived(satoshisReceived: Long, satoshisSent: Long, accountIndex: Int) {
            Intent("com.mycelium.wallet.notifySatoshisReceived").apply {
                putExtra(IntentContract.SATOSHIS_RECEIVED, satoshisReceived)
                putExtra(IntentContract.SATOSHIS_SENT, satoshisSent)
                putExtra(IntentContract.ACCOUNTS_INDEX, intArrayOf(accountIndex))
                send(this)
            }
        }
        fun notifySatoshisReceivedUnrelated(satoshisReceived: Long, satoshisSent: Long, guid: String) {
            Intent("com.mycelium.wallet.notifySatoshisReceivedUnrelated").apply {
                putExtra(IntentContract.SATOSHIS_RECEIVED, satoshisReceived)
                putExtra(IntentContract.SATOSHIS_SENT, satoshisSent)
                putExtra(IntentContract.UNRELATED_ACCOUNT_GUID, guid)
                send(this)
            }
        }

        fun notifyBroadcastTransactionBroadcastCompleted(operationId : String, txHash : String, isSuccess: Boolean, message: String) {
            Intent("com.mycelium.wallet.notifyBroadcastTransactionBroadcastCompleted").apply {
                putExtra(IntentContract.OPERATION_ID, operationId)
                putExtra(IntentContract.TRANSACTION_HASH, txHash)
                putExtra(IntentContract.IS_SUCCESS, isSuccess)
                putExtra(IntentContract.MESSAGE, message)
                send(this)
            }
        }

        fun sendUnsignedTransactionToMbw(operationId: String, transaction: Transaction,
                                         accountIndex: Int, utxosHex : List<String>) {
            Intent("com.mycelium.wallet.sendUnsignedTransactionToMbw").apply {
                putExtra(IntentContract.ACCOUNT_INDEX_EXTRA, accountIndex)
                putExtra(IntentContract.OPERATION_ID, operationId)
                putExtra(IntentContract.TRANSACTION_BYTES, transaction.bitcoinSerialize())
                putExtra(IntentContract.CONNECTED_OUTPUTS, utxosHex.toTypedArray())
                send(this)
            }
        }

        fun sendUnsignedTransactionToMbwUnrelated(operationId: String,
                                                  unsignedTransaction: Transaction,
                                                  txOutputHex : List<String>, guid: String) {
            Intent("com.mycelium.wallet.sendUnsignedTransactionToMbwUnrelated").apply {
                putExtra(IntentContract.UNRELATED_ACCOUNT_GUID, guid)
                putExtra(IntentContract.OPERATION_ID, operationId)
                putExtra(IntentContract.TRANSACTION_BYTES, unsignedTransaction.bitcoinSerialize())
                putExtra(IntentContract.CONNECTED_OUTPUTS, txOutputHex.toTypedArray())
                send(this)
            }
        }
    }
}
