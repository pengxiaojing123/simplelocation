package com.iki.simplelocation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iki.location.EasyLocationCallback
import com.iki.location.EasyLocationClient
import com.iki.location.EasyLocationError
import com.iki.location.SimpleLocationManager
import com.iki.location.callback.PermissionCallback
import com.iki.location.callback.SingleLocationCallback
import com.iki.location.model.LocationData
import com.iki.location.model.LocationError
import com.iki.location.model.LocationRequest
import com.iki.location.util.LocationServiceChecker
import com.iki.simplelocation.ui.theme.SimplelocationTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * 定位 SDK 测试 Activity
 * 
 * 测试功能:
 * 1. 权限检查与申请
 * 2. GMS 可用性检查
 * 3. GMS 精确定位开关检测与请求开启
 * 4. GPS 状态检查
 * 5. 单次定位（GMS 优先，GPS 兜底）
 * 6. 最后已知位置
 */
class LocationTestActivity : ComponentActivity() {
    
    companion object {
        private const val REQUEST_CHECK_SETTINGS = 10010
    }
    
    private lateinit var locationManager: SimpleLocationManager
    private lateinit var easyLocationClient: EasyLocationClient
    
    // 用于通知 Compose 设置检查结果
    private var onSettingsResult: ((Boolean) -> Unit)? = null
    
    // 用于一键定位的回调
    private var onEasyLocationResult: ((LocationData?, EasyLocationError?) -> Unit)? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        locationManager = SimpleLocationManager.getInstance(this)
        easyLocationClient = EasyLocationClient(this)
        
        setContent {
            SimplelocationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LocationTestScreen(
                        activity = this,
                        locationManager = locationManager,
                        onRequestPermission = { callback ->
                            locationManager.requestLocationPermission(this, callback)
                        },
                        onRequestEnableGmsAccuracy = { resultCallback ->
                            onSettingsResult = resultCallback
                            requestEnableGmsAccuracy()
                        },
                        onEasyLocation = { request, resultCallback ->
                            onEasyLocationResult = resultCallback
                            requestEasyLocation(request)
                        }
                    )
                }
            }
        }
    }
    
    /**
     * 一键定位（使用 EasyLocationClient）
     */
    private fun requestEasyLocation(request: LocationRequest) {
        easyLocationClient.getLocation(request, object : EasyLocationCallback {
            override fun onSuccess(location: LocationData) {
                onEasyLocationResult?.invoke(location, null)
                onEasyLocationResult = null
            }
            
            override fun onError(error: EasyLocationError) {
                onEasyLocationResult?.invoke(null, error)
                onEasyLocationResult = null
            }
        })
    }
    
    /**
     * 请求开启 GMS 精确定位开关
     */
    private fun requestEnableGmsAccuracy() {
        kotlinx.coroutines.MainScope().launch {
            when (val result = locationManager.checkLocationSettings()) {
                is SimpleLocationManager.LocationSettingsResult.Satisfied -> {
                    onSettingsResult?.invoke(true)
                }
                is SimpleLocationManager.LocationSettingsResult.Resolvable -> {
                    // 弹出系统对话框请求用户开启
                    result.startResolutionForResult(this@LocationTestActivity, REQUEST_CHECK_SETTINGS)
                }
                is SimpleLocationManager.LocationSettingsResult.PermissionRequired -> {
                    onSettingsResult?.invoke(false)
                }
                is SimpleLocationManager.LocationSettingsResult.LocationDisabled -> {
                    onSettingsResult?.invoke(false)
                }
            }
        }
    }
    
    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        locationManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
        easyLocationClient.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        // 处理 EasyLocationClient 的回调
        easyLocationClient.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            REQUEST_CHECK_SETTINGS -> {
                // 用户响应了定位设置对话框
                val success = resultCode == Activity.RESULT_OK
                onSettingsResult?.invoke(success)
                onSettingsResult = null
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        locationManager.stopLocationUpdates()
        easyLocationClient.destroy()
        onSettingsResult = null
        onEasyLocationResult = null
    }
}

