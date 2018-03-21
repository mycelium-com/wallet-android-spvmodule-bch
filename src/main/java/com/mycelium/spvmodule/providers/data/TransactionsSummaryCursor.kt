package com.mycelium.spvmodule.providers.data

import android.database.MatrixCursor
import com.mycelium.spvmodule.providers.TransactionContract.TransactionSummary.*

class TransactionsSummaryCursor(initialCapacity: Int)
    : MatrixCursor(arrayOf(
        _ID,
        VALUE,
        IS_INCOMING,
        TIME,
        HEIGHT,
        CONFIRMATIONS,
        IS_QUEUED_OUTGOING,
        CONFIRMATION_RISK_PROFILE_LENGTH,
        CONFIRMATION_RISK_PROFILE_RBF_RISK,
        CONFIRMATION_RISK_PROFILE_DOUBLE_SPEND,
        DESTINATION_ADDRESS,
        TO_ADDRESSES),
        initialCapacity)
