package com.iki.location.provider

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import com.iki.location.model.LocationData
import com.iki.location.util.LocationLogger
import com.iki.location.model.LocationError
import com.iki.location.model.LocationProvider
import com.iki.location.model.LocationRequest
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/**
 * GPS 定位提供者
 * 
 * 使用系统原生的 LocationManager 进行单次定位
 * 作为 GMS 定位失败时的备选方案
 */
class GpsLocationProvider(private val context: Context) {
    
    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    
    private var timeoutJob: Job? = null
    private var currentListeners = mutableListOf<LocationListener>()
    
    /**
     * 检查 GPS 是否启用
     */
    fun isGpsEnabled(): Boolean {
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            LocationLogger.e( "[GPS-Provider] Error checking GPS status", e)
            false
        }
    }
    
    /**
     * 检查网络定位是否启用
     */
    fun isNetworkLocationEnabled(): Boolean {
        return try {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            LocationLogger.e( "[GPS-Provider] Error checking network location status", e)
            false
        }
    }
    
    /**
     * 检查是否有任何定位提供者可用
     */
    fun isAnyProviderEnabled(): Boolean {
        return isGpsEnabled() || isNetworkLocationEnabled()
    }
    
    /**
     * 获取单次定位
     */
    @SuppressLint("MissingPermission")
    suspend fun getLocation(
        request: LocationRequest
    ): Result<LocationData> = withContext(Dispatchers.Main) {
        LocationLogger.d( "[GPS-Provider] getLocation() 开始")
        LocationLogger.d( "[GPS-Provider] GPS开启: ${isGpsEnabled()}, 网络定位开启(WiFi): ${isNetworkLocationEnabled()}")
        
        if (!isAnyProviderEnabled()) {
            LocationLogger.e( "[GPS-Provider] 没有可用的定位提供者")
            return@withContext Result.failure(
                Exception(LocationError.LocationDisabled().message)
            )
        }
        
        // HIGH_ACCURACY 模式：同时使用 GPS 和网络定位(WiFi)，取先返回的
        if (request.priority == LocationRequest.Priority.HIGH_ACCURACY && 
            isGpsEnabled() && isNetworkLocationEnabled()) {
            LocationLogger.d( "[GPS-Provider] HIGH_ACCURACY模式: 同时请求GPS和网络定位(WiFi)")
            return@withContext getLocationFromMultipleProviders(request)
        }
        
        // 其他模式：选择单个 provider
        val provider = when {
            request.priority == LocationRequest.Priority.LOW_POWER && isNetworkLocationEnabled() -> 
                LocationManager.NETWORK_PROVIDER
            isGpsEnabled() -> LocationManager.GPS_PROVIDER
            isNetworkLocationEnabled() -> LocationManager.NETWORK_PROVIDER
            else -> return@withContext Result.failure(
                Exception(LocationError.LocationDisabled().message)
            )
        }
        
        LocationLogger.d( "[GPS-Provider] 单Provider模式: $provider")
        return@withContext getLocationFromSingleProvider(provider, request)
    }
    
    /**
     * 从多个 Provider 同时获取定位（HIGH_ACCURACY模式）
     */
    @SuppressLint("MissingPermission")
    private suspend fun getLocationFromMultipleProviders(
        request: LocationRequest
    ): Result<LocationData> = withContext(Dispatchers.Main) {
        val startTime = System.currentTimeMillis()
        
        suspendCancellableCoroutine { continuation ->
            var hasResumed = false
            
            fun onLocationReceived(location: Location, providerName: String) {
                if (!hasResumed) {
                    hasResumed = true
                    val costTime = System.currentTimeMillis() - startTime
                    LocationLogger.d( "[GPS-Provider] ✅ 收到位置! provider=$providerName, 耗时: ${costTime}ms")
                    LocationLogger.d( "[GPS-Provider] 位置: lat=${location.latitude}, lng=${location.longitude}, accuracy=${location.accuracy}m")
                    
                    timeoutJob?.cancel()
                    clearListeners()
                    
                    val providerType = if (providerName == LocationManager.GPS_PROVIDER) 
                        LocationProvider.GPS else LocationProvider.NETWORK
                    continuation.resume(Result.success(LocationData.fromLocation(location, providerType)))
                }
            }
            
            // GPS Listener
            val gpsListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    onLocationReceived(location, LocationManager.GPS_PROVIDER)
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            
            // Network(WiFi) Listener
            val networkListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    onLocationReceived(location, LocationManager.NETWORK_PROVIDER)
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            
            currentListeners.add(gpsListener)
            currentListeners.add(networkListener)
            
            // 设置超时
            LocationLogger.d( "[GPS-Provider] 设置超时: ${request.timeoutMillis}ms")
            timeoutJob = CoroutineScope(Dispatchers.Main).launch {
                delay(request.timeoutMillis)
                if (!hasResumed) {
                    val costTime = System.currentTimeMillis() - startTime
                    LocationLogger.w( "[GPS-Provider] ⏰ 超时! 已等待: ${costTime}ms")
                    hasResumed = true
                    clearListeners()
                    
                    LocationLogger.e( "[GPS-Provider] ❌ 超时")
                    continuation.resume(Result.failure(Exception(LocationError.Timeout().message)))
                }
            }
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    LocationLogger.d( "[GPS-Provider] Android R+, 使用 getCurrentLocation API")
                    
                    LocationLogger.d( "[GPS-Provider] 请求 GPS...")
                    locationManager.getCurrentLocation(
                        LocationManager.GPS_PROVIDER,
                        null,
                        context.mainExecutor
                    ) { location ->
                        if (location != null) {
                            onLocationReceived(location, LocationManager.GPS_PROVIDER)
                        } else {
                            LocationLogger.d( "[GPS-Provider] GPS getCurrentLocation 返回 null")
                        }
                    }
                    
                    LocationLogger.d( "[GPS-Provider] 请求网络定位(WiFi)...")
                    locationManager.getCurrentLocation(
                        LocationManager.NETWORK_PROVIDER,
                        null,
                        context.mainExecutor
                    ) { location ->
                        if (location != null) {
                            onLocationReceived(location, LocationManager.NETWORK_PROVIDER)
                        } else {
                            LocationLogger.d( "[GPS-Provider] Network getCurrentLocation 返回 null")
                        }
                    }
                } else {
                    LocationLogger.d( "[GPS-Provider] Android < R, 使用 requestSingleUpdate API")
                    
                    @Suppress("DEPRECATION")
                    locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, gpsListener, Looper.getMainLooper())
                    @Suppress("DEPRECATION")
                    locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, networkListener, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                LocationLogger.e( "[GPS-Provider] 请求定位异常", e)
                if (!hasResumed) {
                    hasResumed = true
                    timeoutJob?.cancel()
                    clearListeners()
                    continuation.resume(Result.failure(e))
                }
            }
            
            continuation.invokeOnCancellation {
                if (!hasResumed) {
                    hasResumed = true
                    timeoutJob?.cancel()
                    clearListeners()
                }
            }
        }
    }
    
    /**
     * 从单个 Provider 获取定位
     */
    @SuppressLint("MissingPermission")
    private suspend fun getLocationFromSingleProvider(
        provider: String,
        request: LocationRequest
    ): Result<LocationData> = withContext(Dispatchers.Main) {
        LocationLogger.d( "[GPS-Provider] 使用provider: $provider, timeout: ${request.timeoutMillis}ms")
        
        val startTime = System.currentTimeMillis()
        
        suspendCancellableCoroutine { continuation ->
            var hasResumed = false
            
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val costTime = System.currentTimeMillis() - startTime
                    LocationLogger.d( "[GPS-Provider] onLocationChanged! 耗时: ${costTime}ms")
                    LocationLogger.d( "[GPS-Provider] 位置: lat=${location.latitude}, lng=${location.longitude}, accuracy=${location.accuracy}m")
                    if (!hasResumed) {
                        hasResumed = true
                        timeoutJob?.cancel()
                        removeListener(this)
                        
                        val providerType = if (provider == LocationManager.GPS_PROVIDER) 
                            LocationProvider.GPS else LocationProvider.NETWORK
                        continuation.resume(Result.success(LocationData.fromLocation(location, providerType)))
                    }
                }
                
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    LocationLogger.w( "[GPS-Provider] Provider disabled: $provider")
                    if (!hasResumed) {
                        hasResumed = true
                        timeoutJob?.cancel()
                        removeListener(this)
                        continuation.resume(Result.failure(Exception(LocationError.LocationDisabled().message)))
                    }
                }
            }
            
            currentListeners.add(listener)
            
            // 设置超时
            timeoutJob = CoroutineScope(Dispatchers.Main).launch {
                delay(request.timeoutMillis)
                if (!hasResumed) {
                    LocationLogger.w( "[GPS-Provider] ⏰ 超时!")
                    hasResumed = true
                    removeListener(listener)
                    
                    continuation.resume(Result.failure(Exception(LocationError.Timeout().message)))
                }
            }
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    locationManager.getCurrentLocation(provider, null, context.mainExecutor) { location ->
                        if (!hasResumed && location != null) {
                            hasResumed = true
                            timeoutJob?.cancel()
                            val providerType = if (provider == LocationManager.GPS_PROVIDER)
                                LocationProvider.GPS else LocationProvider.NETWORK
                            continuation.resume(Result.success(LocationData.fromLocation(location, providerType)))
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                LocationLogger.e( "[GPS-Provider] 请求定位异常", e)
                if (!hasResumed) {
                    hasResumed = true
                    timeoutJob?.cancel()
                    continuation.resume(Result.failure(e))
                }
            }
            
            continuation.invokeOnCancellation {
                if (!hasResumed) {
                    hasResumed = true
                    timeoutJob?.cancel()
                    removeListener(listener)
                }
            }
        }
    }
    
    /**
     * 获取最后已知位置
     */
    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(): LocationData? {
        return try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            var bestLocation: Location? = null
            
            for (p in providers) {
                try {
                    val location = locationManager.getLastKnownLocation(p)
                    if (location != null) {
                        if (bestLocation == null || location.accuracy < bestLocation.accuracy) {
                            bestLocation = location
                        }
                    }
                } catch (e: Exception) {
                    LocationLogger.e( "[GPS-Provider] Error getting last known location from $p", e)
                }
            }
            
            bestLocation?.let { location ->
                val providerType = when (location.provider) {
                    LocationManager.GPS_PROVIDER -> LocationProvider.GPS
                    LocationManager.NETWORK_PROVIDER -> LocationProvider.NETWORK
                    else -> LocationProvider.UNKNOWN
                }
                LocationData.fromLocation(location, providerType)
            }
        } catch (e: Exception) {
            LocationLogger.e( "[GPS-Provider] Error getting last known location", e)
            null
        }
    }
    
    /**
     * 取消定位请求
     */
    fun cancel() {
        timeoutJob?.cancel()
        timeoutJob = null
        clearListeners()
    }
    
    private fun clearListeners() {
        currentListeners.forEach { removeListener(it) }
        currentListeners.clear()
    }
    
    private fun removeListener(listener: LocationListener) {
        try {
            locationManager.removeUpdates(listener)
        } catch (e: Exception) {
            LocationLogger.e( "[GPS-Provider] Error removing location listener", e)
        }
    }
}
