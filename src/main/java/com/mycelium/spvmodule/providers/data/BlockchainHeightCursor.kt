package com.mycelium.spvmodule.providers.data

import android.database.MatrixCursor
import com.mycelium.spvmodule.providers.TransactionContract.BlockchainHeight.*

class BlockchainHeightCursor
    : MatrixCursor(arrayOf(
        HEIGHT),
        1)
