package com.mycelium.spvmodule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri

class PackageRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.data != null) {
            val packageName = intent.data!!.encodedSchemeSpecificPart
            if (packageName == SpvModuleApplication.getMbwModulePackage()) {
                when (intent.action) {
                    Intent.ACTION_PACKAGE_REMOVED -> if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        val uninstallIntent = Intent(Intent.ACTION_DELETE)
                        uninstallIntent.data = Uri.parse("package:" + context.packageName)
                        uninstallIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(uninstallIntent)
                    }
                    Intent.ACTION_PACKAGE_REPLACED -> Runtime.getRuntime().exit(0)
                }
            }
        }
    }

    companion object {
        fun register(context: Context) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            context.registerReceiver(PackageRemovedReceiver(), filter)
        }
    }
}
