package com.mycelium.spvmodule.providers.data

import android.database.MatrixCursor
import com.mycelium.spvmodule.providers.TransactionContract.TransactionDetails.*

class TransactionDetailsCursor
    : MatrixCursor(arrayOf(
        _ID,
        HEIGHT,
        TIME,
        RAW_SIZE,
        INPUTS,
        OUTPUTS),
        1)
