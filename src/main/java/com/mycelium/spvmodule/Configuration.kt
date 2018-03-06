package com.mycelium.spvmodule

import android.content.SharedPreferences
import com.google.common.base.Strings

class Configuration(private val prefs: SharedPreferences) {
    val connectivityNotificationEnabled: Boolean
        get() = prefs.getBoolean(PREFS_KEY_CONNECTIVITY_NOTIFICATION, false)

    val trustedPeerHost: String?
        get() {
            if (!prefs.contains(PREFS_KEY_TRUSTED_PEER)) {
                val nodeList = if (BuildConfig.APPLICATION_ID.contains(".test")) TRUSTED_FULL_NODES_TEST else TRUSTED_FULL_NODES_MAIN
                prefs.edit().putString(PREFS_KEY_TRUSTED_PEER, nodeList[(Math.random() * nodeList.size).toInt()]).apply()
            }
            return Strings.emptyToNull(prefs.getString(PREFS_KEY_TRUSTED_PEER, "")!!.trim { it <= ' ' })
        }

    val trustedPeerOnly: Boolean
        get() = prefs.getBoolean(PREFS_KEY_TRUSTED_PEER_ONLY, true)

    val broadcastUsingAlternative: Boolean
        get() = prefs.getBoolean(PREFS_KEY_BROADCAST_USING_ALTERNATIVE, true)

    val lastUsedAgo: Long
        get() = System.currentTimeMillis() - prefs.getLong(PREFS_KEY_LAST_USED, 0)

    val bestChainHeightEver: Int
        get() = prefs.getInt(PREFS_KEY_BEST_CHAIN_HEIGHT_EVER, 0)

    fun maybeIncrementBestChainHeightEver(bestChainHeightEver: Int) {
        if (bestChainHeightEver > this.bestChainHeightEver) {
           incrementBestChainHeightEver(bestChainHeightEver)
        }
    }

    fun incrementBestChainHeightEver(bestChainHeightEver: Int) {
        prefs.edit().putInt(PREFS_KEY_BEST_CHAIN_HEIGHT_EVER, bestChainHeightEver).apply()
    }

    fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private val TRUSTED_FULL_NODES_MAIN = arrayOf(
                "bitcoin-abc-1.mycelium.com:8444",
                "bitcoin-abc-2.mycelium.com:8444",
                "bitcoin-abc-3.mycelium.com:8444")
        private val TRUSTED_FULL_NODES_TEST = arrayOf(
                "bitcoin-abc-1.mycelium.com:18444",
                "bitcoin-abc-2.mycelium.com:18444",
                "bitcoin-abc-3.mycelium.com:18444")
        val PREFS_KEY_CONNECTIVITY_NOTIFICATION = "connectivity_notification"
        val PREFS_KEY_TRUSTED_PEER = "trusted_peer"
        val PREFS_KEY_TRUSTED_PEER_ONLY = "trusted_peer_only"
        val PREFS_KEY_BROADCAST_USING_ALTERNATIVE = "broadcast_using_alternative"
        val PREFS_KEY_DATA_USAGE = "data_usage"
        val PREFS_KEY_SYNC_PROGRESS = "sync_progress"
        val PREFS_KEY_HEADER = "header"
        val PREFS_KEY_BCH_SETTINGS = "bitcoin_cash_settings"

        private val PREFS_KEY_LAST_USED = "last_used"
        private val PREFS_KEY_BEST_CHAIN_HEIGHT_EVER = "best_chain_height_ever"
    }
}
