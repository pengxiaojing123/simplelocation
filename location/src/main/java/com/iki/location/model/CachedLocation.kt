package com.iki.location.model

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 缓存的定位数据
 * 
 * 包含定位结果及其元数据，用于保存和获取上次定位信息
 */
data class CachedLocation(
    /** 纬度 */
    val latitude: Double,
    /** 经度 */
    val longitude: Double,
    /** 定位精度（米） */
    val accuracy: Float,
    /** GPS 定位类型 */
    val gpsType: String,
    /** GPS 定位时间戳（毫秒，UTC时间） */
    val gpsPositionTime: Long,
    /** 定位数据老化时间（毫秒）- 从定位点创建到保存时已经过去的时间 */
    val gpsMillsOldWhenSaved: Long
) {
    
    companion object {
        // GPS 定位类型常量
        /** GPS 卫星定位（最准确） */
        const val TYPE_GPS = "gps"
        /** 网络定位（基站 + WiFi） */
        const val TYPE_NETWORK = "network"
        /** 被动定位（使用其他应用的定位结果） */
        const val TYPE_PASSIVE = "passive"
        /** 融合定位（Google Play Services） */
        const val TYPE_FUSED = "fused"
        /** 未知类型 */
        const val TYPE_UNKNOWN = "unknown"
        
        /**
         * 从 LocationData 创建 CachedLocation
         */
        fun fromLocationData(locationData: LocationData): CachedLocation {
            val gpsType = when (locationData.provider) {
                LocationProvider.GPS -> TYPE_GPS
                LocationProvider.NETWORK -> TYPE_NETWORK
                LocationProvider.GMS -> TYPE_FUSED
                LocationProvider.UNKNOWN -> TYPE_UNKNOWN
            }
            
            val currentTime = System.currentTimeMillis()
            val gpsMillsOldWhenSaved = currentTime - locationData.timestamp
            
            return CachedLocation(
                latitude = locationData.latitude,
                longitude = locationData.longitude,
                accuracy = locationData.accuracy,
                gpsType = gpsType,
                gpsPositionTime = locationData.timestamp,
                gpsMillsOldWhenSaved = gpsMillsOldWhenSaved
            )
        }
    }
    
    /**
     * 获取当前的数据老化时间（毫秒）
     * 
     * 从定位点创建到现在已经过去的时间
     */
    fun getCurrentAgeMillis(): Long {
        return System.currentTimeMillis() - gpsPositionTime
    }
    
    /**
     * 检查数据是否过期
     * 
     * @param maxAgeMillis 最大有效时间（毫秒）
     * @return 如果数据已过期返回 true
     */
    fun isExpired(maxAgeMillis: Long): Boolean {
        return getCurrentAgeMillis() > maxAgeMillis
    }
    
    /**
     * 转换为 JSON 字符串（用于持久化存储）
     */
    fun toJson(): String {
        return JSONObject().apply {
            put("latitude", latitude)
            put("longitude", longitude)
            put("accuracy", accuracy.toDouble())
            put("gps_type", gpsType)
            put("gps_position_time", gpsPositionTime)
            put("gps_mills_old_when_saved", gpsMillsOldWhenSaved)
        }.toString()
    }
}

/**
 * 定位缓存管理器
 * 
 * 负责保存和读取缓存的定位数据
 */
class LocationCacheManager private constructor(context: Context) {
    
    companion object {
        private const val PREF_NAME = "location_cache"
        private const val KEY_CACHED_LOCATION = "cached_location"
        
        @Volatile
        private var instance: LocationCacheManager? = null
        
        fun getInstance(context: Context): LocationCacheManager {
            return instance ?: synchronized(this) {
                instance ?: LocationCacheManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    
    // 内存缓存
    private var cachedLocation: CachedLocation? = null
    
    /**
     * 保存定位数据到缓存
     */
    fun saveLocation(locationData: LocationData) {
        val cached = CachedLocation.fromLocationData(locationData)
        cachedLocation = cached
        
        // 同时保存到 SharedPreferences（持久化）
        prefs.edit().putString(KEY_CACHED_LOCATION, cached.toJson()).apply()
    }
    
    /**
     * 获取缓存的定位数据
     * 
     * @return 缓存的定位数据，如果没有缓存返回 null
     */
    fun getLastLocation(): CachedLocation? {
        // 优先返回内存缓存
        cachedLocation?.let { return it }
        
        // 从 SharedPreferences 读取
        val json = prefs.getString(KEY_CACHED_LOCATION, null) ?: return null
        
        return try {
            val jsonObject = JSONObject(json)
            CachedLocation(
                latitude = jsonObject.getDouble("latitude"),
                longitude = jsonObject.getDouble("longitude"),
                accuracy = jsonObject.getDouble("accuracy").toFloat(),
                gpsType = jsonObject.getString("gps_type"),
                gpsPositionTime = jsonObject.getLong("gps_position_time"),
                gpsMillsOldWhenSaved = jsonObject.getLong("gps_mills_old_when_saved")
            ).also { cachedLocation = it }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 清除缓存
     */
    fun clearCache() {
        cachedLocation = null
        prefs.edit().remove(KEY_CACHED_LOCATION).apply()
    }
    
    /**
     * 检查是否有缓存的定位数据
     */
    fun hasCache(): Boolean {
        return cachedLocation != null || prefs.contains(KEY_CACHED_LOCATION)
    }
}

