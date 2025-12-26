package com.iki.location.model

import android.location.Location
import com.iki.location.R

/**
 * 定位结果数据类
 */
data class LocationData(
    /** 纬度 */
    val latitude: Double,
    /** 经度 */
    val longitude: Double,
    /** 精度 (米) */
    val accuracy: Float,
    /** 海拔 (米) */
    val altitude: Double,
    /** 速度 (米/秒) */
    val speed: Float,
    /** 方向角 (度) */
    val bearing: Float,
    /** 定位时间戳 (毫秒) */
    val timestamp: Long,
    /** 定位来源 */
    val provider: LocationProvider,
    /** 是否为模拟定位 */
    val isMocked: Boolean = false
) {
    companion object {
        /**
         * 从 Android Location 对象创建 LocationData
         */
        fun fromLocation(location: Location, provider: LocationProvider): LocationData {
            return LocationData(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy,
                altitude = location.altitude,
                speed = location.speed,
                bearing = location.bearing,
                timestamp = location.time,
                provider = provider,
                isMocked = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    location.isMock
                } else {
                    @Suppress("DEPRECATION")
                    location.isFromMockProvider
                }
            )
        }
    }
}

/**
 * 定位提供者类型
 */
enum class LocationProvider {
    /** Google Mobile Services 定位 */
    GMS,
    /** GPS 定位 */
    GPS,
    /** 网络定位 */
    NETWORK,
    /** 未知来源 */
    UNKNOWN
}

/**
 * 定位错误类型
 * 
 * @param message 默认错误消息（用于日志等场景）
 * @param code 错误码
 * @param messageResId 本地化消息资源ID（用于多语言场景）
 */
sealed class LocationError(
    val message: String, 
    val code: Int,
    val messageResId: Int
) {
    /** 权限未授予 */
    class PermissionDenied(message: String = "Location permission denied") : 
        LocationError(message, ERROR_PERMISSION_DENIED, R.string.location_error_base_permission_denied)
    
    /** 定位服务未开启 */
    class LocationDisabled(message: String = "Location service is disabled") : 
        LocationError(message, ERROR_LOCATION_DISABLED, R.string.location_error_base_location_disabled)
    
    /** GMS 不可用 */
    class GmsUnavailable(message: String = "Google Play Services is unavailable") : 
        LocationError(message, ERROR_GMS_UNAVAILABLE, R.string.location_error_base_gms_unavailable)
    
    /** GMS 精确定位开关未开启 */
    class GmsAccuracyDisabled(message: String = "Google Location Accuracy is disabled") : 
        LocationError(message, ERROR_GMS_ACCURACY_DISABLED, R.string.location_error_base_gms_accuracy_disabled)
    
    /** 定位超时 */
    class Timeout(message: String = "Location request timeout") : 
        LocationError(message, ERROR_TIMEOUT, R.string.location_error_base_timeout)
    
    /** 定位失败 */
    class LocationFailed(message: String = "Failed to get location") : 
        LocationError(message, ERROR_LOCATION_FAILED, R.string.location_error_base_location_failed)
    
    /** 未知错误 */
    class Unknown(message: String = "Unknown error", val throwable: Throwable? = null) : 
        LocationError(message, ERROR_UNKNOWN, R.string.location_error_base_unknown)
    
    companion object {
        const val ERROR_PERMISSION_DENIED = 1001
        const val ERROR_LOCATION_DISABLED = 1002
        const val ERROR_GMS_UNAVAILABLE = 1003
        const val ERROR_GMS_ACCURACY_DISABLED = 1004
        const val ERROR_TIMEOUT = 1005
        const val ERROR_LOCATION_FAILED = 1006
        const val ERROR_UNKNOWN = 1099
    }
    
    /**
     * 获取本地化错误消息
     * 
     * @param context Context 用于获取资源
     * @return 本地化的错误消息
     */
    fun getLocalizedMessage(context: android.content.Context): String {
        return context.getString(messageResId)
    }
}

/**
 * 单次定位请求配置
 * 
 * @param priority 定位精度优先级，影响定位速度和精度
 * @param timeoutMillis 定位超时时间（毫秒），超时后返回失败
 */
data class LocationRequest(
    /** 定位精度优先级 */
    val priority: Priority = Priority.HIGH_ACCURACY,
    /** 定位超时时间 (毫秒)，默认30秒 */
    val timeoutMillis: Long = 30000L
) {
    enum class Priority {
        /** 高精度 - 同时使用GPS和WiFi，取最快返回的结果 */
        HIGH_ACCURACY,
        /** 低功耗 - 优先使用网络定位(WiFi) */
        LOW_POWER
    }
}

