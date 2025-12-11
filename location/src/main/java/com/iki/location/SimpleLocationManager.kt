package com.iki.location

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.common.api.ResolvableApiException
import com.iki.location.callback.*
import com.iki.location.model.LocationData
import com.iki.location.model.LocationError
import com.iki.location.model.LocationProvider
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
 * 3. GPS 备选定位 - GMS 失败时自动切换到 GPS 定位
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
    
    private var stateListener: LocationStateListener? = null
    private var currentProvider: String? = null
    private var isLocating = false
    
    // ==================== 权限管理 ====================
    
    /**
     * 检查是否有定位权限
     */
    fun hasLocationPermission(): Boolean {
        return permissionManager.hasForegroundLocationPermission()
    }
    
    /**
     * 检查是否有精确定位权限
     */
    fun hasFineLocationPermission(): Boolean {
        return permissionManager.hasFineLocationPermission()
    }
    
    /**
     * 检查是否有后台定位权限
     */
    fun hasBackgroundLocationPermission(): Boolean {
        return permissionManager.hasBackgroundLocationPermission()
    }
    
    /**
     * 获取权限状态
     */
    fun getPermissionStatus(): LocationPermissionManager.PermissionStatus {
        return permissionManager.getPermissionStatus()
    }
    
    /**
     * 请求前台定位权限
     * 
     * @param activity Activity 实例
     * @param callback 权限回调
     */
    fun requestLocationPermission(activity: Activity, callback: PermissionCallback) {
        permissionManager.requestForegroundLocationPermission(activity, callback)
    }
    
    /**
     * 请求后台定位权限
     * 
     * 注意: Android 11+ 需要先获取前台权限，再单独申请后台权限
     */
    fun requestBackgroundLocationPermission(activity: Activity, callback: PermissionCallback) {
        permissionManager.requestBackgroundLocationPermission(activity, callback)
    }
    
    /**
     * 处理权限请求结果
     * 
     * 在 Activity/Fragment 的 onRequestPermissionsResult 中调用
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
     * 
     * 这个开关在设置中通常叫做:
     * - Google 位置精确度 / Google Location Accuracy
     * - 使用 Google 位置信息服务提高位置精确度
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
     * 
     * @param request 定位请求配置
     * @return 位置设置检查结果
     */
    suspend fun checkLocationSettings(
        request: LocationRequest = LocationRequest()
    ): LocationSettingsResult {
        if (!hasLocationPermission()) {
            return LocationSettingsResult.PermissionRequired
        }
        
        if (!isGmsAvailable()) {
            // GMS 不可用，检查 GPS
            return if (isGpsEnabled()) {
                LocationSettingsResult.Satisfied
            } else {
                LocationSettingsResult.LocationDisabled
            }
        }
        
        // 检查 GMS 位置设置
        return when (val result = gmsProvider.checkLocationSettings(request)) {
            is GmsLocationProvider.LocationSettingsCheckResult.Satisfied -> {
                LocationSettingsResult.Satisfied
            }
            is GmsLocationProvider.LocationSettingsCheckResult.Resolvable -> {
                LocationSettingsResult.Resolvable(result.exception)
            }
            is GmsLocationProvider.LocationSettingsCheckResult.Failed -> {
                if (isGpsEnabled()) {
                    LocationSettingsResult.Satisfied // GPS 可用作为备选
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
     * 2. GMS 不可用或失败时，自动切换到 GPS 定位
     * 3. 如果 GMS 精确定位开关未开启，会记录警告但仍尝试定位
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
        Log.d(TAG, "定位配置: priority=${request.priority}, timeout=${request.timeoutMillis}ms, interval=${request.intervalMillis}ms")
        
        val startTime = System.currentTimeMillis()
        
        // 首先尝试 GMS 定位
        if (isGmsAvailable()) {
            Log.d(TAG, "[GMS] GMS可用，开始GMS定位...")
            
            // 检查 Google Location Accuracy 开关
            val isAccuracyEnabled = isGoogleLocationAccuracyEnabled()
            Log.d(TAG, "[GMS] Google Location Accuracy开关: ${if (isAccuracyEnabled) "已开启" else "未开启"}")
            if (!isAccuracyEnabled) {
                Log.w(TAG, "[GMS] ⚠️ Google Location Accuracy未开启，精度可能降低")
            }
            
            Log.d(TAG, "[GMS] 调用 gmsProvider.getLocation()...")
            val gmsStartTime = System.currentTimeMillis()
            val gmsResult = gmsProvider.getLocation(request)
            val gmsCostTime = System.currentTimeMillis() - gmsStartTime
            
            if (gmsResult.isSuccess) {
                val location = gmsResult.getOrNull()
                Log.d(TAG, "[GMS] ✅ GMS定位成功! 耗时: ${gmsCostTime}ms")
                Log.d(TAG, "[GMS] 位置: lat=${location?.latitude}, lng=${location?.longitude}, accuracy=${location?.accuracy}m")
                notifyProviderSwitch(currentProvider, "GMS", "GMS location success")
                currentProvider = "GMS"
                return gmsResult
            }
            
            Log.w(TAG, "[GMS] ❌ GMS定位失败! 耗时: ${gmsCostTime}ms, 错误: ${gmsResult.exceptionOrNull()?.message}")
            Log.w(TAG, "[GMS] 异常详情: ${gmsResult.exceptionOrNull()}")
        } else {
            Log.d(TAG, "[GMS] GMS不可用 (errorCode=${gmsProvider.getGmsAvailabilityErrorCode()})，直接使用GPS")
        }
        
        // GMS 失败或不可用，尝试 GPS 定位
        Log.d(TAG, "[GPS] 开始GPS定位 (GMS回退)...")
        Log.d(TAG, "[GPS] GPS开启: ${gpsProvider.isGpsEnabled()}, 网络定位开启: ${gpsProvider.isNetworkLocationEnabled()}")
        
        val gpsStartTime = System.currentTimeMillis()
        val gpsResult = gpsProvider.getLocation(request)
        val gpsCostTime = System.currentTimeMillis() - gpsStartTime
        
        if (gpsResult.isSuccess) {
            val location = gpsResult.getOrNull()
            Log.d(TAG, "[GPS] ✅ GPS定位成功! 耗时: ${gpsCostTime}ms")
            Log.d(TAG, "[GPS] 位置: lat=${location?.latitude}, lng=${location?.longitude}, accuracy=${location?.accuracy}m")
            notifyProviderSwitch(currentProvider, "GPS", "Fallback to GPS")
            currentProvider = "GPS"
        } else {
            Log.e(TAG, "[GPS] ❌ GPS定位也失败! 耗时: ${gpsCostTime}ms, 错误: ${gpsResult.exceptionOrNull()?.message}")
        }
        
        val totalTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "========== 定位结束 (总耗时: ${totalTime}ms) ==========")
        
        return gpsResult
    }
    
    // ==================== 连续定位 ====================
    
    /**
     * 开始连续定位
     * 
     * 策略与单次定位相同:
     * 1. 优先使用 GMS
     * 2. GMS 失败时自动切换到 GPS
     * 
     * @param request 定位请求配置
     * @param callback 定位结果回调
     */
    fun startLocationUpdates(
        request: LocationRequest = LocationRequest(),
        callback: ContinuousLocationCallback
    ) {
        if (!hasLocationPermission()) {
            callback.onLocationError(LocationError.PermissionDenied())
            return
        }
        
        if (isLocating) {
            stopLocationUpdates()
        }
        
        isLocating = true
        stateListener?.onLocationStarted()
        
        val wrappedCallback = object : LocationResultCallback {
            private var gmsFailedCount = 0
            private var useGps = !isGmsAvailable()
            
            override fun onLocationSuccess(location: LocationData) {
                gmsFailedCount = 0 // 重置失败计数
                callback.onLocationSuccess(location)
            }
            
            override fun onLocationError(error: LocationError) {
                if (!useGps && error !is LocationError.PermissionDenied) {
                    gmsFailedCount++
                    
                    // GMS 连续失败 3 次，切换到 GPS
                    if (gmsFailedCount >= 3) {
                        Log.w(TAG, "GMS failed $gmsFailedCount times, switching to GPS")
                        useGps = true
                        gmsProvider.stopLocationUpdates()
                        
                        notifyProviderSwitch("GMS", "GPS", "GMS failed multiple times")
                        callback.onProviderChanged("GMS", "GPS")
                        
                        // 启动 GPS 定位
                        gpsProvider.startLocationUpdates(request, this)
                        return
                    }
                }
                
                callback.onLocationError(error)
            }
        }
        
        // 优先使用 GMS
        if (isGmsAvailable()) {
            launch {
                // 检查并记录 Google Location Accuracy 状态
                val isAccuracyEnabled = isGoogleLocationAccuracyEnabled()
                if (!isAccuracyEnabled) {
                    Log.w(TAG, "Google Location Accuracy is disabled")
                }
            }
            
            currentProvider = "GMS"
            gmsProvider.startLocationUpdates(request, wrappedCallback)
        } else {
            currentProvider = "GPS"
            gpsProvider.startLocationUpdates(request, wrappedCallback)
        }
    }
    
    /**
     * 停止连续定位
     */
    fun stopLocationUpdates() {
        gmsProvider.stopLocationUpdates()
        gpsProvider.stopLocationUpdates()
        
        isLocating = false
        currentProvider = null
        stateListener?.onLocationStopped()
    }
    
    // ==================== 状态监听 ====================
    
    /**
     * 设置定位状态监听器
     */
    fun setLocationStateListener(listener: LocationStateListener?) {
        this.stateListener = listener
    }
    
    /**
     * 获取当前使用的定位提供者
     */
    fun getCurrentProvider(): String? {
        return currentProvider
    }
    
    /**
     * 是否正在定位
     */
    fun isLocating(): Boolean {
        return isLocating
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 获取最后已知位置
     */
    suspend fun getLastKnownLocation(): LocationData? {
        // 优先从 GMS 获取
        if (isGmsAvailable()) {
            val gmsLocation = gmsProvider.getLastKnownLocation()
            if (gmsLocation != null) {
                return gmsLocation
            }
        }
        
        // 从 GPS 获取
        return gpsProvider.getLastKnownLocation()
    }
    
    /**
     * 通知定位来源切换
     */
    private fun notifyProviderSwitch(from: String?, to: String, reason: String) {
        if (from != null && from != to) {
            stateListener?.onProviderSwitched(from, to, reason)
        }
    }
    
    /**
     * 销毁资源
     */
    fun destroy() {
        stopLocationUpdates()
        job.cancel()
        instance = null
    }
    
    /**
     * 位置设置检查结果
     */
    sealed class LocationSettingsResult {
        /** 设置满足要求 */
        object Satisfied : LocationSettingsResult()
        
        /** 需要权限 */
        object PermissionRequired : LocationSettingsResult()
        
        /** 定位服务未开启 */
        object LocationDisabled : LocationSettingsResult()
        
        /** 可以通过对话框解决 */
        data class Resolvable(val exception: ResolvableApiException) : LocationSettingsResult() {
            /**
             * 启动设置解决对话框
             */
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

