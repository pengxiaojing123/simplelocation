# EasyLocationClient 接口文档

## 1. 简介
`EasyLocationClient` 是一个封装了完整定位流程的一站式定位客户端（单例模式）。它内部集成了权限申请、GMS 服务检查、定位开关检测、定位策略选择以及定位缓存，旨在通过简单的接口调用完成复杂的定位过程。

## 2. 快速开始

### 2.1 获取实例
```kotlin
// Kotlin
val client = EasyLocationClient.getInstance(activity)
```

```java
// Java
EasyLocationClient client = EasyLocationClient.getInstance(activity);
```

### 2.2 Activity 集成（必需）
需要在 Activity 中转发权限和设置结果：

```kotlin
// Kotlin
class YourActivity : AppCompatActivity() {
    private lateinit var locationClient: EasyLocationClient
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationClient = EasyLocationClient.getInstance(this)
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        locationClient.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        locationClient.onActivityResult(requestCode, resultCode, data)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        locationClient.destroy()
    }
}
```

```java
// Java
public class YourActivity extends AppCompatActivity {
    private EasyLocationClient locationClient;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        locationClient = EasyLocationClient.getInstance(this);
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        locationClient.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        locationClient.onActivityResult(requestCode, resultCode, data);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        locationClient.destroy();
    }
}
```

## 3. 接口使用方式
`EasyLocationClient` 提供了两种 `getLocation` 重载方法：

允许调用者指定是否强制要求精确定位权限以及超时时间。

**参数说明**：
| 参数 | 类型 | 说明 |
|------|------|------|
| `requireFineLocation` | Boolean | `true`: 强制要求精确定位权限；`false`: 接受模糊定位 |
| `timeoutMillis` | Long | 定位超时时间（毫秒） |
| `callback` | EasyLocationCallback | 结果回调 |

```kotlin
// Kotlin
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

```java
// Java
client.getLocation(true, 15000L, new EasyLocationCallback() {
    @Override
    public void onSuccess(LocationData location) {
        // 获取到精确定位
    }
    
    @Override
    public void onError(EasyLocationError error) {
        if (error instanceof EasyLocationError.FineLocationRequired) {
            // 用户只给了模糊定位，提示用户去设置
        }
    }
});
```

## 4. 定位缓存

定位成功后会自动缓存，支持持久化（应用重启后仍可读取）。

### 4.1 获取缓存的定位
```kotlin
// Kotlin
val cachedLocation = client.getLastLocation()
cachedLocation?.let {
    Log.d("Cache", """
        位置: (${it.latitude}, ${it.longitude})
        精度: ${it.accuracy}m
        类型: ${it.gpsType}
        定位时间: ${it.gpsPositionTime}
        数据年龄: ${it.getCurrentAgeMillis()}ms
        是否过期(5分钟): ${it.isExpired(5 * 60 * 1000)}
    """.trimIndent())
}
```

```java
// Java
CachedLocation cached = client.getLastLocation();
if (cached != null) {
    Log.d("Cache", "位置: (" + cached.getLatitude() + ", " + cached.getLongitude() + ")");
    Log.d("Cache", "数据年龄: " + cached.getCurrentAgeMillis() + "ms");
    Log.d("Cache", "是否过期: " + cached.isExpired(5 * 60 * 1000)); // 5分钟
}
```

### 4.2 缓存管理
```kotlin
// 检查是否有缓存
if (client.hasLocationCache()) {
    // 有缓存数据
}

// 清除缓存
client.clearLocationCache()
```

### 4.3 CachedLocation 属性
| 属性 | 类型 | 说明 |
|------|------|------|
| `latitude` | Double | 纬度 |
| `longitude` | Double | 经度 |
| `accuracy` | Float | 定位精度（米） |
| `gpsType` | String | 定位类型：`gps`, `network`, `fused`, `unknown` |
| `gpsPositionTime` | Long | 定位时间戳（毫秒，UTC） |
| `gpsMillsOldWhenSaved` | Long | **每次读取时动态计算**，表示从定位点产生到当前读取时刻的时间（毫秒） |

> **注意**：`gpsMillsOldWhenSaved` 会在每次调用 `getLastLocation()` 时重新计算，因此每次获取都会返回最新的数据年龄。

### 4.4 CachedLocation 方法
| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `getCurrentAgeMillis()` | Long | 获取当前数据年龄（毫秒），与 `gpsMillsOldWhenSaved` 等价 |
| `isExpired(maxAgeMillis)` | Boolean | 检查数据是否过期 |

## 5. 状态查询

```kotlin
// 检查是否有定位权限（模糊或精确）
val hasPermission = client.hasLocationPermission()

