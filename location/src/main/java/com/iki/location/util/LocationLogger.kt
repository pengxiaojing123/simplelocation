package com.iki.location.util

import android.util.Log

/**
 * 定位 SDK 日志工具类
 * 
 * 提供统一的日志输出控制，可通过 [isEnabled] 开关控制日志输出
 * 
 * 使用示例:
 * ```kotlin
 * // 开启日志（默认开启）
 * LocationLogger.isEnabled = true
 * 
 * // 关闭日志
 * LocationLogger.isEnabled = false
 * 
 * // 输出日志
 * LocationLogger.d("这是调试日志")
 * LocationLogger.e("这是错误日志")
 * ```
 */
object LocationLogger {
    
    /** 日志 TAG */
    const val TAG = "mylocation"
    
    /** 是否启用日志输出，默认开启 */
    @JvmStatic
    var isEnabled: Boolean = true
    
    /**
     * Debug 级别日志
     */
    @JvmStatic
    fun d(message: String) {
        if (isEnabled) {
            Log.d(TAG, message)
        }
    }
    
    /**
     * Debug 级别日志（带自定义 TAG）
     */
    @JvmStatic
    fun d(tag: String, message: String) {
        if (isEnabled) {
            Log.d(tag, message)
        }
    }
    
    /**
     * Info 级别日志
     */
    @JvmStatic
    fun i(message: String) {
        if (isEnabled) {
            Log.i(TAG, message)
        }
    }
    
    /**
     * Info 级别日志（带自定义 TAG）
     */
    @JvmStatic
    fun i(tag: String, message: String) {
        if (isEnabled) {
            Log.i(tag, message)
        }
    }
    
    /**
     * Warning 级别日志
     */
    @JvmStatic
    fun w(message: String) {
        if (isEnabled) {
            Log.w(TAG, message)
        }
    }
    
    /**
     * Warning 级别日志（带自定义 TAG）
     */
    @JvmStatic
    fun w(tag: String, message: String) {
        if (isEnabled) {
            Log.w(tag, message)
        }
    }
    
    /**
     * Error 级别日志
     */
    @JvmStatic
    fun e(message: String) {
        if (isEnabled) {
            Log.e(TAG, message)
        }
    }
    
    /**
     * Error 级别日志（带自定义 TAG）
     */
    @JvmStatic
    fun e(tag: String, message: String) {
        if (isEnabled) {
            Log.e(tag, message)
        }
    }
    
    /**
     * Error 级别日志（带异常）
     */
    @JvmStatic
    fun e(message: String, throwable: Throwable) {
        if (isEnabled) {
            Log.e(TAG, message, throwable)
        }
    }
    
    /**
     * Error 级别日志（带自定义 TAG 和异常）
     */
    @JvmStatic
    fun e(tag: String, message: String, throwable: Throwable) {
        if (isEnabled) {
            Log.e(tag, message, throwable)
        }
    }
    
    /**
     * Verbose 级别日志
     */
    @JvmStatic
    fun v(message: String) {
        if (isEnabled) {
            Log.v(TAG, message)
        }
    }
    
    /**
     * Verbose 级别日志（带自定义 TAG）
     */
    @JvmStatic
    fun v(tag: String, message: String) {
        if (isEnabled) {
            Log.v(tag, message)
        }
    }
}

