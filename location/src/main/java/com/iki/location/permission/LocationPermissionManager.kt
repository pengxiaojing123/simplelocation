package com.iki.location.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.iki.location.callback.PermissionCallback

/**
 * 定位权限管理器
 * 
 * 处理 Android 各版本的定位权限申请：
 * - Android 6.0 (API 23): 引入运行时权限
 * - Android 12 (API 31): 可以只申请粗略定位权限
 */
class LocationPermissionManager private constructor(private val context: Context) {
    
    companion object {
        const val REQUEST_CODE_LOCATION = 10001
        
        private var instance: LocationPermissionManager? = null
        
        fun getInstance(context: Context): LocationPermissionManager {
            return instance ?: synchronized(this) {
                instance ?: LocationPermissionManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    private var permissionCallback: PermissionCallback? = null
    
    /**
     * 获取需要申请的定位权限列表
     */
    fun getLocationPermissions(): Array<String> {
        return arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
    
    /**
     * 检查是否有定位权限
     */
    fun hasLocationPermission(): Boolean {
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    
    /**
     * 检查是否有精确定位权限
     */
    fun hasFineLocationPermission(): Boolean {
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    
    /**
     * 检查是否有粗略定位权限
     */
    fun hasCoarseLocationPermission(): Boolean {
        return hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    
    /**
     * 检查是否拥有某个权限
     */
    fun hasPermission(permission: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 6.0 以下默认有权限
            true
        }
    }
    
    /**
     * 检查是否应该显示权限申请理由
     * 
     * 返回 true 表示用户之前拒绝过权限，应该向用户解释为什么需要这个权限
     */
    fun shouldShowRequestPermissionRationale(activity: Activity, permission: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activity.shouldShowRequestPermissionRationale(permission)
        } else {
            false
        }
    }
    
    /**
     * 检查权限是否被永久拒绝
     * 
     * 用户选择了 "不再询问" 选项
     */
    fun isPermissionPermanentlyDenied(activity: Activity, permission: String): Boolean {
        return !hasPermission(permission) && 
            !shouldShowRequestPermissionRationale(activity, permission)
    }
    
    /**
     * 请求定位权限
     * 
     * @param activity Activity 实例
     * @param callback 权限回调
     */
    fun requestLocationPermission(
        activity: Activity,
        callback: PermissionCallback
    ) {
        this.permissionCallback = callback
        
        val permissions = getLocationPermissions()
        
        if (hasLocationPermission()) {
            callback.onPermissionGranted(permissions.filter { hasPermission(it) })
            return
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activity.requestPermissions(permissions, REQUEST_CODE_LOCATION)
        } else {
            // Android 6.0 以下默认有权限
            callback.onPermissionGranted(permissions.toList())
        }
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
        if (requestCode == REQUEST_CODE_LOCATION) {
            val granted = mutableListOf<String>()
            val denied = mutableListOf<String>()
            
            permissions.forEachIndexed { index, permission ->
                if (grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED) {
                    granted.add(permission)
                } else {
                    denied.add(permission)
                }
            }
            
            if (denied.isEmpty()) {
                permissionCallback?.onPermissionGranted(granted)
            } else {
                permissionCallback?.onPermissionDenied(denied, false)
            }
        }
    }
    
    /**
     * 打开应用设置页面
     * 用于引导用户手动授权
     */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
    
    /**
     * 打开定位设置页面
     */
    fun openLocationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
    
    /**
     * 获取权限状态摘要
     */
    fun getPermissionStatus(): PermissionStatus {
        return PermissionStatus(
            hasFineLocation = hasFineLocationPermission(),
            hasCoarseLocation = hasCoarseLocationPermission(),
            androidVersion = Build.VERSION.SDK_INT
        )
    }
    
    /**
     * 权限状态数据类
     */
    data class PermissionStatus(
        val hasFineLocation: Boolean,
        val hasCoarseLocation: Boolean,
        val androidVersion: Int
    ) {
        val hasAnyLocationPermission: Boolean
            get() = hasFineLocation || hasCoarseLocation
    }
}
