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

package com.mycelium.spvmodule

import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.support.v4.content.LocalBroadcastManager
import android.support.v7.preference.ListPreference
import android.support.v7.preference.Preference
import android.support.v7.preference.PreferenceFragmentCompat
import android.util.Log
import android.view.View
import com.mycelium.spvmodule.guava.Bip44AccountIdleService
import com.mycelium.spvmodule.view.HeaderPreference

class SettingsFragment : PreferenceFragmentCompat(), Preference.OnPreferenceChangeListener {
    private var application: SpvModuleApplication? = null
    private var config: Configuration? = null
    private var pm: PackageManager? = null

    private val handler = Handler()
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var trustedPeerPreference: Preference? = null
    private var syncProgressPreference: Preference? = null
    private var nodeOptionPref: ListPreference? = null

    private val chainStateBroadcastReceiver: ChainStateBroadcastReceiver = SettingsFragment.ChainStateBroadcastReceiver(this)

    override fun onAttach(context: Context) {
        super.onAttach(context)

        this.application = SpvModuleApplication.getApplication()
        this.config = application!!.configuration
        this.pm = context.packageManager
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preference_settings)

        backgroundThread = HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND)
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
        val header = findPreference(Configuration.PREFS_KEY_HEADER) as HeaderPreference
        header.openListener = {
            val mbwModulePackage = SpvModuleApplication.getMbwModulePackage()

            val isMBWInstalled = SpvModuleApplication.isMbwInstalled(context!!)

            if (isMBWInstalled) {
                val walletPackage = mbwModulePackage
                if (activity?.intent?.getStringExtra("callingPackage") != walletPackage) {
                    val intent = Intent(Intent.ACTION_MAIN)
                    intent.`package` = walletPackage
                    try {
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        Log.e("PreferenceActivity", "Something wrong with wallet", e)
                    }
                }
                activity?.finish()
            } else {
                val installIntent = Intent(Intent.ACTION_VIEW)
                installIntent.data = Uri.parse("https://play.google.com/store/apps/details?id=" + mbwModulePackage)
                startActivity(installIntent)
            }
        }

        nodeOptionPref  = findPreference(Configuration.PREFS_NODE_OPTION) as ListPreference?
        nodeOptionPref!!.onPreferenceChangeListener = this
        trustedPeerPreference = findPreference(Configuration.PREFS_KEY_TRUSTED_PEER)
        trustedPeerPreference!!.onPreferenceChangeListener = this

        val dataUsagePreference = findPreference(Configuration.PREFS_KEY_DATA_USAGE)
        dataUsagePreference.isEnabled = pm!!.resolveActivity(dataUsagePreference.intent, 0) != null

        syncProgressPreference = findPreference(Configuration.PREFS_KEY_SYNC_PROGRESS)
        updateSyncProgress()

        updateTrustedPeer()
        LocalBroadcastManager.getInstance(application!!).registerReceiver(chainStateBroadcastReceiver, IntentFilter(SpvService.ACTION_BLOCKCHAIN_STATE))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.setPadding(0, 0, 0, 0)
    }

    private fun updateSyncProgress() {
        syncProgressPreference?.title = getString(R.string.pref_title_sync_progress
                , Bip44AccountIdleService.getInstance()!!.getSyncProgress())
    }

    class ChainStateBroadcastReceiver(private var fragment: SettingsFragment) : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            fragment.updateSyncProgress()
        }
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(application!!).unregisterReceiver(chainStateBroadcastReceiver)
        nodeOptionPref!!.onPreferenceChangeListener = null
        trustedPeerPreference!!.onPreferenceChangeListener = null

        backgroundThread!!.looper.quit()

        super.onDestroy()
    }

    override fun onPreferenceChange(preference: Preference?, newValue: Any?): Boolean {
        // delay action because preference isn't persisted until after this method returns
        val oldValue = (preference as ListPreference).value
        handler.post {
            if (newValue == "custom" || newValue == "random") {
                AlertDialog.Builder(context)
                        .setTitle(getString(R.string.warning))
                        .setMessage(getString(R.string.random_nodes_warning))
                        .setPositiveButton(getString(R.string.Continue)) { _, _ -> applyChanges(preference) }
                        .setNegativeButton(getString(R.string.cancel)) { _, _ -> revertChanges(oldValue) }
                        .create().show()
            } else {
                applyChanges(preference)
            }
        }
        return true
    }

    private fun revertChanges(oldValue: String?) {
        nodeOptionPref?.value = oldValue
    }

    private fun applyChanges(preference: Preference?) {
        if (preference in arrayOf(nodeOptionPref, trustedPeerPreference)) {
            application!!.stopBlockchainService()
            updateTrustedPeer()
        }
    }

    private fun updateTrustedPeer() {
        val trustedPeer = config!!.trustedPeerHost
        trustedPeerPreference!!.isVisible = nodeOptionPref!!.value == "custom"
        trustedPeerPreference!!.setSummary(trustedPeer ?: getString(R.string.preferences_trusted_peer_summary))
    }

    companion object {
        val LOG_TAG = "SettingsFragment"
    }
}
