package com.iki.location.provider

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.iki.location.callback.LocationResultCallback
import com.iki.location.model.LocationData
import com.iki.location.model.LocationError
import com.iki.location.model.LocationProvider
import com.iki.location.model.LocationRequest
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/**
 * GPS 定位提供者
 * 
 * 使用系统原生的 LocationManager 进行 GPS 定位
 * 作为 GMS 定位失败时的备选方案
 */
class GpsLocationProvider(private val context: Context) : BaseLocationProvider() {
    
    companion object {
        private const val TAG = "mylocation"
    }
    
    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentLocationListener: LocationListener? = null
    private var timeoutJob: Job? = null
    
    /**
     * 检查 GPS 是否启用
     */
    fun isGpsEnabled(): Boolean {
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            Log.e(TAG, "[GPS-Provider] Error checking GPS status", e)
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
            Log.e(TAG, "[GPS-Provider] Error checking network location status", e)
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
    override suspend fun getLocation(
        request: LocationRequest
    ): Result<LocationData> = withContext(Dispatchers.Main) {
        Log.d(TAG, "[GPS-Provider] getLocation() 开始")
        Log.d(TAG, "[GPS-Provider] GPS开启: ${isGpsEnabled()}, 网络定位开启(WiFi): ${isNetworkLocationEnabled()}")
        
        if (!isAnyProviderEnabled()) {
            Log.e(TAG, "[GPS-Provider] 没有可用的定位提供者")
            return@withContext Result.failure(
                Exception(LocationError.LocationDisabled().message)
            )
        }
        
        // HIGH_ACCURACY 模式：同时使用 GPS 和网络定位(WiFi)，取先返回的
        if (request.priority == LocationRequest.Priority.HIGH_ACCURACY && 
            isGpsEnabled() && isNetworkLocationEnabled()) {
            Log.d(TAG, "[GPS-Provider] HIGH_ACCURACY模式: 同时请求GPS和网络定位(WiFi)")
            return@withContext getLocationFromMultipleProviders(request)
        }
        
        // 其他模式：选择单个 provider
        val provider = when {
            isGpsEnabled() -> LocationManager.GPS_PROVIDER
            isNetworkLocationEnabled() -> LocationManager.NETWORK_PROVIDER
            else -> return@withContext Result.failure(
                Exception(LocationError.LocationDisabled().message)
            )
        }
        
        Log.d(TAG, "[GPS-Provider] 单Provider模式: $provider, timeout: ${request.timeoutMillis}ms")
        return@withContext getLocationFromSingleProvider(provider, request)
    }
    
    /**
     * 从多个 Provider 同时获取定位（HIGH_ACCURACY模式）
     * GPS + 网络定位(WiFi) 同时请求，取先返回的结果
     */
    @SuppressLint("MissingPermission")
    private suspend fun getLocationFromMultipleProviders(
        request: LocationRequest
    ): Result<LocationData> = withContext(Dispatchers.Main) {
        val startTime = System.currentTimeMillis()
        
        suspendCancellableCoroutine { continuation ->
            var hasResumed = false
            val listeners = mutableListOf<LocationListener>()
            
            fun onLocationReceived(location: Location, providerName: String) {
                if (!hasResumed) {
                    hasResumed = true
                    val costTime = System.currentTimeMillis() - startTime
                    Log.d(TAG, "[GPS-Provider] ✅ 收到位置! provider=$providerName, 耗时: ${costTime}ms")
                    Log.d(TAG, "[GPS-Provider] 位置: lat=${location.latitude}, lng=${location.longitude}, accuracy=${location.accuracy}m")
                    
                    timeoutJob?.cancel()
                    listeners.forEach { removeListener(it) }
                    
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
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                    Log.d(TAG, "[GPS-Provider] GPS onStatusChanged: status=$status")
                }
                override fun onProviderEnabled(provider: String) {
                    Log.d(TAG, "[GPS-Provider] GPS enabled")
                }
                override fun onProviderDisabled(provider: String) {
                    Log.w(TAG, "[GPS-Provider] GPS disabled")
                }
            }
            
            // Network(WiFi) Listener
            val networkListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    onLocationReceived(location, LocationManager.NETWORK_PROVIDER)
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                    Log.d(TAG, "[GPS-Provider] Network onStatusChanged: status=$status")
                }
                override fun onProviderEnabled(provider: String) {
                    Log.d(TAG, "[GPS-Provider] Network enabled")
                }
                override fun onProviderDisabled(provider: String) {
                    Log.w(TAG, "[GPS-Provider] Network disabled")
                }
            }
            
            listeners.add(gpsListener)
            listeners.add(networkListener)
            
            // 设置超时
            Log.d(TAG, "[GPS-Provider] 设置超时: ${request.timeoutMillis}ms")
            timeoutJob = CoroutineScope(Dispatchers.Main).launch {
                delay(request.timeoutMillis)
                if (!hasResumed) {
                    val costTime = System.currentTimeMillis() - startTime
                    Log.w(TAG, "[GPS-Provider] ⏰ 超时! 已等待: ${costTime}ms")
                    hasResumed = true
                    listeners.forEach { removeListener(it) }
                    
                    // 尝试获取最后已知位置
                    Log.d(TAG, "[GPS-Provider] 尝试获取最后已知位置...")
                    val lastLocation = getLastKnownLocation(null)
                    if (lastLocation != null) {
                        Log.d(TAG, "[GPS-Provider] 使用最后已知位置: provider=${lastLocation.provider}")
                        continuation.resume(Result.success(lastLocation))
                    } else {
                        Log.e(TAG, "[GPS-Provider] ❌ 超时且无最后已知位置")
                        continuation.resume(Result.failure(Exception(LocationError.Timeout().message)))
                    }
                }
            }
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Log.d(TAG, "[GPS-Provider] Android R+, 使用 getCurrentLocation API")
                    
                    // 同时请求 GPS
                    Log.d(TAG, "[GPS-Provider] 请求 GPS...")
                    locationManager.getCurrentLocation(
                        LocationManager.GPS_PROVIDER,
                        null,
                        context.mainExecutor
                    ) { location ->
                        if (location != null) {
                            onLocationReceived(location, LocationManager.GPS_PROVIDER)
                        } else {
                            Log.d(TAG, "[GPS-Provider] GPS getCurrentLocation 返回 null")
                        }
                    }
                    
                    // 同时请求网络定位(WiFi)
                    Log.d(TAG, "[GPS-Provider] 请求网络定位(WiFi)...")
                    locationManager.getCurrentLocation(
                        LocationManager.NETWORK_PROVIDER,
                        null,
                        context.mainExecutor
                    ) { location ->
                        if (location != null) {
                            onLocationReceived(location, LocationManager.NETWORK_PROVIDER)
                        } else {
                            Log.d(TAG, "[GPS-Provider] Network getCurrentLocation 返回 null")
                        }
                    }
                } else {
                    Log.d(TAG, "[GPS-Provider] Android < R, 使用 requestSingleUpdate API")
                    
                    @Suppress("DEPRECATION")
                    locationManager.requestSingleUpdate(
                        LocationManager.GPS_PROVIDER,
                        gpsListener,
                        Looper.getMainLooper()
                    )
                    
                    @Suppress("DEPRECATION")
                    locationManager.requestSingleUpdate(
                        LocationManager.NETWORK_PROVIDER,
                        networkListener,
                        Looper.getMainLooper()
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "[GPS-Provider] 请求定位异常", e)
                if (!hasResumed) {
                    hasResumed = true
                    timeoutJob?.cancel()
                    listeners.forEach { removeListener(it) }
                    continuation.resume(Result.failure(e))
                }
            }
            