// 检查是否有精确定位权限
val hasFinePermission = client.hasFineLocationPermission()

// 检查 GMS (Google Play Services) 是否可用
val isGmsAvailable = client.isGmsAvailable()

// 检查 GPS 是否开启
val isGpsEnabled = client.isGpsEnabled()

// 检查是否有任何定位服务可用
val isServiceEnabled = client.isLocationServiceEnabled()
```

## 6. 定位策略 (Strategy)

### 6.1 GMS 优先策略
```
┌─────────────────────────────────────────────────────────────┐
│                    开始定位                                   │
└───────────────────────┬─────────────────────────────────────┘
                        ▼
              ┌─────────────────┐
              │  GMS 是否可用?   │
              └────────┬────────┘
                 是 ↙     ↘ 否
         ┌──────────────┐  ┌──────────────────┐
         │  使用 FusedLP │  │ 使用原生GPS/Network │
         └──────┬───────┘  └────────┬─────────┘
                ▼                   ▼
         定位成功？────否──→ 降级到原生定位
                │
               是
                ▼
            返回结果
```

1. **优先尝试 GMS (Google Mobile Services)**：
   - 使用 `FusedLocationProviderClient` + `getCurrentLocation()` 请求最新位置
   - 设置 `MaxUpdateAgeMillis = 0` 确保获取实时位置
   - 优点：融合 GPS、WiFi、基站信号，速度快、精度高、耗电低

2. **自动降级 (Fallback)**：
   - GMS 不可用或定位失败时，切换到原生 GPS/Network 定位
   - **HIGH_ACCURACY 模式**：同时请求 GPS 和 Network Provider，取最快返回的有效位置
   - Android R+ 使用 `getCurrentLocation()` API
   - 低版本使用 `requestSingleUpdate()` API

### 6.2 高精度模式
- 内部固定使用 `Priority.HIGH_ACCURACY` 模式
- 每次请求都获取最新实时位置，不使用系统缓存

## 7. 弹窗交互逻辑

SDK 会在定位流程中自动弹出必要的对话框引导用户，尽量减少开发者的手动处理。

### 7.1 弹窗流程总览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           调用 getLocation()                                 │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    ▼
                         ┌──────────────────────┐
                         │   检查定位权限        │
                         └──────────┬───────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
               无权限          有模糊权限        有精确权限
                    │               │               │
                    ▼               │               │
        ┌───────────────────┐       │               │
        │ 【系统权限弹窗】    │       │               │
        │  请求定位权限       │       │               │
        └─────────┬─────────┘       │               │
                  │                 │               │
       ┌──────────┴──────────┐      │               │
       ▼                     ▼      │               │
    用户授权              用户拒绝   │               │
       │                     │      │               │
       │    ┌────────────────┘      │               │
       │    ▼                       │               │
       │  永久拒绝？                 │               │
       │    │                       │               │
       │  是 ▼                      │               │
       │  ┌─────────────────────┐   │               │
       │  │【SDK权限引导弹窗】    │   │               │
       │  │ 引导去应用设置页      │   │               │
       │  └─────────┬───────────┘   │               │
       │            ▼               │               │
       │        返回错误             │               │
       │                            │               │
       ▼                            ▼               ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │                   检查是否需要精确定位                            │
  │              (requireFineLocation = true?)                       │
  └──────────────────────────────┬──────────────────────────────────┘
                                 │
              ┌──────────────────┴──────────────────┐
              ▼                                     ▼
        需要精确定位                           不需要精确定位
        但只有模糊权限                          (继续定位流程)
              │                                     │
              ▼                                     │
    系统是否弹出了选择框？                           │
              │                                     │
      是 ↙       ↘ 否                               │
         │         │                                │
         ▼         ▼                                │
     返回错误   ┌──────────────────────┐            │
               │【SDK精确定位引导弹窗】 │            │
               │ 引导去应用设置页       │            │
               └──────────┬───────────┘            │
                          ▼                        │
                      返回错误                      │
                                                   ▼
                                    ┌──────────────────────────┐
                                    │   检查 GMS 位置精度设置   │
                                    │   (仅 GMS 设备)           │
                                    └─────────────┬────────────┘
                                                  │
                                    ┌─────────────┴─────────────┐
                                    ▼                           ▼
                               设置满足要求                 设置不满足
                                    │                           │
                                    │                           ▼
                                    │           ┌───────────────────────────┐
                                    │           │【GMS系统设置弹窗】         │
                                    │           │ 请求开启"提高位置精确度"   │
                                    │           └─────────────┬─────────────┘
                                    │                         │
                                    │              ┌──────────┴──────────┐
                                    │              ▼                     ▼
                                    │           用户同意              用户拒绝
                                    │              │                     │
                                    │              │                     ▼
                                    │              │              返回 GmsAccuracyDenied
                                    │              │
                                    ▼              ▼
                              ┌─────────────────────────────┐
                              │        开始实际定位          │
                              └──────────────┬──────────────┘
                                             │
                                  ┌──────────┴──────────┐
                                  ▼                     ▼
                              定位成功               定位失败
                                  │                     │
                                  │           定位服务未开启？
                                  │                     │
                                  │              是 ↙   ↘ 否
                                  │                │      │
                                  │                ▼      ▼
                                  │   ┌─────────────────────────┐
                                  │   │【SDK定位服务引导弹窗】   │
                                  │   │ 引导去系统定位设置页     │
                                  │   └───────────┬─────────────┘
                                  │               ▼
                                  ▼           返回错误
                           onSuccess()
```

