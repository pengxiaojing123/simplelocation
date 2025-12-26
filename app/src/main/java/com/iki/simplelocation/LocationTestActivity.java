package com.iki.simplelocation;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ScrollView;
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
import com.iki.location.model.CachedLocation;
import com.iki.location.model.LocationData;
import com.iki.location.model.LocationError;
import com.iki.location.model.LocationRequest;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 定位功能测试页面
 */
public class LocationTestActivity extends AppCompatActivity {

    private static final String TAG = "mylocation";

    private SimpleLocationManager locationManager;
    private EasyLocationClient easyLocationClient;

    private TextView tvLog;
    private ScrollView scrollView;
    private Button btnEasyLocation;
    private Button btnEasyLocationFine;
    private Button btnGetCachedLocation;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_location_test);

        locationManager = SimpleLocationManager.getInstance(getApplicationContext());
        easyLocationClient = new EasyLocationClient(this);

        initViews();
        setupListeners();
        updateStatus();
    }

    private void initViews() {
        tvLog = findViewById(R.id.tvLog);
        scrollView = findViewById(R.id.scrollView);
        btnEasyLocation = findViewById(R.id.btnEasyLocation);
        btnEasyLocationFine = findViewById(R.id.btnEasyLocationFine);
        btnGetCachedLocation = findViewById(R.id.btnGetCachedLocation);
    }

    private void setupListeners() {
        // 一键定位（接受模糊定位）
        btnEasyLocation.setOnClickListener(v -> {
            addLog("🚀 开始一键定位（接受模糊定位）...");
            btnEasyLocation.setEnabled(false);
            btnEasyLocationFine.setEnabled(false);
            long startTime = System.currentTimeMillis();

            // requireFineLocation = false: 接受模糊定位
            // timeoutMillis = 15000: 15秒超时
            easyLocationClient.getLocation(false, 15000L, new EasyLocationCallback() {
                @Override
                public void onSuccess(@NonNull LocationData location) {
                    long costTime = System.currentTimeMillis() - startTime;
                    btnEasyLocation.setEnabled(true);
                    btnEasyLocationFine.setEnabled(true);
                    
                    addLog("✅ 定位成功! 耗时: " + costTime + "ms");
                    addLog("   来源: " + location.getProvider());
                    addLog("   经纬度: (" + location.getLatitude() + ", " + location.getLongitude() + ")");
                    addLog("   精度: " + location.getAccuracy() + "m");
                    updateStatus();
                }

                @Override
                public void onError(@NonNull EasyLocationError error) {
                    long costTime = System.currentTimeMillis() - startTime;
                    btnEasyLocation.setEnabled(true);
                    btnEasyLocationFine.setEnabled(true);
                    
                    addLogError("❌ 定位失败! 耗时: " + costTime + "ms");
                    addLogError("   错误码: " + error.getCode());
                    addLogError("   错误: " + error.getMessage());
                    handleError(error);
                    updateStatus();
                }
            });
        });

        // 一键定位（要求精确定位）
        btnEasyLocationFine.setOnClickListener(v -> {
            addLog("🎯 开始一键定位（要求精确定位）...");
            btnEasyLocation.setEnabled(false);
            btnEasyLocationFine.setEnabled(false);
            long startTime = System.currentTimeMillis();

            // requireFineLocation = true: 要求精确定位，模糊定位会报错
            // timeoutMillis = 15000: 15秒超时
            easyLocationClient.getLocation(true, 15000L, new EasyLocationCallback() {
                @Override
                public void onSuccess(@NonNull LocationData location) {
                    long costTime = System.currentTimeMillis() - startTime;
                    btnEasyLocation.setEnabled(true);
                    btnEasyLocationFine.setEnabled(true);
                    
                    addLog("✅ 精确定位成功! 耗时: " + costTime + "ms");
                    addLog("   来源: " + location.getProvider());
                    addLog("   经纬度: (" + location.getLatitude() + ", " + location.getLongitude() + ")");
                    addLog("   精度: " + location.getAccuracy() + "m");
                    updateStatus();
                }

                @Override
                public void onError(@NonNull EasyLocationError error) {
                    long costTime = System.currentTimeMillis() - startTime;
                    btnEasyLocation.setEnabled(true);
                    btnEasyLocationFine.setEnabled(true);
                    
                    addLogError("❌ 精确定位失败! 耗时: " + costTime + "ms");
                    addLogError("   错误码: " + error.getCode());
                    addLogError("   错误: " + error.getMessage());
                    handleError(error);
                    updateStatus();
                }
            });
        });

        // 获取缓存的定位数据
        btnGetCachedLocation.setOnClickListener(v -> {
            addLog("📦 获取缓存的定位数据...");
            
            CachedLocation cachedLocation = easyLocationClient.getLastLocation();
            
            if (cachedLocation != null) {
                addLog("✅ 找到缓存的定位数据:");
                addLog("   经纬度: (" + cachedLocation.getLatitude() + ", " + cachedLocation.getLongitude() + ")");
                addLog("   精度: " + cachedLocation.getAccuracy() + "m");
                addLog("   定位类型 (gps_type): " + cachedLocation.getGpsType());
                addLog("   定位时间戳 (gps_position_time): " + cachedLocation.getGpsPositionTime());
                addLog("   保存时老化时间 (gps_mills_old_when_saved): " + cachedLocation.getGpsMillsOldWhenSaved() + "ms");
                addLog("   当前老化时间: " + cachedLocation.getCurrentAgeMillis() + "ms");
                
                // 格式化定位时间
                String positionTime = timeFormat.format(new Date(cachedLocation.getGpsPositionTime()));
                addLog("   定位时间 (格式化): " + positionTime);
                
                // 检查是否过期（5分钟）
                boolean isExpired = cachedLocation.isExpired(5 * 60 * 1000);
                addLog("   是否过期 (>5分钟): " + (isExpired ? "⚠️ 是" : "✅ 否"));
            } else {
                addLogError("❌ 没有缓存的定位数据");
                addLogError("   💡 请先进行一次定位");
            }
        });

    }

    private void handleError(EasyLocationError error) {
        if (error instanceof EasyLocationError.PermissionPermanentlyDenied) {
            addLogError("   💡 提示: 权限被永久拒绝，请到设置中开启");
            addLogError("   💡 点击「应用设置」按钮前往开启");
        } else if (error instanceof EasyLocationError.PermissionDenied) {
            addLogError("   💡 提示: 权限被拒绝，请重试并授予权限");
        } else if (error instanceof EasyLocationError.FineLocationRequired) {
            addLogError("   💡 提示: 用户只授予了模糊定位，但此操作需要精确定位权限");
            addLogError("   💡 请到设置中将定位权限改为「精确」");
        } else if (error instanceof EasyLocationError.LocationDisabled) {
            addLogError("   💡 提示: 请开启设备定位服务");
        } else if (error instanceof EasyLocationError.GmsAccuracyDenied) {
            addLogError("   💡 提示: 用户拒绝开启 Google 精确定位");
        }
    }

    private void updateStatus() {
        TextView tvStatus = findViewById(R.id.tvStatus);
        StringBuilder sb = new StringBuilder();
        sb.append("权限: ").append(locationManager.hasLocationPermission() ? "✅" : "❌");
        sb.append(" | 精确权限: ").append(locationManager.hasFineLocationPermission() ? "✅" : "❌");
        sb.append(" | GMS: ").append(locationManager.isGmsAvailable() ? "✅" : "❌");
        sb.append(" | GPS: ").append(locationManager.isGpsEnabled() ? "✅" : "❌");
        tvStatus.setText(sb.toString());
    }

    private void addLog(String message) {
        String time = timeFormat.format(new Date());
        String log = "[" + time + "] " + message + "\n";
        tvLog.append(log);
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        Log.d(TAG, message);
    }

    private void addLogError(String message) {
        String time = timeFormat.format(new Date());
        String log = "[" + time + "] " + message + "\n";
        tvLog.append(log);
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        Log.e(TAG, message);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        locationManager.onRequestPermissionsResult(requestCode, permissions, grantResults);
        easyLocationClient.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        easyLocationClient.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        easyLocationClient.destroy();
    }
}

