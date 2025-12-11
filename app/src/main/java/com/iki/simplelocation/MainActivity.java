package com.iki.simplelocation;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 直接跳转到测试页面
        startActivity(new Intent(this, LocationTestActivity.class));
        finish();
    }
}

