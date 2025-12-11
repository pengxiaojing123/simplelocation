package com.iki.location.callback

import com.iki.location.model.LocationData
import com.iki.location.model.LocationError

/**
 * 定位结果回调接口
 */
interface LocationResultCallback {
    /**
     * 定位成功
     * @param location 定位结果
     */
    fun onLocationSuccess(location: LocationData)
    
    /**
     * 定位失败
     * @param error 错误信息
     */
    fun onLocationError(error: LocationError)
}

/**
 * 单次定位回调接口
 */
interface SingleLocationCallback : LocationResultCallback

/**
 * 连续定位回调接口
 */
interface ContinuousLocationCallback : LocationResultCallback {
    /**
     * 定位提供者发生变化时回调
     * @param oldProvider 旧的定位提供者
     * @param newProvider 新的定位提供者
     */
    fun onProviderChanged(oldProvider: String, newProvider: String) {}
}

/**
 * 权限申请回调接口
 */
interface PermissionCallback {
    /**
     * 权限授予
     * @param permissions 授予的权限列表
     */
    fun onPermissionGranted(permissions: List<String>)
    
    /**
     * 权限拒绝
     * @param deniedPermissions 被拒绝的权限列表
     * @param permanentlyDenied 是否被永久拒绝 (用户选择了"不再询问")
     */
    fun onPermissionDenied(deniedPermissions: List<String>, permanentlyDenied: Boolean)
}

/**
 * GMS 精确定位开关状态回调
 */
interface GmsAccuracyCallback {
    /**
     * 精确定位开关状态变化
     * @param enabled 是否开启
     */
    fun onAccuracyStateChanged(enabled: Boolean)
}

/**
 * 定位状态监听器
 */
interface LocationStateListener {
    /**
     * 开始定位
     */
    fun onLocationStarted()
    
    /**
     * 停止定位
     */
    fun onLocationStopped()
    
    /**
     * 定位来源切换
     * @param from 原定位来源
     * @param to 新定位来源
     * @param reason 切换原因
     */
    fun onProviderSwitched(from: String, to: String, reason: String)
}

