package com.iki.location

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.iki.location.callback.PermissionCallback
import com.iki.location.callback.SingleLocationCallback
import com.iki.location.model.LocationData
import com.iki.location.model.LocationError
import com.iki.location.model.LocationRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * 一站式定位客户端
 * 
 * 封装了完整的定位流程：
 * 1. 检查并申请定位权限
 * 2. 检查精确定位权限（如果 requireFineLocation = true）
 * 3. 检查并请求开启 GMS 精确定位开关（如果 GMS 可用）
 * 4. 执行定位
 * 5. 返回定位结果或错误
 * 
 * 使用示例:
 * ```java
 * EasyLocationClient client = new EasyLocationClient(activity);
 * 
 * // 一键定位（使用默认配置）
 * client.getLocation(new EasyLocationCallback() {
 *     @Override
 *     public void onSuccess(LocationData location) {
 *         // 定位成功
 *     }
 *     
 *     @Override
 *     public void onError(EasyLocationError error) {
 *         // 处理错误
 *     }
 * });
 * 
 * // 或者指定参数
 * client.getLocation(
 *     true,   // requireFineLocation: 要求精确定位
 *     15000L, // timeoutMillis: 超时时间
 *     callback
 * );
 * ```
 */
class EasyLocationClient(activity: Activity) {
    
    companion object {
        private const val TAG = "mylocation"
        const val REQUEST_CODE_GMS_SETTINGS = 10086
        
        /** 默认超时时间 30 秒 */
        const val DEFAULT_TIMEOUT_MILLIS = 30000L
    }
    
    private val activityRef = WeakReference(activity)
    private val locationManager = SimpleLocationManager.getInstance(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var currentCallback: EasyLocationCallback? = null
    private var requireFineLocation: Boolean = false
    private var timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
    private var isProcessing = false
    
    /**
     * 一键获取定位（使用默认配置）
     * 
     * 默认配置：
     * - requireFineLocation = false（接受模糊定位）
     * - timeoutMillis = 30000（30秒超时）
     * 
     * @param callback 回调
     */
    fun getLocation(callback: EasyLocationCallback) {
        getLocation(
            requireFineLocation = false,
            timeoutMillis = DEFAULT_TIMEOUT_MILLIS,
            callback = callback
        )
    }
    
    /**
     * 一键获取定位
     * 
     * @param requireFineLocation 是否要求精确定位权限。
     *                            如果为 true，用户只授予模糊定位权限时会触发错误回调
     * @param timeoutMillis 定位超时时间（毫秒）
     * @param callback 回调
     */
    fun getLocation(
        requireFineLocation: Boolean,
        timeoutMillis: Long,
        callback: EasyLocationCallback
    ) {
        val activity = activityRef.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.e(TAG, "[EasyLocation] Activity 已销毁")
            callback.onError(EasyLocationError.ActivityDestroyed)
            return
        }
        
        if (isProcessing) {
            Log.w(TAG, "[EasyLocation] 已有定位请求在处理中")
            callback.onError(EasyLocationError.AlreadyProcessing)
            return
        }
        
        isProcessing = true
        currentCallback = callback
        this.requireFineLocation = requireFineLocation
        this.timeoutMillis = timeoutMillis
        
        Log.d(TAG, "[EasyLocation] ========== 开始一键定位流程 ==========")
        Log.d(TAG, "[EasyLocation] 配置: requireFineLocation=$requireFineLocation, timeoutMillis=$timeoutMillis")
        
        // Step 1: 检查权限
        checkAndRequestPermission(activity)
    }
    
