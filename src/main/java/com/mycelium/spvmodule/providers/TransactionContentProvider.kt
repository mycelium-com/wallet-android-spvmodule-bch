package com.mycelium.spvmodule.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import com.mycelium.modularizationtools.CommunicationManager
import com.mycelium.spvmodule.BuildConfig
import com.mycelium.spvmodule.Constants
import com.mycelium.spvmodule.SpvModuleApplication
import com.mycelium.spvmodule.TransactionFee
import com.mycelium.spvmodule.currency.ExactBitcoinCashValue
import com.mycelium.spvmodule.guava.Bip44AccountIdleService
import com.mycelium.spvmodule.guava.Bip44DownloadProgressTracker
import com.mycelium.spvmodule.providers.TransactionContract.*
import com.mycelium.spvmodule.providers.data.*
import org.bitcoinj.core.Utils
import java.util.UUID
import java.util.concurrent.TimeUnit

class TransactionContentProvider : ContentProvider() {
    private var communicationManager: CommunicationManager? = null

    private val LOG_TAG = this::class.java.simpleName

    override fun onCreate(): Boolean {
        CommunicationManager.init(context)
        communicationManager = CommunicationManager.getInstance()
        return true
    }

    override fun query(uri: Uri?, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        SpvModuleApplication.getApplication().waitUntilInitialized()
        checkSignature(callingPackage)
        val match = URI_MATCHER.match(uri)
        val service = Bip44AccountIdleService.getInstance()
        when (match) {
            TRANSACTION_SUMMARY_LIST ->
                if (selection == TransactionSummary.SELECTION_ACCOUNT_INDEX) {

                    Log.d(LOG_TAG, "query, TRANSACTION_SUMMARY_LIST")
                    val accountIndex = selectionArgs!!.get(0)

                    val transactionsSummary = service.getTransactionsSummary(accountIndex.toInt())
                    val cursor = TransactionsSummaryCursor(transactionsSummary.size)

                    for (rowItem in transactionsSummary) {
                        val riskProfile = rowItem.confirmationRiskProfile.orNull()
                        val columnValues = listOf(
                                Utils.HEX.encode(rowItem.txid.bytes),          //TransactionContract.TransactionSummary._ID
                                rowItem.value.value.toPlainString(),           //TransactionContract.TransactionSummary.VALUE
                                if (rowItem.isIncoming) 1 else 0,              //TransactionContract.TransactionSummary.IS_INCOMING
                                rowItem.time,                                  //TransactionContract.TransactionSummary.TIME
                                rowItem.height,                                //TransactionContract.TransactionSummary.HEIGHT
                                rowItem.confirmations,                         //TransactionContract.TransactionSummary.CONFIRMATIONS
                                if (rowItem.isQueuedOutgoing) 1 else 0,        //TransactionContract.TransactionSummary.IS_QUEUED_OUTGOING
                                riskProfile?.unconfirmedChainLength ?: -1,     //TransactionContract.TransactionSummary.CONFIRMATION_RISK_PROFILE_LENGTH
                                riskProfile?.hasRbfRisk,                       //TransactionContract.TransactionSummary.CONFIRMATION_RISK_PROFILE_RBF_RISK
                                riskProfile?.isDoubleSpend,                    //TransactionContract.TransactionSummary.CONFIRMATION_RISK_PROFILE_DOUBLE_SPEND
                                rowItem.destinationAddress.orNull()?.toString(),//TransactionContract.TransactionSummary.DESTINATION_ADDRESS
                                rowItem.toAddresses.joinToString(",")          //TransactionContract.TransactionSummary.TO_ADDRESSES
                        )
                        cursor.addRow(columnValues)
                    }
                    return cursor
                } else if (selection == TransactionSummary.SELECTION_SINGLE_ADDRESS_ACCOUNT_GUID) {
                    Log.d(LOG_TAG, "query, TRANSACTION_SUMMARY_LIST")
                    val guid = selectionArgs!!.get(0)
                    val transactionsSummary = service.getTransactionsSummary(guid)
                    var cursor = MatrixCursor(emptyArray(), 0)
                    cursor = TransactionsSummaryCursor(transactionsSummary.size)

                    for (rowItem in transactionsSummary) {
                        val riskProfile = rowItem.confirmationRiskProfile.orNull()
                        val columnValues = listOf(
                                Utils.HEX.encode(rowItem.txid.bytes),           //TransactionContract.TransactionSummary._ID
                                rowItem.value.value.toPlainString(),           //TransactionContract.TransactionSummary.VALUE
                                if (rowItem.isIncoming) 1 else 0,              //TransactionContract.TransactionSummary.IS_INCOMING
                                rowItem.time,                                  //TransactionContract.TransactionSummary.TIME
                                rowItem.height,                                //TransactionContract.TransactionSummary.HEIGHT
                                rowItem.confirmations,                         //TransactionContract.TransactionSummary.CONFIRMATIONS
                                if (rowItem.isQueuedOutgoing) 1 else 0,        //TransactionContract.TransactionSummary.IS_QUEUED_OUTGOING
                                riskProfile?.unconfirmedChainLength ?: -1,     //TransactionContract.TransactionSummary.CONFIRMATION_RISK_PROFILE_LENGTH
                                riskProfile?.hasRbfRisk,                       //TransactionContract.TransactionSummary.CONFIRMATION_RISK_PROFILE_RBF_RISK
                                riskProfile?.isDoubleSpend,                    //TransactionContract.TransactionSummary.CONFIRMATION_RISK_PROFILE_DOUBLE_SPEND
                                rowItem.destinationAddress.orNull()?.toString(),//TransactionContract.TransactionSummary.DESTINATION_ADDRESS
                                rowItem.toAddresses.joinToString(",")          //TransactionContract.TransactionSummary.TO_ADDRESSES
                        )
                        cursor.addRow(columnValues)
                    }
                    return cursor
                }

            TRANSACTION_SUMMARY_ID -> {
                Log.d(LOG_TAG, "query, TRANSACTION_SUMMARY_ID $selection")
                when (selection) {
                    TransactionSummary.SELECTION_ACCOUNT_INDEX -> {
                        val accountIndex = selectionArgs!!.get(0)
                        val transactionsSummary = service.getTransactionsSummary(accountIndex.toInt())
                        return TransactionsSummaryCursor(transactionsSummary.size).apply {
                            addTransactionsToCursorSince(transactionsSummary, this, 0)
                        }
                    }
                    TransactionSummary.SELECTION_ACCOUNT_INDEX_SINCE -> {
                        val accountIndex = selectionArgs!!.get(0)
                        val since = selectionArgs.get(1)
                        val transactionsSummary = service.getTransactionsSummary(accountIndex.toInt())
                        return TransactionsSummaryCursor(transactionsSummary.size).apply {
                            addTransactionsToCursorSince(transactionsSummary, this, since.toLong())
                        }
                    }
                    TransactionSummary.SELECTION_SINGLE_ADDRESS_ACCOUNT_GUID -> {
                        val guid = selectionArgs!!.get(0)
                        val since = 0L
                        val transactionsSummary = service.getTransactionsSummary(guid)
                        return TransactionsSummaryCursor(transactionsSummary.size).apply {
                            addTransactionsToCursorSince(transactionsSummary, this, since)
                        }
                    }
                    TransactionSummary.SELECTION_SINGLE_ADDRESS_ACCOUNT_GUID_SINCE -> {
                        val guid = selectionArgs!!.get(0)
                        val since = selectionArgs.get(1)
                        val transactionsSummary = service.getTransactionsSummary(guid)
                        return TransactionsSummaryCursor(transactionsSummary.size).apply {
                            addTransactionsToCursorSince(transactionsSummary, this, since.toLong())
                        }
                    }
                    else -> {
                        Log.e(LOG_TAG, "query, TRANSACTION_SUMMARY_LIST failed for selection $selection")
                    }
                }
            }

            TRANSACTION_DETAILS_ID ->
                if (selection == TransactionDetails.SELECTION_ACCOUNT_INDEX) {
                    val cursor = TransactionDetailsCursor()
                    val accountIndex = selectionArgs!!.get(0)
                    var accountUuid: UUID? = null
                    var accountIdInt: Int = -1
                    try {
                        accountIdInt = accountIndex.toInt()
                    } catch (e:NumberFormatException) {
                        accountUuid = UUID.fromString(accountIndex)
                    }
                    val hash = uri!!.lastPathSegment
                    val transactionDetails : com.mycelium.spvmodule.model.TransactionDetails
                    if(accountIdInt == -1) {
                        transactionDetails = service.getTransactionDetails(accountUuid!!, hash)
                                ?: return cursor
                    } else {
                        transactionDetails = service.getTransactionDetails(accountIndex.toInt(), hash)
                                ?: return cursor
                    }

                    val inputs = transactionDetails.inputs.map { "${it.value} BCH${it.address}" }.joinToString(",")
                    val outputs = transactionDetails.outputs.map { "${it.value} BCH${it.address}" }.joinToString(",")
                    val columnValues = listOf(
                        transactionDetails.hash.toString(), //TransactionContract.Transaction._ID
                        transactionDetails.height,       //TransactionContract.Transaction.HEIGHT
                        transactionDetails.time,         //TransactionContract.Transaction.TIME
                        transactionDetails.rawSize,      //TransactionContract.Transaction.RAW_SIZE
                        inputs,                          //TransactionContract.Transaction.INPUTS
                        outputs)                         //TransactionContract.Transaction.OUTPUTS
                    cursor.addRow(columnValues)
                    return cursor
                }
            ACCOUNT_BALANCE_ID, ACCOUNT_BALANCE_LIST -> {
                val cursor = AccountBalanceCursor()
                if (selection == AccountBalance.SELECTION_ACCOUNT_INDEX) {
                    // this is the ACCOUNT_BALANCE_ID case but we don't read the selection from the url (yet?)
                    listOf(selectionArgs!!.get(0).toInt()).forEach { accountIndex ->
                        val columnValues = listOf(
                                accountIndex,                             //TransactionContract.AccountBalance._ID
                                service.getAccountBalance(accountIndex),  //TransactionContract.AccountBalance.CONFIRMED
                                service.getAccountSending(accountIndex),  //TransactionContract.AccountBalance.SENDING
                                service.getAccountReceiving(accountIndex) //TransactionContract.AccountBalance.RECEIVING
                        )
                        cursor.addRow(columnValues)
                    }
                } else if (selection == AccountBalance.SELECTION_GUID) {
                    val guid = selectionArgs!!.get(0)

                    val columnValues = listOf(
                            0,                             //TransactionContract.AccountBalance._ID
                            service.getUnrelatedAccountBalance(guid),  //TransactionContract.AccountBalance.CONFIRMED
                            service.getUnrelatedAccountSending(guid),  //TransactionContract.AccountBalance.SENDING
                            service.getUnrelatedAccountReceiving(guid) //TransactionContract.AccountBalance.RECEIVING
                    )
                    cursor.addRow(columnValues)
                } else {
                    // we assume no selection for now and return all accounts
                    service.getAccountIndices().forEach { accountIndex ->
                        val columnValues = listOf(
                                accountIndex,                             //TransactionContract.AccountBalance._ID
                                service.getAccountBalance(accountIndex),  //TransactionContract.AccountBalance.CONFIRMED
                                service.getAccountSending(accountIndex),  //TransactionContract.AccountBalance.SENDING
                                service.getAccountReceiving(accountIndex) //TransactionContract.AccountBalance.RECEIVING
                        )
                        cursor.addRow(columnValues)
                    }
                }
                return cursor
            }
            CURRENT_RECEIVE_ADDRESS_LIST, CURRENT_RECEIVE_ADDRESS_ID -> {
                val cursor = CurrentReceiveAddressCursor()
                if (selection == CurrentReceiveAddress.SELECTION_ACCOUNT_INDEX) {
                    // this is the CURRENT_RECEIVE_ADDRESS_ID case but we don't read the selection from the url (yet?)
                    val accountIndex = selectionArgs!![0].toInt()

                    val currentReceiveAddress = service.getAccountCurrentReceiveAddress(accountIndex)
                    val qrAddressString = Constants.QR_ADDRESS_PREFIX + currentReceiveAddress
                    val columnValues = listOf(
                            accountIndex,                           //TransactionContract.CurrentReceiveAddress._ID
                            currentReceiveAddress?.toString(),      //TransactionContract.CurrentReceiveAddress.ADDRESS
                            qrAddressString                         //TransactionContract.CurrentReceiveAddress.ADDRESS_QR
                    )
                    cursor.addRow(columnValues)

                } else if (selection == CurrentReceiveAddress.SELECTION_UNRELATED) {
                    val guid = selectionArgs!![0]
                    val currentReceiveAddress = service.getAccountCurrentReceiveAddressUnrelatedHD(guid)
                    val qrAddressString = Constants.QR_ADDRESS_PREFIX + currentReceiveAddress
                    val columnValues = listOf(
                            guid,                           //TransactionContract.CurrentReceiveAddress._ID
                            currentReceiveAddress?.toString(),      //TransactionContract.CurrentReceiveAddress.ADDRESS
                            qrAddressString                         //TransactionContract.CurrentReceiveAddress.ADDRESS_QR
                    )
                    cursor.addRow(columnValues)
                }
                return cursor
            }
            VALIDATE_QR_CODE_ID -> {
                val cursor = ValidateQrCodeCursor()
                if (selection == ValidateQrCode.SELECTION_COMPLETE) {
                    //val accountIndex = selectionArgs!![0].toInt()
                    val qrCode = selectionArgs!![1]
                    val isValid = service.isValid(qrCode)
                    val columnValues = listOf(
                            qrCode,                     //ValidateQrCode.QR_CODE
                            if (isValid) 1 else 0       //ValidateQrCode.IS_VALID
                    )
                    cursor.addRow(columnValues)
                }
                return cursor
            }
            CALCULATE_MAX_SPENDABLE_ID -> {
                val cursor = CalculateMaxSpendableCursor()
                if (selection == CalculateMaxSpendable.SELECTION_HD) {
                    val accountIndex = selectionArgs!![0].toInt()
                    val txFeeStr = selectionArgs[1]
                    val txFee = TransactionFee.valueOf(txFeeStr)
                    val txFeeFactor = selectionArgs[2].toFloat()
                    val maxSpendableAmount = service.calculateMaxSpendableAmount(accountIndex, txFee, txFeeFactor)
                    val columnValues = listOf(
                            txFee,                  //CalculateMaxSpendable.TX_FEE
                            txFeeFactor,            //CalculateMaxSpendable.TX_FEE_FACTOR
                            maxSpendableAmount      //CalculateMaxSpendable.MAX_SPENDABLE
                    )
                    cursor.addRow(columnValues)
                } else if (selection == CalculateMaxSpendable.SELECTION_SA) {
                    val accountGuid = selectionArgs!!.get(0)
                    val txFeeStr = selectionArgs[1]
                    val txFee = TransactionFee.valueOf(txFeeStr)
                    val txFeeFactor = selectionArgs[2].toFloat()
                    val maxSpendableAmount = service.calculateMaxSpendableAmountUnrelated(accountGuid, txFee, txFeeFactor)
                    val columnValues = listOf(
                            txFee,                  //CalculateMaxSpendable.TX_FEE
                            txFeeFactor,            //CalculateMaxSpendable.TX_FEE_FACTOR
                            maxSpendableAmount      //CalculateMaxSpendable.MAX_SPENDABLE
                    )
                    cursor.addRow(columnValues)
                }
                return cursor
            }

            GET_MAX_FUNDS_TRANSFERRABLE_ID -> {
                val cursor = GetMaxFundsTransferrableCursor()
                if (selection == GetMaxFundsTransferrable.SELECTION_HD) {
                    val accountIndex = selectionArgs!![0].toInt()
                    val maxAmount = service.getMaxFundsTranferableBySingleTransactionHD(accountIndex)
                    cursor.addRow(listOf(maxAmount))

                } else if (selection == GetMaxFundsTransferrable.SELECTION_UNRELATED) {
                    val accountGuid = selectionArgs!!.get(0)
                    val maxAmount = service.getMaxFundsTranferableBySingleTransactionUnrelated(accountGuid)
                    cursor.addRow(listOf(maxAmount))
                }
                return cursor
            }

            ESTIMATE_FEES_FROM_TRANSFERRABLE_AMOUNT_ID -> {
                val cursor = EstimateFeeFromTransferrableAmountCursor()

                if (selection == EstimateFeeFromTransferrableAmount.SELECTION_HD) {
                    val accountIndex = selectionArgs!![0].toInt()
                    val txFeeStr = selectionArgs[1]
                    val txFee = TransactionFee.valueOf(txFeeStr)
                    val txFeeFactor = selectionArgs[2].toFloat()
                    val amount = selectionArgs[3].toLong()
                    val estimatedFee = service.calculateFeeToTransferAmountHD(accountIndex, amount, txFee, txFeeFactor)
                    cursor.addRow(listOf(estimatedFee))
                } else if (selection == EstimateFeeFromTransferrableAmount.SELECTION_UNRELATED) {
                    val accountGuid = selectionArgs!!.get(0)
                    val txFeeStr = selectionArgs[1]
                    val txFee = TransactionFee.valueOf(txFeeStr)
                    val txFeeFactor = selectionArgs[2].toFloat()
                    val amount = selectionArgs[3].toLong()
                    val estimatedFee = service.calculateFeeToTransferAmountUnrelated(accountGuid, amount, txFee, txFeeFactor)
                    cursor.addRow(listOf(estimatedFee))
                }

                return cursor
            }


            CHECK_SEND_AMOUNT_ID -> {
                val cursor = CheckSendAmountCursor()
                if (selection == CheckSendAmount.SELECTION_COMPLETE) {
                    val accountIndex = selectionArgs!![0].toInt()
                    val txFeeStr = selectionArgs[1]
                    val txFee = TransactionFee.valueOf(txFeeStr)
                    val txFeeFactor = selectionArgs[2].toFloat()
                    val amountToSend = selectionArgs[3].toLong()
                    val checkSendAmount = service.checkSendAmount(accountIndex, txFee, txFeeFactor, amountToSend)
                    val columnValues = listOf(
                            txFee,                  //CheckSendAmount.TX_FEE
                            txFeeFactor,            //CheckSendAmount.TX_FEE_FACTOR
                            amountToSend,           //CheckSendAmount.AMOUNT_TO_SEND
                            checkSendAmount!!.name  //CheckSendAmount.RESULT
                    )
                    cursor.addRow(columnValues)
                }
                return cursor
            }
            GET_SYNC_PROGRESS_ID -> {
                return SyncProgressCursor().apply {
                    addRow(listOf(Bip44DownloadProgressTracker.getSyncProgress()))
                }
            }
            GET_PRIVATE_KEYS_COUNT_ID -> {
                if (selection == GetPrivateKeysCount.SELECTION_ACCOUNT_INDEX) {
                    val accountIndex = selectionArgs!![0].toInt()
                    return GetPrivateKeysCountCursor().apply {
                        val issuedKeysPair = Bip44AccountIdleService.getInstance().getPrivateKeysCount(accountIndex)
                        addRow(listOf(issuedKeysPair.first, issuedKeysPair.second))
                    }
                } else if (selection == GetPrivateKeysCount.SELECTION_UNRELATED) {
                    val accountGuid = selectionArgs!![0]
                    return GetPrivateKeysCountCursor().apply {
                        val issuedKeysPair = Bip44AccountIdleService.getInstance().getPrivateKeysCountUnrelated(accountGuid)
                        addRow(listOf(issuedKeysPair.first, issuedKeysPair.second))
                    }
                }
            }
            BLOCKCHAIN_HEIGHT_ID -> {
                val cursor = BlockchainHeightCursor()
                val columnValues = listOf(
                        Bip44AccountIdleService.getInstance()!!.getBestChainHeight())
                cursor.addRow(columnValues)
                return cursor
            }
            UriMatcher.NO_MATCH -> {
                Log.e(LOG_TAG, "no match for uri $uri")
            }
            else -> {
                Log.e(LOG_TAG, "unhandled match $match")
                // Do nothing.
            }
        }
        return MatrixCursor(emptyArray(), 0)
    }

