package com.iki.simplelocation;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 主 Activity
 * 直接跳转到定位测试页面
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 直接跳转到定位测试页面
        Intent intent = new Intent(this, LocationTestActivity.class);
        startActivity(intent);
        finish();
    }
}

