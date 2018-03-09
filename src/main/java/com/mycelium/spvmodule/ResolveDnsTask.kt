package com.mycelium.spvmodule

import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import java.net.UnknownHostException

abstract class ResolveDnsTask(private val backgroundHandler: Handler) {
    private val callbackHandler: Handler = Handler(Looper.myLooper())

    fun resolve(hostname: String) {
        backgroundHandler.post {
            try {
                val address = InetAddress.getByName(removePort(hostname)) // blocks on network

                callbackHandler.post { onSuccess(address) }
            } catch (x: UnknownHostException) {
                callbackHandler.post { onUnknownHost() }
            }
        }
    }

    private fun removePort(hostname: String) = hostname.replace(Regex(":.*"), "")

    protected abstract fun onSuccess(address: InetAddress)

    protected abstract fun onUnknownHost()
}