    /**
     * @since time in milliseconds
     */
    private fun addTransactionsToCursorSince(transactionsSummary: List<com.mycelium.spvmodule.model.TransactionSummary>, cursor: MatrixCursor, since: Long) {
        val sinceSec = TimeUnit.MILLISECONDS.toSeconds(since)
        for (rowItem in transactionsSummary) {
            if (rowItem.time > sinceSec) {
                val riskProfile = rowItem.confirmationRiskProfile.orNull()
                val columnValues = listOf(
                        rowItem.txid.toString(),                       //TransactionContract.TransactionSummary._ID
                        (rowItem.value as ExactBitcoinCashValue).asBitcoinCash
                                .longValue.toString(),                 //TransactionContract.TransactionSummary.VALUE
                        if (rowItem.isIncoming) 1 else 0,              //TransactionContract.TransactionSummary.IS_INCOMING
                        rowItem.time,                                  //TransactionContract.TransactionSummary.TIME
                        rowItem.height,                                //TransactionContract.TransactionSummary.HEIGHT
                        rowItem.confirmations,                         //TransactionContract.TransactionSummary.CONFIRMATIONS
                        if (rowItem.isQueuedOutgoing) 1 else 0,        //TransactionContract.TransactionSummary.IS_QUEUED_OUTGOING
                        riskProfile?.unconfirmedChainLength
                                ?: -1,     //TransactionContract.TransactionSummary.CONFIRMATION_RISK_PROFILE_LENGTH
                        riskProfile?.hasRbfRisk,                       //TransactionContract.TransactionSummary.CONFIRMATION_RISK_PROFILE_RBF_RISK
                        riskProfile?.isDoubleSpend,                    //TransactionContract.TransactionSummary.CONFIRMATION_RISK_PROFILE_DOUBLE_SPEND
                        rowItem.destinationAddress.orNull()?.toString(),//TransactionContract.TransactionSummary.DESTINATION_ADDRESS
                        rowItem.toAddresses.joinToString(",")          //TransactionContract.TransactionSummary.TO_ADDRESSES
                )
                cursor.addRow(columnValues)
            }
        }
    }

