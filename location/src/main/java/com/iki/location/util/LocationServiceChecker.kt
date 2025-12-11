package com.iki.location.util

import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.location.LocationRequest as GmsLocationRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 定位服务状态检查工具类
 * 
 * 提供设备定位相关状态的全面检测能力
 */
object LocationServiceChecker {
    
    private const val TAG = "LocationServiceChecker"
    
    /**
     * 获取完整的定位服务状态
     */
    suspend fun getLocationServiceStatus(context: Context): LocationServiceStatus {
        return withContext(Dispatchers.Main) {
            val gmsStatus = getGmsStatus(context)
            val systemLocationStatus = getSystemLocationStatus(context)
            val googleAccuracyEnabled = checkGoogleLocationAccuracy(context)
            
            LocationServiceStatus(
                isGmsAvailable = gmsStatus.isAvailable,
                gmsVersion = gmsStatus.version,
                gmsErrorCode = gmsStatus.errorCode,
                isSystemLocationEnabled = systemLocationStatus.isEnabled,
                isGpsEnabled = systemLocationStatus.isGpsEnabled,
                isNetworkLocationEnabled = systemLocationStatus.isNetworkEnabled,
                isGoogleLocationAccuracyEnabled = googleAccuracyEnabled,
                locationMode = getLocationMode(context)
            )
        }
    }
    
