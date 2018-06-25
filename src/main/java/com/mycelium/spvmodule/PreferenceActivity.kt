/*
 * Copyright 2011-2015 the original author or authors.
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

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.text.Html
import android.util.Log
import android.view.MenuItem

class PreferenceActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preference)
        title = getString(R.string.settings_title)
        if (intent?.hasExtra("callingPackage") == true) {
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_back_arrow)
        }
        if (savedInstanceState == null) {
            val settingsFragment = SettingsFragment()
            val ft = supportFragmentManager.beginTransaction()
            ft.add(R.id.fragment_container, settingsFragment)
            ft.commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        if (item!!.itemId == android.R.id.home) {
            val mbwModulePackage = SpvModuleApplication.getMbwModulePackage()

            val isMBWInstalled = SpvModuleApplication.isMbwInstalled(this)

            if (isMBWInstalled) {
                val walletPackage = mbwModulePackage
                if (intent?.getStringExtra("callingPackage") != walletPackage) {
                    val intent = Intent(Intent.ACTION_MAIN)
                    intent.`package` = walletPackage
                    try {
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        Log.e("PreferenceActivity", "Something wrong with wallet", e)
                    }
                }
                finish()
            } else {
                val installIntent = Intent(Intent.ACTION_VIEW)
                installIntent.data = Uri.parse("https://play.google.com/store/apps/details?id=" + mbwModulePackage)
                startActivity(installIntent)
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
