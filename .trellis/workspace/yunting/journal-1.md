# Journal - yunting (Part 1)

> AI development session journal
> Started: 2026-06-29

---



## Session 1: 收藏夹跨来源同步

**Date**: 2026-06-30
**Task**: 收藏夹跨来源同步
**Branch**: `main`

### Summary

两源收藏合并展示并跨源原地播放：FavoriteChannel 加 source 字段（读取按 key 戳源，零迁移），ViewModel combine 合并两源，star/播放/lastPlayed/节目单刷新按各条自身 source 路由；收藏视图星标恒显示并在卡片左上角标注来源；修复长按取消跨源台误写当前源。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `424688a` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 2: 电台节目单与回放收听

**Date**: 2026-07-03
**Task**: 电台节目单与回放收听
**Branch**: `main`

### Summary

为播放器新增节目单与回放：9天节目单、云听/蜻蜓统一回放。修复回放卡缓冲（HLS 工厂改按 URI 类型分派）。横屏节目单移至右侧列表区、手机改锁定50%底部面板；节目行上下排布加大字号间距。空节目单不再误报错、回听后自动关闭并统一下滑动画。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `d1280f3` | (see git log) |
| `b3ac8ae` | (see git log) |
| `bd9fa2b` | (see git log) |
| `e6666d2` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 3: 播放器进度条（TV+手机）收尾归档

**Date**: 2026-07-06
**Task**: 播放器进度条（TV+手机）收尾归档
**Branch**: `main`

### Summary

进度条功能已实现并提交：回放可拖动、直播只读，TV+手机双端；手机端细线贴播放栏顶边、胶囊跨骑边界常显；节目单回放显示暂停键、当前直播档显示直播中可切回。归档任务 07-05-player-progress-bar。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `ec4c1ff` | (see git log) |
| `64ec7f4` | (see git log) |
| `755b5b7` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 4: 睡眠定时交互重做 + 回放进度条若干修复

**Date**: 2026-07-07
**Task**: 睡眠定时交互重做 + 回放进度条若干修复
**Branch**: `main`

### Summary

回放长按快进步长递增（封顶60s）；加载中保留0秒占位进度条防面板跳动；睡眠定时重做：手机底部自适应弹窗、电视上下键调节+确认生效+白色环形倒计时（对齐15分网格、无调整确认为空操作）；修复拖动进度条提交后瞬跳回旧位（committedTarget 锁显目标位待 positionMs 追上）。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `e4e1499` | (see git log) |
| `7951772` | (see git log) |
| `92cdd17` | (see git log) |
| `a8e7632` | (see git log) |
| `128fbe4` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 5: 切台同步节目名 + 设置页关于弹窗

**Date**: 2026-07-12
**Task**: 切台同步节目名 + 设置页关于弹窗
**Branch**: `main`

### Summary

playChannel 末尾调用 refreshPrograms()，切台即重拉最新节目名并写回快照，修复切到旧台播放器仍显示昨日节目（收藏快照/跨源 currentChannel 过期路径）。设置页在检查更新下新增「关于」弹窗，复用更新弹窗 Surface 样式，自上而下：手机版 logo（Coil 加载 ic_launcher）、云听大屏版版本大标题、版权声明、免费开源声明、开源地址 github.com/Uynaity/YuntingTV、小草助手口令 R2B1；移除设置页底部版本小字；版本升至 1.7 (versionCode 8)。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `ffecac4` | (see git log) |
| `cd47440` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 6: TV 全屏播放收尾与性能修复

**Date**: 2026-07-17
**Task**: TV 全屏播放收尾与性能修复
**Branch**: `main`

### Summary

完成 TV 全屏播放界面：低版本模糊底图（RenderScript/palette 缩略图放大）、底部随控件淡入淡出、全屏指示图标放大、版本升至 1.10；并修复列表加载竞态取消、APK 下载可中断、收藏解码去抖等性能问题。归档 07-13-tv-fullscreen-player。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `82701f0` | (see git log) |
| `cd10f09` | (see git log) |
| `7cbff38` | (see git log) |
| `62e83f9` | (see git log) |
| `92976fa` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 7: TuneIn HLS 类型识别 + RTHK 302 base 修复

**Date**: 2026-07-27
**Task**: TuneIn HLS 类型识别 + RTHK 302 base 修复
**Branch**: `gateway-edition`

### Summary

起播前向网关 /v1/stream 问流类型，HLS 台显式设 APPLICATION_M3U8，progressive 不设 mime 交给 ExoPlayer 嗅探，修掉 TuneIn HLS 台永远缓冲中。顺带查出 RTHK 一类台卡住的另一个真凶：入口 m3u8 会跨域 302，服务端用跳转前地址做播放列表改写导致 /seg 无限自指；handlers.go 加 finalURL()，/proxy 与 /seg 都改用跟随重定向后的最终地址，附回归测试。服务端在 gitignore 的 radio-proxy/ 内，需重新部署 radio.hku.wtf 才生效。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `5b0c55f` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 8: 电台搜索功能落地（服务端 /v1/search + TV 键盘 + 手机输入法）

**Date**: 2026-07-27
**Task**: 电台搜索功能落地（服务端 /v1/search + TV 键盘 + 手机输入法）
**Branch**: `gateway-edition`

### Summary

