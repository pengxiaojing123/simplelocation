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
 * 2. 检查并请求开启 GMS 精确定位开关（如果 GMS 可用）
 * 3. 执行定位
 * 4. 返回定位结果或错误
 * 
 * 使用示例:
 * ```kotlin
 * class MainActivity : AppCompatActivity() {
 *     private lateinit var easyLocationClient: EasyLocationClient
 *     
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         easyLocationClient = EasyLocationClient(this)
 *         
 *         // 一键定位
 *         easyLocationClient.getLocation(object : EasyLocationCallback {
 *             override fun onSuccess(location: LocationData) {
 *                 // 定位成功
 *             }
 *             override fun onError(error: EasyLocationError) {
 *                 // 处理错误
 *             }
 *         })
 *     }
 *     
 *     override fun onRequestPermissionsResult(...) {
 *         easyLocationClient.onRequestPermissionsResult(requestCode, permissions, grantResults)
 *     }
 *     
 *     override fun onActivityResult(...) {
 *         easyLocationClient.onActivityResult(requestCode, resultCode, data)
 *     }
 *     
 *     override fun onDestroy() {
 *         easyLocationClient.destroy()
 *     }
 * }
 * ```
 */
class EasyLocationClient(activity: Activity) {
    
    companion object {
        private const val TAG = "mylocation"
        const val REQUEST_CODE_GMS_SETTINGS = 10086
    }
    
    private val activityRef = WeakReference(activity)
    private val locationManager = SimpleLocationManager.getInstance(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var currentCallback: EasyLocationCallback? = null
    private var currentRequest: LocationRequest? = null
    private var isProcessing = false
    
    /**
     * 一键获取定位
     * 
     * 流程:
     * 1. 检查权限 → 没有则申请
     * 2. 检查 GMS 精确定位开关 → 没开则请求开启
     * 3. 执行定位 → 返回结果
     * 
     * @param request 定位请求配置
     * @param callback 回调
     */
    fun getLocation(
        request: LocationRequest = LocationRequest(),
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
        currentRequest = request
        
        Log.d(TAG, "[EasyLocation] ========== 开始一键定位流程 ==========")
        
        // Step 1: 检查权限
        checkAndRequestPermission(activity)
    }
    
    /**
     * Step 1: 检查并申请权限
     */
    private fun checkAndRequestPermission(activity: Activity) {
        Log.d(TAG, "[EasyLocation] Step 1: 检查定位权限")
        
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
                finishWithError(EasyLocationError.PermissionDenied(permanentlyDenied))
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
        
        scope.launch {
            // 检查位置设置
            when (val result = locationManager.checkLocationSettings(currentRequest ?: LocationRequest())) {
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
                    finishWithError(EasyLocationError.PermissionDenied(false))
                }
                
                is SimpleLocationManager.LocationSettingsResult.LocationDisabled -> {
                    Log.e(TAG, "[EasyLocation] ❌ 定位服务未开启")
                    finishWithError(EasyLocationError.LocationDisabled)
                }
            }
        }
    }
    
    /**
     * Step 3: 执行定位
     */
    private fun startLocation() {
        Log.d(TAG, "[EasyLocation] Step 3: 开始定位")
        
        val request = currentRequest ?: LocationRequest()
        
        locationManager.getLocation(request, object : SingleLocationCallback {
            override fun onLocationSuccess(location: LocationData) {
                Log.d(TAG, "[EasyLocation] ✅ 定位成功: ${location.provider}, (${location.latitude}, ${location.longitude})")
                finishWithSuccess(location)
            }
            
            override fun onLocationError(error: LocationError) {
                Log.e(TAG, "[EasyLocation] ❌ 定位失败: ${error.message}")
                finishWithError(EasyLocationError.LocationFailed(error))
            }
        })
    }
    
    /**
     * 完成 - 成功
     */
    private fun finishWithSuccess(location: LocationData) {
        Log.d(TAG, "[EasyLocation] ========== 一键定位流程完成 (成功) ==========")
        isProcessing = false
        currentCallback?.onSuccess(location)
        currentCallback = null
        currentRequest = null
    }
    
    /**
     * 完成 - 失败
     */
    private fun finishWithError(error: EasyLocationError) {
        Log.e(TAG, "[EasyLocation] ========== 一键定位流程完成 (失败: ${error.message}) ==========")
        isProcessing = false
        currentCallback?.onError(error)
        currentCallback = null
        currentRequest = null
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
            currentRequest = null
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
    
    /** 权限被拒绝 */
    class PermissionDenied(val permanentlyDenied: Boolean) : 
        EasyLocationError(
            if (permanentlyDenied) "权限被永久拒绝，请到设置中开启" else "定位权限被拒绝",
            CODE_PERMISSION_DENIED
        )
    
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
        const val CODE_GMS_ACCURACY_DENIED = 2002
        const val CODE_LOCATION_DISABLED = 2003
        const val CODE_LOCATION_FAILED = 2004
        const val CODE_ACTIVITY_DESTROYED = 2005
        const val CODE_ALREADY_PROCESSING = 2006
    }
    
    /**
     * 是否可以通过打开设置解决
     */
    val canResolveInSettings: Boolean
        get() = when (this) {
            is PermissionDenied -> permanentlyDenied
            is GmsAccuracyDenied -> true
            is LocationDisabled -> true
            else -> false
        }
}

