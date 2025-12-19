# EasyLocationClient 接口文档

## 1. 简介
`EasyLocationClient` 是一个封装了完整定位流程的一站式定位客户端。它内部集成了权限申请、GMS 服务检查、定位开关检测以及定位策略选择，旨在通过简单的接口调用完成复杂的定位过程。

## 2. 接口使用方式
`EasyLocationClient` 提供了两种 `getLocation` 重载方法，分别通过 **默认配置** 和 **自定义配置** 来获取定位。

### 方式一：默认配置（一键定位）
最简单的调用方式，使用默认参数进行定位。
- **默认配置**：
  - `requireFineLocation`: `false` (接受模糊定位权限)
  - `timeoutMillis`: `30000ms` (30秒超时)

**代码示例**：
```kotlin
val client = EasyLocationClient(activity)

client.getLocation(object : EasyLocationCallback {
    override fun onSuccess(location: LocationData) {
        // 定位成功
        // location.latitude, location.longitude
    }
    
    override fun onError(error: EasyLocationError) {
        // 定位失败，处理 error.message
    }
})
```

### 方式二：自定义配置
允许调用者指定是否强制要求精确定位权限以及超时时间。

**参数说明**：
- `requireFineLocation` (Boolean): 
  - `true`: 强制要求 **精确** 定位权限。如果用户只授予模糊权限，会返回 `FineLocationRequired` 错误。
  - `false`: **任意** 定位权限（精确或模糊）均可。
- `timeoutMillis` (Long): 定位超时时间（毫秒）。
- `callback` (EasyLocationCallback): 结果回调。

**代码示例**：
```kotlin
client.getLocation(
    requireFineLocation = true, // 强制要求精确定位
    timeoutMillis = 15000L,     // 15秒超时
    callback = object : EasyLocationCallback {
        override fun onSuccess(location: LocationData) {
            // 获取到精确定位
        }
        
        override fun onError(error: EasyLocationError) {
            if (error is EasyLocationError.FineLocationRequired) {
                // 用户只给了模糊定位，提示用户去设置
            }
        }
    }
)
```

## 3. 定位策略 (Strategy)

`EasyLocationClient` 内部通过 `SimpleLocationManager` 实现了智能的定位策略，确保在不同设备环境下都能尽可能获取位置。

### 3.1 GMS 优先策略
1. **优先尝试 GMS (Google Mobile Services)**：
   - 如果设备支持 GMS 且服务可用，优先使用 `FusedLocationProviderClient` 进行定位。
   - 优点：融合了 GPS、WiFi、基站等多种信号，定位速度快、精度高、耗电低。

2. **自动降级 (Fallback)**：
   - 如果 GMS 不可用（如国产非 GMS 手机）或 GMS 定位失败/超时，系统会自动切换到 **原生 GPS/Network** 定位。
   - **原生定位**：同时请求 GPS 和 Network Provider，取最快返回的有效位置。

### 3.2 高精度模式
- 默认请求 `Priority.HIGH_ACCURACY`（高精度）模式。
- 在不需要强制精确定位权限的情况下（`requireFineLocation=false`），也会尝试获取尽可能高的精度。

## 4. 交互与行为 (Interaction)

`EasyLocationClient` 将繁琐的交互逻辑封装在内部，自动处理以下场景：

### 4.1 智能权限申请
- **自动检查**：调用时自动检查是否有必要权限。
- **动态申请**：如果没有权限，会自动发起系统权限申请请求。
- **降级处理**：
  - 如果 `requireFineLocation=true` 但用户只给了模糊权限 -> **报错**（提示需要精确权限）。
  - 如果 `requireFineLocation=false` 且用户给了模糊权限 -> **继续执行**（接受模糊位置）。

### 4.2 GMS 定位开关检测
- **自动弹窗解决**：如果 GMS 可用但位置精度设置（Google Location Accuracy）未开启，会自动尝试弹出系统对话框（`ResolvableApiException`），请求用户一键开启（无需跳转设置页）。
- **结果处理**：用户点击"同意"后自动重试定位；点击"拒绝"则返回错误。

### 4.3 错误引导
当遇到无法自动解决的错误时，会返回特定的错误类型，UI 层可据此通过弹窗引导用户跳转系统设置：
- `PermissionPermanentlyDenied`: 权限被永久拒绝（勾选了不再询问）-> 需引导去应用设置页。
- `LocationDisabled`: 系统定位总开关未开启 -> 需引导去系统定位设置页。

### 4.4 生命周期感知
- 内部持有 Activity 的 `WeakReference`，防止内存泄漏。
- 在回调前检查 Activity 状态（是否销毁/正在结束），避免在页面销毁后操作 UI 导致的 Crash。
