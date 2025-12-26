package com.iki.location

import android.app.Activity
import android.content.Intent
import com.iki.location.callback.PermissionCallback
import com.iki.location.callback.SingleLocationCallback
import com.iki.location.model.CachedLocation
import com.iki.location.model.LocationCacheManager
import com.iki.location.model.LocationData
import com.iki.location.model.LocationError
import com.iki.location.model.LocationRequest
import com.iki.location.util.LocationLogger
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
        const val REQUEST_CODE_GMS_SETTINGS = 10086
        
        /** 默认超时时间 30 秒 */
        const val DEFAULT_TIMEOUT_MILLIS = 30000L
    }
    
    private val context = activity.applicationContext
    private val activityRef = WeakReference(activity)
    private val locationManager = SimpleLocationManager.getInstance(activity)
    private val locationCacheManager = LocationCacheManager.getInstance(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var currentCallback: EasyLocationCallback? = null
    private var requireFineLocation: Boolean = false
    private var timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
    private var isProcessing = false
    
    // 用于检测系统是否弹出了权限对话框
    private var permissionRequestStartTime: Long = 0
    // 判断系统是否弹出对话框的阈值（毫秒）
    // 如果权限请求在这个时间内返回，说明系统没有弹出对话框
    private val DIALOG_THRESHOLD_MILLIS = 500L
    
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
            LocationLogger.e( "[EasyLocation] Activity 已销毁")
            callback.onError(EasyLocationError.ActivityDestroyed)
            return
        }
        
        if (isProcessing) {
            LocationLogger.w( "[EasyLocation] 已有定位请求在处理中")
            callback.onError(EasyLocationError.AlreadyProcessing)
            return
        }
        
        isProcessing = true
        currentCallback = callback
        this.requireFineLocation = requireFineLocation
        this.timeoutMillis = timeoutMillis
        
        LocationLogger.d( "[EasyLocation] ========== 开始一键定位流程 ==========")
        LocationLogger.d( "[EasyLocation] 配置: requireFineLocation=$requireFineLocation, timeoutMillis=$timeoutMillis")
        
        // Step 1: 检查权限
        checkAndRequestPermission(activity)
    }
    
    /**
     * Step 1: 检查并申请权限
     */
    private fun checkAndRequestPermission(activity: Activity) {
        LocationLogger.d( "[EasyLocation] Step 1: 检查定位权限")
        
        // 如果需要精确定位，检查是否已有精确权限
        if (requireFineLocation) {
            if (locationManager.hasFineLocationPermission()) {
                LocationLogger.d( "[EasyLocation] 已有精确定位权限")
                checkGmsAccuracy(activity)
                return
            }
            
            // 没有精确权限，需要申请
            LocationLogger.d( "[EasyLocation] 需要精确定位权限，开始申请...")
            requestFineLocationPermission(activity)
            return
        }
        
        // 不要求精确定位，只需要任意定位权限
        if (locationManager.hasLocationPermission()) {
            LocationLogger.d( "[EasyLocation] 已有定位权限")
            checkGmsAccuracy(activity)
            return
        }
        
        LocationLogger.d( "[EasyLocation] 申请定位权限...")
        locationManager.requestLocationPermission(activity, object : PermissionCallback {
            override fun onPermissionGranted(permissions: List<String>) {
                LocationLogger.d( "[EasyLocation] ✅ 权限已授予: $permissions")
                
                val act = activityRef.get()
                if (act != null && !act.isFinishing && !act.isDestroyed) {
                    checkGmsAccuracy(act)
                } else {
                    finishWithError(EasyLocationError.ActivityDestroyed)
                }
            }
            
            override fun onPermissionDenied(deniedPermissions: List<String>, permanentlyDenied: Boolean) {
                LocationLogger.e( "[EasyLocation] ❌ 权限被拒绝: $deniedPermissions, 永久拒绝: $permanentlyDenied")
                if (permanentlyDenied) {
                    val act = activityRef.get()
                    if (act != null && !act.isFinishing && !act.isDestroyed) {
                        showPermissionSettingDialog(act)
                    } else {
                        finishWithError(EasyLocationError.PermissionPermanentlyDenied)
                    }
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
     * 2. 用户之前只授予了模糊权限 -> 尝试弹窗，通过响应时间检测系统是否弹出了对话框
     */
    private fun requestFineLocationPermission(activity: Activity) {
        // 检查是否已有模糊权限（说明用户之前选择了模糊定位）
        val hasCoarseOnly = locationManager.hasLocationPermission() && 
                            !locationManager.hasFineLocationPermission()
        
        if (hasCoarseOnly) {
            LocationLogger.d("[EasyLocation] 用户已有模糊权限，尝试申请精确权限...")
        } else {
            LocationLogger.d("[EasyLocation] 没有任何定位权限，申请权限...")
        }
        
        // 记录请求开始时间，用于检测系统是否弹出了对话框
        permissionRequestStartTime = System.currentTimeMillis()
        
        locationManager.requestLocationPermission(activity, object : PermissionCallback {
            override fun onPermissionGranted(permissions: List<String>) {
                val elapsed = System.currentTimeMillis() - permissionRequestStartTime
                LocationLogger.d("[EasyLocation] ✅ 权限已授予: $permissions, 耗时: ${elapsed}ms")
                
                // 再次检查是否获得了精确权限
                if (locationManager.hasFineLocationPermission()) {
                    LocationLogger.d("[EasyLocation] ✅ 已获得精确定位权限")
                    val act = activityRef.get()
                    if (act != null && !act.isFinishing && !act.isDestroyed) {
                        checkGmsAccuracy(act)
                    } else {
                        finishWithError(EasyLocationError.ActivityDestroyed)
                    }
                } else {
                    // 授予了权限但不是精确权限（用户选择了模糊）
                    val act = activityRef.get()
                    if (act == null || act.isFinishing || act.isDestroyed) {
                        finishWithError(EasyLocationError.FineLocationRequired)
                        return
                    }
                    
                    // 检测系统是否弹出了对话框
                    val dialogShown = elapsed >= DIALOG_THRESHOLD_MILLIS
                    LocationLogger.d("[EasyLocation] 权限请求耗时: ${elapsed}ms, 阈值: ${DIALOG_THRESHOLD_MILLIS}ms, 系统弹出对话框: $dialogShown")
                    
                    if (dialogShown) {
                        // 系统弹出了对话框，用户主动选择了模糊定位
                        // 直接返回错误回调，让业务层决定如何处理
                        LocationLogger.w("[EasyLocation] ⚠️ 用户主动选择了模糊定位，返回错误回调")
                        finishWithError(EasyLocationError.FineLocationRequired)
                    } else {
                        // 系统没有弹出对话框（响应太快），说明系统记住了之前的选择
                        // SDK 主动弹出引导对话框，引导用户去设置
                        LocationLogger.w("[EasyLocation] ⚠️ 系统未弹出对话框（耗时 ${elapsed}ms < ${DIALOG_THRESHOLD_MILLIS}ms），弹出引导对话框")
                        showFineLocationRequiredDialog(act)
                    }
                }
            }
            
            override fun onPermissionDenied(deniedPermissions: List<String>, permanentlyDenied: Boolean) {
                val elapsed = System.currentTimeMillis() - permissionRequestStartTime
                LocationLogger.e("[EasyLocation] ❌ 权限被拒绝: $deniedPermissions, 永久拒绝: $permanentlyDenied, 耗时: ${elapsed}ms")
                
                if (permanentlyDenied) {
                    val act = activityRef.get()
                    if (act != null && !act.isFinishing && !act.isDestroyed) {
                        showPermissionSettingDialog(act)
                    } else {
                        finishWithError(EasyLocationError.PermissionPermanentlyDenied)
                    }
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
        LocationLogger.d( "[EasyLocation] Step 2: 检查 GMS 精确定位开关")
        
        // 检查 GMS 是否可用
        if (!locationManager.isGmsAvailable()) {
            LocationLogger.d( "[EasyLocation] GMS 不可用，跳过精确定位检查")
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
                    LocationLogger.d( "[EasyLocation] ✅ GMS 位置设置满足要求")
                    startLocation()
                }
                
                is SimpleLocationManager.LocationSettingsResult.Resolvable -> {
                    LocationLogger.d( "[EasyLocation] GMS 位置设置需要用户确认，弹出对话框...")
                    try {
                        result.exception.startResolutionForResult(activity, REQUEST_CODE_GMS_SETTINGS)
                    } catch (e: Exception) {
                        LocationLogger.e( "[EasyLocation] 无法弹出 GMS 设置对话框", e)
                        // 即使无法弹出对话框，也尝试定位
                        startLocation()
                    }
                }
                
                is SimpleLocationManager.LocationSettingsResult.PermissionRequired -> {
                    LocationLogger.e( "[EasyLocation] ❌ 需要权限（不应该到达这里）")
                    finishWithError(EasyLocationError.PermissionDenied)
                }
                
                is SimpleLocationManager.LocationSettingsResult.LocationDisabled -> {
                    LocationLogger.e( "[EasyLocation] ❌ 定位服务未开启")
                    showLocationSettingDialog(activity)
                }
            }
        }
    }
    
    /**
     * Step 3: 执行定位
     */
    private fun startLocation() {
        LocationLogger.d( "[EasyLocation] Step 3: 开始定位")
        
        // 内部固定使用 HIGH_ACCURACY
        val request = LocationRequest(
            priority = LocationRequest.Priority.HIGH_ACCURACY,
            timeoutMillis = timeoutMillis
        )
        
        locationManager.getLocation(request, object : SingleLocationCallback {
            override fun onLocationSuccess(location: LocationData) {
                LocationLogger.d( "[EasyLocation] ✅ 定位成功: ${location.provider}, (${location.latitude}, ${location.longitude})")
                finishWithSuccess(location)
            }
            
            override fun onLocationError(error: LocationError) {
                if (error is LocationError.LocationDisabled) {
                    val activity = activityRef.get()
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        LocationLogger.e( "[EasyLocation] ❌ 定位服务未开启")
                        showLocationSettingDialog(activity)
                        return
                    }
                }
                
                LocationLogger.e( "[EasyLocation] ❌ 定位失败: ${error.message}")
                finishWithError(EasyLocationError.LocationFailed(error))
            }
        })
    }
    
    /**
     * 显示定位服务未开启提示框
     */
    private fun showLocationSettingDialog(activity: Activity) {
        android.app.AlertDialog.Builder(activity)
            .setTitle(context.getString(R.string.location_dialog_title_hint))
            .setMessage(context.getString(R.string.location_dialog_msg_location_disabled))
            .setPositiveButton(context.getString(R.string.location_btn_go_settings)) { _, _ ->
                openLocationSettings()
                finishWithError(EasyLocationError.LocationDisabled)
            }
            .setNegativeButton(context.getString(R.string.location_btn_cancel)) { _, _ ->
                finishWithError(EasyLocationError.LocationDisabled)
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * 显示权限未授予提示框
     */
    private fun showPermissionSettingDialog(activity: Activity) {
        LocationLogger.d("[EasyLocation] 显示权限永久拒绝引导对话框")
        android.app.AlertDialog.Builder(activity)
            .setTitle(context.getString(R.string.location_dialog_title_hint))
            .setMessage(context.getString(R.string.location_dialog_msg_permission_denied))
            .setPositiveButton(context.getString(R.string.location_btn_go_settings)) { _, _ ->
                LocationLogger.d("[EasyLocation] 用户点击去设置")
                openAppSettings()
                finishWithError(EasyLocationError.PermissionPermanentlyDenied)
            }
            .setNegativeButton(context.getString(R.string.location_btn_cancel)) { _, _ ->
                LocationLogger.d("[EasyLocation] 用户点击取消")
                finishWithError(EasyLocationError.PermissionPermanentlyDenied)
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * 显示需要精确定位权限提示框
     * 
     * 仅当系统没有弹出权限选择对话框时调用
     * （系统记住了用户之前的模糊定位选择，无法再次弹出选择框）
     */
    private fun showFineLocationRequiredDialog(activity: Activity) {
        LocationLogger.d("[EasyLocation] 显示精确定位引导对话框")
        android.app.AlertDialog.Builder(activity)
            .setTitle(context.getString(R.string.location_dialog_title_fine_location_required))
            .setMessage(context.getString(R.string.location_dialog_msg_fine_location_required))
            .setPositiveButton(context.getString(R.string.location_btn_go_settings)) { _, _ ->
                LocationLogger.d("[EasyLocation] 用户点击去设置")
                openAppSettings()
                finishWithError(EasyLocationError.FineLocationRequired)
            }
            .setNegativeButton(context.getString(R.string.location_btn_cancel)) { _, _ ->
                LocationLogger.d("[EasyLocation] 用户点击取消")
                finishWithError(EasyLocationError.FineLocationRequired)
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * 完成 - 成功
     */
    private fun finishWithSuccess(location: LocationData) {
        LocationLogger.d("[EasyLocation] ========== 一键定位流程完成 (成功) ==========")
        
        // 保存定位数据到缓存
        locationCacheManager.saveLocation(location)
        LocationLogger.d("[EasyLocation] 定位数据已缓存: provider=${location.provider}, accuracy=${location.accuracy}m")
        
        isProcessing = false
        currentCallback?.onSuccess(location)
        currentCallback = null
    }
    
    /**
     * 完成 - 失败
     */
    private fun finishWithError(error: EasyLocationError) {
        LocationLogger.e( "[EasyLocation] ========== 一键定位流程完成 (失败: ${error.message}) ==========")
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
                LocationLogger.d( "[EasyLocation] GMS 设置对话框结果: resultCode=$resultCode")
                if (resultCode == Activity.RESULT_OK) {
                    LocationLogger.d( "[EasyLocation] ✅ 用户同意开启精确定位")
                    startLocation()
                } else {
                    LocationLogger.w( "[EasyLocation] ❌ 用户拒绝开启精确定位")
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
            LocationLogger.d( "[EasyLocation] 取消定位请求")
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
    
    // ==================== 缓存相关 ====================
    
    /**
     * 获取上次缓存的定位数据
     * 
     * @return 缓存的定位数据，如果没有缓存返回 null
     */
    fun getLastLocation(): CachedLocation? {
        return locationCacheManager.getLastLocation()
    }
    
    /**
     * 检查是否有缓存的定位数据
     */
    fun hasLocationCache(): Boolean {
        return locationCacheManager.hasCache()
    }
    
    /**
     * 清除定位缓存
     */
    fun clearLocationCache() {
        locationCacheManager.clearCache()
    }
    
    // ==================== 状态查询 ====================
    
    /**
     * 检查是否有定位权限（模糊或精确）
     */
    fun hasLocationPermission(): Boolean {
        return locationManager.hasLocationPermission()
    }
    
    /**
     * 检查是否有精确定位权限
     */
    fun hasFineLocationPermission(): Boolean {
        return locationManager.hasFineLocationPermission()
    }
    
    /**
     * 检查 GMS (Google Play Services) 是否可用
     */
    fun isGmsAvailable(): Boolean {
        return locationManager.isGmsAvailable()
    }
    
    /**
     * 检查 GPS 是否开启
     */
    fun isGpsEnabled(): Boolean {
        return locationManager.isGpsEnabled()
    }
    
    /**
     * 检查是否有任何定位服务可用
     */
    fun isLocationServiceEnabled(): Boolean {
        return locationManager.isLocationServiceEnabled()
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
 * 
 * @param message 默认错误消息（用于日志等场景）
 * @param code 错误码
 * @param messageResId 本地化消息资源ID（用于多语言场景）
 */
sealed class EasyLocationError(
    val message: String, 
    val code: Int,
    val messageResId: Int
) {
    
    /** 权限被拒绝（用户本次拒绝，可以再次申请） */
    object PermissionDenied : 
        EasyLocationError("Permission denied", CODE_PERMISSION_DENIED, R.string.location_error_permission_denied)
    
    /** 权限被永久拒绝（用户选择了"不再询问"，需要去设置中开启） */
    object PermissionPermanentlyDenied : 
        EasyLocationError("Permission permanently denied", CODE_PERMISSION_PERMANENTLY_DENIED, R.string.location_error_permission_permanently_denied)
    
    /** 要求精确定位但用户只授予了模糊定位 */
    object FineLocationRequired : 
        EasyLocationError("Fine location required", CODE_FINE_LOCATION_REQUIRED, R.string.location_error_fine_location_required)
    
    /** GMS 精确定位开关被拒绝 */
    object GmsAccuracyDenied : 
        EasyLocationError("GMS accuracy denied", CODE_GMS_ACCURACY_DENIED, R.string.location_error_gms_accuracy_denied)
    
    /** 定位服务未开启 */
    object LocationDisabled : 
        EasyLocationError("Location disabled", CODE_LOCATION_DISABLED, R.string.location_error_location_disabled)
    
    /** 定位失败 */
    class LocationFailed(val originalError: LocationError) : 
        EasyLocationError("Location failed: ${originalError.message}", CODE_LOCATION_FAILED, R.string.location_error_location_failed)
    
    /** Activity 已销毁 */
    object ActivityDestroyed : 
        EasyLocationError("Activity destroyed", CODE_ACTIVITY_DESTROYED, R.string.location_error_activity_destroyed)
    
    /** 已有请求在处理中 */
    object AlreadyProcessing : 
        EasyLocationError("Already processing", CODE_ALREADY_PROCESSING, R.string.location_error_already_processing)
    
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
     * 获取本地化错误消息
     * 
     * @param context Context 用于获取资源
     * @return 本地化的错误消息
     */
    fun getLocalizedMessage(context: android.content.Context): String {
        return context.getString(messageResId)
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
