package com.mycelium.spvmodule.guava

import android.content.Context
import android.content.SharedPreferences
import com.mycelium.spvmodule.Constants
import com.mycelium.spvmodule.SpvModuleApplication
import org.bitcoinj.store.BlockStore
import org.bitcoinj.store.SPVBlockStore
import java.io.File

class BlockStoreController(val context: Context) {
    var blockStore: BlockStore
    private var blockchainFile: File
    private val sharedPreferences: SharedPreferences = SpvModuleApplication.getApplication().getSharedPreferences(
            Bip44AccountIdleService.SHARED_PREFERENCES_FILE_NAME, Context.MODE_PRIVATE)

    init {
        blockchainFile = getBlockchainFile()
        blockStore = initBlockStore()
    }

    private fun getBlockchainFile(): File {
        return File(context.getDir("blockstore", Context.MODE_PRIVATE),
                Constants.Files.BLOCKCHAIN_FILENAME + "-BCH")
    }

    fun resetBlockchainState() {
        removeBlockStore()
        blockStore = initBlockStore()
    }

    private fun removeBlockStore() {
        blockStore.close()
        synchronized(blockchainFile.absolutePath.intern()) {
            sharedPreferences.edit()
                    .remove(Bip44AccountIdleService.SYNC_PROGRESS_PREF)
                    .apply()
            if (blockchainFile.exists()) {
                blockchainFile.delete()
            }
        }
    }

    private fun initBlockStore(): BlockStore {
        val blockStore = synchronized(blockchainFile.absolutePath.intern()) {
            SPVBlockStore(Constants.NETWORK_PARAMETERS, blockchainFile)
        }
        blockStore.chainHead // detect corruptions as early as possible
        return blockStore
    }

    protected fun finalize() {
        blockStore.close()
    }
}