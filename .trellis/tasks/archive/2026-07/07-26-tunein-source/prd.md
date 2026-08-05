# 接入 TuneIn(radiotime OPML) 作为第四来源

## 背景

全球电台目前只有 radio-browser 一个来源，实测三个体感问题：

- 起播慢：`byuuid` resolve 单跳 2.2–5.7s（本轮已加列表预热缓解，但源站链路本身仍慢）。
- 重复条目多：同批数据去重剔除率 9–18.7%。
- 元数据薄：`Channel.Subtitle` 对全球台恒为空，卡片无节目行；favicon 各台自填，常失效。

已验证 TuneIn 播放器后端 `opml.radiotime.com` 可用（无需伪装 UA，无 Cloudflare 拦截）：

| 用途 | 端点 | 产出 |
|---|---|---|
| 浏览 | `Browse.ashx?id={guide_id}&render=json` | 子节点(`type:link`)与电台(`item:station`)混排 |
| 起播 | `Tune.ashx?id={s...}&render=json` | `url` / `media_type` / `is_hls_advanced` / `is_direct` |

对比 radio-browser：resolve 1.15s（vs 2.2–5.7s）、类型由 `media_type` 显式给出（不再靠 URL 后缀猜）、附带 `current_track` / `subtext` / `reliability` / `genre_id`、logo 走统一 CDN。

## Goal

新增 `tunein` 来源，与既有三来源（云听 / 蜻蜓 / 全球电台）并存，用户可在设置页切换。

**并存而非替换**：radio-browser 的标签/分类体系与搜索能力是 radiotime 没有的，两者互补；且 radiotime 为商业服务的非公开接口，需保留 radio-browser 兜底。

## Requirements

### 网关侧（radio-proxy）

- 新增 `source_tunein.go`，实现既有 `Source` 接口四个方法（`Provinces` / `Categories` / `Channels` / `Programs`）。
- 树遍历按「有 `item:station` 就收，有 `type:link` 就下钻」递归，**不写死深度**（实测百慕大 3 层到台、美国 5 层：洲→国→州→都会区→台），设深度上限防环。
- 子树缓存按 `guide_id` 存遍历结果，TTL 6–24h；冷启动慢可接受，之后靠缓存。
- 起播复用现有 `/proxy` 透传与 `urlCache`：`Tune.ashx` 结果写入缓存，与本轮已落地的预热机制共用一套。
- `media_type` / `is_hls_advanced` 决定是否给播放地址补 `.m3u8` 后缀（复用 `isHLSStream` 链路）。

### APP 侧

- `RadioSourceType` 增加 `TUNEIN`，设置页来源下拉多一项。
- `Channel.Subtitle` 用 `current_track` 填充（全球台首次有节目行）。
- 无需改播放器：类型判定本轮已改为「mimeType 优先、URI 后缀兜底」，网关补后缀即可命中。

### 模型映射

| 统一契约 | radiotime 来源 |
|---|---|
| `Province` | 国家层拍平（与现有全球电台一致）；国家以下层级在 `Channels` 时递归合并 |
| `Category` | 本次仅「全部」哨兵项 `id=="0"` |
| `Channel.ContentID` | `guide_id`（`s...`） |
| `Channel.Title` | `text` |
| `Channel.Subtitle` | `current_track`，缺失退 `subtext` |
| `Channel.Image` | `image`，经 `/img` 透传 |
| `Channel.PlayUrlLow` | `/proxy/{guide_id}`，HLS 补 `.m3u8` |
| `Program` | 无节目单，返回空列表 |

### 不在本次范围

- 不替换或下线 radio-browser。
- 不做 radiotime 的 genre 分类树（`Categories` 先返回「全部」单项）。
- 不做列表探活（另议）。
- 不做 Radio Garden（实测起播 5.3–6.9s，比现状更慢，无收益）。

## Acceptance Criteria

- [ ] 设置页出现第四个来源，切换后列表正常加载，其余三来源行为不变。
- [ ] 国家列表可用；选定国家后能拿到该国全部电台（含多层级下钻合并结果）。
- [ ] 点击任一电台能起播；HLS 台与 mp3 台都不出现持续「缓冲中」。
- [ ] 卡片显示 `current_track` 作副标题；无该字段退 `subtext`，两者皆无时留空不崩。
- [ ] 遍历对「3 层到台」与「5 层到台」两种结构都正确，有深度上限，异常结构不无限递归。
- [ ] 子树缓存命中后，同一国家二次拉取不再产生外部 HTTP。
- [ ] `go test ./...` 通过（含树遍历与映射自检）；`./gradlew assembleDebug` 通过。
- [ ] 新来源与 radio-browser 的 `urlCache` 互不串号。

## 约束与风险

- **合规**：`opml.radiotime.com` 是 TuneIn 播放器后端，无公开条款授权第三方调用。技术可用但接口可能随时变更或限流。故作为可选来源提供、保留 radio-browser 兜底，不做全量迁移。已向用户说明。
- 遍历成本：单次 Browse 约 1s，大国需下钻数十节点，必须靠缓存摊平，否则首屏不可用。
- `ContentID` 命名空间：radiotime 用 `s...`、radio-browser 用 uuid，形态天然不冲突，但缓存键仍需按来源隔离。
