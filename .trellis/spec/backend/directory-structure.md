# Directory Structure

> 本项目（Android TV / 手机电台 App，`cn.radio.tv`）非 UI 代码的组织方式。

---

## Overview

单模块 Android 应用，MVVM + Jetpack Compose。源码根：`app/src/main/java/cn/radio/tv/`。
按职责分三层：`data`（数据）、`player`（播放）、`ui`（展示），入口 `MainActivity.kt`。

---

## Directory Layout

```
cn/radio/tv/
├── MainActivity.kt              # 单 Activity 入口；权限请求、setContent
├── data/
│   ├── model/Models.kt          # 全部业务数据类（Channel/Program/Province/Category/FavoriteChannel…）
│   ├── prefs/UserPreferences.kt # DataStore 偏好存储（见 database-guidelines.md）
│   ├── remote/                  # 网络层：Retrofit/OkHttp
│   │   ├── NetworkModule.kt     #   gatewayApi（统一网关，无签名）+ yecaoApi
│   │   └── GatewayApi.kt        #   自建网关 Retrofit 接口（四来源共用，source= 区分）
│   └── source/                  # 数据来源抽象：把网关响应映射到共享业务模型
│       ├── RadioSource.kt       #   接口 + BaseRadioSource 通用实现
│       ├── RadioSourceType.kt   #   来源枚举（含持久化 key）
│       └── GatewaySource.kt     #   唯一实现：按 type 走网关取数
├── player/                      # Media3/ExoPlayer 播放层
│   ├── RadioPlayer.kt           #   ExoPlayer 封装（MediaSource 分派、重试）
│   ├── PlaybackService.kt       #   MediaSessionService（后台播放 + 通知）
│   └── PlaybackBridge.kt        #   进程内自定义状态桥（retrySeconds）
└── ui/
    ├── RadioScreen.kt / RadioViewModel.kt   # 主界面 + 唯一 ViewModel（UiState 单一数据源）
    ├── SettingsScreen.kt
    ├── components/              # 可复用 Compose 组件
    └── theme/                   # Color / Type / Theme
```

---

## Module Organization

- **新增一个电台来源**：先在服务端网关（`radio-proxy/`）接入并归一化，APP 侧只需在
  `RadioSourceType` 加枚举项（`key` 即网关 `source` 参数）。`GatewaySource` 与上层不改——
  第三方接口差异、签名、字段映射全在网关消化。
  仅两处可能需要按来源特化，都在 `GatewaySource`：`defaultProvinceCode`（该来源不支持
  「全部」或全量太大时给个默认地区），以及 `fetchPlaybill` 短路（来源无节目单时直接返回空，
  省一次必然为空的请求）。除此之外别在 APP 侧写 `when (type)` 分支。
- **TuneIn 是唯一的「离线目录」来源**：`data/tunein_catalog.json` 是 `tools/tunein_crawl.py`
  的产物，网关启动时一次性读入内存，**服务端自己不爬**（原因见 quality-guidelines.md）。
  更新目录 = 换文件重启。清洗/去重/分类本地化都在网关侧做，规则变更重启即可，不必重爬。
  缺文件或文件损坏只让 tunein 不可用，其余三来源正常。
- **按来源遍历用 `RadioSourceType.entries`，别逐个列举**：收藏合并流曾逐个 `combine` 三来源，
  加第四个来源时漏改会让该来源的收藏静默不显示（无报错，最难查）。
- **业务模型统一放 `data/model/Models.kt`**：网关 DTO 在 `GatewaySource` 内就地映射为共享模型，
  不把 DTO 泄漏到 `source` 层之外。
- **电台列表是分页的**：`/v1/channels` 带 `offset`/`limit`，切片在网关 `gateway.go:paginate`
  统一做（`Source.Channels` 一律返回全量），四来源共享。页大小 `RadioSource.PAGE_SIZE`
  须与服务端 `defaultPageSize` 一致；`limit=0`(`NO_LIMIT`) = 不分页。响应仍是
  `List<Channel>`，没有分页包装对象——「还有下一页」由客户端按「本页条数 == limit」判断。
  新增调用点前先读 quality-guidelines.md 里那条「`fetchChannels` 不再返回全量」。
- **状态只在 `RadioViewModel`**：UI 无状态，通过 `UiState` + 事件回调驱动。

---

## Naming Conventions

- 包名全小写；类名 PascalCase；文件名与主类同名。
- 数据来源实现：`<来源名>Source`（现仅 `GatewaySource`）；Retrofit 接口：`<来源名>Api`。
- Compose 组件文件放 `ui/components/`，函数名 PascalCase（Composable 约定）。
- DataStore 偏好 key 见 `UserPreferences` 的 `companion object`，按来源隔离用 key 后缀。

---

## Examples

- 来源抽象与通用实现的范例：`data/source/RadioSource.kt`（含 `BaseRadioSource`）。
- API 单例（`gatewayApi` / `yecaoApi`）的范例：`data/remote/NetworkModule.kt`。
- 单实现多来源的范例：`data/source/GatewaySource.kt`（按 `RadioSourceType` 带 `source=` 参数）。
