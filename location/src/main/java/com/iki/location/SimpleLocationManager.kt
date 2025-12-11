package com.iki.location

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.common.api.ResolvableApiException
import com.iki.location.callback.*
import com.iki.location.model.LocationData
import com.iki.location.model.LocationError
import com.iki.location.model.LocationRequest
import com.iki.location.permission.LocationPermissionManager
import com.iki.location.provider.GmsLocationProvider
import com.iki.location.provider.GpsLocationProvider
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

/**
 * 简单定位管理器
 * 
 * 核心功能:
 * 1. 权限管理 - 支持 Android 各版本的权限申请
 * 2. GMS 优先定位 - 优先使用 Google Play Services 定位
 * 3. GPS 备选定位 - GMS 失败时自动切换到 GPS/WiFi 定位
 * 4. GMS 精确定位开关检测 - 检测并处理 Google Location Accuracy 开关状态
 * 
 * 使用示例:
 * ```kotlin
 * val locationManager = SimpleLocationManager.getInstance(context)
 * 
 * // 检查并请求权限
 * locationManager.requestLocationPermission(activity, object : PermissionCallback {
 *     override fun onPermissionGranted(permissions: List<String>) {
 *         // 开始定位
 *         locationManager.getLocation(callback = object : SingleLocationCallback {
 *             override fun onLocationSuccess(location: LocationData) {
 *                 // 处理定位结果
 *             }
 *             override fun onLocationError(error: LocationError) {
 *                 // 处理错误
 *             }
 *         })
 *     }
 *     override fun onPermissionDenied(deniedPermissions: List<String>, permanentlyDenied: Boolean) {
 *         // 处理权限拒绝
 *     }
 * })
 * ```
 */
