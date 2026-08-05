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
