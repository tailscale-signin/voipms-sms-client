package net.kourlas.voipms_sms.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class NetworkManager {
    fun isNetworkConnectionAvailable(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    companion object {
        private var instance: NetworkManager? = null

        /**
         * Gets the sole instance of the NetworkManager class. Initializes the
         * instance if it does not already exist.
         */
        fun getInstance(): NetworkManager = instance ?: synchronized(this) {
            instance ?: NetworkManager().also { instance = it }
        }
    }
}