    override fun insert(uri: Uri?, values: ContentValues?): Uri {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun update(uri: Uri?, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun delete(uri: Uri?, selection: String?, selectionArgs: Array<out String>?): Int {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    private fun checkSignature(callingPackage: String) {
        communicationManager!!.checkSignature(callingPackage)
    }

    override fun getType(uri: Uri): String? {
        checkSignature(callingPackage)
        return when (URI_MATCHER.match(uri)) {
            TRANSACTION_SUMMARY_LIST, TRANSACTION_SUMMARY_ID -> TransactionSummary.CONTENT_TYPE
            TRANSACTION_DETAILS_LIST, TRANSACTION_DETAILS_ID -> TransactionDetails.CONTENT_TYPE
            ACCOUNT_BALANCE_LIST, ACCOUNT_BALANCE_ID -> AccountBalance.CONTENT_TYPE
            CURRENT_RECEIVE_ADDRESS_LIST, CURRENT_RECEIVE_ADDRESS_ID -> CurrentReceiveAddress.CONTENT_TYPE
            VALIDATE_QR_CODE_ID -> ValidateQrCode.CONTENT_TYPE
            CALCULATE_MAX_SPENDABLE_ID -> CalculateMaxSpendable.CONTENT_TYPE
            CHECK_SEND_AMOUNT_ID -> CheckSendAmount.CONTENT_TYPE
            BLOCKCHAIN_HEIGHT_ID -> BlockchainHeight.CONTENT_TYPE
            else -> null
        }
    }

    companion object {
        private val TRANSACTION_SUMMARY_LIST = 1
        private val TRANSACTION_SUMMARY_ID = 2
        private val TRANSACTION_DETAILS_LIST = 3
        private val TRANSACTION_DETAILS_ID = 4
        private val ACCOUNT_BALANCE_LIST = 5
        private val ACCOUNT_BALANCE_ID = 6
        private val CURRENT_RECEIVE_ADDRESS_LIST = 7
        private val CURRENT_RECEIVE_ADDRESS_ID = 8
        private val VALIDATE_QR_CODE_ID = 9
        private val CALCULATE_MAX_SPENDABLE_ID = 10
        private val CHECK_SEND_AMOUNT_ID = 11
        private val GET_SYNC_PROGRESS_ID = 12
        private val GET_PRIVATE_KEYS_COUNT_ID = 13
        private val ESTIMATE_FEES_FROM_TRANSFERRABLE_AMOUNT_ID = 14
        private val GET_MAX_FUNDS_TRANSFERRABLE_ID = 15
        private val BLOCKCHAIN_HEIGHT_ID = 16

        private val URI_MATCHER: UriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            val auth = TransactionContract.AUTHORITY(BuildConfig.APPLICATION_ID)
            addURI(auth, TransactionSummary.TABLE_NAME, TRANSACTION_SUMMARY_LIST)
            addURI(auth, "${TransactionSummary.TABLE_NAME}/*", TRANSACTION_SUMMARY_ID)
            addURI(auth, TransactionDetails.TABLE_NAME, TRANSACTION_DETAILS_LIST)
            addURI(auth, "${TransactionDetails.TABLE_NAME}/*", TRANSACTION_DETAILS_ID)
            addURI(auth, AccountBalance.TABLE_NAME, ACCOUNT_BALANCE_LIST)
            addURI(auth, "${AccountBalance.TABLE_NAME}/*", ACCOUNT_BALANCE_ID)
            addURI(auth, CurrentReceiveAddress.TABLE_NAME, CURRENT_RECEIVE_ADDRESS_LIST)
            addURI(auth, "${CurrentReceiveAddress.TABLE_NAME}/*", CURRENT_RECEIVE_ADDRESS_ID)
            addURI(auth, ValidateQrCode.TABLE_NAME, VALIDATE_QR_CODE_ID)
            addURI(auth, CalculateMaxSpendable.TABLE_NAME, CALCULATE_MAX_SPENDABLE_ID)
            addURI(auth, CheckSendAmount.TABLE_NAME, CHECK_SEND_AMOUNT_ID)
            addURI(auth, GetSyncProgress.TABLE_NAME, GET_SYNC_PROGRESS_ID)
            addURI(auth, GetPrivateKeysCount.TABLE_NAME, GET_PRIVATE_KEYS_COUNT_ID)
            addURI(auth, EstimateFeeFromTransferrableAmount.TABLE_NAME, ESTIMATE_FEES_FROM_TRANSFERRABLE_AMOUNT_ID)
            addURI(auth, GetMaxFundsTransferrable.TABLE_NAME, GET_MAX_FUNDS_TRANSFERRABLE_ID)
            addURI(auth, BlockchainHeight.TABLE_NAME, BLOCKCHAIN_HEIGHT_ID)
        }

        private fun getTableFromMatch(match: Int): String = when (match) {
            TRANSACTION_SUMMARY_LIST, TRANSACTION_SUMMARY_ID -> TransactionSummary.TABLE_NAME
            TRANSACTION_DETAILS_LIST, TRANSACTION_DETAILS_ID -> TransactionDetails.TABLE_NAME
            ACCOUNT_BALANCE_LIST, ACCOUNT_BALANCE_ID -> AccountBalance.TABLE_NAME
            CURRENT_RECEIVE_ADDRESS_LIST, CURRENT_RECEIVE_ADDRESS_ID -> CurrentReceiveAddress.TABLE_NAME
            VALIDATE_QR_CODE_ID -> ValidateQrCode.TABLE_NAME
            CALCULATE_MAX_SPENDABLE_ID -> CalculateMaxSpendable.TABLE_NAME
            CHECK_SEND_AMOUNT_ID -> CheckSendAmount.TABLE_NAME
            GET_SYNC_PROGRESS_ID -> GetSyncProgress.TABLE_NAME
            GET_PRIVATE_KEYS_COUNT_ID -> GetPrivateKeysCount.TABLE_NAME
            else -> throw IllegalArgumentException("Unknown match " + match)
        }
    }
}