### 7.2 四种弹窗类型详解

#### 弹窗1️⃣：系统权限请求弹窗
| 项目 | 说明 |
|------|------|
| **触发条件** | 没有任何定位权限时 |
| **弹窗来源** | Android 系统 |
| **用户选项** | • 精确位置（仅一次/始终允许）<br>• 大致位置（仅一次/始终允许）<br>• 拒绝 |
| **SDK行为** | 自动发起权限请求，无需开发者处理 |

```
┌─────────────────────────────────────┐
│         允许 XXX 访问此设备的位置？    │
│                                     │
│   ○ 使用应用时允许                   │
│   ○ 仅限这一次                       │
│   ○ 不允许                           │
│                                     │
│         ┌─────────────────────┐     │
│         │  精确 ←──────→ 大致  │     │
│         └─────────────────────┘     │
└─────────────────────────────────────┘
```

#### 弹窗2️⃣：SDK 精确定位引导弹窗
| 项目 | 说明 |
|------|------|
| **触发条件** | `requireFineLocation=true`，用户之前选择了模糊定位，且系统不再弹出权限选择框 |
| **弹窗来源** | SDK (`AlertDialog`) |
| **用户选项** | • 去设置（跳转应用设置页）<br>• 取消 |
| **判定逻辑** | 通过检测权限请求响应时间判断系统是否弹出了对话框（<500ms 视为系统未弹出） |

> **为什么需要这个弹窗？**
> 
> Android 12+ 的"模糊定位"特性会让系统记住用户的选择。如果用户之前选择了"大致位置"，后续再次请求权限时，系统会直接返回之前的选择而不弹出对话框，导致应用无法引导用户切换到精确定位。SDK 通过响应时间检测这种情况并主动弹窗引导。

**判定逻辑代码示意：**
```kotlin
val elapsed = System.currentTimeMillis() - permissionRequestStartTime
val dialogShown = elapsed >= 500 // 500ms 阈值

if (dialogShown) {
    // 系统弹出了对话框，用户主动选择了模糊定位 → 返回错误回调
    finishWithError(EasyLocationError.FineLocationRequired)
} else {
    // 系统没有弹出对话框（记住了之前的选择）→ SDK 弹出引导对话框
    showFineLocationRequiredDialog(activity)
}
```

#### 弹窗3️⃣：GMS 精确定位设置弹窗
| 项目 | 说明 |
|------|------|
| **触发条件** | GMS 设备上，"提高位置精确度"（Google Location Accuracy）未开启 |
| **弹窗来源** | Google Play Services 系统对话框 (`ResolvableApiException`) |
| **用户选项** | • 同意（一键开启，无需跳转设置）<br>• 拒绝 |
| **SDK行为** | 用户同意后自动重试定位；拒绝则返回 `GmsAccuracyDenied` |

```
┌─────────────────────────────────────┐
│    为获取更精确的位置信息，请开启       │
│    "Google 位置精确度"               │
│                                     │
│              [拒绝]  [同意]          │
└─────────────────────────────────────┘
```

> **注意**：此弹窗只在 GMS 设备上出现。非 GMS 设备（如国产手机）不会触发此弹窗，会直接使用原生 GPS/Network 定位。