    /**
     * Step 1: 检查并申请权限
     */
    private fun checkAndRequestPermission(activity: Activity) {
        Log.d(TAG, "[EasyLocation] Step 1: 检查定位权限")
        
        // 如果需要精确定位，检查是否已有精确权限
        if (requireFineLocation) {
            if (locationManager.hasFineLocationPermission()) {
                Log.d(TAG, "[EasyLocation] 已有精确定位权限")
                checkGmsAccuracy(activity)
                return
            }
            
            // 没有精确权限，需要申请
            Log.d(TAG, "[EasyLocation] 需要精确定位权限，开始申请...")
            requestFineLocationPermission(activity)
            return
        }
        
        // 不要求精确定位，只需要任意定位权限
        if (locationManager.hasLocationPermission()) {
            Log.d(TAG, "[EasyLocation] 已有定位权限")
            checkGmsAccuracy(activity)
            return
        }
        
        Log.d(TAG, "[EasyLocation] 申请定位权限...")
        locationManager.requestLocationPermission(activity, object : PermissionCallback {
            override fun onPermissionGranted(permissions: List<String>) {
                Log.d(TAG, "[EasyLocation] ✅ 权限已授予: $permissions")
                
                val act = activityRef.get()
                if (act != null && !act.isFinishing && !act.isDestroyed) {
                    checkGmsAccuracy(act)
                } else {
                    finishWithError(EasyLocationError.ActivityDestroyed)
                }
            }
            
            override fun onPermissionDenied(deniedPermissions: List<String>, permanentlyDenied: Boolean) {
                Log.e(TAG, "[EasyLocation] ❌ 权限被拒绝: $deniedPermissions, 永久拒绝: $permanentlyDenied")
                if (permanentlyDenied) {
                    finishWithError(EasyLocationError.PermissionPermanentlyDenied)
                } else {
                    finishWithError(EasyLocationError.PermissionDenied)
                }
            }
        })
    }
    
    /**
     * 申请精确定位权限
     * 
     * 处理以下情况：
     * 1. 用户之前没有任何权限 -> 正常弹窗
     * 2. 用户之前只授予了模糊权限 -> 尝试弹窗，如果不弹窗则引导去设置
     */
    private fun requestFineLocationPermission(activity: Activity) {
        // 检查是否已有模糊权限（说明用户之前选择了模糊定位）
        val hasCoarseOnly = locationManager.hasLocationPermission() && 
                            !locationManager.hasFineLocationPermission()
        
        if (hasCoarseOnly) {
            Log.d(TAG, "[EasyLocation] 用户已有模糊权限，尝试申请精确权限...")
        }
        
        locationManager.requestLocationPermission(activity, object : PermissionCallback {
            override fun onPermissionGranted(permissions: List<String>) {
                Log.d(TAG, "[EasyLocation] ✅ 权限已授予: $permissions")
                
                // 再次检查是否获得了精确权限
                if (locationManager.hasFineLocationPermission()) {
                    Log.d(TAG, "[EasyLocation] ✅ 已获得精确定位权限")
                    val act = activityRef.get()
                    if (act != null && !act.isFinishing && !act.isDestroyed) {
                        checkGmsAccuracy(act)
                    } else {
                        finishWithError(EasyLocationError.ActivityDestroyed)
                    }
                } else {
                    // 授予了权限但不是精确权限（用户选择了模糊）
                    Log.e(TAG, "[EasyLocation] ❌ 用户选择了模糊定位，需要精确定位")
                    finishWithError(EasyLocationError.FineLocationRequired)
                }
            }
            
            override fun onPermissionDenied(deniedPermissions: List<String>, permanentlyDenied: Boolean) {
                Log.e(TAG, "[EasyLocation] ❌ 权限被拒绝: $deniedPermissions, 永久拒绝: $permanentlyDenied")
                
                // 如果之前已有模糊权限，但系统没有弹窗让用户选择精确权限
                // 这种情况下需要引导用户去设置
                if (hasCoarseOnly && locationManager.hasLocationPermission()) {
                    Log.e(TAG, "[EasyLocation] ❌ 用户已有模糊权限，需要去设置中修改为精确权限")
                    finishWithError(EasyLocationError.FineLocationRequired)
                } else if (permanentlyDenied) {
                    finishWithError(EasyLocationError.PermissionPermanentlyDenied)
                } else {
                    finishWithError(EasyLocationError.PermissionDenied)
                }
            }
        })
    }
    
