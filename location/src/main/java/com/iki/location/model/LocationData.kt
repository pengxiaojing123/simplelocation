package com.iki.location.model

import android.location.Location

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
 */
sealed class LocationError(val message: String, val code: Int) {
    /** 权限未授予 */
    class PermissionDenied(message: String = "Location permission denied") : 
        LocationError(message, ERROR_PERMISSION_DENIED)
    
    /** 定位服务未开启 */
    class LocationDisabled(message: String = "Location service is disabled") : 
        LocationError(message, ERROR_LOCATION_DISABLED)
    
    /** GMS 不可用 */
    class GmsUnavailable(message: String = "Google Play Services is unavailable") : 
        LocationError(message, ERROR_GMS_UNAVAILABLE)
    
    /** GMS 精确定位开关未开启 */
    class GmsAccuracyDisabled(message: String = "Google Location Accuracy is disabled") : 
        LocationError(message, ERROR_GMS_ACCURACY_DISABLED)
    
    /** 定位超时 */
    class Timeout(message: String = "Location request timeout") : 
        LocationError(message, ERROR_TIMEOUT)
    
    /** 定位失败 */
    class LocationFailed(message: String = "Failed to get location") : 
        LocationError(message, ERROR_LOCATION_FAILED)
    
    /** 未知错误 */
    class Unknown(message: String = "Unknown error", val throwable: Throwable? = null) : 
        LocationError(message, ERROR_UNKNOWN)
    
    companion object {
        const val ERROR_PERMISSION_DENIED = 1001
        const val ERROR_LOCATION_DISABLED = 1002
        const val ERROR_GMS_UNAVAILABLE = 1003
        const val ERROR_GMS_ACCURACY_DISABLED = 1004
        const val ERROR_TIMEOUT = 1005
        const val ERROR_LOCATION_FAILED = 1006
        const val ERROR_UNKNOWN = 1099
    }
}

/**
 * 定位请求配置
 */
data class LocationRequest(
    /** 定位间隔 (毫秒) */
    val intervalMillis: Long = 10000L,
    /** 最快定位间隔 (毫秒) */
    val fastestIntervalMillis: Long = 5000L,
    /** 定位优先级 */
    val priority: Priority = Priority.HIGH_ACCURACY,
    /** 超时时间 (毫秒) */
    val timeoutMillis: Long = 30000L,
    /** 最小位移距离 (米) - 仅当位移大于此值时才回调 */
    val minDistanceMeters: Float = 0f,
    /** 是否需要后台定位权限 */
    val needBackgroundLocation: Boolean = false
) {
    enum class Priority {
        /** 高精度 - 优先使用GPS */
        HIGH_ACCURACY,
        /** 平衡模式 - 综合考虑精度和电量 */
        BALANCED_POWER_ACCURACY,
        /** 低功耗 - 优先使用网络定位 */
        LOW_POWER,
        /** 被动模式 - 只使用其他应用获取的定位 */
        PASSIVE
    }
}