class SimpleLocationManager private constructor(
    private val context: Context
) : CoroutineScope {
    
    companion object {
        private const val TAG = "mylocation"
        const val REQUEST_CHECK_SETTINGS = 10010
        
        @Volatile
        private var instance: SimpleLocationManager? = null
        
        /**
         * 获取 SimpleLocationManager 单例
         */
        @JvmStatic
        fun getInstance(context: Context): SimpleLocationManager {
            return instance ?: synchronized(this) {
                instance ?: SimpleLocationManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job
    
    private val gmsProvider = GmsLocationProvider(context)
    private val gpsProvider = GpsLocationProvider(context)
    private val permissionManager = LocationPermissionManager.getInstance(context)
    
    // ==================== 权限管理 ====================
    
    /**
     * 检查是否有定位权限
     */
    fun hasLocationPermission(): Boolean {
        return permissionManager.hasLocationPermission()
    }
    
    /**
     * 检查是否有精确定位权限
     */
    fun hasFineLocationPermission(): Boolean {
        return permissionManager.hasFineLocationPermission()
    }
    
    /**
     * 获取权限状态
     */
    fun getPermissionStatus(): LocationPermissionManager.PermissionStatus {
        return permissionManager.getPermissionStatus()
    }
    
    /**
     * 请求定位权限
     * 
     * @param activity Activity 实例
     * @param callback 权限回调
     */
    fun requestLocationPermission(activity: Activity, callback: PermissionCallback) {
        permissionManager.requestLocationPermission(activity, callback)
    }
    
    /**
     * 处理权限请求结果
     * 
     * 在 Activity 的 onRequestPermissionsResult 中调用
     */
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        permissionManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    
    /**
     * 打开应用设置页面
     */
    fun openAppSettings() {
        permissionManager.openAppSettings(context)
    }
    
    /**
     * 打开定位设置页面
     */
    fun openLocationSettings() {
        permissionManager.openLocationSettings(context)
    }
    
    // ==================== GMS 状态检测 ====================
    
    /**
     * 检查 GMS 是否可用
     */
    fun isGmsAvailable(): Boolean {
        return gmsProvider.isGmsAvailable()
    }
    
    /**
     * 检查 Google Location Accuracy (精确定位开关) 是否开启
     */
    suspend fun isGoogleLocationAccuracyEnabled(): Boolean {
        return gmsProvider.isGoogleLocationAccuracyEnabled()
    }
    
    /**
     * 检查 GPS 是否开启
     */
    fun isGpsEnabled(): Boolean {
        return gpsProvider.isGpsEnabled()
    }
    
    /**
     * 检查是否有任何定位服务可用
     */
    fun isLocationServiceEnabled(): Boolean {
        return gpsProvider.isAnyProviderEnabled()
    }
    
    /**
     * 检查位置设置是否满足定位要求
     */
    suspend fun checkLocationSettings(
        request: LocationRequest = LocationRequest()
    ): LocationSettingsResult {
        if (!hasLocationPermission()) {
            return LocationSettingsResult.PermissionRequired
        }
        
        if (!isGmsAvailable()) {
            return if (isGpsEnabled()) {
                LocationSettingsResult.Satisfied
            } else {
                LocationSettingsResult.LocationDisabled
            }
        }
        
        return when (val result = gmsProvider.checkLocationSettings(request)) {
            is GmsLocationProvider.LocationSettingsCheckResult.Satisfied -> {
                LocationSettingsResult.Satisfied
            }
            is GmsLocationProvider.LocationSettingsCheckResult.Resolvable -> {
                LocationSettingsResult.Resolvable(result.exception)
            }
            is GmsLocationProvider.LocationSettingsCheckResult.Failed -> {
                if (isGpsEnabled()) {
                    LocationSettingsResult.Satisfied
                } else {
                    LocationSettingsResult.LocationDisabled
                }
            }
        }
    }
    
    // ==================== 单次定位 ====================
    
    /**
     * 获取单次定位
     * 
     * 策略:
     * 1. 优先使用 GMS 定位
     * 2. GMS 不可用或失败时，自动切换到 GPS/WiFi 定位
     * 
     * @param request 定位请求配置
     * @param callback 定位结果回调
     */
    fun getLocation(
        request: LocationRequest = LocationRequest(),
        callback: SingleLocationCallback
    ) {
        if (!hasLocationPermission()) {
            callback.onLocationError(LocationError.PermissionDenied())
            return
        }
        
        launch {
            val location = getLocationInternal(request)
            location.fold(
                onSuccess = { callback.onLocationSuccess(it) },
                onFailure = { exception ->
                    val error = when {
                        exception.message?.contains("permission", ignoreCase = true) == true ->
                            LocationError.PermissionDenied(exception.message ?: "Permission denied")
                        exception.message?.contains("disabled", ignoreCase = true) == true ->
                            LocationError.LocationDisabled(exception.message ?: "Location disabled")
                        exception.message?.contains("timeout", ignoreCase = true) == true ->
                            LocationError.Timeout(exception.message ?: "Timeout")
                        else -> LocationError.LocationFailed(exception.message ?: "Location failed")
                    }
                    callback.onLocationError(error)
                }
            )
        }
    }
    
    /**
     * 获取单次定位 (协程版本)
     */
    suspend fun getLocation(
        request: LocationRequest = LocationRequest()
    ): Result<LocationData> {
        if (!hasLocationPermission()) {
            return Result.failure(Exception(LocationError.PermissionDenied().message))
        }
        return getLocationInternal(request)
    }
    
    /**
     * 内部定位实现
     */
    private suspend fun getLocationInternal(
        request: LocationRequest
    ): Result<LocationData> {
        Log.d(TAG, "========== 开始定位 ==========")
        Log.d(TAG, "定位配置: priority=${request.priority}, timeout=${request.timeoutMillis}ms")
        
        val startTime = System.currentTimeMillis()
        
        // 首先尝试 GMS 定位
        if (isGmsAvailable()) {
            Log.d(TAG, "[GMS] GMS可用，开始GMS定位...")
            
            val gmsStartTime = System.currentTimeMillis()
            val gmsResult = gmsProvider.getLocation(request)
            val gmsCostTime = System.currentTimeMillis() - gmsStartTime
            
            if (gmsResult.isSuccess) {
                val location = gmsResult.getOrNull()
                Log.d(TAG, "[GMS] ✅ GMS定位成功! 耗时: ${gmsCostTime}ms")
                Log.d(TAG, "[GMS] 位置: lat=${location?.latitude}, lng=${location?.longitude}, accuracy=${location?.accuracy}m")
                return gmsResult
            }
            
            Log.w(TAG, "[GMS] ❌ GMS定位失败! 耗时: ${gmsCostTime}ms, 错误: ${gmsResult.exceptionOrNull()?.message}")
        } else {
            Log.d(TAG, "[GMS] GMS不可用 (errorCode=${gmsProvider.getGmsAvailabilityErrorCode()})，直接使用GPS/WiFi")
        }
        
        // GMS 失败或不可用，尝试 GPS/WiFi 定位
        Log.d(TAG, "[GPS] 开始GPS/WiFi定位...")
        Log.d(TAG, "[GPS] GPS开启: ${gpsProvider.isGpsEnabled()}, 网络定位开启: ${gpsProvider.isNetworkLocationEnabled()}")
        
        val gpsStartTime = System.currentTimeMillis()
        val gpsResult = gpsProvider.getLocation(request)
        val gpsCostTime = System.currentTimeMillis() - gpsStartTime
        
        if (gpsResult.isSuccess) {
            val location = gpsResult.getOrNull()
            Log.d(TAG, "[GPS] ✅ GPS/WiFi定位成功! 耗时: ${gpsCostTime}ms")
            Log.d(TAG, "[GPS] 位置: lat=${location?.latitude}, lng=${location?.longitude}, accuracy=${location?.accuracy}m")
        } else {
            Log.e(TAG, "[GPS] ❌ GPS/WiFi定位也失败! 耗时: ${gpsCostTime}ms, 错误: ${gpsResult.exceptionOrNull()?.message}")
        }
        
        val totalTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "========== 定位结束 (总耗时: ${totalTime}ms) ==========")
        
        return gpsResult
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 获取最后已知位置
     */
    suspend fun getLastKnownLocation(): LocationData? {
        if (isGmsAvailable()) {
            val gmsLocation = gmsProvider.getLastKnownLocation()
            if (gmsLocation != null) {
                return gmsLocation
            }
        }
        return gpsProvider.getLastKnownLocation()
    }
    
    /**
     * 取消定位请求
     */
    fun cancel() {
        gmsProvider.cancel()
        gpsProvider.cancel()
    }
    
    /**
     * 销毁资源
     */
    fun destroy() {
        cancel()
        job.cancel()
        instance = null
    }
    
    /**
     * 位置设置检查结果
     */
    sealed class LocationSettingsResult {
        object Satisfied : LocationSettingsResult()
        object PermissionRequired : LocationSettingsResult()
        object LocationDisabled : LocationSettingsResult()
        
        data class Resolvable(val exception: ResolvableApiException) : LocationSettingsResult() {
            fun startResolutionForResult(activity: Activity, requestCode: Int = REQUEST_CHECK_SETTINGS) {
                try {
                    exception.startResolutionForResult(activity, requestCode)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start resolution", e)
                }
            }
        }
    }
}
