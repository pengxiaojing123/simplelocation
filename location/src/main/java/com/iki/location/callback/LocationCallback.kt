package com.iki.location.callback

import com.iki.location.model.LocationData
import com.iki.location.model.LocationError

/**
 * 定位结果回调接口
 */
interface SingleLocationCallback {
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
