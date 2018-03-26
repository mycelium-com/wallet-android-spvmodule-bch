package com.mycelium.spvmodule

import android.content.SharedPreferences
import android.util.Log
import com.google.common.base.Strings
import com.google.gson.annotations.SerializedName
import com.mycelium.spvmodule.BuildConfig.IS_TESTNET

import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET


interface  MyceliumNodesApi {
    @GET("/nodes.json")
    fun getNodes(): Call<MyceliumNodesResponse>
}

class MyceliumNodesResponse(@SerializedName("BCH-testnet") val bchTestnet: BCHNetResponse,
                            @SerializedName("BCH-mainnet") val bchMainnet: BCHNetResponse)

class BCHNetResponse(val bitcoind: BitcoinDResponse)

class BitcoinDResponse(val primary: Array<String>, val backup: Array<String>)

class Configuration(private val prefs: SharedPreferences) {
    init {
        updateMyceliumTrustedPeers()
    }

    fun updateMyceliumTrustedPeers() {
        val oldPeers = myceliumPeerHosts
        Retrofit.Builder()
                .baseUrl("https://mycelium-wallet.s3.amazonaws.com")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(MyceliumNodesApi::class.java)
                .getNodes()
                .enqueue(object: Callback<MyceliumNodesResponse> {
                    override fun onFailure(call: Call<MyceliumNodesResponse>?, t: Throwable?) {
                        Log.e(TAG, "Ignoring error: ${t?.message ?: "no message"}")
                    }

                    override fun onResponse(call: Call<MyceliumNodesResponse>, response: Response<MyceliumNodesResponse>) {
                        if(response.isSuccessful) {
                            val myceliumNodesResponse = response.body()
                            // TODO Get back to TLS when ready
                            // As TLS is not supported by bitcoincashj now, use tcp nodes without TLS, they can be retrieved from 'backup' instead of 'primary'
                            val newPeers = mutableSetOf(*if (IS_TESTNET) {
                                myceliumNodesResponse?.bchTestnet?.bitcoind?.backup ?: return
                            } else {
                                myceliumNodesResponse?.bchMainnet?.bitcoind?.backup ?: return
                            })

                            if (newPeers.containsAll(oldPeers) && oldPeers.containsAll(newPeers)) {
                                return
                            }

                            // the new peers are different from the old peers and not empty. store them!
                            prefs.edit().putStringSet(PREFS_MYCELIUM_FULL_NODES, newPeers).apply()
                        }
                    }
                })
    }

    val connectivityNotificationEnabled: Boolean
        get() = prefs.getBoolean(PREFS_KEY_CONNECTIVITY_NOTIFICATION, false)

    val peerHostConfig
        get() = prefs.getString(PREFS_NODE_OPTION, "mycelium")

    val trustedPeerHost
        get() = prefs.getString(PREFS_KEY_TRUSTED_PEER, "")!!.trim { it <= ' ' }

    val myceliumPeerHosts: Set<String>
        get() = prefs.getStringSet(PREFS_MYCELIUM_FULL_NODES, mutableSetOf(*BuildConfig.TRUSTED_FULL_NODES))

    val bestChainHeightEver: Int
        get() = prefs.getInt(PREFS_KEY_BEST_CHAIN_HEIGHT_EVER, 0)

    fun maybeIncrementBestChainHeightEver(bestChainHeightEver: Int) {
        if (bestChainHeightEver > this.bestChainHeightEver) {
           incrementBestChainHeightEver(bestChainHeightEver)
        }
    }

    private fun incrementBestChainHeightEver(bestChainHeightEver: Int) {
        prefs.edit().putInt(PREFS_KEY_BEST_CHAIN_HEIGHT_EVER, bestChainHeightEver).apply()
    }

    fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private val TAG = "Configuration"
        const val PREFS_KEY_CONNECTIVITY_NOTIFICATION = "connectivity_notification"
        const val PREFS_KEY_TRUSTED_PEER = "trusted_peer"
        const val PREFS_KEY_DATA_USAGE = "data_usage"
        const val PREFS_KEY_SYNC_PROGRESS = "sync_progress"
        const val PREFS_KEY_HEADER = "header"
        const val PREFS_NODE_OPTION = "node_option"
        const val PREFS_MYCELIUM_FULL_NODES = "mycelium_nodes"

        private const val PREFS_KEY_BEST_CHAIN_HEIGHT_EVER = "best_chain_height_ever"
    }
}