服务端 radio-proxy 新增 /v1/search：scopeCache 缓存范围内电台并建拼音索引，title/首字母/全拼三路子串匹配、前缀优先，go test 全绿；顺带修掉 fetchChannels 翻页重新回源。客户端加 RadioSource.searchChannels（GatewaySource 实现，Base 默认返回空）、ViewModel 搜索管道 debounce(1s)+flatMapLatest、searchResults 与 channels 分开存以便退出搜索原样恢复、loadMoreChannels 顶部分流搜索续页；UI 上 TV/横屏左栏 AnimatedContent 换成 6 列方键键盘，手机顶栏切 M3 SearchBar 并为此在 Theme 嵌一层 compose-material3 主题，手机启用 edge-to-edge + safeDrawing 且底部手势区补绘播放器色。assembleDebug 通过；真机验收（键盘焦点、防抖只发一次请求）未跑。spec 新增「混用 tv-material 与 compose material3」条目。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `a93348a` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 9: Android TV 性能重构 阶段E+4：节目单键控缓存、副标题批量按 id 查、收口归档

**Date**: 2026-08-05
**Task**: Android TV 性能重构 阶段E+4：节目单键控缓存、副标题批量按 id 查、收口归档
**Branch**: `perf/tv-refactor`

### Summary

阶段E 把节目单收进 ProgramRepository（键控 TTL + single-flight + 引用计数取消），并配合服务端新增的 /v1/channels/by-ids 删掉两条全量目录下载路径（收藏刷新、正在播电台兜底）；阶段4 复查清理 + 真机验收 + 归档。真机读数：打开今天节目单 1→0 次请求，收藏页 TuneIn 美国节点 2,148,833→828 字节。66 项单测全过、lint 0 error、release 通过。Macrobenchmark 与帧时长基线因无低端 TV 实体设备未做，已如实记录。

### Main Changes

### 阶段E：节目单与副标题刷新的取数拓扑

**第一步 `750e2fb`** —— 新增 `data/program/ProgramRepository`，按 (来源, contentId, 日期)
键控。此前节目单面板与直播进度条各自直调 `fetchPlaybill`，同一天同一个台要两次往返。
三条语义收在一处：single-flight（同键在途一份，后来者挂同一个 Deferred）、TTL 缓存
（10 分钟，LRU 32 条）、引用计数取消（最后一个等待者走掉才取消，单个被取消不连累其他）。

两个关键决定：
- `currentWindow` 比 TTL 多一条准入判据——缓存答不上「此刻在播哪档」就无视 TTL 重取。
  节目刚切档时后端常还没发布下一档，只看 TTL 会把副标题钉死在上一节目。
- 引用计数清账必须在 `NonCancellable` 下做，否则协程被取消时 `withLock` 一挂起就抛，
  计数减不回去，该键从此再不发请求。有单测钉住。

`loadPlaybill` 从自增令牌改为取消上一次：令牌只让旧结果不写回，请求照样跑完。

**第二步 `e71ed01`** —— 服务端 radio-proxy 新增 `GET /v1/channels/by-ids`（走已有
scopeCache，只是查表），客户端两条「按 contentId 找几个台」的全量下载路径改走它：
收藏刷新与正在播电台的兜底。`NO_LIMIT` / `ALL_CATEGORY_ID` 随之删除。
旧网关 404 时记进程级开关并保留旧快照——那是部署事实不是偶发失败，不必每次再撞一发。

**阶段4 `70f7f72`** —— 复查揪出三处：FavoriteRefreshTest 自身有数据竞争（并行刷新 +
普通 ArrayList，首跑侥幸通过、复查重跑才炸）、`ChannelRepository.invalidate()` 是阶段D
留下的死代码、注释里的体量数字是旧报告的估值（换成线上实测）。

### 真机读数（PLJ110 / SDK 36）

| 场景 | 重构前 | 重构后 |
|---|---|---|
| 回前台 + 打开今天节目单 | 1 次 `/v1/programs` | 0 |
| 昨天 → 今天 → 昨天 | 3 次 | 1 |
| 收藏页 TuneIn 美国节点（4 台） | 2,148,833 字节 | 828 字节 |
| 收藏页 蜻蜓网络台（3 台） | 9,170 字节 | 635 字节 |

### 交接注意

- **radio-proxy 仍被根 `.gitignore` 忽略**（按项目所有者决定）。本轮服务端改动
  （`gateway.go` 的 `fetchChannelsByIDs`、`main.go` 路由、`byids_test.go`、README）
  **不在版本控制内**，只存在于本机与已部署的 radio.hku.wtf。
- 设备上的 debug 包若是别的机器编译的，签名必对不上。可用 `run-as` 备份 DataStore
  再卸载重装：`adb shell run-as cn.radio.tv cp ...`，注意**必须直接调命令**——
  经 `sh -c` 会撞上该 ROM 的沙箱视图问题（同一目录一会儿在一会儿不在，能读不能写）。
- 该 ROM（Oplus）过滤第三方 app 的 OkHttp 日志，但 `Log.i` 的 PerfCounters 读得到。
  数请求用 `adb logcat -s PerfCounters`，别指望 okhttp 日志。

### 未做（如实记录）

Macrobenchmark/Perfetto 基线与低端 TV benchmark 脚本、冷启动耗时与帧时长 P50/P95/P99
——无低端 TV 实体设备，模拟器读数不具参考性。替代方案为 PerfCounters 的设备无关计数。


### Git Commits

| Hash | Message |
|------|---------|
| `750e2fb` | (see git log) |
| `e71ed01` | (see git log) |
| `70f7f72` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete
