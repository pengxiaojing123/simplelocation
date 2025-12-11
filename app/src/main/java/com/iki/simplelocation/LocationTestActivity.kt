package com.iki.simplelocation

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.LinearLayout
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.graphics.Color
import android.util.TypedValue
import com.iki.location.SimpleLocationManager
import com.iki.location.callback.PermissionCallback
import com.iki.location.callback.SingleLocationCallback
import com.iki.location.model.LocationData
import com.iki.location.model.LocationError
import com.iki.location.model.LocationRequest
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 定位 SDK 测试 Activity
 */
class LocationTestActivity : Activity(), CoroutineScope {
    
    private val job = SupervisorJob()
    override val coroutineContext get() = Dispatchers.Main + job
    
    private lateinit var locationManager: SimpleLocationManager
    private lateinit var logTextView: TextView
    private lateinit var statusTextView: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        locationManager = SimpleLocationManager.getInstance(this)
        
        // 创建 UI
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        // 标题
        root.addView(TextView(this).apply {
            text = "📍 定位 SDK 测试"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(Color.BLACK)
        })
        
        // 状态显示
        statusTextView = TextView(this).apply {
            text = "状态加载中..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 16, 0, 16)
        }
        root.addView(statusTextView)
        
        // 按钮1: 请求权限
        root.addView(createButton("1️⃣ 请求定位权限") {
            requestPermission()
        })
        
        // 按钮2: 检查 GMS
        root.addView(createButton("2️⃣ 检查 GMS 精确定位开关") {
            checkGmsAccuracy()
        })
        
        // 按钮3: 单次定位
        root.addView(createButton("3️⃣ 单次定位 (GMS优先，GPS兜底)") {
            getLocation()
        })
        
        // 按钮4: 获取最后位置
        root.addView(createButton("4️⃣ 获取最后已知位置") {
            getLastLocation()
        })
        
        // 按钮5: 打开设置
        root.addView(createButton("⚙️ 打开定位设置") {
            locationManager.openLocationSettings()
            log("打开定位设置...")
        })
        
        // 按钮6: 清除日志
        root.addView(createButton("🗑️ 清除日志") {
            logTextView.text = ""
        })
        
        // 日志标题
        root.addView(TextView(this).apply {
            text = "日志:"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.BLACK)
            setPadding(0, 24, 0, 8)
        })
        
        // 日志显示区域
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }
        
        logTextView = TextView(this).apply {
            setPadding(16, 16, 16, 16)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        scrollView.addView(logTextView)
        root.addView(scrollView)
        
        setContentView(root)
        
        // 更新状态
        updateStatus()
    }
    
    private fun createButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = 8
            }
            setOnClickListener { onClick() }
        }
    }
    
    private fun updateStatus() {
        val sb = StringBuilder()
        sb.appendLine("━━━ 状态检查 ━━━")
        sb.appendLine("定位权限: ${if (locationManager.hasLocationPermission()) "✅" else "❌"}")
        sb.appendLine("精确定位权限: ${if (locationManager.hasFineLocationPermission()) "✅" else "❌"}")
        sb.appendLine("GMS 可用: ${if (locationManager.isGmsAvailable()) "✅" else "❌"}")
        sb.appendLine("GPS 开启: ${if (locationManager.isGpsEnabled()) "✅" else "❌"}")
        sb.appendLine("定位服务可用: ${if (locationManager.isLocationServiceEnabled()) "✅" else "❌"}")
        statusTextView.text = sb.toString()
    }
    
    private fun requestPermission() {
        log("请求定位权限...")
        locationManager.requestLocationPermission(this, object : PermissionCallback {
            override fun onPermissionGranted(permissions: List<String>) {
                log("✅ 权限已授予: $permissions")
                updateStatus()
            }
            
            override fun onPermissionDenied(deniedPermissions: List<String>, permanentlyDenied: Boolean) {
                log("❌ 权限被拒绝: $deniedPermissions, 永久拒绝: $permanentlyDenied")
                updateStatus()
            }
        })
    }
    
    private fun checkGmsAccuracy() {
        log("检查 GMS 精确定位开关...")
        launch {
            val isEnabled = locationManager.isGoogleLocationAccuracyEnabled()
            log(if (isEnabled) "✅ GMS 精确定位已开启" else "⚠️ GMS 精确定位未开启")
        }
    }
    
    private fun getLocation() {
        if (!locationManager.hasLocationPermission()) {
            log("❌ 请先授予定位权限")
            return
        }
        
        val startTime = System.currentTimeMillis()
        log("开始单次定位...")
        
        locationManager.getLocation(
            request = LocationRequest(timeoutMillis = 15000),
            callback = object : SingleLocationCallback {
                override fun onLocationSuccess(location: LocationData) {
                    val costTime = System.currentTimeMillis() - startTime
                    log("✅ 定位成功! 耗时: ${costTime}ms")
                    log("   来源: ${location.provider}")
                    log("   经度: ${location.longitude}")
                    log("   纬度: ${location.latitude}")
                    log("   精度: ${location.accuracy}m")
                    val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(Date(location.timestamp))
                    log("   时间: $timeStr")
                }
                
                override fun onLocationError(error: LocationError) {
                    val costTime = System.currentTimeMillis() - startTime
                    log("❌ 定位失败! 耗时: ${costTime}ms")
                    log("   错误: ${error.message}")
                }
            }
        )
    }
    
    private fun getLastLocation() {
        log("获取最后已知位置...")
        launch {
            val location = locationManager.getLastKnownLocation()
            if (location != null) {
                log("✅ 最后已知位置:")
                log("   来源: ${location.provider}")
                log("   经度: ${location.longitude}")
                log("   纬度: ${location.latitude}")
            } else {
                log("⚠️ 没有最后已知位置")
            }
        }
    }
    
    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        runOnUiThread {
            logTextView.append("[$time] $message\n")
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        locationManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
