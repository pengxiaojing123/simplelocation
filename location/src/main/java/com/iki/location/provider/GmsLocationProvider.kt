package com.iki.location.provider

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
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
 * - 使用 FusedLocationProviderClient 进行单次定位
 * - 检测 GMS 是否可用
 * - 检测 Google 位置精确度开关状态
 */
class GmsLocationProvider(private val context: Context) {
    
    companion object {
        private const val TAG = "mylocation"
    }
    
    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }
    
    private val settingsClient: SettingsClient by lazy {
        LocationServices.getSettingsClient(context)
    }
    
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
                        val locationSettingsStates = response.locationSettingsStates
                        val isNetworkLocationUsable = locationSettingsStates?.isNetworkLocationUsable == true
                        val isLocationUsable = locationSettingsStates?.isLocationUsable == true
                        val isAccuracyEnabled = isLocationUsable && isNetworkLocationUsable
                        
                        Log.d(TAG, "Location accuracy check - Network: $isNetworkLocationUsable, Location: $isLocationUsable")
                        
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
     */
    suspend fun checkLocationSettings(
        request: SimpleLocationRequest
    ): LocationSettingsCheckResult = withContext(Dispatchers.Main) {
        try {
            val priority = when (request.priority) {
                SimpleLocationRequest.Priority.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
                SimpleLocationRequest.Priority.LOW_POWER -> Priority.PRIORITY_LOW_POWER
            }
            
            val gmsRequest = LocationRequest.Builder(priority, 1000L).build()
            val settingsRequest = LocationSettingsRequest.Builder()
                .addLocationRequest(gmsRequest)
                .setAlwaysShow(true)
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
    suspend fun getLocation(
        request: SimpleLocationRequest
    ): Result<LocationData> = withContext(Dispatchers.Main) {
        Log.d(TAG, "[GMS-Provider] getLocation() 开始")
        
        if (!isGmsAvailable()) {
            Log.e(TAG, "[GMS-Provider] GMS不可用，返回失败")
            return@withContext Result.failure(
                Exception(LocationError.GmsUnavailable().message)
            )
        }
        
        try {
            cancellationTokenSource?.cancel()
            cancellationTokenSource = CancellationTokenSource()
            
            val priority = when (request.priority) {
                SimpleLocationRequest.Priority.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
                SimpleLocationRequest.Priority.LOW_POWER -> Priority.PRIORITY_LOW_POWER
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
                        Log.d(TAG, "[GMS-Provider] 位置: lat=${location.latitude}, lng=${location.longitude}, accuracy=${location.accuracy}m")
                    } else {
                        Log.w(TAG, "[GMS-Provider] getCurrentLocation返回null")
                    }
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                }.addOnFailureListener { exception ->
                    val costTime = System.currentTimeMillis() - startTime
                    Log.e(TAG, "[GMS-Provider] getCurrentLocation onFailure, 耗时: ${costTime}ms", exception)
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
                Log.d(TAG, "[GMS-Provider] ✅ 定位成功")
                Result.success(LocationData.fromLocation(location, LocationProvider.GMS))
            } else {
                Log.e(TAG, "[GMS-Provider] ❌ 无法获取位置 (返回null)")
                Result.failure(Exception(LocationError.LocationFailed().message))
            }
        } catch (e: Exception) {
            Log.e(TAG, "[GMS-Provider] ❌ getLocation异常", e)
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
     * 取消定位请求
     */
    fun cancel() {
        cancellationTokenSource?.cancel()
        cancellationTokenSource = null
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
