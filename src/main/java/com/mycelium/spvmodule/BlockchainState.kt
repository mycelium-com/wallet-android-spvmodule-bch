package com.mycelium.spvmodule

/*
 * Copyright 2014-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import android.content.Intent

import java.util.Date
import java.util.EnumSet

/**
 * @param bestChainDate date as found in the last block
 * @param bestChainHeight 0-based index of the last block of the best chain
 * @param replaying it's not the first time these blocks are being parsed. We have seen higher bestChainHeights before.
 * @param chainDownloadPercentDone percent of currently downloaded blocks
 * @param impediments problems when downloading (network and/or storage)
 */
class BlockchainState(
        val bestChainDate: Date,
        val bestChainHeight: Int,
        val replaying: Boolean,
        val chainDownloadPercentDone: Int,
        impediments: Set<Impediment>) {

    enum class Impediment {
        STORAGE, NETWORK
    }

    val impediments: EnumSet<Impediment> = EnumSet.copyOf(impediments)

    fun putExtras(intent: Intent) {
        intent.putExtra(EXTRA_BEST_CHAIN_DATE, bestChainDate.time)
        intent.putExtra(EXTRA_BEST_CHAIN_HEIGHT, bestChainHeight)
        intent.putExtra(EXTRA_REPLAYING, replaying)
        intent.putExtra(EXTRA_CHAIN_DOWNLOAD_PERCENT_DONE, chainDownloadPercentDone)
        intent.putExtra(EXTRA_IMPEDIMENTS, impediments.map {it.toString()}.toTypedArray())
    }

    companion object {
        private val EXTRA_BEST_CHAIN_DATE = "best_chain_date"
        private val EXTRA_BEST_CHAIN_HEIGHT = "best_chain_height"
        private val EXTRA_REPLAYING = "replaying"
        private val EXTRA_IMPEDIMENTS = "impediment"
        private val EXTRA_CHAIN_DOWNLOAD_PERCENT_DONE = "chain_download_percent_done"
    }
}
