package com.iki.simplelocation;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.iki.location.EasyLocationCallback;
import com.iki.location.EasyLocationClient;
import com.iki.location.EasyLocationError;
import com.iki.location.SimpleLocationManager;
import com.iki.location.callback.PermissionCallback;
import com.iki.location.callback.SingleLocationCallback;
import com.iki.location.model.LocationData;
import com.iki.location.model.LocationError;
import com.iki.location.model.LocationRequest;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.GlobalScope;

/**
 * 定位 SDK 测试 Activity (Java 版本)
 */
public class LocationTestActivity extends AppCompatActivity {

    private static final int REQUEST_CHECK_SETTINGS = 10010;

    private SimpleLocationManager locationManager;
    private EasyLocationClient easyLocationClient;

    // UI 组件
    private TextView tvStatusPermission;
    private TextView tvStatusFinePermission;
    private TextView tvStatusGms;
    private TextView tvStatusGps;
    private TextView tvStatusLocation;
    private TextView tvStatusGmsAccuracy;
    private TextView tvLogs;
    private Button btnEasyLocation;
    private Button btnSingleLocation;

    private StringBuilder logsBuilder = new StringBuilder();
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    private boolean isLocating = false;
    private boolean isEasyLocating = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_location_test);

        // 初始化 SDK
        locationManager = SimpleLocationManager.Companion.getInstance(this);
        easyLocationClient = new EasyLocationClient(this);

        // 初始化 UI
        initViews();
        setupClickListeners();
        updateStatus();
    }

    private void initViews() {
        tvStatusPermission = findViewById(R.id.tvStatusPermission);
        tvStatusFinePermission = findViewById(R.id.tvStatusFinePermission);
        tvStatusGms = findViewById(R.id.tvStatusGms);
        tvStatusGps = findViewById(R.id.tvStatusGps);
        tvStatusLocation = findViewById(R.id.tvStatusLocation);
        tvStatusGmsAccuracy = findViewById(R.id.tvStatusGmsAccuracy);
        tvLogs = findViewById(R.id.tvLogs);
        btnEasyLocation = findViewById(R.id.btnEasyLocation);
        btnSingleLocation = findViewById(R.id.btnSingleLocation);
    }

    private void setupClickListeners() {
        // 一键定位
        btnEasyLocation.setOnClickListener(v -> {
            if (isEasyLocating) return;
            
            isEasyLocating = true;
            btnEasyLocation.setEnabled(false);
            btnEasyLocation.setText("一键定位中...");
            
            long startTime = System.currentTimeMillis();
            addLog("🚀 一键定位开始...");
            addLog("   自动处理: 权限申请 → GMS精确定位检测 → 定位");

            LocationRequest request = new LocationRequest(
                10000L, 5000L,
                LocationRequest.Priority.HIGH_ACCURACY,
                15000L, 0f, false
            );

            easyLocationClient.getLocation(request, new EasyLocationCallback() {
                @Override
                public void onSuccess(@NonNull LocationData location) {
                    long costTime = System.currentTimeMillis() - startTime;
                    isEasyLocating = false;
                    btnEasyLocation.setEnabled(true);
                    btnEasyLocation.setText("🚀 一键定位 (自动处理权限+GMS开关)");
                    
                    addLog("✅ 一键定位成功! 耗时: " + costTime + "ms");
                    addLog("   来源: " + location.getProvider());
                    addLog("   经度: " + location.getLongitude());
                    addLog("   纬度: " + location.getLatitude());
                    addLog("   精度: " + location.getAccuracy() + "m");
                    updateStatus();
                }

                @Override
                public void onError(@NonNull EasyLocationError error) {
                    long costTime = System.currentTimeMillis() - startTime;
                    isEasyLocating = false;
                    btnEasyLocation.setEnabled(true);
                    btnEasyLocation.setText("🚀 一键定位 (自动处理权限+GMS开关)");
                    
                    addLogError("❌ 一键定位失败! 耗时: " + costTime + "ms");
                    addLogError("   错误码: " + error.getCode());
                    addLogError("   错误: " + error.getMessage());
                    
                    if (error instanceof EasyLocationError.PermissionDenied) {
                        EasyLocationError.PermissionDenied permError = (EasyLocationError.PermissionDenied) error;
                        if (permError.getPermanentlyDenied()) {
                            addLogError("   💡 提示: 权限被永久拒绝，请到设置中开启");
                        }
                    } else if (error instanceof EasyLocationError.GmsAccuracyDenied) {
                        addLogError("   💡 提示: 用户拒绝开启精确定位");
                    } else if (error instanceof EasyLocationError.LocationDisabled) {
                        addLogError("   💡 提示: 请开启定位服务");
                    }
                    updateStatus();
                }
            });
        });

        // 请求权限
        findViewById(R.id.btnRequestPermission).setOnClickListener(v -> {
            addLog("请求定位权限...");
            locationManager.requestLocationPermission(this, new PermissionCallback() {
                @Override
                public void onPermissionGranted(@NonNull List<String> permissions) {
                    addLog("✅ 权限已授予: " + permissions);
                    updateStatus();
                }

                @Override
                public void onPermissionDenied(@NonNull List<String> deniedPermissions, boolean permanentlyDenied) {
                    addLogError("❌ 权限被拒绝: " + deniedPermissions + ", 永久拒绝: " + permanentlyDenied);
                    updateStatus();
                }
            });
        });

        // 检查 GMS 精确定位开关
        findViewById(R.id.btnCheckGmsAccuracy).setOnClickListener(v -> {
            addLog("检查 GMS 精确定位开关...");
            checkGmsAccuracyAsync();
        });

        // 请求开启 GMS 精确定位
        findViewById(R.id.btnEnableGmsAccuracy).setOnClickListener(v -> {
            if (!locationManager.isGmsAvailable()) {
                addLogError("❌ GMS 不可用，无法请求开启精确定位");
                return;
            }
            addLog("请求开启 GMS 精确定位开关...");
            requestEnableGmsAccuracy();
        });

        // 单次定位
        btnSingleLocation.setOnClickListener(v -> {
            if (!locationManager.hasLocationPermission()) {
                addLogError("❌ 请先授予定位权限");
                return;
            }
            if (isLocating) return;

            isLocating = true;
            btnSingleLocation.setEnabled(false);
            btnSingleLocation.setText("定位中...");

            long startTime = System.currentTimeMillis();
            addLog("开始单次定位...");

            LocationRequest request = new LocationRequest(
                10000L, 5000L,
                LocationRequest.Priority.HIGH_ACCURACY,
                15000L, 0f, false
            );

            locationManager.getLocation(request, new SingleLocationCallback() {
                @Override
                public void onLocationSuccess(@NonNull LocationData location) {
                    long costTime = System.currentTimeMillis() - startTime;
                    isLocating = false;
                    btnSingleLocation.setEnabled(true);
                    btnSingleLocation.setText("3️⃣ 单次定位 (GMS优先，GPS兜底)");
                    
                    addLog("✅ 定位成功! 耗时: " + costTime + "ms");
                    addLog("   来源: " + location.getProvider());
                    addLog("   经度: " + location.getLongitude());
                    addLog("   纬度: " + location.getLatitude());
                    addLog("   精度: " + location.getAccuracy() + "m");
                }

                @Override
                public void onLocationError(@NonNull LocationError error) {
                    long costTime = System.currentTimeMillis() - startTime;
                    isLocating = false;
                    btnSingleLocation.setEnabled(true);
                    btnSingleLocation.setText("3️⃣ 单次定位 (GMS优先，GPS兜底)");
                    
                    addLogError("❌ 定位失败! 耗时: " + costTime + "ms");
                    addLogError("   错误: " + error.getMessage());
                }
            });
        });

        // 获取最后已知位置
        findViewById(R.id.btnLastLocation).setOnClickListener(v -> {
            addLog("获取最后已知位置...");
            getLastKnownLocationAsync();
        });

        // 定位设置
        findViewById(R.id.btnLocationSettings).setOnClickListener(v -> {
            addLog("打开定位设置...");
            locationManager.openLocationSettings();
        });

        // 应用设置
        findViewById(R.id.btnAppSettings).setOnClickListener(v -> {
            addLog("打开应用设置...");
            locationManager.openAppSettings();
        });

        // 清除日志
        findViewById(R.id.btnClearLogs).setOnClickListener(v -> {
            logsBuilder = new StringBuilder();
            tvLogs.setText("暂无日志");
        });
    }

    private void checkGmsAccuracyAsync() {
        new Thread(() -> {
            try {
                // 使用 runBlocking 调用挂起函数
                Object result = BuildersKt.runBlocking(
                    EmptyCoroutineContext.INSTANCE,
                    (scope, continuation) -> locationManager.isGoogleLocationAccuracyEnabled(continuation)
                );
                boolean isEnabled = result != null && (Boolean) result;
                
                runOnUiThread(() -> {
                    if (isEnabled) {
                        addLog("✅ GMS 精确定位已开启");
                    } else {
                        addLog("⚠️ GMS 精确定位未开启");
                    }
                    updateStatus();
                });
            } catch (Exception e) {
                runOnUiThread(() -> addLogError("检查失败: " + e.getMessage()));
            }
        }).start();
    }

    private void getLastKnownLocationAsync() {
        new Thread(() -> {
            try {
                Object result = BuildersKt.runBlocking(
                    EmptyCoroutineContext.INSTANCE,
                    (scope, continuation) -> locationManager.getLastKnownLocation(continuation)
                );
                LocationData location = (LocationData) result;
                
                runOnUiThread(() -> {
                    if (location != null) {
                        addLog("✅ 最后已知位置:");
                        addLog("   来源: " + location.getProvider());
                        addLog("   经度: " + location.getLongitude());
                        addLog("   纬度: " + location.getLatitude());
                    } else {
                        addLog("⚠️ 没有最后已知位置");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> addLogError("获取失败: " + e.getMessage()));
            }
        }).start();
    }

    private void requestEnableGmsAccuracy() {
        new Thread(() -> {
            try {
                Object result = BuildersKt.runBlocking(
                    EmptyCoroutineContext.INSTANCE,
                    (scope, continuation) -> locationManager.checkLocationSettings(
                        new LocationRequest(10000L, 5000L, LocationRequest.Priority.HIGH_ACCURACY, 15000L, 0f, false),
                        continuation
                    )
                );
                
                runOnUiThread(() -> {
                    if (result instanceof SimpleLocationManager.LocationSettingsResult.Satisfied) {
                        addLog("✅ GMS 位置设置已满足要求");
                    } else if (result instanceof SimpleLocationManager.LocationSettingsResult.Resolvable) {
                        SimpleLocationManager.LocationSettingsResult.Resolvable resolvable = 
                            (SimpleLocationManager.LocationSettingsResult.Resolvable) result;
                        resolvable.startResolutionForResult(this, REQUEST_CHECK_SETTINGS);
                    } else if (result instanceof SimpleLocationManager.LocationSettingsResult.LocationDisabled) {
                        addLogError("❌ 定位服务未开启");
                    } else {
                        addLogError("❌ 需要权限");
                    }
                    updateStatus();
                });
            } catch (Exception e) {
                runOnUiThread(() -> addLogError("请求失败: " + e.getMessage()));
            }
        }).start();
    }

    private void updateStatus() {
        tvStatusPermission.setText("定位权限: " + (locationManager.hasLocationPermission() ? "✅" : "❌"));
        tvStatusFinePermission.setText("精确定位权限: " + (locationManager.hasFineLocationPermission() ? "✅" : "❌"));
        tvStatusGms.setText("GMS 可用: " + (locationManager.isGmsAvailable() ? "✅" : "❌"));
        tvStatusGps.setText("GPS 开启: " + (locationManager.isGpsEnabled() ? "✅" : "❌"));
        tvStatusLocation.setText("定位服务可用: " + (locationManager.isLocationServiceEnabled() ? "✅" : "❌"));
        // GMS 精确定位开关需要异步检查，这里暂时不更新
    }

    private void addLog(String message) {
        String time = timeFormat.format(new Date());
        String log = "[" + time + "] " + message + "\n";
        logsBuilder.append(log);
        tvLogs.setText(logsBuilder.toString());
    }

    private void addLogError(String message) {
        String time = timeFormat.format(new Date());
        String log = "[" + time + "] " + message + "\n";
        logsBuilder.append(log);
        
        // 简单处理，不使用 SpannableString
        tvLogs.setText(logsBuilder.toString());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        locationManager.onRequestPermissionsResult(requestCode, permissions, grantResults);
        easyLocationClient.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updateStatus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        easyLocationClient.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_CHECK_SETTINGS) {
            if (resultCode == Activity.RESULT_OK) {
                addLog("✅ 用户同意开启精确定位");
            } else {
                addLogError("⚠️ 用户拒绝开启精确定位");
            }
            updateStatus();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        locationManager.stopLocationUpdates();
        easyLocationClient.destroy();
    }
}

