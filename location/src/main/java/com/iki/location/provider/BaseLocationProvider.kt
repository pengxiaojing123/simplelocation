package com.iki.location.provider

import com.iki.location.callback.LocationResultCallback
import com.iki.location.model.LocationData
import com.iki.location.model.LocationRequest

/**
 * 定位提供者基类
 */
abstract class BaseLocationProvider {
    
    /**
     * 获取单次定位
     * 
     * @param request 定位请求配置
     * @return 定位结果
     */
    abstract suspend fun getLocation(request: LocationRequest): Result<LocationData>
    
    /**
     * 开始连续定位
     * 
     * @param request 定位请求配置
     * @param callback 定位结果回调
     */
    abstract fun startLocationUpdates(
        request: LocationRequest,
        callback: LocationResultCallback
    )
    
    /**
     * 停止连续定位
     */
    abstract fun stopLocationUpdates()
}