            continuation.invokeOnCancellation {
                Log.d(TAG, "[GPS-Provider] 定位请求被取消")
                if (!hasResumed) {
                    hasResumed = true
                    timeoutJob?.cancel()
                    listeners.forEach { removeListener(it) }
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
        Log.d(TAG, "[GPS-Provider] 使用provider: $provider, timeout: ${request.timeoutMillis}ms")
        
        val startTime = System.currentTimeMillis()
        
        suspendCancellableCoroutine { continuation ->
            var hasResumed = false
            
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val costTime = System.currentTimeMillis() - startTime
                    Log.d(TAG, "[GPS-Provider] onLocationChanged! 耗时: ${costTime}ms")
                    Log.d(TAG, "[GPS-Provider] 位置: lat=${location.latitude}, lng=${location.longitude}, " +
                            "accuracy=${location.accuracy}m, provider=${location.provider}")
                    if (!hasResumed) {
                        hasResumed = true
                        timeoutJob?.cancel()
                        removeListener(this)
                        
                        val locationData = LocationData.fromLocation(
                            location,
                            if (provider == LocationManager.GPS_PROVIDER) 
                                LocationProvider.GPS 
                            else 
                                LocationProvider.NETWORK
                        )
                        continuation.resume(Result.success(locationData))
                    }
                }
                
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                    Log.d(TAG, "[GPS-Provider] onStatusChanged: provider=$provider, status=$status")
                }
                
                override fun onProviderEnabled(provider: String) {
                    Log.d(TAG, "[GPS-Provider] onProviderEnabled: $provider")
                }
                
                override fun onProviderDisabled(provider: String) {
                    Log.w(TAG, "[GPS-Provider] onProviderDisabled: $provider")
                    if (!hasResumed) {
                        hasResumed = true
                        timeoutJob?.cancel()
                        removeListener(this)
                        continuation.resume(
                            Result.failure(Exception(LocationError.LocationDisabled().message))
                        )
                    }
                }
            }
            
