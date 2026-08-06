# 电台列表分页加载（offset/limit）

## Goal

手机上 TuneIn 列表加载极慢、美国（3718 台）加载不出来。改为分段加载：首屏只取一页，
用户滑到接近底部自动取下一页。服务端配套支持 `offset`/`limit`。

## 背景（实测数据）

服务端出数据只要 7ms，瓶颈全在传输体积：

| 请求 | 条数 | 体积 | gzip 后 |
|---|---|---|---|
| tunein 美国·全部 | 3718 | 918 KB | 136 KB |
| tunein 全球·全部 | 14832 | 3.7 MB | ~550 KB |

918 KB 在 4G 上约 10–20s，撞上 OkHttp 的 15s `readTimeout`（`NetworkModule.kt:42`），
故「一直加载不出来」。体积构成：image 56%、playUrlLow 19%、subtitle 13%。

`Caddyfile` 明确禁用了 `encode`，理由「音频已压缩」——对 `/proxy` 成立，但被无差别
应用到了 `/v1` JSON 接口。

## Requirements

### 服务端

- `/v1/channels` 支持 `offset`（默认 0）与 `limit`（默认 60，上限 300）。
- 切片在 `gateway.go:fetchChannels` 统一做，不改各 `Source` 实现——四来源一并受益。
- `limit=0` 显式表示不分页（取全量），供收藏刷新使用。
- 越界（offset 超出总数）返回空数组而非报错。
- 响应仍是 `List<Channel>`，不引入分页包装对象——契约形状不变，老客户端不带参数
  时行为需与改动前一致（见下方兼容性）。
- Caddy 对 `/v1/*` 开 gzip，`/proxy`、`/seg` 保持不压缩。

### APP

- `GatewayApi.getChannels` 加 `offset`/`limit` 参数。
- `RadioSource.fetchChannels` 加 `offset` 参数（带默认值 0），非分页来源无需改动。
- ViewModel：`loadChannels()` 取首页；新增 `loadMoreChannels()` 追加下一页；
  `UiState` 加 `hasMoreChannels` / `isLoadingMore`。
- 「还有下一页」判据：本页返回条数 == 请求的 limit。
- `LazyVerticalGrid` 滑到接近底部（剩余不足一屏）自动触发下一页；D-pad 与触摸
  滚动共用同一触发路径。
- 加载下一页时底部显示进度指示，不遮挡已有内容。

## 约束

- **收藏刷新必须取全量**：`BaseRadioSource.refreshFavoritePrograms`（`RadioSource.kt:80`）
  靠拉全量列表按 `contentId` 匹配收藏。若被默认 limit 截断，排在首页之外的收藏台会
  静默匹配不到、副标题不刷新（无报错，最难查）。该调用须显式要全量。
- **筛选切换要重置分页**：切来源/地区/分类时 offset 归零，且不能让上一页的慢响应
  追加进新列表——沿用现有 `channelsGeneration` 代次校验机制（`RadioViewModel.kt:343`）。
- **重复触发要幂等**：滚动会连续多次命中触发条件，加载中不得重复发起同一页。
- 缓存 key 已含全部 query（`cache.go:70`），分页天然分开缓存，缓存层不动。

## 兼容性

不带 `offset`/`limit` 的请求会拿到默认第一页（60 条）而非全量，这对已发布版本
是行为变更。当前只有本机调试在用该网关，可接受；若需要严格向后兼容，改为
「不带 limit = 全量」并由新版 APP 显式传 limit。

## Acceptance Criteria

- [ ] `curl '/v1/channels?source=tunein&provinceCode=100436&offset=0&limit=60'` 返回 60 条
- [ ] `offset=60` 返回的首条 != `offset=0` 的首条，无重叠无遗漏
- [ ] `offset` 超出总数返回空数组，HTTP 200
- [ ] `limit=0` 返回全量 3718 条
- [ ] `limit=9999` 被夹到 300
- [ ] 其余三来源不带分页参数时行为与改动前一致
- [ ] Caddy 对 `/v1/*` 返回 `Content-Encoding: gzip`，`/proxy` 不压缩
- [ ] 手机实测：TuneIn 美国列表首屏秒出
- [ ] 手机实测：滑到底部自动加载下一页，无重复项、无跳动
- [ ] 手机实测：切换地区/分类后列表从第一页重来，不残留上一筛选的项
- [ ] 收藏刷新对第 300 名之后的收藏台仍能刷新副标题
- [ ] TV D-pad 下键连续按到底部能触发加载
- [ ] `go test ./...` 与 `./gradlew assembleDebug` 通过