@Composable
fun LocationTestScreen(
    activity: Activity,
    locationManager: SimpleLocationManager,
    onRequestPermission: (PermissionCallback) -> Unit,
    onRequestEnableGmsAccuracy: ((Boolean) -> Unit) -> Unit,
    onEasyLocation: (LocationRequest, (LocationData?, EasyLocationError?) -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    
    // 状态
    var logs by remember { mutableStateOf(listOf<LogItem>()) }
    var isLocating by remember { mutableStateOf(false) }
    var isEasyLocating by remember { mutableStateOf(false) }
    var lastLocation by remember { mutableStateOf<LocationData?>(null) }
    var isCheckingSettings by remember { mutableStateOf(false) }
    var gmsAccuracyEnabled by remember { mutableStateOf<Boolean?>(null) }
    
    // 添加日志
    fun addLog(message: String, isError: Boolean = false) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        logs = logs + LogItem(time, message, isError)
    }
    
    // 初始化时检查 GMS 精确定位开关状态
    LaunchedEffect(Unit) {
        gmsAccuracyEnabled = locationManager.isGoogleLocationAccuracyEnabled()
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        // 标题
        Text(
            text = "📍 定位 SDK 测试",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // 状态卡片
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("状态检查", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
                
                StatusRow("定位权限", locationManager.hasLocationPermission())
                StatusRow("精确定位权限", locationManager.hasFineLocationPermission())
                StatusRow("GMS 可用", locationManager.isGmsAvailable())
                StatusRow("GPS 开启", locationManager.isGpsEnabled())
                StatusRow("定位服务可用", locationManager.isLocationServiceEnabled())
                StatusRow("GMS 精确定位开关", gmsAccuracyEnabled ?: false)
            }
        }
        
        // 操作按钮
        Text("操作", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(8.dp))
        
        // 请求权限
        Button(
            onClick = {
                addLog("请求定位权限...")
                onRequestPermission(object : PermissionCallback {
                    override fun onPermissionGranted(permissions: List<String>) {
                        addLog("✅ 权限已授予: $permissions")
                    }
                    
                    override fun onPermissionDenied(deniedPermissions: List<String>, permanentlyDenied: Boolean) {
                        addLog("❌ 权限被拒绝: $deniedPermissions, 永久拒绝: $permanentlyDenied", true)
                    }
                })
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("1️⃣ 请求定位权限")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 检查 GMS 精确定位开关
        Button(
            onClick = {
                scope.launch {
                    addLog("检查 GMS 精确定位开关...")
                    val isEnabled = locationManager.isGoogleLocationAccuracyEnabled()
                    gmsAccuracyEnabled = isEnabled
                    addLog(if (isEnabled) "✅ GMS 精确定位已开启" else "⚠️ GMS 精确定位未开启")
                    
                    // 同时获取完整的定位服务状态
                    val status = LocationServiceChecker.getLocationServiceStatus(activity)
                    addLog("📊 完整状态:")
                    addLog("   GMS版本: ${status.gmsVersion}")
                    addLog("   GPS: ${if (status.isGpsEnabled) "✅" else "❌"}")
                    addLog("   网络定位: ${if (status.isNetworkLocationEnabled) "✅" else "❌"}")
                    addLog("   精确定位: ${if (status.isGoogleLocationAccuracyEnabled) "✅" else "❌"}")
                    addLog("   推荐策略: ${status.getRecommendedStrategy()}")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("2️⃣ 检查 GMS 精确定位开关")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 请求开启 GMS 精确定位开关
        Button(
            onClick = {
                if (!locationManager.isGmsAvailable()) {
                    addLog("❌ GMS 不可用，无法请求开启精确定位", true)
                    return@Button
                }
                
                isCheckingSettings = true
                addLog("请求开启 GMS 精确定位开关...")
                
                onRequestEnableGmsAccuracy { success ->
                    isCheckingSettings = false
                    if (success) {
                        addLog("✅ 用户同意开启精确定位")
                        scope.launch {
                            gmsAccuracyEnabled = locationManager.isGoogleLocationAccuracyEnabled()
                        }
                    } else {
                        addLog("⚠️ 用户拒绝开启精确定位或定位服务未开启", true)
                    }
                }
            },
            enabled = !isCheckingSettings,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (gmsAccuracyEnabled == true) 
                    MaterialTheme.colorScheme.secondary 
                else 
                    MaterialTheme.colorScheme.error
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isCheckingSettings) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                if (gmsAccuracyEnabled == true) 
                    "2️⃣.1 GMS 精确定位已开启 ✓" 
                else 
                    "2️⃣.1 请求开启 GMS 精确定位开关"
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 单次定位
        Button(
            onClick = {
                if (!locationManager.hasLocationPermission()) {
                    addLog("❌ 请先授予定位权限", true)
                    return@Button
                }
                
                isLocating = true
                val startTime = System.currentTimeMillis()
                addLog("开始单次定位...")
                
                locationManager.getLocation(
                    request = LocationRequest(timeoutMillis = 15000),
                    callback = object : SingleLocationCallback {
                        override fun onLocationSuccess(location: LocationData) {
                            val costTime = System.currentTimeMillis() - startTime
                            isLocating = false
                            lastLocation = location
                            addLog("✅ 定位成功! 耗时: ${costTime}ms")
                            addLog("   来源: ${location.provider}")
                            addLog("   经度: ${location.longitude}")
                            addLog("   纬度: ${location.latitude}")
                            addLog("   精度: ${location.accuracy}m")
                            addLog("   时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(location.timestamp))}")
                        }
                        
                        override fun onLocationError(error: LocationError) {
                            val costTime = System.currentTimeMillis() - startTime
                            isLocating = false
                            addLog("❌ 定位失败! 耗时: ${costTime}ms", true)
                            addLog("   错误: ${error.message}", true)
                        }
                    }
                )
            },
            enabled = !isLocating,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLocating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (isLocating) "定位中..." else "3️⃣ 单次定位 (GMS优先，GPS兜底)")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 获取最后已知位置
        Button(
            onClick = {
                scope.launch {
                    addLog("获取最后已知位置...")
                    val location = locationManager.getLastKnownLocation()
                    if (location != null) {
                        addLog("✅ 最后已知位置:")
                        addLog("   来源: ${location.provider}")
                        addLog("   经度: ${location.longitude}")
                        addLog("   纬度: ${location.latitude}")
                    } else {
                        addLog("⚠️ 没有最后已知位置")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("4️⃣ 获取最后已知位置")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 一键定位（推荐使用）
        Text("⭐ 推荐接口", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = {
                isEasyLocating = true
                val startTime = System.currentTimeMillis()
                addLog("🚀 一键定位开始...")
                addLog("   自动处理: 权限申请 → GMS精确定位检测 → 定位")
                
                onEasyLocation(LocationRequest(timeoutMillis = 15000)) { location, error ->
                    val costTime = System.currentTimeMillis() - startTime
                    isEasyLocating = false
                    
                    if (location != null) {
                        lastLocation = location
                        addLog("✅ 一键定位成功! 耗时: ${costTime}ms")
                        addLog("   来源: ${location.provider}")
                        addLog("   经度: ${location.longitude}")
                        addLog("   纬度: ${location.latitude}")
                        addLog("   精度: ${location.accuracy}m")
                    } else if (error != null) {
                        addLog("❌ 一键定位失败! 耗时: ${costTime}ms", true)
                        addLog("   错误码: ${error.code}", true)
                        addLog("   错误: ${error.message}", true)
                        
                        when (error) {
                            is EasyLocationError.PermissionDenied -> {
                                if (error.permanentlyDenied) {
                                    addLog("   💡 提示: 权限被永久拒绝，请到设置中开启", true)
                                }
                            }
                            is EasyLocationError.GmsAccuracyDenied -> {
                                addLog("   💡 提示: 用户拒绝开启精确定位", true)
                            }
                            is EasyLocationError.LocationDisabled -> {
                                addLog("   💡 提示: 请开启定位服务", true)
                            }
                            else -> {}
                        }
                    }
                }
            },
            enabled = !isEasyLocating,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isEasyLocating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                if (isEasyLocating) "一键定位中..." else "🚀 一键定位 (自动处理权限+GMS开关)",
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 打开定位设置
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    locationManager.openLocationSettings()
                    addLog("打开定位设置...")
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("定位设置")
            }
            
            Button(
                onClick = {
                    locationManager.openAppSettings()
                    addLog("打开应用设置...")
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("应用设置")
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 清除日志
        OutlinedButton(
            onClick = { logs = emptyList() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("🗑️ 清除日志")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 日志区域
        Text("日志", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(8.dp))
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth()
            ) {
                if (logs.isEmpty()) {
                    Text(
                        "暂无日志",
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                } else {
                    logs.forEach { log ->
                        Text(
                            text = "[${log.time}] ${log.message}",
                            color = if (log.isError) Color.Red else Color.Unspecified,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun StatusRow(label: String, enabled: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Text(
            text = if (enabled) "✅" else "❌",
            fontSize = 16.sp
        )
    }
}

data class LogItem(
    val time: String,
    val message: String,
    val isError: Boolean = false
)

