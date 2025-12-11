package com.iki.location.provider

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.iki.location.callback.LocationResultCallback
import com.iki.location.model.LocationData
import com.iki.location.model.LocationError
import com.iki.location.model.LocationProvider
import com.iki.location.model.LocationRequest as SimpleLocationRequest
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * GMS (Google Mobile Services) 定位提供者
 * 
 * 功能:
 * - 使用 FusedLocationProviderClient 进行定位
 * - 检测 GMS 是否可用
 * - 检测 Google 位置精确度开关状态
 * - 支持单次定位和连续定位
 */
class GmsLocationProvider(private val context: Context) : BaseLocationProvider() {
    
    companion object {
        private const val TAG = "mylocation"
    }
    
    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }
    
    private val settingsClient: SettingsClient by lazy {
        LocationServices.getSettingsClient(context)
    }
    
    private var locationCallback: LocationCallback? = null
    private var cancellationTokenSource: CancellationTokenSource? = null
    
    /**
     * 检查 GMS 是否可用
     */
    fun isGmsAvailable(): Boolean {
        return try {
            val apiAvailability = GoogleApiAvailability.getInstance()
            val resultCode = apiAvailability.isGooglePlayServicesAvailable(context)
            resultCode == ConnectionResult.SUCCESS
        } catch (e: Exception) {
            Log.e(TAG, "Error checking GMS availability", e)
            false
        }
    }
    
    /**
     * 获取 GMS 不可用的错误码
     */
    fun getGmsAvailabilityErrorCode(): Int {
        return try {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        } catch (e: Exception) {
            ConnectionResult.SERVICE_MISSING
        }
    }
    
    /**
     * 检查 Google 位置精确度开关是否开启
     * 
     * 这个开关在设置中叫做：
     * - Google 位置精确度 / Google Location Accuracy
     * - 使用 Google 位置信息服务提高位置精确度
     * 
     * 通过检查 LocationSettingsResponse 来判断
     */
    suspend fun isGoogleLocationAccuracyEnabled(): Boolean = withContext(Dispatchers.Main) {
        if (!isGmsAvailable()) {
            return@withContext false
        }
        
        try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                1000L
            ).build()
            
            val settingsRequest = LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest)
                .build()
            
            suspendCancellableCoroutine { continuation ->
                settingsClient.checkLocationSettings(settingsRequest)
                    .addOnSuccessListener { response ->
                        // 检查位置设置状态
                        val locationSettingsStates = response.locationSettingsStates
                        val isGpsUsable = locationSettingsStates?.isGpsUsable == true
                        val isNetworkLocationUsable = locationSettingsStates?.isNetworkLocationUsable == true
                        val isLocationUsable = locationSettingsStates?.isLocationUsable == true
                        
                        // Google Location Accuracy 启用时，网络定位应该可用
                        // 这是判断精确定位开关状态的关键指标
                        val isAccuracyEnabled = isLocationUsable && isNetworkLocationUsable
                        
                        Log.d(TAG, "Location accuracy check - GPS: $isGpsUsable, " +
                                "Network: $isNetworkLocationUsable, Location: $isLocationUsable")
                        
                        if (continuation.isActive) {
                            continuation.resume(isAccuracyEnabled)
                        }
                    }
                    .addOnFailureListener { exception ->
                        Log.w(TAG, "Failed to check location accuracy", exception)
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Google Location Accuracy", e)
            false
        }
    }
    
    /**
     * 检查位置设置是否满足要求
     * 
     * @return LocationSettingsResponse 如果设置满足要求
     * @throws ResolvableApiException 如果设置不满足但可以通过用户操作解决
     */
    suspend fun checkLocationSettings(
        request: SimpleLocationRequest
    ): LocationSettingsCheckResult = withContext(Dispatchers.Main) {
        try {
            val gmsRequest = createGmsLocationRequest(request)
            val settingsRequest = LocationSettingsRequest.Builder()
                .addLocationRequest(gmsRequest)
                .setAlwaysShow(true) // 确保始终显示对话框
                .build()
            
            suspendCancellableCoroutine<LocationSettingsCheckResult> { continuation ->
                settingsClient.checkLocationSettings(settingsRequest)
                    .addOnSuccessListener { response ->
                        if (continuation.isActive) {
                            continuation.resume(LocationSettingsCheckResult.Satisfied(response))
                        }
                    }
                    .addOnFailureListener { exception ->
                        if (continuation.isActive) {
                            val result: LocationSettingsCheckResult = when (exception) {
                                is ResolvableApiException -> {
                                    LocationSettingsCheckResult.Resolvable(exception)
                                }
                                else -> {
                                    LocationSettingsCheckResult.Failed(exception)
                                }
                            }
                            continuation.resume(result)
                        }
                    }
            }
        } catch (e: Exception) {
            LocationSettingsCheckResult.Failed(e)
        }
    }
    
    /**
     * 获取单次定位
     */
    @SuppressLint("MissingPermission")
    override suspend fun getLocation(
        request: SimpleLocationRequest
    ): Result<LocationData> = withContext(Dispatchers.Main) {
        Log.d(TAG, "[GMS-Provider] getLocation() 开始")
        
        if (!isGmsAvailable()) {
            Log.e(TAG, "[GMS-Provider] GMS不可用，返回失败")
            return@withContext Result.failure(
                Exception(LocationError.GmsUnavailable().message)
            )
        }
        
        // 检查 Google Location Accuracy
        Log.d(TAG, "[GMS-Provider] 检查 Google Location Accuracy...")
        val isAccuracyEnabled = isGoogleLocationAccuracyEnabled()
        Log.d(TAG, "[GMS-Provider] Google Location Accuracy: $isAccuracyEnabled")
        if (!isAccuracyEnabled && request.priority == SimpleLocationRequest.Priority.HIGH_ACCURACY) {
            Log.w(TAG, "[GMS-Provider] ⚠️ 高精度模式但精确定位开关未开启")
        }
        
        try {
            cancellationTokenSource?.cancel()
            cancellationTokenSource = CancellationTokenSource()
            
            val priority = when (request.priority) {
                SimpleLocationRequest.Priority.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
                SimpleLocationRequest.Priority.BALANCED_POWER_ACCURACY -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
                SimpleLocationRequest.Priority.LOW_POWER -> Priority.PRIORITY_LOW_POWER
                SimpleLocationRequest.Priority.PASSIVE -> Priority.PRIORITY_PASSIVE
            }
            
            Log.d(TAG, "[GMS-Provider] 调用 fusedLocationClient.getCurrentLocation(priority=$priority)...")
            val startTime = System.currentTimeMillis()
            
            val location = suspendCancellableCoroutine<Location?> { continuation ->
                fusedLocationClient.getCurrentLocation(
                    priority,
                    cancellationTokenSource!!.token
                ).addOnSuccessListener { location ->
                    val costTime = System.currentTimeMillis() - startTime
                    Log.d(TAG, "[GMS-Provider] getCurrentLocation onSuccess, 耗时: ${costTime}ms, location=$location")
                    if (location != null) {
                        Log.d(TAG, "[GMS-Provider] 位置详情: lat=${location.latitude}, lng=${location.longitude}, " +
                                "accuracy=${location.accuracy}m, provider=${location.provider}, time=${location.time}")
                    } else {
                        Log.w(TAG, "[GMS-Provider] getCurrentLocation返回null")
                    }
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                }.addOnFailureListener { exception ->
                    val costTime = System.currentTimeMillis() - startTime
                    Log.e(TAG, "[GMS-Provider] getCurrentLocation onFailure, 耗时: ${costTime}ms", exception)
                    Log.e(TAG, "[GMS-Provider] 异常类型: ${exception.javaClass.simpleName}, 消息: ${exception.message}")
                    if (continuation.isActive) {
                        continuation.resumeWithException(exception)
                    }
                }
                
                continuation.invokeOnCancellation {
                    Log.d(TAG, "[GMS-Provider] getCurrentLocation 被取消")
                    cancellationTokenSource?.cancel()
                }
            }
            
            if (location != null) {
                Log.d(TAG, "[GMS-Provider] ✅ 定位成功，返回结果")
                Result.success(LocationData.fromLocation(location, LocationProvider.GMS))
            } else {
                // 尝试获取最后已知位置
                Log.d(TAG, "[GMS-Provider] location为null，尝试获取最后已知位置...")
                val lastLocation = getLastKnownLocation()
                if (lastLocation != null) {
                    Log.d(TAG, "[GMS-Provider] 使用最后已知位置: lat=${lastLocation.latitude}, lng=${lastLocation.longitude}")
                    Result.success(lastLocation)
                } else {
                    Log.e(TAG, "[GMS-Provider] ❌ 无法获取位置，也没有最后已知位置")
                    Result.failure(Exception(LocationError.LocationFailed().message))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[GMS-Provider] ❌ getLocation异常", e)
            Log.e(TAG, "[GMS-Provider] 异常类型: ${e.javaClass.simpleName}, 消息: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * 获取最后已知位置
     */
    @SuppressLint("MissingPermission")
    suspend fun getLastKnownLocation(): LocationData? = withContext(Dispatchers.Main) {
        try {
            suspendCancellableCoroutine<LocationData?> { continuation ->
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { location ->
                        if (continuation.isActive) {
                            val locationData = location?.let {
                                LocationData.fromLocation(it, LocationProvider.GMS)
                            }
                            continuation.resume(locationData)
                        }
                    }
                    .addOnFailureListener { exception ->
                        Log.e(TAG, "Failed to get last known location", exception)
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
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
        request: SimpleLocationRequest,
        callback: LocationResultCallback
    ) {
        if (!isGmsAvailable()) {
            callback.onLocationError(LocationError.GmsUnavailable())
            return
        }
        
        stopLocationUpdates()
        
        val gmsRequest = createGmsLocationRequest(request)
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val locationData = LocationData.fromLocation(location, LocationProvider.GMS)
                    callback.onLocationSuccess(locationData)
                }
            }
            
            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    Log.w(TAG, "Location is not available")
                }
            }
        }
        
        try {
            fusedLocationClient.requestLocationUpdates(
                gmsRequest,
                locationCallback!!,
                Looper.getMainLooper()
            ).addOnFailureListener { exception ->
                Log.e(TAG, "Failed to start location updates", exception)
                callback.onLocationError(
                    LocationError.LocationFailed("Failed to start location updates: ${exception.message}")
                )
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
        locationCallback?.let { callback ->
            try {
                fusedLocationClient.removeLocationUpdates(callback)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping location updates", e)
            }
        }
        locationCallback = null
        
        cancellationTokenSource?.cancel()
        cancellationTokenSource = null
    }
    
    /**
     * 创建 GMS LocationRequest
     */
    private fun createGmsLocationRequest(request: SimpleLocationRequest): LocationRequest {
        val priority = when (request.priority) {
            SimpleLocationRequest.Priority.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
            SimpleLocationRequest.Priority.BALANCED_POWER_ACCURACY -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            SimpleLocationRequest.Priority.LOW_POWER -> Priority.PRIORITY_LOW_POWER
            SimpleLocationRequest.Priority.PASSIVE -> Priority.PRIORITY_PASSIVE
        }
        
        return LocationRequest.Builder(priority, request.intervalMillis)
            .setMinUpdateIntervalMillis(request.fastestIntervalMillis)
            .setMinUpdateDistanceMeters(request.minDistanceMeters)
            .setDurationMillis(request.timeoutMillis)
            .build()
    }
    
    /**
     * 位置设置检查结果
     */
    sealed class LocationSettingsCheckResult {
        data class Satisfied(val response: LocationSettingsResponse) : LocationSettingsCheckResult()
        data class Resolvable(val exception: ResolvableApiException) : LocationSettingsCheckResult()
        data class Failed(val exception: Exception) : LocationSettingsCheckResult()
    }
}