    /**
     * Step 2: 检查 GMS 精确定位开关
     */
    private fun checkGmsAccuracy(activity: Activity) {
        Log.d(TAG, "[EasyLocation] Step 2: 检查 GMS 精确定位开关")
        
        // 检查 GMS 是否可用
        if (!locationManager.isGmsAvailable()) {
            Log.d(TAG, "[EasyLocation] GMS 不可用，跳过精确定位检查")
            startLocation()
            return
        }
        
        // 内部固定使用 HIGH_ACCURACY
        val request = LocationRequest(
            priority = LocationRequest.Priority.HIGH_ACCURACY,
            timeoutMillis = timeoutMillis
        )
        
        scope.launch {
            when (val result = locationManager.checkLocationSettings(request)) {
                is SimpleLocationManager.LocationSettingsResult.Satisfied -> {
                    Log.d(TAG, "[EasyLocation] ✅ GMS 位置设置满足要求")
                    startLocation()
                }
                
                is SimpleLocationManager.LocationSettingsResult.Resolvable -> {
                    Log.d(TAG, "[EasyLocation] GMS 位置设置需要用户确认，弹出对话框...")
                    try {
                        result.exception.startResolutionForResult(activity, REQUEST_CODE_GMS_SETTINGS)
                    } catch (e: Exception) {
                        Log.e(TAG, "[EasyLocation] 无法弹出 GMS 设置对话框", e)
                        // 即使无法弹出对话框，也尝试定位
                        startLocation()
                    }
                }
                
                is SimpleLocationManager.LocationSettingsResult.PermissionRequired -> {
                    Log.e(TAG, "[EasyLocation] ❌ 需要权限（不应该到达这里）")
                    finishWithError(EasyLocationError.PermissionDenied)
                }
                
                is SimpleLocationManager.LocationSettingsResult.LocationDisabled -> {
                    Log.e(TAG, "[EasyLocation] ❌ 定位服务未开启")
                    showLocationSettingDialog(activity)
                }
            }
        }
    }
    