    /**
     * 检查 GMS (Google Mobile Services) 状态
     */
    fun getGmsStatus(context: Context): GmsStatus {
        return try {
            val apiAvailability = GoogleApiAvailability.getInstance()
            val resultCode = apiAvailability.isGooglePlayServicesAvailable(context)
            
            GmsStatus(
                isAvailable = resultCode == ConnectionResult.SUCCESS,
                version = getGmsVersion(context),
                errorCode = resultCode,
                errorMessage = when (resultCode) {
                    ConnectionResult.SUCCESS -> null
                    ConnectionResult.SERVICE_MISSING -> "Google Play Services is missing"
                    ConnectionResult.SERVICE_UPDATING -> "Google Play Services is updating"
                    ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> "Google Play Services needs update"
                    ConnectionResult.SERVICE_DISABLED -> "Google Play Services is disabled"
                    ConnectionResult.SERVICE_INVALID -> "Google Play Services is invalid"
                    else -> "Unknown error: $resultCode"
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error checking GMS status", e)
            GmsStatus(
                isAvailable = false,
                version = -1,
                errorCode = ConnectionResult.SERVICE_MISSING,
                errorMessage = e.message
            )
        }
    }
    
    /**
     * 获取 GMS 版本号
     */
    private fun getGmsVersion(context: Context): Int {
        return try {
            val gmsPackage = "com.google.android.gms"
            val packageInfo = context.packageManager.getPackageInfo(gmsPackage, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            -1
        }
    }
    
    /**
     * 获取系统定位状态
     */
    fun getSystemLocationStatus(context: Context): SystemLocationStatus {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        
        return if (locationManager != null) {
            val isGpsEnabled = try {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            } catch (e: Exception) {
                false
            }
            
            val isNetworkEnabled = try {
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            } catch (e: Exception) {
                false
            }
            
            val isEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locationManager.isLocationEnabled
            } else {
                isGpsEnabled || isNetworkEnabled
            }
            
            SystemLocationStatus(
                isEnabled = isEnabled,
                isGpsEnabled = isGpsEnabled,
                isNetworkEnabled = isNetworkEnabled
            )
        } else {
            SystemLocationStatus(
                isEnabled = false,
                isGpsEnabled = false,
                isNetworkEnabled = false
            )
        }
    }
    
    /**
     * 检查 Google Location Accuracy (精确定位开关) 是否开启
     * 
     * 这个开关在设置中叫做:
     * - Google 位置精确度 / Google Location Accuracy
     * - "使用 Google 位置信息服务提高位置精确度"
     * 
     * 开启后，设备会使用 Wi-Fi、移动网络和传感器来辅助 GPS 提高定位精度
     */
    suspend fun checkGoogleLocationAccuracy(context: Context): Boolean {
        val gmsStatus = getGmsStatus(context)
        if (!gmsStatus.isAvailable) {
            return false
        }
        
        return try {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            val settingsClient = LocationServices.getSettingsClient(context)
            
            // 创建一个高精度定位请求来检查设置
            val locationRequest = GmsLocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                1000L
            ).build()
            
            val settingsRequest = LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest)
                .build()
            
            suspendCancellableCoroutine { continuation ->
                settingsClient.checkLocationSettings(settingsRequest)
                    .addOnSuccessListener { response ->
                        val states = response.locationSettingsStates
                        
                        // Google Location Accuracy 开启时:
                        // - isNetworkLocationUsable 应为 true (因为可以使用网络辅助定位)
                        // - isLocationUsable 应为 true
                        val isAccuracyEnabled = states?.isNetworkLocationUsable == true &&
                                states.isLocationUsable == true
                        
                        Log.d(TAG, "Google Location Accuracy check - " +
                                "GPS: ${states?.isGpsUsable}, " +
                                "Network: ${states?.isNetworkLocationUsable}, " +
                                "BLE: ${states?.isBleUsable}, " +
                                "Location: ${states?.isLocationUsable}")
                        
                        if (continuation.isActive) {
                            continuation.resume(isAccuracyEnabled)
                        }
                    }
                    .addOnFailureListener { exception ->
                        Log.w(TAG, "Failed to check Google Location Accuracy", exception)
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
     * 获取定位模式
     * 
     * @return 定位模式:
     *  - LOCATION_MODE_OFF (0): 定位关闭
     *  - LOCATION_MODE_SENSORS_ONLY (1): 仅 GPS
     *  - LOCATION_MODE_BATTERY_SAVING (2): 省电模式 (网络定位)
     *  - LOCATION_MODE_HIGH_ACCURACY (3): 高精度 (GPS + 网络)
     */
    @Suppress("DEPRECATION")
    fun getLocationMode(context: Context): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Android 9+ 使用 LocationManager.isLocationEnabled
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                if (locationManager?.isLocationEnabled == true) {
                    Settings.Secure.LOCATION_MODE_HIGH_ACCURACY // 简化返回
                } else {
                    Settings.Secure.LOCATION_MODE_OFF
                }
            } else {
                // Android 8 及以下使用 Settings.Secure
                Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_OFF
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting location mode", e)
            Settings.Secure.LOCATION_MODE_OFF
        }
    }
    
    /**
     * 获取定位模式的描述文本
     */
    @Suppress("DEPRECATION")
    fun getLocationModeDescription(mode: Int): String {
        return when (mode) {
            Settings.Secure.LOCATION_MODE_OFF -> "Off"
            Settings.Secure.LOCATION_MODE_SENSORS_ONLY -> "Device only (GPS)"
            Settings.Secure.LOCATION_MODE_BATTERY_SAVING -> "Battery saving (Network)"
            Settings.Secure.LOCATION_MODE_HIGH_ACCURACY -> "High accuracy (GPS + Network)"
            else -> "Unknown"
        }
    }
    
    /**
     * GMS 状态数据类
     */
    data class GmsStatus(
        val isAvailable: Boolean,
        val version: Int,
        val errorCode: Int,
        val errorMessage: String?
    )
    
    /**
     * 系统定位状态数据类
     */
    data class SystemLocationStatus(
        val isEnabled: Boolean,
        val isGpsEnabled: Boolean,
        val isNetworkEnabled: Boolean
    )
    
    /**
     * 完整的定位服务状态数据类
     */
    data class LocationServiceStatus(
        /** GMS 是否可用 */
        val isGmsAvailable: Boolean,
        /** GMS 版本号 */
        val gmsVersion: Int,
        /** GMS 错误码 (如果不可用) */
        val gmsErrorCode: Int,
        /** 系统定位是否开启 */
        val isSystemLocationEnabled: Boolean,
        /** GPS 是否开启 */
        val isGpsEnabled: Boolean,
        /** 网络定位是否开启 */
        val isNetworkLocationEnabled: Boolean,
        /** Google Location Accuracy (精确定位开关) 是否开启 */
        val isGoogleLocationAccuracyEnabled: Boolean,
        /** 定位模式 */
        val locationMode: Int
    ) {
        /**
         * 是否可以使用 GMS 高精度定位
         */
        val canUseGmsHighAccuracy: Boolean
            get() = isGmsAvailable && isGoogleLocationAccuracyEnabled
        
        /**
         * 是否可以进行任何定位
         */
        val canLocate: Boolean
            get() = isSystemLocationEnabled && (isGpsEnabled || isNetworkLocationEnabled)
        
        /**
         * 获取推荐的定位策略
         */
        fun getRecommendedStrategy(): LocationStrategy {
            return when {
                !isSystemLocationEnabled -> LocationStrategy.LOCATION_DISABLED
                canUseGmsHighAccuracy -> LocationStrategy.GMS_HIGH_ACCURACY
                isGmsAvailable -> LocationStrategy.GMS_BALANCED
                isGpsEnabled -> LocationStrategy.GPS_ONLY
                isNetworkLocationEnabled -> LocationStrategy.NETWORK_ONLY
                else -> LocationStrategy.LOCATION_DISABLED
            }
        }
    }
    
    /**
     * 定位策略枚举
     */
    enum class LocationStrategy {
        /** GMS 高精度定位 (GPS + 网络 + Google 服务) */
        GMS_HIGH_ACCURACY,
        /** GMS 平衡模式 */
        GMS_BALANCED,
        /** 仅 GPS 定位 */
        GPS_ONLY,
        /** 仅网络定位 */
        NETWORK_ONLY,
        /** 定位不可用 */
        LOCATION_DISABLED
    }
}

