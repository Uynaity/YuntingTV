# 电视广播直播 App — 需求与设计文档

## 1. 项目概述
一款面向 **Android TV** 的简洁电视广播直播 App，数据来自云听（radio.cn）开放接口。
界面采用左右布局，整体视觉风格参考 **YouTube Music**（深色、卡片、圆角、留白）。

- 平台：Android TV（Compose for TV，`androidx.tv.material3`）
- `minSdk 31` / `targetSdk 36` / `compileSdk 36`
- 语言/UI：Kotlin + Jetpack Compose
- 包名：`com.example.myapplication`

## 2. 数据来源（radio.cn 接口）

### 2.1 通用约定
- BaseUrl：`https://ytmsout.radio.cn`
- 所有请求为带签名的 GET。
- 签名算法：
  - 取本次请求所有 query 参数，按 key 字母序拼成 `a=b&c=d`（使用未编码的原始值）。
  - `signText = "<排序后的参数>&timestamp=<毫秒时间戳>&key=<KEY>"`；当无参数时 `signText = "timestamp=<毫秒时间戳>&key=<KEY>"`。
  - `sign = MD5(signText).toUpperCase()`
  - `KEY = f0fc4c668392f9f9a447e48584c214ee`（前端硬编码盐）
- 必需请求头：
  - `Content-Type: application/json`
  - `platformCode: WEB`
  - `equipmentId: 0000`
  - `timestamp: <毫秒时间戳>`（与签名中的一致）
  - `sign: <MD5 大写>`
  - `Origin: https://www.radio.cn`，`Referer: https://www.radio.cn/`
  - `User-Agent: <浏览器 UA>`
- 响应通用结构：`{ "code": 0, "message": "SUCCESS", "data": [...], "extInfo": null }`，`code == 0` 为成功。

### 2.2 接口清单
| 用途 | Path | 参数 | data 元素 |
|------|------|------|-----------|
| 省份列表 | `/web/appProvince/list/all` | 无 | `{ provinceName: String, provinceCode: Int }` |
| 分类列表 | `/web/appCategory/list/all` | 无 | `{ id: String, categoryName: String }` |
| 电台列表 | `/web/appBroadcast/list` | `categoryId`、`provinceCode` | 见下 |

电台元素（仅关注以下字段）：
- `contentId: String` — 电台唯一 id
- `title: String` — 电台名称
- `subtitle: String` — 当前节目（如「正在直播：xxx」或「暂无节目单」）
- `image: String` — 封面图 URL（部分为 http）
- `playUrlLow: String` — HLS 播放地址（`.m3u8`，http）

> 注：`provinceCode` 为电台列表必填项；`provinceCode=0` 表示「国家（全国台）」，`categoryId=0` 表示「全部」。

## 3. 界面设计

整体为左右两栏布局。

### 3.1 左侧 ≈ 1/3：播放器面板
- 大尺寸电台封面（圆角卡片）
- 电台名称（title）
- 当前节目（subtitle）
- 播放/暂停按钮（**仅此一个控制按钮**，按需求不做上一台/下一台/音量）
- 未选择电台时显示占位状态

### 3.2 右侧 ≈ 2/3：电台列表
- 顶部两行横向可滚动筛选列表：
  - 第一行：城市（省份）筛选 chips
  - 第二行：类型（分类）筛选 chips
- 下方电台 Grid：每个卡片含 电台图片、电台名称、当前节目
- 点击/确认某卡片 → 在左侧播放器加载并播放该电台

### 3.3 交互与风格
- 深色主题，YouTube Music 风格：深色背景、卡片圆角、选中/聚焦高亮。
- 完整遥控器（D-pad）焦点导航：筛选 chips、电台卡片、播放按钮均可聚焦。
- 切换城市或类型 → 重新请求电台列表刷新 Grid。

## 4. 行为细节
- **首次启动**默认地区 = 「国家」(`provinceCode=0`)、分类 = 「全部」(`categoryId=0`)。
- **记住上次选择**：用户选择的省份与分类持久化（DataStore/SharedPreferences），下次启动恢复。
- 省份与分类**通过接口动态获取**（不内置静态 JSON）。
- 选中电台播放 HLS（`playUrlLow`）；播放/暂停切换。
- 网络/解析失败给出可见的错误与重试。

## 5. 技术栈
- UI：Jetpack Compose + Compose for TV（`androidx.tv:tv-material`）
- 播放：Media3 ExoPlayer（含 HLS）
- 网络：Retrofit + OkHttp（签名拦截器）+ kotlinx.serialization
- 图片：Coil（`coil-compose`）
- 异步/状态：Coroutines + ViewModel + StateFlow
- 持久化：DataStore Preferences

## 6. 工程结构（计划）
```
com.example.myapplication/
  MainActivity.kt
  data/
    model/      ApiResponse, Channel, Province, Category
    remote/     SignInterceptor, RadioApi, NetworkModule
    prefs/      UserPreferences
    RadioRepository.kt
  player/       RadioPlayer (ExoPlayer 封装)
  ui/
    RadioViewModel.kt
    RadioScreen.kt
    components/  PlayerPanel, FilterRow, ChannelGrid, ChannelCard
    theme/       (已有)
```

## 7. 权限
- `android.permission.INTERNET`
- `usesCleartextTraffic=true`（封面与播放地址含 http）
