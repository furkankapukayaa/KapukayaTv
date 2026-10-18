package com.example.mirror

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/** Ağda bulunan bir alıcı (TV). */
data class MirrorTarget(val name: String, val host: String, val port: Int)

/**
 * Yerel ağda otomatik bulma (mDNS / NSD).
 * TV kendini "_kapukayatv._tcp" olarak duyurur, telefon bu servisleri listeler.
 */
class MirrorDiscovery(context: Context) {

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // NsdManager aynı anda tek bir çözümleme (resolve) yapabildiği için kuyruk kullanılır
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    // ---- Alıcı (TV) tarafı ----

    fun advertise(name: String, port: Int) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = MirrorProtocol.SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Servis duyurulamadı: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "registerService hatası", e)
        }
    }

    fun stopAdvertising() {
        registrationListener?.let {
            try {
                nsd.unregisterService(it)
            } catch (e: Exception) {
                // Zaten kayıtlı değil
            }
        }
        registrationListener = null
    }

    // ---- Gönderici (telefon) tarafı ----

    fun discover(onFound: (MirrorTarget) -> Unit, onLost: (String) -> Unit) {
        stopDiscovery()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Arama başlatılamadı: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(resolveQueue) {
                    resolveQueue.addLast(info)
                }
                resolveNext(onFound)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                onLost(info.serviceName)
            }
        }
        discoveryListener = listener
        try {
            nsd.discoverServices(MirrorProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices hatası", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext(onFound: (MirrorTarget) -> Unit) {
        val next = synchronized(resolveQueue) {
            if (resolving || resolveQueue.isEmpty()) return
            resolving = true
            resolveQueue.removeFirst()
        }
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                synchronized(resolveQueue) { resolving = false }
                resolveNext(onFound)
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    info.hostAddresses.firstOrNull { it is java.net.Inet4Address }?.hostAddress
                        ?: info.hostAddresses.firstOrNull()?.hostAddress
                } else {
                    info.host?.hostAddress
                }
                if (host != null) onFound(MirrorTarget(info.serviceName, host, info.port))
                synchronized(resolveQueue) { resolving = false }
                resolveNext(onFound)
            }
        })
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (e: Exception) {
                // Zaten durmuş
            }
        }
        discoveryListener = null
        synchronized(resolveQueue) {
            resolveQueue.clear()
            resolving = false
        }
    }

    companion object {
        private const val TAG = "MirrorDiscovery"
    }
}
