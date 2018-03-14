package com.mycelium.spvmodule

import android.content.Intent
import android.util.Log
import com.mrd.bitlib.StandardTransactionBuilder
import com.mycelium.modularizationtools.CommunicationManager
import org.bitcoinj.core.Address
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.core.TransactionOutput
import org.spongycastle.util.encoders.Hex
import org.spongycastle.util.encoders.HexEncoder
import java.nio.ByteBuffer
import java.util.*
import kotlin.collections.ArrayList

class SpvMessageSender {
    companion object {
        private val LOG_TAG: String = SpvMessageSender::class.java.simpleName

        fun sendTransactions(transactionSet: Set<Transaction>,
                             unspentTransactionOutputSet: Set<TransactionOutput>) {
            val transactions = transactionSet.map {
                val transactionBytes = it.bitcoinSerialize()
                //Log.d(LOG_TAG, "Sharing transaction $it with transaction bytes ${Hex.encode(transactionBytes)}")
                val blockHeight = when (it.confidence.confidenceType) {
                    TransactionConfidence.ConfidenceType.BUILDING -> it.confidence.appearedAtChainHeight
                // at the risk of finding Satoshi, values up to 5 are interpreted as type.
                // Sorry dude. Don't file this bug report.
                    else -> it.confidence.confidenceType.value
                }
                ByteBuffer.allocate(/* 1 int */ 4 + transactionBytes.size + /* 1 Long */ 8)
                        .putInt(blockHeight).put(transactionBytes).putLong(it.updateTime.time).array()
            }.toTypedArray()
            val connectedOutputs = HashMap<String, ByteArray>()
            for(transaction in transactionSet) {
                for(transactionInput in transaction.inputs) {
                    connectedOutputs[transactionInput.outpoint.toString()] =
                            transactionInput.connectedOutput?.bitcoinSerialize() ?: continue
                    //Log.d(LOG_TAG, "Sharing connected output $txi with
                    // ${Hex.encode(txi!!.connectedOutput!!.bitcoinSerialize())}")
                }
            }
            val utxos = unspentTransactionOutputSet.associate {
                //Log.d(LOG_TAG, "Sharing utxo ${it.outPointFor} ${Hex.encode(it.outPointFor.bitcoinSerialize())}")
                Pair(it.outPointFor.toString(), it.outPointFor.bitcoinSerialize())
            }
            val utxoHM = HashMap<String, ByteArray>(utxos.size).apply { putAll(utxos) }
            // report back known transactions
            val intent = Intent("com.mycelium.wallet.receivedTransactions").apply {
                putExtra(IntentContract.TRANSACTIONS, transactions)
                putExtra(IntentContract.CONNECTED_OUTPUTS, connectedOutputs)
                putExtra(IntentContract.UTXOS, utxoHM)
            }
            send(intent)
        }

        private fun dumpTxos(txos: HashMap<String, ByteArray>) {
            txos.entries.forEach {
                val hexString = it.value.joinToString(separator = "") { String.format("%02x", it) }
                Log.d(LOG_TAG, "dumpTxos, ${it.key} : $hexString")
            }
        }

        private fun send(intent: Intent) {
            SpvModuleApplication.sendMbw(intent)
        }

        fun requestAccountLevelKeys(accountIndexList: List<Int>, creationTimeSeconds : Long) {
            Log.d(LOG_TAG, "requestAccountLevelKeys, accountIndexList = $accountIndexList, " +
                    "creationTimeSeconds = $creationTimeSeconds")
            Intent("com.mycelium.wallet.requestAccountLevelKeysToMBW").run {
                putExtra(IntentContract.ACCOUNT_INDEXES_EXTRA, accountIndexList.toIntArray())
                putExtra(IntentContract.CREATIONTIMESECONDS, creationTimeSeconds)
                send(this)
            }
        }

        fun requestPrivateKeySingleaddress(guid: String) {
            Log.d(LOG_TAG, "requestPrivateKey")
            Intent("com.mycelium.wallet.requestSingleAddressPrivateKeyToMBW").run {
                putExtra(IntentContract.SINGLE_ADDRESS_ACCOUNT_GUID, guid)
                send(this)
            }
        }

        fun notifySatoshisReceived(satoshisReceived: Long, satoshisSent: Long, accountIndex: Int) {
            val intent = Intent("com.mycelium.wallet.notifySatoshisReceived").apply {
                putExtra(IntentContract.SATOSHIS_RECEIVED, satoshisReceived)
                putExtra(IntentContract.SATOSHIS_SENT, satoshisSent)
                putExtra(IntentContract.ACCOUNTS_INDEX, intArrayOf(accountIndex))
            }
            send(intent)
        }
        fun notifySingleAddressSatoshisReceived(satoshisReceived: Long, satoshisSent: Long, guid: String) {
            val intent = Intent("com.mycelium.wallet.notifySatoshisReceivedSingleAddress").apply {
                putExtra(IntentContract.SATOSHIS_RECEIVED, satoshisReceived)
                putExtra(IntentContract.SATOSHIS_SENT, satoshisSent)
                putExtra(IntentContract.SINGLE_ADDRESS_ACCOUNT_GUID, guid)
            }
            send(intent)
        }

        fun notifyBroadcastTransactionBroadcastCompleted(operationId : String, txHash : String, isSuccess: Boolean, message: String) {
            val intent = Intent("com.mycelium.wallet.notifyBroadcastTransactionBroadcastCompleted").apply {
                putExtra(IntentContract.OPERATION_ID, operationId)
                putExtra(IntentContract.TRANSACTION_HASH, txHash)
                putExtra(IntentContract.IS_SUCCESS, isSuccess)
                putExtra(IntentContract.MESSAGE, message)
            }
            send(intent)
        }

        fun sendUnsignedTransactionToMbw(operationId: String,
                                         unsignedTransaction: StandardTransactionBuilder.UnsignedTransaction,
                                         accountIndex: Int) {
            val intent = Intent("com.mycelium.wallet.sendUnsignedTransactionToMbw").apply {
                putExtra(IntentContract.UNSIGNED_TRANSACTION, unsignedTransaction)
                putExtra(IntentContract.ACCOUNT_INDEX_EXTRA, accountIndex)
                putExtra(IntentContract.OPERATION_ID, operationId)
            }
            send(intent)
        }

        fun sendUnsignedTransactionToMbw(operationId: String, transaction: Transaction,
                                         accountIndex: Int, txUTXOs: List<ByteArray>) {
            val intent = Intent("com.mycelium.wallet.sendUnsignedTransactionToMbw").apply {
                putExtra(IntentContract.ACCOUNT_INDEX_EXTRA, accountIndex)
                putExtra(IntentContract.OPERATION_ID, operationId)
                putExtra(IntentContract.TRANSACTION_BYTES, transaction.bitcoinSerialize())
                var index = 0
                for (utxo in txUTXOs) {
                    putExtra(IntentContract.CONNECTED_OUTPUTS+index++, utxo)
                }
            }

            send(intent)
        }

        fun sendUnsignedTransactionToMbwSingleAddress(operationId: String,
                                                      unsignedTransaction: Transaction,
                                                      txOutputHex : List<String>, guid: String) {
            val intent = Intent("com.mycelium.wallet.sendUnsignedTransactionToMbwSingleAddress").apply {
                putExtra(IntentContract.SINGLE_ADDRESS_ACCOUNT_GUID, guid)
                putExtra(IntentContract.OPERATION_ID, operationId)
                putExtra(IntentContract.TRANSACTION_BYTES, unsignedTransaction.bitcoinSerialize())
                putExtra(IntentContract.CONNECTED_OUTPUTS, txOutputHex.toTypedArray())
            }
            send(intent)
        }
    }
}