            // 设置超时
            Log.d(TAG, "[GPS-Provider] 设置超时: ${request.timeoutMillis}ms")
            timeoutJob = CoroutineScope(Dispatchers.Main).launch {
                delay(request.timeoutMillis)
                if (!hasResumed) {
                    val costTime = System.currentTimeMillis() - startTime
                    Log.w(TAG, "[GPS-Provider] ⏰ 超时! 已等待: ${costTime}ms")
                    hasResumed = true
                    removeListener(listener)
                    
                    // 超时时尝试获取最后已知位置
                    Log.d(TAG, "[GPS-Provider] 尝试获取最后已知位置...")
                    val lastLocation = getLastKnownLocation(provider)
                    if (lastLocation != null) {
                        Log.d(TAG, "[GPS-Provider] 使用最后已知位置: lat=${lastLocation.latitude}, lng=${lastLocation.longitude}")
                        continuation.resume(Result.success(lastLocation))
                    } else {
                        Log.e(TAG, "[GPS-Provider] ❌ 超时且无最后已知位置")
                        continuation.resume(
                            Result.failure(Exception(LocationError.Timeout().message))
                        )
                    }
                }
            }
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Log.d(TAG, "[GPS-Provider] Android R+, 使用 getCurrentLocation API")
                    locationManager.getCurrentLocation(
                        provider,
                        null,
                        context.mainExecutor
                    ) { location ->
                        val costTime = System.currentTimeMillis() - startTime
                        Log.d(TAG, "[GPS-Provider] getCurrentLocation回调, 耗时: ${costTime}ms, location=$location")
                        if (!hasResumed && location != null) {
                            hasResumed = true
                            timeoutJob?.cancel()
                            Log.d(TAG, "[GPS-Provider] 位置: lat=${location.latitude}, lng=${location.longitude}, accuracy=${location.accuracy}m")
                            
                            val locationData = LocationData.fromLocation(
                                location,
                                if (provider == LocationManager.GPS_PROVIDER)
                                    LocationProvider.GPS
                                else
                                    LocationProvider.NETWORK
                            )
                            continuation.resume(Result.success(locationData))
                        } else if (location == null) {
                            Log.w(TAG, "[GPS-Provider] getCurrentLocation返回null")
                        }
                    }
                } else {
                    // 对于旧版本，使用 requestSingleUpdate
                    Log.d(TAG, "[GPS-Provider] Android < R, 使用 requestSingleUpdate API")
                    @Suppress("DEPRECATION")
                    locationManager.requestSingleUpdate(
                        provider,
                        listener,
                        Looper.getMainLooper()
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "[GPS-Provider] 请求定位异常", e)
                if (!hasResumed) {
                    hasResumed = true
                    timeoutJob?.cancel()
                    continuation.resume(Result.failure(e))
                }
            }
            
            continuation.invokeOnCancellation {
                Log.d(TAG, "[GPS-Provider] 定位请求被取消")
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
    fun getLastKnownLocation(provider: String? = null): LocationData? {
        return try {
            val providers = if (provider != null) {
                listOf(provider)
            } else {
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            }
            
            var bestLocation: Location? = null
            
            for (p in providers) {
                try {
                    val location = locationManager.getLastKnownLocation(p)
                    Log.d(TAG, "[GPS-Provider] getLastKnownLocation($p): $location")
                    if (location != null) {
                        if (bestLocation == null || location.accuracy < bestLocation.accuracy) {
                            bestLocation = location
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[GPS-Provider] Error getting last known location from $p", e)
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
            Log.e(TAG, "[GPS-Provider] Error getting last known location", e)
            null
        }
    }
    
    /**
     * 开始连续定位
     */
    @SuppressLint("MissingPermission")
    override fun startLocationUpdates(
        request: LocationRequest,
        callback: LocationResultCallback
    ) {
        if (!isAnyProviderEnabled()) {
            callback.onLocationError(LocationError.LocationDisabled())
            return
        }
        
        stopLocationUpdates()
        
        val provider = when {
            isGpsEnabled() && request.priority == LocationRequest.Priority.HIGH_ACCURACY ->
                LocationManager.GPS_PROVIDER
            isGpsEnabled() -> LocationManager.GPS_PROVIDER
            isNetworkLocationEnabled() -> LocationManager.NETWORK_PROVIDER
            else -> {
                callback.onLocationError(LocationError.LocationDisabled())
                return
            }
        }
        
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val providerType = when (location.provider) {
                    LocationManager.GPS_PROVIDER -> LocationProvider.GPS
                    LocationManager.NETWORK_PROVIDER -> LocationProvider.NETWORK
                    else -> LocationProvider.UNKNOWN
                }
                val locationData = LocationData.fromLocation(location, providerType)
                callback.onLocationSuccess(locationData)
            }
            
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                // Deprecated but needed for older versions
            }
            
            override fun onProviderEnabled(provider: String) {
                Log.d(TAG, "[GPS-Provider] Provider enabled: $provider")
            }
            
            override fun onProviderDisabled(provider: String) {
                Log.d(TAG, "[GPS-Provider] Provider disabled: $provider")
                callback.onLocationError(LocationError.LocationDisabled())
            }
        }
        
        currentLocationListener = listener
        
        try {
            locationManager.requestLocationUpdates(
                provider,
                request.intervalMillis,
                request.minDistanceMeters,
                listener,
                Looper.getMainLooper()
            )
            
            // 如果是高精度模式，同时请求网络定位
            if (request.priority == LocationRequest.Priority.HIGH_ACCURACY && 
                provider == LocationManager.GPS_PROVIDER && isNetworkLocationEnabled()) {
                try {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        request.intervalMillis,
                        request.minDistanceMeters,
                        listener,
                        Looper.getMainLooper()
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "[GPS-Provider] Failed to add network provider", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[GPS-Provider] Error starting location updates", e)
            callback.onLocationError(LocationError.Unknown(e.message ?: "Unknown error", e))
        }
    }
    
    /**
     * 停止连续定位
     */
    override fun stopLocationUpdates() {
        timeoutJob?.cancel()
        timeoutJob = null
        
        currentLocationListener?.let { listener ->
            try {
                locationManager.removeUpdates(listener)
            } catch (e: Exception) {
                Log.e(TAG, "[GPS-Provider] Error stopping location updates", e)
            }
        }
        currentLocationListener = null
    }
    
    /**
     * 移除监听器
     */
    private fun removeListener(listener: LocationListener) {
        try {
            locationManager.removeUpdates(listener)
        } catch (e: Exception) {
            Log.e(TAG, "[GPS-Provider] Error removing location listener", e)
        }
    }
}