#### 弹窗4️⃣：SDK 定位服务引导弹窗
| 项目 | 说明 |
|------|------|
| **触发条件** | 系统定位服务总开关未开启（GPS 和 Network 都关闭） |
| **弹窗来源** | SDK (`AlertDialog`) |
| **用户选项** | • 去设置（跳转系统定位设置页）<br>• 取消 |
| **SDK行为** | 点击"去设置"后跳转并返回 `LocationDisabled` 错误 |

```
┌─────────────────────────────────────┐
│               提示                   │
│                                     │
│    定位服务未开启，请在系统设置中       │
│    开启定位功能                       │
│                                     │
│         [取消]      [去设置]         │
└─────────────────────────────────────┘
```

### 7.3 弹窗行为汇总表

| 弹窗 | 来源 | 触发时机 | 用户操作后的SDK行为 |
|------|------|----------|---------------------|
| 系统权限弹窗 | Android 系统 | 无定位权限 | 授权→继续流程；拒绝→返回错误 |
| 精确定位引导弹窗 | SDK | 需要精确定位但只有模糊权限 | 跳转设置页，返回 `FineLocationRequired` |
| GMS精确度弹窗 | Google Play Services | GMS位置精度未开启 | 同意→重试定位；拒绝→返回 `GmsAccuracyDenied` |
| 定位服务引导弹窗 | SDK | 系统定位开关关闭 | 跳转设置页，返回 `LocationDisabled` |
| 权限永久拒绝弹窗 | SDK | 权限被永久拒绝 | 跳转设置页，返回 `PermissionPermanentlyDenied` |

### 7.4 生命周期感知
- 使用 `WeakReference` 持有 Activity，防止内存泄漏
- 回调前检查 Activity 状态（`isFinishing`/`isDestroyed`），避免在页面销毁后操作 UI 导致 Crash
- 弹窗设置 `setCancelable(false)`，防止用户通过点击外部或返回键绕过选择

## 8. 错误类型

| 错误类型 | Code | 说明 | 可通过设置解决 |
|----------|------|------|----------------|
| `PermissionDenied` | 2001 | 权限被拒绝（可再次申请） | ❌ |
| `PermissionPermanentlyDenied` | 2002 | 权限被永久拒绝（需去设置开启） | ✅ |
| `FineLocationRequired` | 2003 | 需要精确定位但用户只给了模糊定位 | ✅ |
| `GmsAccuracyDenied` | 2004 | GMS 精确定位开关被拒绝 | ✅ |
| `LocationDisabled` | 2005 | 系统定位服务未开启 | ✅ |
| `LocationFailed` | 2006 | 定位失败（内含原始错误） | ❌ |
| `ActivityDestroyed` | 2007 | Activity 已销毁 | ❌ |
| `AlreadyProcessing` | 2008 | 已有请求在处理中 | ❌ |

### 错误处理示例
```kotlin
override fun onError(error: EasyLocationError) {
    // 获取本地化错误消息
    val localizedMessage = error.getLocalizedMessage(context)
    
    // 根据错误类型处理
    when (error) {
        is EasyLocationError.PermissionPermanentlyDenied,
        is EasyLocationError.FineLocationRequired -> {
            // 引导用户去应用设置页
            client.openAppSettings()
        }
        is EasyLocationError.LocationDisabled,
        is EasyLocationError.GmsAccuracyDenied -> {
            // 引导用户去系统定位设置页
            client.openLocationSettings()
        }
        is EasyLocationError.LocationFailed -> {
            // 获取原始错误
            val originalError = error.originalError
            Log.e("Location", "原始错误: ${originalError.message}")
        }
        else -> {
            // 其他错误
            Log.e("Location", "定位失败: ${error.message}")
        }
    }
    
    // 也可以用 canResolveInSettings 判断
    if (error.canResolveInSettings) {
        // 显示引导弹窗
    }
}
```

## 9. LocationData 属性

| 属性 | 类型 | 说明 |
|------|------|------|
| `latitude` | Double | 纬度 |
| `longitude` | Double | 经度 |
| `accuracy` | Float | 定位精度（米） |
| `altitude` | Double | 海拔（米） |
| `speed` | Float | 速度（米/秒） |
| `bearing` | Float | 方位角（度） |
| `timestamp` | Long | 定位时间戳 |
| `provider` | LocationProvider | 定位来源：`GPS`, `NETWORK`, `GMS`, `UNKNOWN` |

## 10. 其他方法

| 方法 | 说明 |
|------|------|
| `cancel()` | 取消当前定位请求 |
| `destroy()` | 销毁客户端，释放资源 |
| `openAppSettings()` | 打开应用设置页面（权限被永久拒绝时使用） |
| `openLocationSettings()` | 打开系统定位设置页面 |
