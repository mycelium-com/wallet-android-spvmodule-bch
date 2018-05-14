package com.mycelium.spvmodule.providers.data

import android.database.MatrixCursor
import com.mycelium.spvmodule.providers.TransactionContract
import com.mycelium.spvmodule.providers.TransactionContract.AccountBalance.*

class AccountBalanceCursor
    : MatrixCursor(arrayOf(
        _ID,
        CONFIRMED,
        SENDING,
        RECEIVING,
        SYMBOL),
        1)