    /**
     * Step 3: 执行定位
     */
    private fun startLocation() {
        Log.d(TAG, "[EasyLocation] Step 3: 开始定位")
        
        // 内部固定使用 HIGH_ACCURACY
        val request = LocationRequest(
            priority = LocationRequest.Priority.HIGH_ACCURACY,
            timeoutMillis = timeoutMillis
        )
        
        locationManager.getLocation(request, object : SingleLocationCallback {
            override fun onLocationSuccess(location: LocationData) {
                Log.d(TAG, "[EasyLocation] ✅ 定位成功: ${location.provider}, (${location.latitude}, ${location.longitude})")
                finishWithSuccess(location)
            }
            
            override fun onLocationError(error: LocationError) {
                if (error is LocationError.LocationDisabled) {
                    val activity = activityRef.get()
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        Log.e(TAG, "[EasyLocation] ❌ 定位服务未开启")
                        showLocationSettingDialog(activity)
                        return
                    }
                }
                
                Log.e(TAG, "[EasyLocation] ❌ 定位失败: ${error.message}")
                finishWithError(EasyLocationError.LocationFailed(error))
            }
        })
    }
    
    /**
     * 显示定位服务未开启提示框
     */
    private fun showLocationSettingDialog(activity: Activity) {
        android.app.AlertDialog.Builder(activity)
            .setTitle("提示")
            .setMessage("定位服务未开启，无法获取位置，请前往设置打开。")
            .setPositiveButton("去设置") { _, _ ->
                openLocationSettings()
                finishWithError(EasyLocationError.LocationDisabled)
            }
            .setNegativeButton("取消") { _, _ ->
                finishWithError(EasyLocationError.LocationDisabled)
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * 完成 - 成功
     */
    private fun finishWithSuccess(location: LocationData) {
        Log.d(TAG, "[EasyLocation] ========== 一键定位流程完成 (成功) ==========")
        isProcessing = false
        currentCallback?.onSuccess(location)
        currentCallback = null
    }
    
    /**
     * 完成 - 失败
     */
    private fun finishWithError(error: EasyLocationError) {
        Log.e(TAG, "[EasyLocation] ========== 一键定位流程完成 (失败: ${error.message}) ==========")
        isProcessing = false
        currentCallback?.onError(error)
        currentCallback = null
    }
    
    /**
     * 处理权限申请结果
     * 
     * 在 Activity 的 onRequestPermissionsResult 中调用
     */
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        locationManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    
    /**
     * 处理 Activity 结果
     * 
     * 在 Activity 的 onActivityResult 中调用
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_CODE_GMS_SETTINGS -> {
                Log.d(TAG, "[EasyLocation] GMS 设置对话框结果: resultCode=$resultCode")
                if (resultCode == Activity.RESULT_OK) {
                    Log.d(TAG, "[EasyLocation] ✅ 用户同意开启精确定位")
                    startLocation()
                } else {
                    Log.w(TAG, "[EasyLocation] ❌ 用户拒绝开启精确定位")
                    finishWithError(EasyLocationError.GmsAccuracyDenied)
                }
            }
        }
    }
    
    /**
     * 取消当前定位请求
     */
    fun cancel() {
        if (isProcessing) {
            Log.d(TAG, "[EasyLocation] 取消定位请求")
            isProcessing = false
            currentCallback = null
            locationManager.cancel()
        }
    }
    
    /**
     * 销毁客户端
     */
    fun destroy() {
        cancel()
        activityRef.clear()
    }
    
    /**
     * 打开应用设置页面（用于权限被永久拒绝时引导用户）
     */
    fun openAppSettings() {
        locationManager.openAppSettings()
    }
    
    /**
     * 打开定位设置页面
     */
    fun openLocationSettings() {
        locationManager.openLocationSettings()
    }
}

/**
 * 一键定位回调
 */
interface EasyLocationCallback {
    /**
     * 定位成功
     */
    fun onSuccess(location: LocationData)
    
    /**
     * 定位失败
     */
    fun onError(error: EasyLocationError)
}

/**
 * 一键定位错误类型
 */
sealed class EasyLocationError(val message: String, val code: Int) {
    
    /** 权限被拒绝（用户本次拒绝，可以再次申请） */
    object PermissionDenied : 
        EasyLocationError("定位权限被拒绝", CODE_PERMISSION_DENIED)
    
    /** 权限被永久拒绝（用户选择了"不再询问"，需要去设置中开启） */
    object PermissionPermanentlyDenied : 
        EasyLocationError("定位权限被永久拒绝，请到设置中开启", CODE_PERMISSION_PERMANENTLY_DENIED)
    
    /** 要求精确定位但用户只授予了模糊定位 */
    object FineLocationRequired : 
        EasyLocationError("需要精确定位权限，但用户只授予了模糊定位", CODE_FINE_LOCATION_REQUIRED)
    
    /** GMS 精确定位开关被拒绝 */
    object GmsAccuracyDenied : 
        EasyLocationError("用户拒绝开启精确定位", CODE_GMS_ACCURACY_DENIED)
    
    /** 定位服务未开启 */
    object LocationDisabled : 
        EasyLocationError("定位服务未开启，请到设置中开启", CODE_LOCATION_DISABLED)
    
    /** 定位失败 */
    class LocationFailed(val originalError: LocationError) : 
        EasyLocationError("定位失败: ${originalError.message}", CODE_LOCATION_FAILED)
    
    /** Activity 已销毁 */
    object ActivityDestroyed : 
        EasyLocationError("页面已关闭", CODE_ACTIVITY_DESTROYED)
    
    /** 已有请求在处理中 */
    object AlreadyProcessing : 
        EasyLocationError("已有定位请求在处理中", CODE_ALREADY_PROCESSING)
    
    companion object {
        const val CODE_PERMISSION_DENIED = 2001
        const val CODE_PERMISSION_PERMANENTLY_DENIED = 2002
        const val CODE_FINE_LOCATION_REQUIRED = 2003
        const val CODE_GMS_ACCURACY_DENIED = 2004
        const val CODE_LOCATION_DISABLED = 2005
        const val CODE_LOCATION_FAILED = 2006
        const val CODE_ACTIVITY_DESTROYED = 2007
        const val CODE_ALREADY_PROCESSING = 2008
    }
    
    /**
     * 是否可以通过打开设置解决
     */
    val canResolveInSettings: Boolean
        get() = when (this) {
            is PermissionPermanentlyDenied -> true
            is FineLocationRequired -> true  // 可以到设置中改为精确定位
            is GmsAccuracyDenied -> true
            is LocationDisabled -> true
            else -> false
        }
}
