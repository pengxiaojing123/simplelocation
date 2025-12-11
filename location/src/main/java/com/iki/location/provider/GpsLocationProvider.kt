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
        private const val TAG = "GpsLocationProvider"
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
            Log.e(TAG, "Error checking GPS status", e)
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
            Log.e(TAG, "Error checking network location status", e)
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
     * 获取可用的最佳定位提供者
     */
    private fun getBestProvider(request: LocationRequest): String? {
        val criteria = android.location.Criteria().apply {
            when (request.priority) {
                LocationRequest.Priority.HIGH_ACCURACY -> {
                    accuracy = android.location.Criteria.ACCURACY_FINE
                    powerRequirement = android.location.Criteria.POWER_HIGH
                }
                LocationRequest.Priority.BALANCED_POWER_ACCURACY -> {
                    accuracy = android.location.Criteria.ACCURACY_MEDIUM
                    powerRequirement = android.location.Criteria.POWER_MEDIUM
                }
                LocationRequest.Priority.LOW_POWER -> {
                    accuracy = android.location.Criteria.ACCURACY_COARSE
                    powerRequirement = android.location.Criteria.POWER_LOW
                }
                LocationRequest.Priority.PASSIVE -> {
                    accuracy = android.location.Criteria.NO_REQUIREMENT
                    powerRequirement = android.location.Criteria.NO_REQUIREMENT
                }
            }
        }
        
        return locationManager.getBestProvider(criteria, true)
    }
    
    /**
     * 获取单次定位
     */
    @SuppressLint("MissingPermission")
    override suspend fun getLocation(
        request: LocationRequest
    ): Result<LocationData> = withContext(Dispatchers.Main) {
        if (!isAnyProviderEnabled()) {
            return@withContext Result.failure(
                Exception(LocationError.LocationDisabled().message)
            )
        }
        
        try {
            // 优先使用 GPS，其次使用网络定位
            val provider = when {
                isGpsEnabled() && request.priority == LocationRequest.Priority.HIGH_ACCURACY -> 
                    LocationManager.GPS_PROVIDER
                isGpsEnabled() -> LocationManager.GPS_PROVIDER
                isNetworkLocationEnabled() -> LocationManager.NETWORK_PROVIDER
                else -> getBestProvider(request) ?: return@withContext Result.failure(
                    Exception(LocationError.LocationDisabled().message)
                )
            }
            
            Log.d(TAG, "Using provider: $provider")
            
            suspendCancellableCoroutine<Result<LocationData>> { continuation ->
                var hasResumed = false
                
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
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
                        // Deprecated in API 29, but needed for older versions
                    }
                    
                    override fun onProviderEnabled(provider: String) {
                        Log.d(TAG, "Provider enabled: $provider")
                    }
                    
                    override fun onProviderDisabled(provider: String) {
                        Log.d(TAG, "Provider disabled: $provider")
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
                timeoutJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(request.timeoutMillis)
                    if (!hasResumed) {
                        hasResumed = true
                        removeListener(listener)
                        
                        // 超时时尝试获取最后已知位置
                        val lastLocation = getLastKnownLocation(provider)
                        if (lastLocation != null) {
                            continuation.resume(Result.success(lastLocation))
                        } else {
                            continuation.resume(
                                Result.failure(Exception(LocationError.Timeout().message))
                            )
                        }
                    }
                }
                
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        locationManager.getCurrentLocation(
                            provider,
                            null,
                            context.mainExecutor
                        ) { location ->
                            if (!hasResumed && location != null) {
                                hasResumed = true
                                timeoutJob?.cancel()
                                
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
                    } else {
                        // 对于旧版本，使用 requestSingleUpdate
                        @Suppress("DEPRECATION")
                        locationManager.requestSingleUpdate(
                            provider,
                            listener,
                            Looper.getMainLooper()
                        )
                    }
                } catch (e: Exception) {
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get GPS location", e)
            Result.failure(e)
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
                    if (location != null) {
                        if (bestLocation == null || location.accuracy < bestLocation.accuracy) {
                            bestLocation = location
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting last known location from $p", e)
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
            Log.e(TAG, "Error getting last known location", e)
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
                Log.d(TAG, "Provider enabled: $provider")
            }
            
            override fun onProviderDisabled(provider: String) {
                Log.d(TAG, "Provider disabled: $provider")
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
            
            // 如果 GPS 不可用，同时请求网络定位
            if (provider == LocationManager.GPS_PROVIDER && isNetworkLocationEnabled()) {
                try {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        request.intervalMillis,
                        request.minDistanceMeters,
                        listener,
                        Looper.getMainLooper()
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add network provider", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting location updates", e)
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
                Log.e(TAG, "Error stopping location updates", e)
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
            Log.e(TAG, "Error removing location listener", e)
        }
    }
}

